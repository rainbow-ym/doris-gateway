package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import javax.annotation.PostConstruct;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Separate export authorization/envelope lifecycle. No plaintext result is stored in the system database. */
@Service
public class Sm4ExportService {
    private final JdbcTemplate jdbc;private final AdminStore store;private final AccountAccessService access;private final ObjectMapper json;
    private final Semaphore slots=new Semaphore(2);
    @org.springframework.beans.factory.annotation.Autowired private ObjectIdentityProvider identities;
    @Value("${SM4_EXPORT_KEY_FILE:/opt/gateway-admin/data/sm4-export-keys.properties}") private String keyFile;
    @Value("${SM4_EXPORT_KEY_ID:v1}") private String currentKey;
    public Sm4ExportService(JdbcTemplate jdbc,AdminStore store,AccountAccessService access,ObjectMapper json){this.jdbc=jdbc;this.store=store;this.access=access;this.json=json;}
    @PostConstruct public void initialize(){
        jdbc.execute("CREATE TABLE IF NOT EXISTS sm4_export_profiles(username VARCHAR(128),database_name VARCHAR(255),enabled BOOLEAN NOT NULL,allow_decrypt BOOLEAN NOT NULL,max_rows INT NOT NULL,ttl_minutes INT NOT NULL,PRIMARY KEY(username,database_name))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sm4_export_receipts(id VARCHAR(36) PRIMARY KEY,username VARCHAR(128),database_name VARCHAR(255),expires_at BIGINT NOT NULL,revoked BOOLEAN NOT NULL DEFAULT FALSE,row_count INT NOT NULL,nonce VARCHAR(64) NOT NULL UNIQUE)");
    }
    public List<Map<String,Object>> profiles(){return jdbc.queryForList("SELECT username,database_name AS databaseName,enabled,allow_decrypt AS allowDecrypt,max_rows AS maxRows,ttl_minutes AS ttlMinutes FROM sm4_export_profiles ORDER BY username,database_name");}
    public Map<String,Object> profile(String user,String db){
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT enabled,allow_decrypt AS allowDecrypt,max_rows AS maxRows,ttl_minutes AS ttlMinutes FROM sm4_export_profiles WHERE username=? AND database_name=?",user,db);
        return rows.isEmpty()?Collections.singletonMap("enabled",false):rows.get(0);
    }
    public void save(Map<String,Object> r)throws Exception {
        String user=text(r,"username"),db=text(r,"databaseName");access.resolve(user,db);
        int rows=number(r,"maxRows",200,1,1000),ttl=number(r,"ttlMinutes",60,1,1440);
        if(Boolean.TRUE.equals(r.get("enabled")))key(currentKey);
        jdbc.update("MERGE INTO sm4_export_profiles(username,database_name,enabled,allow_decrypt,max_rows,ttl_minutes) KEY(username,database_name) VALUES(?,?,?,?,?,?)",user,db,Boolean.TRUE.equals(r.get("enabled")),Boolean.TRUE.equals(r.get("allowDecrypt")),rows,ttl);
        store.audit("SM4_EXPORT_PROFILE_CHANGED","user="+user+",database="+db+",enabled="+Boolean.TRUE.equals(r.get("enabled"))+",decrypt="+Boolean.TRUE.equals(r.get("allowDecrypt")));
    }
    private byte[] key(String id)throws Exception {
        Properties p=new Properties();Path path=Paths.get(keyFile);
        if(!Files.isRegularFile(path)||Files.size(path)>65536)throw new IllegalStateException("未配置 SM4 密钥文件，请运行安装包的密钥初始化脚本；不要覆盖已有密钥。");
        try(java.io.InputStream in=Files.newInputStream(path)){p.load(in);}
        String encoded=p.getProperty(id);if(encoded==null)throw new IllegalStateException("密钥版本不可用，请恢复对应的原密钥文件。");
        byte[] result=Base64.getDecoder().decode(encoded);if(result.length!=16)throw new IllegalStateException("SM4 密钥长度必须为 16 字节。");return result;
    }
    @SuppressWarnings("unchecked") public Map<String,Object> capture(Map<String,Object> request)throws Exception {
        if(!slots.tryAcquire())throw new IllegalStateException("加密导出繁忙，请稍后重试。");
        try{
            String user=text(request,"username"),db=text(request,"database");Map<String,Object> policy=profile(user,db);
            if(!Boolean.TRUE.equals(policy.get("enabled")))throw new SecurityException("该账号和数据库未启用末行加密。");
            Map<String,Object> account=access.resolve(user,db);
            List<String> labels=(List<String>)request.get("labels");List<List<String>> displayed=(List<List<String>>)request.get("displayed");
            List<Map<String,Object>> originals=(List<Map<String,Object>>)request.get("originals");List<Map<String,String>> sources=(List<Map<String,String>>)request.get("sources");
            if(labels==null||labels.isEmpty()||labels.size()>256||displayed==null||displayed.size()>((Number)policy.get("maxRows")).intValue()||originals==null||originals.size()!=displayed.size()||sources==null||sources.isEmpty()||sources.size()>256)
                throw new IllegalArgumentException("导出结构不完整或行列超限。");
            for(List<String> row:displayed)if(row.size()!=labels.size())throw new IllegalArgumentException("结果列数不一致。");
            if(!(request.get("carrier") instanceof Number)||((Number)request.get("carrier")).intValue()<0||((Number)request.get("carrier")).intValue()>=labels.size())throw new IllegalArgumentException("密文承载列无效。");
            for(Map<String,Object> row:originals)for(Map.Entry<String,Object> cell:row.entrySet()){
                int index=Integer.parseInt(cell.getKey());if(index<0||index>=labels.size()||(cell.getValue()!=null&&!(cell.getValue() instanceof String)))throw new IllegalArgumentException("原值单元格格式无效。");
            }
            validateTypes(request.get("types"),labels.size(),"ROW_V2".equals(request.get("format"))?-1:((Number)request.get("carrier")).intValue());
            requireUnambiguousRows(displayed,originals);
            if(json.writeValueAsBytes(request).length>1024*1024)throw new IllegalArgumentException("导出缓冲超过 1 MiB，请缩小查询范围。");
            Map<String,Object> payload=new LinkedHashMap<>();String id=UUID.randomUUID().toString();long expires=System.currentTimeMillis()+((Number)policy.get("ttlMinutes")).longValue()*60000;
            payload.put("id",id);payload.put("username",user);payload.put("database",db);payload.put("expires",expires);payload.put("epoch",account.get("epoch"));
            payload.put("labels",labels);payload.put("originals",originals);payload.put("sources",bindSources(sources,false));payload.put("rows",displayed.size());
            payload.put("digest",digest(labels,displayed));payload.put("carrier",request.get("carrier"));
            payload.put("types",request.get("types"));
            payload.put("format",request.get("format"));
            String envelope=Sm4ExportCodec.encrypt(json.writeValueAsBytes(payload),currentKey,key(currentKey));
            if(envelope.length()>30000)throw new IllegalArgumentException("SM4 密文超过单元格 30000 字符上限，请减少查询行数。");
            String packet=envelope.substring(envelope.lastIndexOf(':')+1);
            jdbc.update("INSERT INTO sm4_export_receipts(id,username,database_name,expires_at,row_count,nonce) VALUES(?,?,?,?,?,?)",id,user,db,expires,displayed.size(),packet.substring(0,16));
            store.audit("SM4_EXPORT_CAPTURED","id="+id+",user="+user+",database="+db+",rows="+displayed.size());
            return Collections.singletonMap("envelope",envelope);
        }finally{slots.release();}
    }
    /** A batch contains independently authenticated rows; one row combines all protected cells. */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @SuppressWarnings("unchecked") public Map<String,Object> captureRows(Map<String,Object> request)throws Exception {
        if(!"ROW_V2".equals(request.get("format")))throw new IllegalArgumentException("需要逐行密文协议，请配套升级 Proxy。");
        if(json.writeValueAsBytes(request).length>1024*1024)throw new IllegalArgumentException("逐行加密请求超过 1 MiB。");
        Map<String,Object> policy=profile(text(request,"username"),text(request,"database"));
        if(!Boolean.TRUE.equals(policy.get("enabled")))throw new SecurityException("未启用逐行加密。");
        access.resolve(text(request,"username"),text(request,"database"));
        List<String> labels=(List<String>)request.get("labels");
        List<List<String>> rows=(List<List<String>>)request.get("displayed");
        List<Map<String,Object>> originals=(List<Map<String,Object>>)request.get("originals");
        if(labels==null||labels.isEmpty()||labels.size()>255||rows==null||originals==null||rows.size()!=originals.size()||rows.size()>((Number)policy.get("maxRows")).intValue())throw new IllegalArgumentException("逐行结果结构或行数无效。");
        for(String label:labels)if("__gateway_sm4".equalsIgnoreCase(label))throw new IllegalArgumentException("业务列不能使用保留列名 __gateway_sm4。");
        validateTypes(request.get("types"),labels.size(),-1);
        List<String> envelopes=new ArrayList<>();long size=0;
        for(int i=0;i<rows.size();i++){
            Map<String,Object> row=new LinkedHashMap<>(request);row.put("displayed",Collections.singletonList(rows.get(i)));row.put("originals",Collections.singletonList(originals.get(i)));row.put("carrier",0);
            String envelope=(String)capture(row).get("envelope");size+=envelope.length();
            if(size>768*1024)throw new IllegalArgumentException("逐行密文总量超过 768 KiB，请减少查询范围。");
            envelopes.add(envelope);
        }
        return Collections.singletonMap("envelopes",envelopes);
    }
    public Map<String,Object> decryptRows(String csv,String administrator)throws Exception {
        List<List<String>> input=ExportCsv.parse(csv);
        if(input.isEmpty())throw new IllegalArgumentException("CSV 缺少表头。");
        List<String> fullHeader=input.remove(0);
        if(fullHeader.size()<2||!"__gateway_sm4".equals(fullHeader.get(fullHeader.size()-1)))throw new IllegalArgumentException("最后一列必须是 __gateway_sm4；不支持旧版末行文件。");
        if(input.size()>1000)throw new IllegalArgumentException("CSV 超过 1000 行。");
        List<String> labels=new ArrayList<>(fullHeader.subList(0,fullHeader.size()-1));
        List<List<String>> output=new ArrayList<>();output.add(labels);
        for(List<String> row:input){
            if(row.size()!=fullHeader.size())throw new IllegalArgumentException("CSV 行列数不一致。");
            List<String> displayed=new ArrayList<>(row.subList(0,labels.size()));
            List<String> envelopeRow=new ArrayList<>(Collections.nCopies(labels.size(),""));envelopeRow.set(0,row.get(labels.size()));
            // Use the existing receipt, identity, permission and authenticated content validation.
            // This intermediate serialization must not apply spreadsheet formula escaping.
            String single=rawCsv(Arrays.asList(labels,displayed,envelopeRow));
            Map<String,Object> restored=decrypt(single,administrator,"ROW_V2");
            output.add(ExportCsv.parse((String)restored.get("csv")).get(1));
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("csv",ExportCsv.write(output));result.put("filename","decrypted-rows-"+UUID.randomUUID()+".csv");result.put("rowCount",input.size());return result;
    }
    private static String rawCsv(List<List<String>> rows){
        StringBuilder result=new StringBuilder();
        for(List<String> row:rows){for(int i=0;i<row.size();i++){if(i>0)result.append(',');result.append('"').append(row.get(i).replace("\"","\"\"")).append('"');}result.append("\r\n");}return result.toString();
    }
    public Map<String,Object> decrypt(String csv,String administrator)throws Exception {return decrypt(csv,administrator,null);}
    @SuppressWarnings("unchecked") private Map<String,Object> decrypt(String csv,String administrator,String requiredFormat)throws Exception {
        if(!slots.tryAcquire())throw new IllegalStateException("解密服务繁忙，请稍后重试。");
        String id="unverified";
        try{
            List<List<String>> rows=ExportCsv.parse(csv);if(rows.size()<2)throw new IllegalArgumentException("需要包含表头和最后一行密文的 CSV。");
            List<String> labels=rows.remove(0),trailer=rows.remove(rows.size()-1);int carrier=-1;String envelope=null;
            if(trailer.size()!=labels.size())throw new IllegalArgumentException("密文行列数不一致。");
            for(int i=0;i<trailer.size();i++)if(trailer.get(i).startsWith(Sm4ExportCodec.PREFIX)){if(carrier!=-1)throw new IllegalArgumentException("密文行存在多个附件。");carrier=i;envelope=trailer.get(i);}else if(!trailer.get(i).isEmpty())throw new IllegalArgumentException("密文行的占位单元格必须为空。");
            if(carrier<0)throw new IllegalArgumentException("未找到末行密文；请检查导出是否截断或丢失最后一行。");
            Map<String,Object> p=json.readValue(Sm4ExportCodec.decrypt(envelope,key(Sm4ExportCodec.keyId(envelope))),Map.class);id=text(p,"id");
            if(requiredFormat!=null&&!requiredFormat.equals(p.get("format")))throw new IllegalArgumentException("不是逐行格式密文，请重新导出。");
            List<Map<String,Object>> receipts=jdbc.queryForList("SELECT * FROM sm4_export_receipts WHERE id=?",id);
            if(receipts.size()!=1||Boolean.TRUE.equals(receipts.get(0).get("revoked"))||((Number)p.get("expires")).longValue()<System.currentTimeMillis()
                    ||((Number)receipts.get(0).get("expires_at")).longValue()<System.currentTimeMillis())throw new SecurityException("密文不属于当前实例、已过期或已撤销。");
            String user=text(p,"username"),db=text(p,"database");Map<String,Object> policy=profile(user,db);
            if(!Boolean.TRUE.equals(policy.get("enabled"))||!Boolean.TRUE.equals(policy.get("allowDecrypt")))throw new SecurityException("该账号当前没有明文解密授权。");
            Map<String,Object> account=access.resolve(user,db);
            if(!Objects.equals(String.valueOf(account.get("epoch")),String.valueOf(p.get("epoch"))))throw new SecurityException("原账号凭据版本已变化，拒绝解密旧文件。");
            if(((Number)p.get("carrier")).intValue()!=carrier||!labels.equals(p.get("labels"))||((Number)p.get("rows")).intValue()!=rows.size()||!digest(labels,rows).equals(p.get("digest")))
                throw new IllegalArgumentException("文件与原结果不一致：可能被排序、删行、改值、改列或格式转换；拒绝猜测还原。");
            List<Map<String,String>> sources=bindSources((List<Map<String,String>>)p.get("sources"),true);
            checkPermissions(account,sources);
            List<Map<String,Object>> originals=(List<Map<String,Object>>)p.get("originals");
            // Also reject ambiguous files captured by an older worker.
            requireUnambiguousRows(rows,originals);
            for(int r=0;r<rows.size();r++)for(Map.Entry<String,Object> entry:originals.get(r).entrySet())rows.get(r).set(Integer.parseInt(entry.getKey()),entry.getValue()==null?"":String.valueOf(entry.getValue()));
            rows.add(0,labels);String output=ExportCsv.write(rows);
            store.audit("SM4_EXPORT_DECRYPTED","id="+id+",administrator="+administrator+",sourceUser="+user);
            Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("csv",output);result.put("filename","decrypted-"+id+".csv");return result;
        }catch(Exception e){store.audit("SM4_EXPORT_DECRYPT_DENIED","id="+id+",administrator="+administrator);throw e;}
        finally{slots.release();}
    }
    private List<Map<String,String>> bindSources(List<Map<String,String>> sources,boolean verify)throws Exception {
        List<Map<String,String>> result=new ArrayList<>();
        for(Map<String,String> source:sources){
            Map<String,Object> mapping=store.anyMapping(source.get("database"));
            if(!"ACTIVE".equals(mapping.get("status")))throw new SecurityException("来源库已解除接入或不可用。");
            long connection=((Number)mapping.get("connection_id")).longValue();String physical=String.valueOf(mapping.get("physical_database"));
            Map<String,Object> object=store.managedObject(connection,physical,source.get("table"));
            if(!"ACTIVE".equals(object.get("status")))throw new SecurityException("来源表当前不可用，拒绝解密或捕获。");
            String generation=String.valueOf(object.get("generation_id")),identity=identities.identity(connection,physical,source.get("table"));
            if(verify&&(!generation.equals(source.get("generation"))||!identity.equals(source.get("physicalIdentity"))))throw new SecurityException("来源表已删除、改名或同名重建，旧文件不可解密。");
            if(verify&&source.containsKey("physicalDatabase")&&!physical.equals(source.get("physicalDatabase")))throw new SecurityException("来源库映射已变化，旧文件不可解密。");
            Map<String,String> bound=new LinkedHashMap<>(source);bound.put("generation",generation);bound.put("physicalIdentity",identity);bound.put("physicalDatabase",physical);result.add(bound);
        }
        return result;
    }
    private void checkPermissions(Map<String,Object> account,List<Map<String,String>> sources)throws Exception {
        try(Connection c=DriverManager.getConnection(String.valueOf(account.get("url")),String.valueOf(account.get("username")),String.valueOf(account.get("password")))){
            for(Map<String,String> source:sources)try(Statement s=c.createStatement()){
                String physical=source.get("physicalDatabase");
                s.setQueryTimeout(5);try(ResultSet ignored=s.executeQuery("SELECT "+quote(source.get("column"))+" FROM "+quote(physical)+"."+quote(source.get("table"))+" WHERE 1=0")){ }
            }
        }
    }
    private static String quote(String s){if(s==null||s.isEmpty()||s.length()>255)throw new IllegalArgumentException("来源名称无效。");return "`"+s.replace("`","``")+"`";}
    private static void requireUnambiguousRows(List<List<String>> rows,List<Map<String,Object>> originals){
        Map<List<String>,Map<String,Object>> seen=new HashMap<>();
        for(int i=0;i<rows.size();i++){
            Map<String,Object> previous=seen.putIfAbsent(rows.get(i),originals.get(i));
            if(previous!=null&&!previous.equals(originals.get(i)))throw new IllegalArgumentException("存在脱敏后相同但原值不同的行，无法验证行对应关系；请在查询中包含稳定唯一标识。");
        }
    }
    private static void validateTypes(Object supplied,int columns,int carrier){
        if(!(supplied instanceof List)||((List<?>)supplied).size()!=columns)throw new IllegalArgumentException("缺少完整结果列类型，请同步升级 Proxy 和管理端。");
        List<?> types=(List<?>)supplied;
        for(int i=0;i<types.size();i++){
            Object value=types.get(i);if(!(value instanceof Integer))throw new IllegalArgumentException("结果列类型无效。");
            int t=(Integer)value;
            boolean text=t==Types.CHAR||t==Types.VARCHAR||t==Types.LONGVARCHAR||t==Types.NCHAR||t==Types.NVARCHAR||t==Types.LONGNVARCHAR;
            boolean numeric=t==Types.TINYINT||t==Types.SMALLINT||t==Types.INTEGER||t==Types.BIGINT||t==Types.NUMERIC||t==Types.DECIMAL;
            if((!text&&!numeric)||(i==carrier&&!text))throw new IllegalArgumentException("结果列或密文承载列类型不支持。");
        }
    }
    public List<Map<String,Object>> receipts(){return jdbc.queryForList("SELECT id,username,database_name AS databaseName,expires_at AS expiresAt,revoked,row_count AS rowCount FROM sm4_export_receipts ORDER BY expires_at DESC LIMIT 100");}
    public void revoke(String id){jdbc.update("UPDATE sm4_export_receipts SET revoked=TRUE WHERE id=?",id);store.audit("SM4_EXPORT_REVOKED","id="+id);}
    private String digest(List<String> labels,List<List<String>> rows)throws Exception{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(Arrays.asList(labels,rows))));}
    private static String text(Map<String,Object> r,String k){Object v=r.get(k);if(!(v instanceof String)||((String)v).isEmpty()||((String)v).length()>255)throw new IllegalArgumentException("缺少有效参数："+k);return (String)v;}
    private static int number(Map<String,Object> r,String k,int def,int min,int max){Object v=r.get(k);int n=v==null?def:Integer.parseInt(String.valueOf(v));if(n<min||n>max)throw new IllegalArgumentException(k+" 超出范围。");return n;}
}
