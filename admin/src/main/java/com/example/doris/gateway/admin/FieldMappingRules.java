package com.example.doris.gateway.admin;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.annotation.PostConstruct;
import java.util.*;

/** Cluster-local dictionary; no shared mutable static catalog. */
@Service
public class FieldMappingRules {
    private final JdbcTemplate jdbc;
    public FieldMappingRules(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @PostConstruct public void initialize(){jdbc.execute("CREATE TABLE IF NOT EXISTS field_mapping_rules(id BIGINT AUTO_INCREMENT PRIMARY KEY,database_name VARCHAR(255) NOT NULL,table_name VARCHAR(255) NOT NULL,match_kind VARCHAR(16) NOT NULL,expression VARCHAR(255) NOT NULL,sensitive_type VARCHAR(64) NOT NULL,enabled BOOLEAN NOT NULL DEFAULT TRUE,UNIQUE(database_name,table_name,match_kind,expression))");}
    public List<Map<String,Object>> list(){return jdbc.queryForList("SELECT id,database_name AS databaseName,table_name AS tableName,match_kind AS matchKind,expression,sensitive_type AS sensitiveType,enabled FROM field_mapping_rules ORDER BY id");}
    public synchronized void save(Map<String,Object> request){
        String db=value(request,"databaseName","*"),table=value(request,"tableName","*"),kind=value(request,"matchKind","EXACT"),expression=value(request,"expression",""),type=value(request,"sensitiveType","");
        if(!Arrays.asList("EXACT","PREFIX","SUFFIX").contains(kind)||expression.isEmpty()||expression.length()>255||db.length()>255||table.length()>255)throw new IllegalArgumentException("请填写有效匹配条件；支持精确、前缀、后缀匹配。");
        if(!Arrays.asList("ID_CARD","PERSON_NAME","MOBILE_PHONE","ADDRESS").contains(type))throw new IllegalArgumentException("请选择字段的敏感类型。");
        Long id=request.get("id")==null?null:Long.valueOf(String.valueOf(request.get("id")));
        boolean enabled=Boolean.parseBoolean(value(request,"enabled","true"));
        if(id==null)jdbc.update("INSERT INTO field_mapping_rules(database_name,table_name,match_kind,expression,sensitive_type,enabled) VALUES(?,?,?,?,?,?)",db,table,kind,expression,type,enabled);
        else jdbc.update("UPDATE field_mapping_rules SET database_name=?,table_name=?,match_kind=?,expression=?,sensitive_type=?,enabled=? WHERE id=?",db,table,kind,expression,type,enabled,id);
    }
    public void delete(long id){jdbc.update("DELETE FROM field_mapping_rules WHERE id=?",id);}
    public String recommend(String db,String table,String column){return match(list(),db,table,column);}
    static String match(List<Map<String,Object>> rules,String db,String table,String column){
        int best=-1;Set<String> found=new LinkedHashSet<>();
        for(Map<String,Object> rule:rules){
            if(!Boolean.TRUE.equals(rule.get("enabled")))continue;
            String d=String.valueOf(rule.get("databaseName")),t=String.valueOf(rule.get("tableName"));
            if(!"*".equals(d)&&!d.equalsIgnoreCase(db)||!"*".equals(t)&&!t.equalsIgnoreCase(table))continue;
            String kind=String.valueOf(rule.get("matchKind")),e=String.valueOf(rule.get("expression")).toLowerCase(Locale.ROOT),c=column.toLowerCase(Locale.ROOT);
            boolean hit="EXACT".equals(kind)?c.equals(e):"PREFIX".equals(kind)?c.startsWith(e):c.endsWith(e);
            if(!hit)continue;
            int rank=("EXACT".equals(kind)?4:0)+("*".equals(d)?0:2)+("*".equals(t)?0:1);
            String type=String.valueOf(rule.get("sensitiveType"));
            if(rank>best){best=rank;found.clear();found.add(type);}
            else if(rank==best)found.add(type);
        }
        if(found.size()>1)throw new IllegalArgumentException("字段映射规则冲突："+db+"."+table+"."+column+"；请调整规则后重试。");
        return found.isEmpty()?null:found.iterator().next();
    }
    static String value(Map<String,Object> r,String key,String fallback){Object v=r.get(key);return v==null||String.valueOf(v).trim().isEmpty()?fallback:String.valueOf(v).trim();}
}
