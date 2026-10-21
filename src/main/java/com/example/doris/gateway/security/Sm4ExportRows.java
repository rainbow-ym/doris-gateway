package com.example.doris.gateway.security;

import com.example.doris.gateway.lineage.LineageEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shardingsphere.mask.merge.dql.MaskMergedResult;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryHeader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import com.example.doris.gateway.export.ExportValue;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import com.example.doris.gateway.observability.Telemetry;
import com.example.doris.gateway.observability.Observation;
import static com.example.doris.gateway.observability.Observation.*;

/** Bounded opt-in materialization. Ordinary columns retain their native metadata and values. */
public final class Sm4ExportRows {
    private static final Semaphore SLOTS=new Semaphore(concurrency());
    private static int concurrency(){
        String value=System.getenv("SM4_EXPORT_MAX_CONCURRENT");int limit=value==null?8:Integer.parseInt(value);
        if(limit<1||limit>64)throw new IllegalArgumentException("SM4_EXPORT_MAX_CONCURRENT 必须为 1—64。");
        return limit;
    }
    private static final ObjectMapper JSON=new ObjectMapper();
    private final List<Object[]> rows;
    private int index=-1;
    private boolean released;
    private final Telemetry.Session observation;
    private Sm4ExportRows(List<Object[]> rows,Telemetry.Session observation){this.rows=rows;this.observation=observation;}
    public boolean next(){boolean has=++index<rows.size();if(!has)clear();return has;}
    public Object value(int column){return rows.get(index)[column];}
    public void clear(){
        int size=rows.size();boolean exhausted=index>=size;
        rows.clear();if(!released){released=true;SLOTS.release();
            observation.add(Measure.BUFFER_ROWS_RELEASED,size);observation.add(Measure.PERMIT_RELEASED,1);
            observation.add(Measure.CONSUMED_ROWS,Math.min(size,Math.max(0,index+1)));
            observation.finishAs(exhausted?Reason.OK:Reason.RESULT_ABANDONED);
        }
    }
    @SuppressWarnings("unchecked")
    public static Sm4ExportRows prepare(MaskMergedResult merged,List<QueryHeader> headers,String user,String db)throws SQLException {
        if(merged.exportSources().isEmpty())return null;
        ProxyObservationReporter.initialize();
        Telemetry.Session observation=Telemetry.begin(Operation.ENCRYPT_QUERY,null,null);
        try{
            Map<String,Object> policy;try(Observation.Scope ignored=observation.stage(Stage.POLICY)){policy=JSON.readValue(LineageEventEmitter.accountRequest("export-policy",user,db),Map.class);}
            if(!Boolean.TRUE.equals(policy.get("enabled"))){observation.finishAs(Reason.DISABLED);return null;}
            if(!SLOTS.tryAcquire()){observation.reason(Reason.CAPACITY);throw new SQLException("逐行加密查询繁忙，请稍后重试。","HY000");}
            try{
                if(!"ROW_TYPED_V1".equals(policy.get("captureProtocol")))throw new SQLException("Worker 尚不支持类型化加密协议，请配套升级 Worker、Proxy 和 Platform。","0A000");
                return collect(new RowReader(){
                    public boolean next()throws SQLException{return merged.next();}
                    public Object value(int column)throws SQLException{return merged.getValue(column,Object.class);}
                    public Object original(int column)throws SQLException{
                        int type=headers.get(column-1).getColumnType();
                        // JDBC getString retains TIME durations, temporal fractions and large-number text.
                        boolean binary=type==Types.BINARY||type==Types.VARBINARY||type==Types.LONGVARBINARY||type==Types.BLOB;
                        return merged.exportOriginalValue(column,binary?byte[].class:String.class);
                    }
                    public boolean protectedColumn(int column){return merged.isExportProtected(column);}
                },headers,user,db,policy,merged.exportSources(),observation,body->LineageEventEmitter.captureExport(body,observation.requestId(),observation.spanId()));
            }catch(Exception e){SLOTS.release();observation.add(Measure.PERMIT_RELEASED,1);throw e;}
        }catch(SQLException e){observation.finish(e);throw e;}catch(Exception e){observation.finish(e);throw new SQLException("逐行 SM4 加密失败，未返回结果："+e.getMessage(),"HY000");}
    }
    interface RowReader {
        boolean next()throws SQLException;
        Object value(int column)throws SQLException;
        Object original(int column)throws SQLException;
        boolean protectedColumn(int column);
    }
    interface Capture {String send(String body)throws Exception;}
    static Sm4ExportRows collect(RowReader merged,List<QueryHeader> headers,String user,String db,Map<String,Object> policy,
            List<Map<String,String>> sources,Telemetry.Session observation,Capture capture)throws Exception {
        if(headers.size()>255)throw new SQLException("逐行加密最多支持 255 个业务列。");
        boolean editable=Boolean.TRUE.equals(policy.get("allowEdit"));int maxRows=((Number)policy.get("maxRows")).intValue();
        List<String> labels=new ArrayList<>(),typeNames=new ArrayList<>(),checkedLabels=new ArrayList<>();List<Integer> types=new ArrayList<>();
        for(int i=0;i<headers.size();i++){
            QueryHeader h=headers.get(i);labels.add(h.getColumnLabel());types.add(h.getColumnType());typeNames.add(h.getColumnTypeName()==null?"JDBC_"+h.getColumnType():h.getColumnTypeName());
            if(!editable||merged.protectedColumn(i+1))checkedLabels.add(h.getColumnLabel());
            if("__gateway_sm4".equalsIgnoreCase(h.getColumnLabel()))throw new SQLException("结果包含保留列名 __gateway_sm4，请使用其他别名。","42000");
        }
        if(labels.contains(null)||labels.contains("")||new HashSet<>(labels).size()!=labels.size())throw new SQLException("CSV 导出要求非空且唯一的结果列名，请给重名列设置别名。","42000");
        List<Object[]> buffered=new ArrayList<>();List<String> digests=new ArrayList<>();List<Map<String,Object>> originals=new ArrayList<>();
        ExportValue.Budget budget=new ExportValue.Budget(512*1024);
        try(Observation.Scope ignored=observation.stage(Stage.RESULT_COLLECT)){
            while(merged.next()){
                if(buffered.size()>=Math.min(1000,maxRows))throw new SQLException("逐行加密实际结果超过 "+maxRows+" 行上限，请减少返回行数。","54000");
                budget.charge(64L+8L*(headers.size()+1));
                Object[] data=new Object[headers.size()+1];List<String> checked=new ArrayList<>();Map<String,Object> original=new LinkedHashMap<>();
                for(int i=0;i<headers.size();i++){
                    boolean protectedColumn=merged.protectedColumn(i+1);QueryHeader header=headers.get(i);
                    try {
                        Object current;
                        try{current=merged.value(i+1);}catch(Exception e){throw new SQLException("读取结果失败；请核对驱动与字段类型。","HY000");}
                        data[i]=ExportValue.snapshot(current,budget);
                        if(protectedColumn&&data[i]!=null){data[i]=ExportValue.text(data[i]);budget.charge(2L*((String)data[i]).length());}
                        if(!editable||protectedColumn){String shown=ExportValue.text(data[i]);budget.charge(2L*shown.length());checked.add(shown);}
                        if(protectedColumn){
                            Object originalValue;
                            try{originalValue=merged.original(i+1);}catch(Exception e){throw new SQLException("读取受保护原值失败；请核对驱动与字段类型。","HY000");}
                            Object raw=ExportValue.snapshot(originalValue,budget);List<String> encoded=ExportValue.encode(raw);
                            budget.charge(encoded.get(1)==null?16:32L+2L*encoded.get(1).length());original.put(String.valueOf(i),encoded);
                        }
                    } catch(SQLException|IllegalArgumentException e){
                        String kind=protectedColumn?"受保护列":"普通列";
                        throw new SQLException("逐行加密第 "+(i+1)+" 列 ["+header.getColumnLabel()+"]，"+kind+"，类型 "+header.getColumnTypeName()+" (JDBC "+header.getColumnType()+")："+e.getMessage(),e instanceof SQLException?((SQLException)e).getSQLState():"0A000");
                    }
                }
                String digest=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(Arrays.asList(checkedLabels,Collections.singletonList(checked)))));
                buffered.add(data);digests.add(digest);originals.add(original);observation.add(Measure.PROTECTED_CELLS,original.size());
            }
        }
        observation.add(Measure.ROWS,buffered.size());
        Map<String,Object> request=new LinkedHashMap<>();request.put("username",user);request.put("database",db);request.put("labels",labels);
        request.put("digests",digests);request.put("originals",originals);request.put("sources",sources);request.put("format","ROW_TYPED_V1");request.put("allowEdit",editable);
        request.put("types",types);request.put("typeNames",typeNames);
        String encoded;try(Observation.Scope ignored=observation.stage(Stage.SERIALIZE)){encoded=JSON.writeValueAsString(request);}
        int requestBytes=encoded.getBytes(StandardCharsets.UTF_8).length;observation.add(Measure.HTTP_BYTES,requestBytes);
        if(requestBytes>1024*1024)throw new SQLException("逐行加密请求超过 1 MiB。","54000");
        Map<?,?> response;try(Observation.Scope ignored=observation.stage(Stage.WORKER_CALL)){response=JSON.readValue(capture.send(encoded),Map.class);}
        Object supplied=response.get("envelopes");
        if(!(supplied instanceof List)||((List<?>)supplied).size()!=buffered.size())throw new SQLException("控制面未返回逐行 SM4 密文，请配套升级服务。");
        long encryptedBytes=0;
        for(int i=0;i<buffered.size();i++){
            String envelope=String.valueOf(((List<?>)supplied).get(i));
            if(!envelope.startsWith("__GW_SM4_V1__:")||envelope.length()>30000)throw new SQLException("控制面未返回有效 SM4 密文。");
            encryptedBytes+=envelope.length();if(encryptedBytes>768*1024)throw new SQLException("逐行密文总量超限，请减少查询范围。");
            buffered.get(i)[headers.size()]=envelope;
        }
        // Change metadata only after capture succeeds; original extraction above needs the source type.
        for(int i=0;i<headers.size();i++)if(merged.protectedColumn(i+1)){
            QueryHeader h=headers.get(i);int length=256;
            for(Object[] row:buffered)if(row[i]!=null)length=Math.max(length,((String)row[i]).getBytes(StandardCharsets.UTF_8).length);
            headers.set(i,new QueryHeader(h.getSchema(),h.getTable(),h.getColumnLabel(),h.getColumnName(),Types.VARCHAR,"VARCHAR",length,0,false,false,false,false));
        }
        headers.add(new QueryHeader("","","__gateway_sm4","__gateway_sm4",Types.VARCHAR,"VARCHAR",30000,0,false,false,false,false));
        observation.add(Measure.CIPHERTEXT_BYTES,encryptedBytes);
        return new Sm4ExportRows(buffered,observation);
    }
}
