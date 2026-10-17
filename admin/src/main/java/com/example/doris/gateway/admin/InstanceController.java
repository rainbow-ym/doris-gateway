package com.example.doris.gateway.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import java.util.*;

/** Stable identity belongs to this cluster's physically independent management database. */
@RestController
public class InstanceController {
    private final JdbcTemplate jdbc;private final AdminStore store;
    @Value("${GATEWAY_MULTI_USER_ENABLED:false}") private boolean enabled;
    public InstanceController(JdbcTemplate jdbc,AdminStore store){this.jdbc=jdbc;this.store=store;}
    @GetMapping("/api/instance") public synchronized Map<String,Object> identity(){
        List<String> ids=jdbc.queryForList("SELECT meta_value FROM managed_registry_meta WHERE meta_key='cluster_instance_id'",String.class);
        String id=ids.isEmpty()?UUID.randomUUID().toString():ids.get(0);
        if(ids.isEmpty())jdbc.update("INSERT INTO managed_registry_meta VALUES('cluster_instance_id',?)",id);
        Map<String,Object> result=new LinkedHashMap<>();result.put("id",id);result.put("multiUserEnabled",enabled);result.put("version","2026.10.15-accounts.1");return result;
    }
}
