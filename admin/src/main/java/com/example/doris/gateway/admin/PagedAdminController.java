package com.example.doris.gateway.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;

@RestController
public class PagedAdminController {
    private final JdbcTemplate jdbc;private final AdminStore store;
    public PagedAdminController(JdbcTemplate jdbc,AdminStore store){this.jdbc=jdbc;this.store=store;}
    @GetMapping("/api/pages/{resource}") public Map<String,Object> page(@PathVariable String resource,@RequestParam(defaultValue="1") int page,
        @RequestParam(defaultValue="25") int size,@RequestParam(required=false) String database,@RequestParam(required=false) String status,
        @RequestParam(required=false) Long connectionId,@RequestParam(defaultValue="") String q){
        if(page<1||page>100000||size<1||size>100)throw new IllegalArgumentException("分页参数超出范围。");
        String from,select,order="id DESC";List<String> conditions=new ArrayList<>();List<Object> args=new ArrayList<>();String databaseColumn=null;
        switch(resource){
            case "objects":from="managed_objects";select="id,connection_id AS connectionId,database_name AS databaseName,table_name AS tableName,status,last_error AS lastError";databaseColumn="database_name";break;
            case "executions":from="managed_sql_executions";select="id,execution_id AS executionId,logical_database AS logicalDatabase,target_table AS targetTable,statement_type AS statementType,status,message,updated_at AS updatedAt";databaseColumn="logical_database";break;
            case "events":from="lineage_events";select="id,event_id AS eventId,logical_database AS logicalDatabase,event_type AS eventType,statement_type AS statementType,created_at AS createdAt";databaseColumn="logical_database";break;
            case "audit":from="audit_log";select="id,action,detail,created_at AS createdAt";break;
            case "versions":from="publish_versions";select="id,database_name AS databaseName,status,message,created_at AS createdAt";databaseColumn="database_name";break;
            case "columns":
                from="metadata_columns";select="id,connection_id AS connectionId,database_name AS databaseName,table_name AS tableName,column_name AS columnName,recommended_policy AS recommendedPolicy,assigned_policy AS assignedPolicy,status";databaseColumn="database_name";
                if(connectionId==null)throw new IllegalArgumentException("请选择连接。");
                conditions.add("(connection_id=? OR EXISTS (SELECT 1 FROM logical_databases l JOIN account_database_bindings b ON b.logical_name=l.logical_name WHERE b.connection_id=? AND b.status='ACTIVE' AND l.connection_id=metadata_columns.connection_id AND l.physical_database=metadata_columns.database_name))");args.add(connectionId);args.add(connectionId);
                if(!q.isEmpty()){conditions.add("(LOWER(column_name) LIKE ? OR LOWER(table_name) LIKE ? OR LOWER(column_comment) LIKE ?)");for(int i=0;i<3;i++)args.add("%"+q.toLowerCase(Locale.ROOT)+"%");}break;
            default:throw new IllegalArgumentException("不支持的列表。");
        }
        if(databaseColumn!=null&&database!=null&&!database.isEmpty()){conditions.add(databaseColumn+"=?");args.add(database);}
        if(status!=null&&!status.isEmpty()&&Arrays.asList("objects","executions","columns","versions").contains(resource)){conditions.add("status=?");args.add(status);}
        if("objects".equals(resource)&&connectionId!=null){conditions.add("connection_id=?");args.add(connectionId);}
        String where=conditions.isEmpty()?"":" WHERE "+String.join(" AND ",conditions);
        long total=jdbc.queryForObject("SELECT COUNT(*) FROM "+from+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT "+select+" FROM "+from+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray());
        Map<String,Object> result=new LinkedHashMap<>();result.put("rows",rows);result.put("total",total);result.put("page",page);result.put("size",size);return result;
    }
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String,String> bad(IllegalArgumentException e){return Collections.singletonMap("message",e.getMessage());}
}
