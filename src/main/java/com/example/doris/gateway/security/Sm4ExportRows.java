package com.example.doris.gateway.security;

import com.example.doris.gateway.lineage.LineageEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.shardingsphere.mask.merge.dql.MaskMergedResult;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryHeader;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Bounded opt-in materialization before the first result packet. Never alters business column types. */
public final class Sm4ExportRows {
    private static final Semaphore SLOTS=new Semaphore(2);
    private static final ObjectMapper JSON=new ObjectMapper();
    private final List<Object[]> rows;
    private int index=-1;
    private boolean released;
    private Sm4ExportRows(List<Object[]> rows){this.rows=rows;}
    public boolean next(){boolean has=++index<rows.size();if(!has)clear();return has;}
    public Object value(int column){return rows.get(index)[column];}
    public void clear(){rows.clear();if(!released){released=true;SLOTS.release();}}
    @SuppressWarnings("unchecked")
    public static Sm4ExportRows prepare(MaskMergedResult merged,List<QueryHeader> headers,String user,String db)throws SQLException {
        if(merged.exportSources().isEmpty())return null;
        try{
            Map<String,Object> policy=JSON.readValue(LineageEventEmitter.accountRequest("export-policy",user,db),Map.class);
            if(!Boolean.TRUE.equals(policy.get("enabled")))return null;
            if(!SLOTS.tryAcquire())throw new SQLException("末行加密查询繁忙，请稍后重试。","HY000");
            try{return collect(merged,headers,user,db,((Number)policy.get("maxRows")).intValue());}catch(Exception e){SLOTS.release();throw e;}
        }catch(SQLException e){throw e;}catch(Exception e){throw new SQLException("末行 SM4 加密失败，未返回结果："+e.getMessage(),"HY000");}
    }
    private static Sm4ExportRows collect(MaskMergedResult merged,List<QueryHeader> headers,String user,String db,int maxRows)throws Exception {
        if(headers.size()>256)throw new SQLException("末行加密最多支持 256 列。");
        List<String> labels=new ArrayList<>();List<Integer> types=new ArrayList<>();int carrier=-1;
        for(int i=0;i<headers.size();i++){
            QueryHeader h=headers.get(i);labels.add(h.getColumnLabel());int t=h.getColumnType();types.add(t);
            boolean text=t==Types.CHAR||t==Types.VARCHAR||t==Types.LONGVARCHAR||t==Types.NCHAR||t==Types.NVARCHAR||t==Types.LONGNVARCHAR;
            boolean number=t==Types.TINYINT||t==Types.SMALLINT||t==Types.INTEGER||t==Types.BIGINT||t==Types.NUMERIC||t==Types.DECIMAL;
            if(!text&&!number)throw new SQLException("末行加密当前仅支持文本、整数和定点数列；请缩小查询字段范围。");
            if(text&&carrier<0)carrier=i;
        }
        if(carrier<0)throw new SQLException("结果没有可承载密文的文本列。");
        List<Object[]> buffered=new ArrayList<>();List<List<String>> displayed=new ArrayList<>();List<Map<String,Object>> originals=new ArrayList<>();long bytes=0;
        while(merged.next()){
            if(buffered.size()>=Math.min(1000,maxRows))throw new SQLException("末行加密查询行数超限，请增加过滤条件或减少 LIMIT。");
            Object[] data=new Object[headers.size()];List<String> view=new ArrayList<>();Map<String,Object> original=new LinkedHashMap<>();
            for(int i=0;i<headers.size();i++){
                data[i]=merged.getValue(i+1,Object.class);String v=canonical(data[i]);view.add(v);bytes+=v.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if(merged.isExportProtected(i+1)){Object raw=merged.exportOriginalValue(i+1);String rawText=raw==null?null:canonical(raw);original.put(String.valueOf(i),rawText);if(rawText!=null)bytes+=rawText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
                if(bytes>512*1024)throw new SQLException("末行加密缓冲超限，请减少查询范围。");
            }
            buffered.add(data);displayed.add(view);originals.add(original);
        }
        Map<String,Object> request=new LinkedHashMap<>();request.put("username",user);request.put("database",db);request.put("labels",labels);request.put("displayed",displayed);request.put("originals",originals);request.put("sources",merged.exportSources());request.put("carrier",carrier);
        request.put("types",types);
        String encoded=JSON.writeValueAsString(request);if(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1024*1024)throw new SQLException("末行加密请求超过 1 MiB。");
        Map<?,?> response=JSON.readValue(LineageEventEmitter.captureExport(encoded),Map.class);String envelope=String.valueOf(response.get("envelope"));
        if(!envelope.startsWith("__GW_SM4_V1__:")||envelope.length()>30000)throw new SQLException("控制面未返回有效 SM4 附件。");
        Object[] trailer=new Object[headers.size()];trailer[carrier]=envelope;buffered.add(trailer);
        return new Sm4ExportRows(buffered);
    }
    static String canonical(Object value)throws SQLException {
        if(value==null)return "";
        if(value instanceof String||value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof java.math.BigInteger)return value.toString();
        if(value instanceof java.math.BigDecimal)return ((java.math.BigDecimal)value).toPlainString();
        throw new SQLException("导出单元格类型无法稳定转换为 CSV。");
    }
}
