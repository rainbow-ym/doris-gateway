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
import com.example.doris.gateway.export.ExportValue;
import com.example.doris.gateway.observability.Telemetry;
import com.example.doris.gateway.observability.Observation;
import static com.example.doris.gateway.observability.Observation.*;

/** Separate export authorization/envelope lifecycle. No plaintext result is stored in the system database. */
@Service
public class Sm4ExportService {
    private final JdbcTemplate jdbc;private final AdminStore store;private final AccountAccessService access;private final ObjectMapper json;
    @Value("${SM4_EXPORT_MAX_CONCURRENT:8}") private int maxConcurrent=8;
    private Semaphore slots;
    @Value("${SM4_EXPORT_AUDIT_RETENTION_DAYS:0}") private int auditRetentionDays=0;
    @org.springframework.beans.factory.annotation.Autowired private ObjectIdentityProvider identities;
    @Value("${SM4_EXPORT_KEY_FILE:/opt/gateway-admin/data/sm4-export-keys.properties}") private String keyFile;
    @Value("${SM4_EXPORT_KEY_ID:v1}") private String currentKey;
    public Sm4ExportService(JdbcTemplate jdbc,AdminStore store,AccountAccessService access,ObjectMapper json){this.jdbc=jdbc;this.store=store;this.access=access;this.json=json;}
    @PostConstruct public void initialize(){
        if(maxConcurrent<1||maxConcurrent>64)throw new IllegalArgumentException("SM4_EXPORT_MAX_CONCURRENT 必须为 1—64。");
        if(auditRetentionDays<0||auditRetentionDays>3650)throw new IllegalArgumentException("SM4_EXPORT_AUDIT_RETENTION_DAYS 必须为 0—3650；0 表示保留审计。");
        slots=new Semaphore(maxConcurrent);
        jdbc.execute("CREATE TABLE IF NOT EXISTS sm4_export_profiles(username VARCHAR(128),database_name VARCHAR(255),enabled BOOLEAN NOT NULL,allow_decrypt BOOLEAN NOT NULL,max_rows INT NOT NULL,ttl_minutes INT NOT NULL,PRIMARY KEY(username,database_name))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sm4_export_receipts(id VARCHAR(36) PRIMARY KEY,username VARCHAR(128),database_name VARCHAR(255),expires_at BIGINT NOT NULL,revoked BOOLEAN NOT NULL DEFAULT FALSE,row_count INT NOT NULL,nonce VARCHAR(64) NOT NULL UNIQUE)");
        jdbc.execute("ALTER TABLE sm4_export_profiles ADD COLUMN IF NOT EXISTS row_key_column VARCHAR(255) NOT NULL DEFAULT ''");
        jdbc.execute("ALTER TABLE sm4_export_profiles ADD COLUMN IF NOT EXISTS allow_edit BOOLEAN NOT NULL DEFAULT FALSE");
        jdbc.execute("ALTER TABLE sm4_export_profiles ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE");
        jdbc.execute("UPDATE sm4_export_profiles SET allow_edit=TRUE,row_key_column='' WHERE row_key_column<>''");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sm4_export_defaults(id INT PRIMARY KEY,linked BOOLEAN NOT NULL,enabled BOOLEAN NOT NULL,allow_decrypt BOOLEAN NOT NULL,allow_edit BOOLEAN NOT NULL,max_rows INT NOT NULL,ttl_minutes INT NOT NULL)");
        jdbc.execute("INSERT INTO sm4_export_defaults SELECT 1,FALSE,FALSE,FALSE,TRUE,200,1440 WHERE NOT EXISTS(SELECT 1 FROM sm4_export_defaults WHERE id=1)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS sm4_export_batches(id VARCHAR(36) PRIMARY KEY REFERENCES sm4_export_receipts(id) ON DELETE CASCADE,metadata CLOB NOT NULL)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS sm4_export_expiry ON sm4_export_receipts(expires_at)");
        if(auditRetentionDays>0)jdbc.execute("CREATE INDEX IF NOT EXISTS sm4_audit_retention ON audit_log(action,created_at)");

    }
    private static final String PROFILE_FIELDS="enabled,allow_decrypt AS allowDecrypt,allow_edit AS allowEdit,max_rows AS maxRows,ttl_minutes AS ttlMinutes,row_key_column AS rowKeyColumn,deleted";
    public List<Map<String,Object>> profiles(){
        List<Map<String,Object>> result=jdbc.queryForList("SELECT username,database_name AS databaseName,"+PROFILE_FIELDS+" FROM sm4_export_profiles ORDER BY username,database_name");
        Set<List<Object>> explicit=new HashSet<>();
        for(Map<String,Object> row:result){explicit.add(Arrays.asList(row.get("username"),row.get("databaseName")));row.put("source","人工配置");}
        result.removeIf(row->Boolean.TRUE.equals(row.get("deleted")));
        Map<String,Object> defaults=defaults();
        if(Boolean.TRUE.equals(defaults.get("linked")))for(Map<String,Object> binding:eligibleBindings()){
            if(!explicit.contains(Arrays.asList(binding.get("username"),binding.get("databaseName")))){
                Map<String,Object> row=new LinkedHashMap<>(defaults);row.putAll(binding);row.put("source","自动关联");result.add(row);
            }
        }
        return result;
    }
    private List<Map<String,Object>> eligibleBindings(){
        return jdbc.queryForList("SELECT DISTINCT c.username,b.logical_name AS databaseName FROM account_database_bindings b JOIN connections c ON c.id=b.connection_id JOIN logical_databases d ON d.logical_name=b.logical_name WHERE b.status='ACTIVE' AND d.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM account_lifecycle_states l WHERE l.username=c.username AND l.status<>'ACTIVE')");
    }
    public Map<String,Object> defaults(){Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);return jdbc.queryForMap("SELECT linked,enabled,allow_decrypt AS allowDecrypt,allow_edit AS allowEdit,max_rows AS maxRows,ttl_minutes AS ttlMinutes FROM sm4_export_defaults WHERE id=1");}
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void saveDefaults(Map<String,Object> r)throws Exception{
        int rows=number(r,"maxRows",200,1,1000),ttl=ttl(r,defaults());
        if(Boolean.TRUE.equals(r.get("linked"))&&Boolean.TRUE.equals(r.get("enabled")))key(currentKey);
        jdbc.update("UPDATE sm4_export_defaults SET linked=?,enabled=?,allow_decrypt=?,allow_edit=?,max_rows=?,ttl_minutes=? WHERE id=1",Boolean.TRUE.equals(r.get("linked")),Boolean.TRUE.equals(r.get("enabled")),Boolean.TRUE.equals(r.get("allowDecrypt")),Boolean.TRUE.equals(r.get("allowEdit")),rows,ttl);
        store.audit("SM4_EXPORT_DEFAULTS_CHANGED","linked="+Boolean.TRUE.equals(r.get("linked"))+",enabled="+Boolean.TRUE.equals(r.get("enabled"))+",decrypt="+Boolean.TRUE.equals(r.get("allowDecrypt")));
    }
    public Map<String,Object> profile(String user,String db){
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.POLICY)){
            Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT "+PROFILE_FIELDS+" FROM sm4_export_profiles WHERE username=? AND database_name=?",user,db);
            if(!rows.isEmpty())return protocol(rows.get(0));
            Map<String,Object> defaults=defaults();
            if(Boolean.TRUE.equals(defaults.get("linked"))){
                Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);
                if(jdbc.queryForObject("SELECT COUNT(*) FROM account_database_bindings b JOIN connections c ON c.id=b.connection_id JOIN logical_databases d ON d.logical_name=b.logical_name WHERE c.username=? AND b.logical_name=? AND b.status='ACTIVE' AND d.status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM account_lifecycle_states l WHERE l.username=c.username AND l.status<>'ACTIVE')",Integer.class,user,db)>0)return protocol(defaults);
            }
            return protocol(Collections.singletonMap("enabled",false));
        }
    }
    private Map<String,Object> protocol(Map<String,Object> policy){
        Map<String,Object> result=new LinkedHashMap<>(policy);result.put("captureProtocol","ROW_TYPED_V1");return result;
    }
    private int ttl(Map<String,Object> r,Map<String,Object> previous){
        if(r.containsKey("ttlDays")){
            if(r.containsKey("ttlMinutes"))throw new IllegalArgumentException("有效期请只提交天数或分钟。");
            return number(r,"ttlDays",1,1,365)*1440;
        }
        return number(r,"ttlMinutes",((Number)previous.getOrDefault("ttlMinutes",60)).intValue(),1,365*1440);
    }
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void save(Map<String,Object> r)throws Exception {
        String user=text(r,"username"),db=text(r,"databaseName");access.resolve(user,db);
        Map<String,Object> previous=profile(user,db);
        int rows=number(r,"maxRows",200,1,1000),ttl=ttl(r,previous);
        // Keep old API callers compatible; the new UI only writes allowEdit, never a business column.
        Object supplied=r.containsKey("rowKeyColumn")?r.get("rowKeyColumn"):previous.getOrDefault("rowKeyColumn","");
        if(!(supplied instanceof String)||((String)supplied).length()>255||"__gateway_sm4".equalsIgnoreCase((String)supplied))throw new IllegalArgumentException("记录标识列无效。");
        boolean edit=Boolean.TRUE.equals(r.getOrDefault("allowEdit",previous.get("allowEdit")));
        String anchor=r.containsKey("allowEdit")?"":(String)supplied;
        if(Boolean.TRUE.equals(r.get("enabled")))key(currentKey);
        jdbc.update("MERGE INTO sm4_export_profiles(username,database_name,enabled,allow_decrypt,max_rows,ttl_minutes,row_key_column,allow_edit,deleted) KEY(username,database_name) VALUES(?,?,?,?,?,?,?,?,FALSE)",user,db,Boolean.TRUE.equals(r.get("enabled")),Boolean.TRUE.equals(r.get("allowDecrypt")),rows,ttl,anchor,edit);
        store.audit("SM4_EXPORT_PROFILE_CHANGED","user="+user+",database="+db+",enabled="+Boolean.TRUE.equals(r.get("enabled"))+",decrypt="+Boolean.TRUE.equals(r.get("allowDecrypt")));
    }
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void saveBatch(Map<String,Object> r)throws Exception{
        Object supplied=r.get("databaseNames");
        if(!(supplied instanceof List)||((List<?>)supplied).isEmpty()||((List<?>)supplied).size()>100)throw new IllegalArgumentException("请选择 1—100 个逻辑库。");
        Set<Object> seen=new HashSet<>();
        for(Object db:(List<?>)supplied){if(!seen.add(db))throw new IllegalArgumentException("逻辑库不能重复。");Map<String,Object> item=new LinkedHashMap<>(r);item.put("databaseName",db);save(item);}
    }
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void deleteProfile(Map<String,Object> r){
        String user=text(r,"username"),db=text(r,"databaseName");
        // A tombstone prevents auto-link from silently granting a deleted profile again.
        jdbc.update("MERGE INTO sm4_export_profiles(username,database_name,enabled,allow_decrypt,max_rows,ttl_minutes,row_key_column,allow_edit,deleted) KEY(username,database_name) VALUES(?,?,FALSE,FALSE,200,1440,'',FALSE,TRUE)",user,db);
        store.audit("SM4_EXPORT_PROFILE_DELETED","user="+user+",database="+db);
    }
    private byte[] key(String id)throws Exception {
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.KEY_LOAD)){
        Properties p=new Properties();Path path=Paths.get(keyFile);
        if(!Files.isRegularFile(path)||Files.size(path)>65536)throw new IllegalStateException("未配置 SM4 密钥文件，请运行安装包的密钥初始化脚本；不要覆盖已有密钥。");
        try(java.io.InputStream in=Files.newInputStream(path)){p.load(in);}
        String encoded=p.getProperty(id);if(encoded==null)throw new IllegalStateException("密钥版本不可用，请恢复对应的原密钥文件。");
        byte[] result=Base64.getDecoder().decode(encoded);if(result.length!=16)throw new IllegalStateException("SM4 密钥长度必须为 16 字节。");return result;
        }
    }
    @SuppressWarnings("unchecked") public Map<String,Object> capture(Map<String,Object> request)throws Exception {
        if(!slots.tryAcquire())throw new IllegalStateException("加密导出繁忙，请稍后重试。");
        try{
            String user=text(request,"username"),db=text(request,"database");Map<String,Object> policy=profile(user,db);
            if(!Boolean.TRUE.equals(policy.get("enabled")))throw new SecurityException("该账号和数据库未启用加密。");
            Map<String,Object> account=access.resolve(user,db);
            List<Object[]> receipts=new ArrayList<>();
            Map<String,Object> result=capturePrepared(request,policy,account,null,key(currentKey),receipts);
            persistReceipts(receipts);return result;
        }finally{slots.release();}
    }
    @SuppressWarnings("unchecked") private Map<String,Object> capturePrepared(Map<String,Object> request,Map<String,Object> policy,
            Map<String,Object> account,List<Map<String,String>> boundSources,byte[] encryptionKey,List<Object[]> receipts)throws Exception {
            String user=text(request,"username"),db=text(request,"database");
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
            payload.put("labels",labels);payload.put("originals",originals);payload.put("sources",boundSources==null?bindSources(sources,false):boundSources);payload.put("rows",displayed.size());
            payload.put("digest",digest(labels,displayed));payload.put("carrier",request.get("carrier"));
            payload.put("types",request.get("types"));
            payload.put("format",request.get("format"));
            byte[] plain;try(Observation.Scope ignored=Telemetry.current().stage(Stage.SERIALIZE)){plain=json.writeValueAsBytes(payload);}
            Telemetry.current().add(Measure.PAYLOAD_BYTES,plain.length);
            String envelope;try(Observation.Scope ignored=Telemetry.current().stage(Stage.SM4_ENCRYPT)){envelope=Sm4ExportCodec.encrypt(plain,currentKey,encryptionKey);}
            Telemetry.current().add(Measure.CIPHERTEXT_BYTES,envelope.length());
            if(envelope.length()>30000)throw new IllegalArgumentException("SM4 密文超过单元格 30000 字符上限，请减少查询行数。");
            String packet=envelope.substring(envelope.lastIndexOf(':')+1);
            receipts.add(new Object[]{id,user,db,expires,displayed.size(),packet.substring(0,16)});
            return Collections.singletonMap("envelope",envelope);
    }
    private void persistReceipts(List<Object[]> receipts){
        if(receipts.isEmpty())return;
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.RECEIPT_WRITE)){
        Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);
        jdbc.batchUpdate("INSERT INTO sm4_export_receipts(id,username,database_name,expires_at,row_count,nonce) VALUES(?,?,?,?,?,?)",receipts);}
        List<Object[]> audit=new ArrayList<>();String now=java.time.Instant.now().toString();
        for(Object[] row:receipts)audit.add(new Object[]{"SM4_EXPORT_CAPTURED","id="+row[0]+",user="+row[1]+",database="+row[2]+",rows="+row[4],now});
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.AUDIT_WRITE)){
        Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);
        jdbc.batchUpdate("INSERT INTO audit_log(action,detail,created_at) VALUES(?,?,?)",audit);}
    }
    /** A batch contains independently authenticated rows; one row combines all protected cells. */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    @SuppressWarnings("unchecked") public Map<String,Object> captureRows(Map<String,Object> request)throws Exception {
        if(!slots.tryAcquire()){Telemetry.current().reason(Reason.CAPACITY);throw new IllegalStateException("加密导出繁忙，请稍后重试。");}
        try{
        if(!"ROW_V2".equals(request.get("format"))&&!"ROW_TYPED_V1".equals(request.get("format")))throw new IllegalArgumentException("需要逐行密文协议，请配套升级 Proxy。");
        if(json.writeValueAsBytes(request).length>1024*1024)throw new IllegalArgumentException("逐行加密请求超过 1 MiB。");
        Map<String,Object> policy=profile(text(request,"username"),text(request,"database"));
        if(!Boolean.TRUE.equals(policy.get("enabled")))throw new SecurityException("未启用逐行加密。");
        if("ROW_TYPED_V1".equals(request.get("format")))return captureTyped(request,policy);
        Map<String,Object> account;try(Observation.Scope ignored=Telemetry.current().stage(Stage.ACCOUNT_CHECK)){account=access.resolve(text(request,"username"),text(request,"database"));}
        List<String> labels=(List<String>)request.get("labels");
        List<List<String>> rows=(List<List<String>>)request.get("displayed");
        List<Map<String,Object>> originals=(List<Map<String,Object>>)request.get("originals");
        if(labels==null||labels.isEmpty()||labels.size()>255||rows==null||originals==null||rows.size()!=originals.size()||rows.size()>((Number)policy.get("maxRows")).intValue())throw new IllegalArgumentException("逐行结果结构或行数无效。");
        Telemetry.current().add(Measure.ROWS,rows.size());
        for(String label:labels)if("__gateway_sm4".equalsIgnoreCase(label))throw new IllegalArgumentException("业务列不能使用保留列名 __gateway_sm4。");
        validateTypes(request.get("types"),labels.size(),-1);
        List<Map<String,String>> sources=(List<Map<String,String>>)request.get("sources");
        if(sources==null||sources.isEmpty()||sources.size()>256)throw new IllegalArgumentException("导出来源无效。");
        // Reuse only within this request: later queries must recheck identity and authorization.
        List<Map<String,String>> boundSources=bindSources(sources,false);
        byte[] encryptionKey=key(currentKey);
        if(Boolean.TRUE.equals(policy.get("allowEdit"))||!String.valueOf(policy.getOrDefault("rowKeyColumn","")).isEmpty())return captureEditable(request,policy,account,boundSources,encryptionKey);
        List<Object[]> receipts=new ArrayList<>();
        List<String> envelopes=new ArrayList<>();long size=0;
        for(int i=0;i<rows.size();i++){
            Map<String,Object> row=new LinkedHashMap<>(request);row.put("displayed",Collections.singletonList(rows.get(i)));row.put("originals",Collections.singletonList(originals.get(i)));row.put("carrier",0);
            String envelope=(String)capturePrepared(row,policy,account,boundSources,encryptionKey,receipts).get("envelope");size+=envelope.length();
            if(size>768*1024)throw new IllegalArgumentException("逐行密文总量超过 768 KiB，请减少查询范围。");
            envelopes.add(envelope);
        }
        persistReceipts(receipts);
        return Collections.singletonMap("envelopes",envelopes);
        }finally{slots.release();}
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> captureTyped(Map<String,Object> request,Map<String,Object> policy)throws Exception{
        boolean editable=Boolean.TRUE.equals(policy.get("allowEdit"));
        if(!Objects.equals(request.get("allowEdit"),editable))throw new IllegalStateException("加密配置在查询期间发生变化，请重新查询。");
        List<String> labels=(List<String>)request.get("labels");List<?> types=(List<?>)request.get("types"),typeNames=(List<?>)request.get("typeNames");
        List<String> digests=(List<String>)request.get("digests");List<Map<String,Object>> originals=(List<Map<String,Object>>)request.get("originals");
        List<Map<String,String>> sources=(List<Map<String,String>>)request.get("sources");
        if(labels==null||labels.isEmpty()||labels.size()>255||types==null||typeNames==null||types.size()!=labels.size()||typeNames.size()!=labels.size()||originals==null||digests==null||digests.size()!=originals.size()||originals.size()>((Number)policy.get("maxRows")).intValue())throw new IllegalArgumentException("类型化加密结构或行列数量无效。");
        uniqueLabels(labels);
        for(int i=0;i<labels.size();i++){
            if("__gateway_sm4".equalsIgnoreCase(labels.get(i))||!(types.get(i) instanceof Integer)||!(typeNames.get(i) instanceof String))throw new IllegalArgumentException("结果列定义无效，第 "+(i+1)+" 列。");
        }
        if(sources==null||sources.isEmpty()||sources.size()>256)throw new IllegalArgumentException("保护字段来源无效。");
        if(originals.isEmpty())return Collections.singletonMap("envelopes",Collections.emptyList());
        List<Integer> indexes=new ArrayList<>();
        for(String value:originals.get(0).keySet()){
            int index=Integer.parseInt(value);if(index<0||index>=labels.size()||!value.equals(String.valueOf(index)))throw new IllegalArgumentException("保护字段位置无效。");indexes.add(index);
        }
        Collections.sort(indexes);if(indexes.isEmpty())throw new IllegalArgumentException("缺少受保护字段。");
        List<String> protectedLabels=new ArrayList<>();for(int index:indexes)protectedLabels.add(labels.get(index));
        String user=text(request,"username"),db=text(request,"database");Map<String,Object> account;
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.ACCOUNT_CHECK)){account=access.resolve(user,db);}
        byte[] encryptionKey=key(currentKey);String id=UUID.randomUUID().toString();long expires=System.currentTimeMillis()+((Number)policy.get("ttlMinutes")).longValue()*60000;
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("v",5);metadata.put("id",id);metadata.put("username",user);metadata.put("database",db);
        metadata.put("epoch",account.get("epoch"));metadata.put("expires",expires);metadata.put("sources",bindSources(sources,false));
        metadata.put("editable",editable);metadata.put("protectedLabels",protectedLabels);metadata.put("checkedLabels",editable?protectedLabels:labels);
        metadata.put("labels",labels);metadata.put("types",types);metadata.put("typeNames",typeNames);
        String common=encryptObserved(metadata,encryptionKey);if(common.length()>30000)throw new IllegalArgumentException("类型化批次元数据过大。");
        List<String> envelopes=new ArrayList<>();long bytes=0;
        for(int i=0;i<originals.size();i++){
            Map<String,Object> original=originals.get(i);if(!original.keySet().equals(originals.get(0).keySet()))throw new IllegalArgumentException("各行受保护字段不一致。");
            String digest=digests.get(i);
            if(digest==null||digest.length()!=44||Base64.getDecoder().decode(digest).length!=32)throw new IllegalArgumentException("显示内容摘要无效。");
            List<Object> cells=new ArrayList<>();for(int index:indexes){Object cell=original.get(String.valueOf(index));String decoded=ExportValue.decode(cell);cells.add(cell);Telemetry.current().add(Measure.RAW_BYTES,decoded.getBytes(StandardCharsets.UTF_8).length);Telemetry.current().add(Measure.PROTECTED_CELLS,1);}
            Map<String,Object> packet=new LinkedHashMap<>();packet.put("v",5);packet.put("b",id);packet.put("r",i);packet.put("o",cells);packet.put("d",digest);
            String token=encryptObserved(packet,encryptionKey);bytes+=token.length();
            if(token.length()>30000||bytes>768*1024)throw new IllegalArgumentException("类型化密文大小超限，请缩小查询范围。");envelopes.add(token);
        }
        Telemetry.current().add(Measure.ROWS,originals.size());Telemetry.current().add(Measure.CIPHERTEXT_BYTES,bytes);
        persistReceipts(Collections.singletonList(new Object[]{id,user,db,expires,originals.size(),id}));
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.RECEIPT_WRITE)){Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);jdbc.update("INSERT INTO sm4_export_batches(id,metadata) VALUES(?,?)",id,common);}
        return Collections.singletonMap("envelopes",envelopes);
    }
    public Map<String,Object> decryptRows(String csv,String administrator)throws Exception {
        List<List<String>> input;try(Observation.Scope ignored=Telemetry.current().stage(Stage.CSV_PARSE)){input=ExportCsv.parse(csv);}
        if(csv!=null)Telemetry.current().add(Measure.HTTP_BYTES,csv.getBytes(StandardCharsets.UTF_8).length);
        if(input.isEmpty())throw new IllegalArgumentException("CSV 缺少表头。");
        List<String> fullHeader=input.remove(0);
        if(fullHeader.size()<2||!"__gateway_sm4".equals(fullHeader.get(fullHeader.size()-1)))throw new IllegalArgumentException("最后一列必须是 __gateway_sm4；不支持旧版末行文件。");
        if(input.size()>1000)throw new IllegalArgumentException("CSV 超过 1000 行。");
        Telemetry.current().add(Measure.ROWS,input.size());
        if(!input.isEmpty()){
            List<String> first=input.get(0);
            if(first.size()!=fullHeader.size())throw new IllegalArgumentException("CSV 行列数不一致。");
            String envelope=first.get(first.size()-1);
            Map<?,?> packet;
            try{packet=json.readValue(decryptObserved(envelope,key(Sm4ExportCodec.keyId(envelope))),Map.class);}
            catch(Exception e){store.audit("SM4_EXPORT_DECRYPT_DENIED","id=unverified,administrator="+administrator);throw e;}
            if(Integer.valueOf(3).equals(packet.get("v"))||Integer.valueOf(4).equals(packet.get("v"))||Integer.valueOf(5).equals(packet.get("v")))return decryptEditable(fullHeader,input,administrator,((Number)packet.get("v")).intValue());
        }
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
    @SuppressWarnings("unchecked")
    private Map<String,Object> captureEditable(Map<String,Object> request,Map<String,Object> policy,
            Map<String,Object> account,List<Map<String,String>> sources,byte[] encryptionKey)throws Exception {
        List<String> labels=(List<String>)request.get("labels");
        List<List<String>> rows=(List<List<String>>)request.get("displayed");
        List<Map<String,Object>> originals=(List<Map<String,Object>>)request.get("originals");
        uniqueLabels(labels);
        boolean automatic=Boolean.TRUE.equals(policy.get("allowEdit"));
        String anchor=String.valueOf(policy.get("rowKeyColumn"));int anchorIndex=automatic?-1:labels.indexOf(anchor);
        if(!automatic&&anchorIndex<0)throw new IllegalArgumentException("可编辑导出必须包含记录标识列："+anchor);
        if(rows.isEmpty())return Collections.singletonMap("envelopes",Collections.emptyList());
        List<Integer> protectedIndexes=new ArrayList<>();
        for(String index:originals.get(0).keySet()){
            int column=Integer.parseInt(index);
            if(column<0||column>=labels.size()||!String.valueOf(column).equals(index))throw new IllegalArgumentException("保护字段位置无效。");
            if(column==anchorIndex)throw new IllegalArgumentException("记录标识列必须是未脱敏的唯一业务编号。");
            protectedIndexes.add(column);
        }
        Collections.sort(protectedIndexes);
        if(protectedIndexes.isEmpty())throw new IllegalArgumentException("缺少需要恢复的保护字段。");
        List<String> protectedLabels=new ArrayList<>();
        for(int column:protectedIndexes)protectedLabels.add(labels.get(column));
        List<String> checkedLabels=new ArrayList<>();if(!automatic)checkedLabels.add(anchor);checkedLabels.addAll(protectedLabels);
        String id=UUID.randomUUID().toString();long expires=System.currentTimeMillis()+((Number)policy.get("ttlMinutes")).longValue()*60000;
        Map<String,Object> metadata=new LinkedHashMap<>();
        metadata.put("id",id);metadata.put("username",request.get("username"));metadata.put("database",request.get("database"));
        metadata.put("expires",expires);metadata.put("epoch",account.get("epoch"));metadata.put("sources",sources);
        metadata.put("checkedLabels",checkedLabels);metadata.put("v",automatic?4:3);
        // Common metadata is authenticated and stored once; no original or displayed cell values are stored.
        String metadataEnvelope=encryptObserved(metadata,encryptionKey);
        if(metadataEnvelope.length()>30000)throw new IllegalArgumentException("导出来源元数据过大，请减少查询字段。");
        Set<String> anchors=new HashSet<>();List<String> envelopes=new ArrayList<>();long size=0;
        for(int r=0;r<rows.size();r++){
            List<String> row=rows.get(r);Map<String,Object> original=originals.get(r);
            if(row.size()!=labels.size()||!original.keySet().equals(originals.get(0).keySet()))throw new IllegalArgumentException("结果列或保护字段不一致。");
            if(!automatic){String identity=row.get(anchorIndex);
            if(identity==null||identity.isEmpty()||!anchors.add(identity))throw new IllegalArgumentException("记录标识列必须非空且在本次结果中唯一。");}
            List<Object> raw=new ArrayList<>();
            for(int column:protectedIndexes){
                Object value=original.get(String.valueOf(column));
                if(value!=null&&!(value instanceof String))throw new IllegalArgumentException("原值格式无效。");
                raw.add(value);
                Telemetry.current().add(Measure.PROTECTED_CELLS,1);
                if(value!=null)Telemetry.current().add(Measure.RAW_BYTES,((String)value).getBytes(StandardCharsets.UTF_8).length);
            }
            Map<String,Object> packet=new LinkedHashMap<>();
            packet.put("v",automatic?4:3);packet.put("b",id);packet.put("r",r);packet.put("o",raw);
            packet.put("d",digest(checkedLabels,Collections.singletonList(selectColumns(labels,row,checkedLabels))));
            String envelope=encryptObserved(packet,encryptionKey);
            Telemetry.current().add(Measure.CIPHERTEXT_BYTES,envelope.length());
            size+=envelope.length();
            if(envelope.length()>30000||size>768*1024)throw new IllegalArgumentException("逐行密文大小超限，请减少查询范围。");
            envelopes.add(envelope);
        }
        persistReceipts(Collections.singletonList(new Object[]{id,request.get("username"),request.get("database"),expires,rows.size(),id}));
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.RECEIPT_WRITE)){
        Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);jdbc.update("INSERT INTO sm4_export_batches(id,metadata) VALUES(?,?)",id,metadataEnvelope);}
        return Collections.singletonMap("envelopes",envelopes);
    }
    private static void uniqueLabels(List<String> labels){
        if(labels.contains("")||labels.contains(null)||new HashSet<>(labels).size()!=labels.size())
            throw new IllegalArgumentException("可编辑 CSV 的表头必须非空且不能重名，请给查询列设置唯一别名。");
    }
    private static List<String> selectColumns(List<String> labels,List<String> row,List<String> selected){
        List<String> values=new ArrayList<>();
        for(String label:selected){
            int index=labels.indexOf(label);
            if(index<0)throw new IllegalArgumentException("文件必需列被删除、改名："+label);
            values.add(row.get(index));
        }
        return values;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> decryptEditable(List<String> header,List<List<String>> input,String administrator,int format)throws Exception {
        if(!slots.tryAcquire()){Telemetry.current().reason(Reason.CAPACITY);throw new IllegalStateException("解密服务繁忙，请稍后重试。");}
        String batch="unverified";int csvRow=1;
        try{
            uniqueLabels(header);List<String> labels=new ArrayList<>(header.subList(0,header.size()-1));
            List<List<String>> output=new ArrayList<>();output.add(labels);
            Map<String,byte[]> keys=new HashMap<>();Map<String,Object> metadata=null;
            List<String> checkedLabels=null;Set<Integer> seen=new HashSet<>();int total=0;
            for(List<String> row:input){
                csvRow++;
                if(row.size()!=header.size())throw new IllegalArgumentException("CSV 行列数不一致。");
                String envelope=row.get(row.size()-1),version=Sm4ExportCodec.keyId(envelope);
                if(!keys.containsKey(version))keys.put(version,key(version));
                Telemetry.current().add(Measure.CIPHERTEXT_BYTES,envelope.length());
                Map<String,Object> packet=json.readValue(decryptObserved(envelope,keys.get(version)),Map.class);
                if(!Integer.valueOf(format).equals(packet.get("v")))throw new IllegalArgumentException("不能混合新旧格式文件。");
                String id=text(packet,"b");
                if(metadata==null){
                    batch=id;
                    List<Map<String,Object>> records;try(Observation.Scope ignored=Telemetry.current().stage(Stage.RECEIPT_CHECK)){
                    Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);records=jdbc.queryForList("SELECT r.*,b.metadata FROM sm4_export_receipts r JOIN sm4_export_batches b ON b.id=r.id WHERE r.id=?",id);}
                    if(records.size()!=1||Boolean.TRUE.equals(records.get(0).get("revoked"))
                            ||((Number)records.get(0).get("expires_at")).longValue()<System.currentTimeMillis())
                        {Telemetry.current().reason(records.size()!=1?Reason.PERMISSION_DENIED:Boolean.TRUE.equals(records.get(0).get("revoked"))?Reason.REVOKED:Reason.EXPIRED);throw new SecurityException("查询批次已过期、撤销或不存在。");}
                    String saved=String.valueOf(records.get(0).get("metadata")),savedVersion=Sm4ExportCodec.keyId(saved);
                    if(!keys.containsKey(savedVersion))keys.put(savedVersion,key(savedVersion));
                    metadata=json.readValue(decryptObserved(saved,keys.get(savedVersion)),Map.class);
                    if(!id.equals(metadata.get("id"))||((Number)metadata.get("expires")).longValue()<System.currentTimeMillis())
                        throw new SecurityException("查询批次身份或有效期无效。");
                    String user=text(metadata,"username"),db=text(metadata,"database");Map<String,Object> policy=profile(user,db);
                    if(!Boolean.TRUE.equals(policy.get("allowDecrypt")))throw new SecurityException("该账号当前没有明文解密授权。");
                    Map<String,Object> account;try(Observation.Scope ignored=Telemetry.current().stage(Stage.ACCOUNT_CHECK)){account=access.resolve(user,db);}
                    if(!Objects.equals(String.valueOf(account.get("epoch")),String.valueOf(metadata.get("epoch")))){Telemetry.current().reason(Reason.ACCOUNT_CHANGED);throw new SecurityException("原账号凭据版本已变化。");}
                    checkPermissions(account,bindSources((List<Map<String,String>>)metadata.get("sources"),true));
                    if(format>=4&&!Integer.valueOf(format).equals(metadata.get("v")))throw new IllegalArgumentException("密文版本与批次不一致。");
                    checkedLabels=(List<String>)metadata.get("checkedLabels");
                    if(format==5&&!Boolean.TRUE.equals(metadata.get("editable"))&&!labels.equals(checkedLabels))throw new IllegalArgumentException("严格模式不允许修改业务列结构。");
                    total=((Number)records.get(0).get("row_count")).intValue();
                }
                if(!batch.equals(id))throw new IllegalArgumentException("不能混合不同查询批次的密文。");
                Object ordinal=packet.get("r");
                if(!(ordinal instanceof Integer)||(Integer)ordinal<0||(Integer)ordinal>=total||!seen.add((Integer)ordinal))
                    throw new IllegalArgumentException("密文行重复或行标识无效。");
                List<String> checked=selectColumns(labels,row,checkedLabels);
                if(!digest(checkedLabels,Collections.singletonList(checked)).equals(packet.get("d")))
                    {Telemetry.current().reason(Reason.TAMPERED);throw new IllegalArgumentException(format>=4?"受保护内容被修改，或与该行密文不匹配；拒绝还原。":"受保护内容或记录标识被修改，或密文被放到其他记录；拒绝还原。");}
                List<Object> raw=(List<Object>)packet.get("o");
                int offset=format==3?1:0;
                List<String> restoreLabels=format==5?(List<String>)metadata.get("protectedLabels"):checkedLabels.subList(offset,checkedLabels.size());
                if(raw.size()!=restoreLabels.size())throw new IllegalArgumentException("密文保护字段结构无效。");
                List<String> restored=new ArrayList<>(row.subList(0,labels.size()));
                for(int i=0;i<raw.size();i++)restored.set(labels.indexOf(restoreLabels.get(i)),format==5?ExportValue.decode(raw.get(i)):raw.get(i)==null?"":String.valueOf(raw.get(i)));
                output.add(restored);
            }
            // Recheck revocation and expiry before releasing any plaintext.
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sm4_export_receipts WHERE id=? AND revoked=FALSE AND expires_at>=?",Integer.class,batch,System.currentTimeMillis())!=1)
                throw new SecurityException("查询批次已过期或撤销。");
            Map<String,Object> currentPolicy=profile(text(metadata,"username"),text(metadata,"database"));
            Map<String,Object> currentAccount=access.resolve(text(metadata,"username"),text(metadata,"database"));
            if(!Boolean.TRUE.equals(currentPolicy.get("allowDecrypt"))
                    ||!Objects.equals(String.valueOf(currentAccount.get("epoch")),String.valueOf(metadata.get("epoch"))))
                throw new SecurityException("解密期间账号授权已变化。");
            checkPermissions(currentAccount,bindSources((List<Map<String,String>>)metadata.get("sources"),true));
            store.audit("SM4_EXPORT_DECRYPTED","id="+batch+",administrator="+administrator+",rows="+input.size());
            Map<String,Object> result=new LinkedHashMap<>();result.put("csv",ExportCsv.write(output));
            result.put("filename","decrypted-editable-"+UUID.randomUUID()+".csv");result.put("rowCount",input.size());return result;
        }catch(Exception e){store.audit("SM4_EXPORT_DECRYPT_DENIED","id="+batch+",administrator="+administrator);if(e instanceof IllegalArgumentException)throw new IllegalArgumentException("CSV 第 "+csvRow+" 行："+e.getMessage(),e);throw e;}
        finally{slots.release();}
    }
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${SM4_EXPORT_CLEANUP_MS:60000}")
    public void cleanupExpiredExports(){
        // Bound each pass; cascade removes batch metadata, never keys or unexpired files.
        jdbc.update("DELETE FROM sm4_export_receipts WHERE id IN (SELECT id FROM sm4_export_receipts WHERE expires_at<? ORDER BY expires_at LIMIT 10000)",System.currentTimeMillis());
        if(auditRetentionDays==0)return;
        String cutoff=java.time.Instant.now().minus(java.time.Duration.ofDays(auditRetentionDays)).toString();
        jdbc.update("DELETE FROM audit_log WHERE id IN (SELECT id FROM audit_log WHERE action IN ('SM4_EXPORT_CAPTURED','SM4_EXPORT_DECRYPTED','SM4_EXPORT_DECRYPT_DENIED') AND created_at<? LIMIT 10000)",cutoff);
    }
    private static String rawCsv(List<List<String>> rows){
        StringBuilder result=new StringBuilder();
        for(List<String> row:rows){for(int i=0;i<row.size();i++){if(i>0)result.append(',');result.append('"').append(row.get(i).replace("\"","\"\"")).append('"');}result.append("\r\n");}return result.toString();
    }
    public Map<String,Object> decrypt(String csv,String administrator)throws Exception {return decrypt(csv,administrator,null);}
    @SuppressWarnings("unchecked") private Map<String,Object> decrypt(String csv,String administrator,String requiredFormat)throws Exception {
        if(!slots.tryAcquire()){Telemetry.current().reason(Reason.CAPACITY);throw new IllegalStateException("解密服务繁忙，请稍后重试。");}
        String id="unverified";
        try{
            List<List<String>> rows=ExportCsv.parse(csv);if(rows.size()<2)throw new IllegalArgumentException("需要包含表头和最后一行密文的 CSV。");
            List<String> labels=rows.remove(0),trailer=rows.remove(rows.size()-1);int carrier=-1;String envelope=null;
            if(trailer.size()!=labels.size())throw new IllegalArgumentException("密文行列数不一致。");
            for(int i=0;i<trailer.size();i++)if(trailer.get(i).startsWith(Sm4ExportCodec.PREFIX)){if(carrier!=-1)throw new IllegalArgumentException("密文行存在多个附件。");carrier=i;envelope=trailer.get(i);}else if(!trailer.get(i).isEmpty())throw new IllegalArgumentException("密文行的占位单元格必须为空。");
            if(carrier<0)throw new IllegalArgumentException("未找到末行密文；请检查导出是否截断或丢失最后一行。");
            byte[] decryptionKey=key(Sm4ExportCodec.keyId(envelope)),plain;
            Telemetry.current().add(Measure.CIPHERTEXT_BYTES,envelope.length());
            try(Observation.Scope ignored=Telemetry.current().stage(Stage.SM4_DECRYPT)){plain=Sm4ExportCodec.decrypt(envelope,decryptionKey);}
            Telemetry.current().add(Measure.PAYLOAD_BYTES,plain.length);
            Map<String,Object> p=json.readValue(plain,Map.class);id=text(p,"id");
            if(requiredFormat!=null&&!requiredFormat.equals(p.get("format")))throw new IllegalArgumentException("不是逐行格式密文，请重新导出。");
            List<Map<String,Object>> receipts;try(Observation.Scope ignored=Telemetry.current().stage(Stage.RECEIPT_CHECK)){
            Telemetry.current().add(Measure.SYSTEM_DB_CALLS,1);receipts=jdbc.queryForList("SELECT * FROM sm4_export_receipts WHERE id=?",id);}
            if(receipts.size()!=1||Boolean.TRUE.equals(receipts.get(0).get("revoked"))||((Number)p.get("expires")).longValue()<System.currentTimeMillis()
                    ||((Number)receipts.get(0).get("expires_at")).longValue()<System.currentTimeMillis()){
                Telemetry.current().reason(receipts.size()!=1?Reason.PERMISSION_DENIED:Boolean.TRUE.equals(receipts.get(0).get("revoked"))?Reason.REVOKED:Reason.EXPIRED);
                throw new SecurityException("密文不属于当前实例、已过期或已撤销。");}
            String user=text(p,"username"),db=text(p,"database");Map<String,Object> policy=profile(user,db);
            if(!Boolean.TRUE.equals(policy.get("allowDecrypt")))throw new SecurityException("该账号当前没有明文解密授权。");
            Map<String,Object> account;try(Observation.Scope ignored=Telemetry.current().stage(Stage.ACCOUNT_CHECK)){account=access.resolve(user,db);}
            if(!Objects.equals(String.valueOf(account.get("epoch")),String.valueOf(p.get("epoch")))){Telemetry.current().reason(Reason.ACCOUNT_CHANGED);throw new SecurityException("原账号凭据版本已变化，拒绝解密旧文件。");}
            if(((Number)p.get("carrier")).intValue()!=carrier||!labels.equals(p.get("labels"))||((Number)p.get("rows")).intValue()!=rows.size()||!digest(labels,rows).equals(p.get("digest")))
                {Telemetry.current().reason(Reason.TAMPERED);throw new IllegalArgumentException("文件与原结果不一致：可能被排序、删行、改值、改列或格式转换；拒绝猜测还原。");}
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
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.SOURCE_CHECK)){
        List<Map<String,String>> result=new ArrayList<>();
        Map<List<String>,Map<String,String>> checked=new HashMap<>();
        for(Map<String,String> source:sources){
            List<String> objectKey=Arrays.asList(source.get("database"),source.get("table"));
            Map<String,String> facts=checked.get(objectKey);
            if(facts==null){
            Map<String,Object> mapping=store.anyMapping(source.get("database"));
            if(!"ACTIVE".equals(mapping.get("status")))throw new SecurityException("来源库已解除接入或不可用。");
            long connection=((Number)mapping.get("connection_id")).longValue();String physical=String.valueOf(mapping.get("physical_database"));
            Map<String,Object> object=store.managedObject(connection,physical,source.get("table"));
            if(!"ACTIVE".equals(object.get("status")))throw new SecurityException("来源表当前不可用，拒绝解密或捕获。");
            String generation=String.valueOf(object.get("generation_id")),identity=identities.identity(connection,physical,source.get("table"));
            facts=new HashMap<>();facts.put("generation",generation);facts.put("identity",identity);facts.put("physical",physical);checked.put(objectKey,facts);
            }
            String generation=facts.get("generation"),identity=facts.get("identity"),physical=facts.get("physical");
            if(verify&&(!generation.equals(source.get("generation"))||!identity.equals(source.get("physicalIdentity")))){Telemetry.current().reason(Reason.SOURCE_CHANGED);throw new SecurityException("来源表已删除、改名或同名重建，旧文件不可解密。");}
            if(verify&&source.containsKey("physicalDatabase")&&!physical.equals(source.get("physicalDatabase"))){Telemetry.current().reason(Reason.SOURCE_CHANGED);throw new SecurityException("来源库映射已变化，旧文件不可解密。");}
            Map<String,String> bound=new LinkedHashMap<>(source);bound.put("generation",generation);bound.put("physicalIdentity",identity);bound.put("physicalDatabase",physical);result.add(bound);
        }
        return result;
        }
    }
    private void checkPermissions(Map<String,Object> account,List<Map<String,String>> sources)throws Exception {
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.PERMISSION_CHECK)){
        Connection connection;
        try(Observation.Scope connecting=Telemetry.current().stage(Stage.DB_CONNECT)){
            Telemetry.current().add(Measure.DB_CONNECTIONS,1);
            connection=DriverManager.getConnection(String.valueOf(account.get("url")),String.valueOf(account.get("username")),String.valueOf(account.get("password")));}
        try(Connection c=connection){
            for(Map<String,String> source:sources)try(Statement s=c.createStatement()){
                String physical=source.get("physicalDatabase");
                Telemetry.current().add(Measure.PERMISSION_PROBES,1);
                s.setQueryTimeout(5);try(ResultSet resultSet=s.executeQuery("SELECT "+quote(source.get("column"))+" FROM "+quote(physical)+"."+quote(source.get("table"))+" WHERE 1=0")){ }
            }
        }
        }
    }
    private static String quote(String s){if(s==null||s.isEmpty()||s.length()>255)throw new IllegalArgumentException("来源名称无效。");return "`"+s.replace("`","``")+"`";}
    private String encryptObserved(Object payload,byte[] encryptionKey)throws Exception {
        byte[] plain;try(Observation.Scope ignored=Telemetry.current().stage(Stage.SERIALIZE)){plain=json.writeValueAsBytes(payload);}
        Telemetry.current().add(Measure.PAYLOAD_BYTES,plain.length);
        try(Observation.Scope ignored=Telemetry.current().stage(Stage.SM4_ENCRYPT)){return Sm4ExportCodec.encrypt(plain,currentKey,encryptionKey);}
    }
    private byte[] decryptObserved(String envelope,byte[] decryptionKey)throws Exception {
        byte[] plain;try(Observation.Scope ignored=Telemetry.current().stage(Stage.SM4_DECRYPT)){plain=Sm4ExportCodec.decrypt(envelope,decryptionKey);}
        Telemetry.current().add(Measure.PAYLOAD_BYTES,plain.length);return plain;
    }
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
    public List<Map<String,Object>> receipts(){return jdbc.queryForList("SELECT r.id,r.username,r.database_name AS databaseName,r.expires_at AS expiresAt,r.revoked,r.row_count AS rowCount,CASE WHEN b.id IS NULL THEN '单行（旧格式）' ELSE '整次查询' END AS revokeScope FROM sm4_export_receipts r LEFT JOIN sm4_export_batches b ON b.id=r.id ORDER BY r.expires_at DESC LIMIT 100");}
    public void revoke(String id){jdbc.update("UPDATE sm4_export_receipts SET revoked=TRUE WHERE id=?",id);store.audit("SM4_EXPORT_REVOKED","id="+id);}
    private String digest(List<String> labels,List<List<String>> rows)throws Exception{return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(Arrays.asList(labels,rows))));}
    private static String text(Map<String,Object> r,String k){Object v=r.get(k);if(!(v instanceof String)||((String)v).isEmpty()||((String)v).length()>255)throw new IllegalArgumentException("缺少有效参数："+k);return (String)v;}
    private static int number(Map<String,Object> r,String k,int def,int min,int max){Object v=r.get(k);int n=v==null?def:Integer.parseInt(String.valueOf(v));if(n<min||n>max)throw new IllegalArgumentException(k+" 超出范围。");return n;}
}
