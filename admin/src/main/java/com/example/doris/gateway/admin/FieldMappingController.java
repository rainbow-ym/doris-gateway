package com.example.doris.gateway.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

@RestController
@RequestMapping("/api/field-mappings")
public class FieldMappingController {
    private final FieldMappingRules rules;private final JdbcTemplate jdbc;private final AdminStore store;
    public FieldMappingController(FieldMappingRules rules,JdbcTemplate jdbc,AdminStore store){this.rules=rules;this.jdbc=jdbc;this.store=store;}
    @GetMapping public List<Map<String,Object>> list(){return rules.list();}
    @PostMapping public Map<String,String> save(@RequestBody Map<String,Object> request){rules.save(request);store.audit("FIELD_MAPPING_SAVED","type="+request.get("sensitiveType"));return Collections.singletonMap("message","规则已保存；请扫描并预览字段建议，已确认的保护策略不会被自动覆盖。");}
    @DeleteMapping("/{id}") public void delete(@PathVariable long id){rules.delete(id);store.audit("FIELD_MAPPING_DELETED","id="+id);}
    @PostMapping("/preview") public Map<String,Object> preview(@RequestBody Map<String,Object> request){
        Map<String,Object> rule=new LinkedHashMap<>(request);rule.put("enabled",true);
        rule.putIfAbsent("databaseName","*");rule.putIfAbsent("tableName","*");
        List<Map<String,Object>> matched=new ArrayList<>();int count=0;
        for(Map<String,Object> c:jdbc.queryForList("SELECT database_name AS databaseName,table_name AS tableName,column_name AS columnName,status,assigned_policy AS assignedPolicy FROM metadata_columns ORDER BY id")) {
            if(FieldMappingRules.match(Collections.singletonList(rule),String.valueOf(c.get("databaseName")),String.valueOf(c.get("tableName")),String.valueOf(c.get("columnName")))!=null){count++;if(matched.size()<100)matched.add(c);}
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("total",count);result.put("sample",matched);return result;
    }
    @ExceptionHandler({IllegalArgumentException.class,org.springframework.dao.DataIntegrityViolationException.class}) @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String,String> bad(Exception e){return Collections.singletonMap("message",e instanceof IllegalArgumentException?e.getMessage():"规则已存在或配置超出限制，请编辑现有规则。");}
}
