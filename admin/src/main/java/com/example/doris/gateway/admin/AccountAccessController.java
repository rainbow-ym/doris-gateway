package com.example.doris.gateway.admin;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.*;

@RestController
@RequestMapping("/api")
public class AccountAccessController {
    private final AccountAccessService service;
    private final AccountAccessStore access;
    private final AdminStore store;
    private final GatewayAccounts accounts;
    public AccountAccessController(AccountAccessService service,AccountAccessStore access,AdminStore store,GatewayAccounts accounts) { this.service=service;this.access=access;this.store=store;this.accounts=accounts; }
    @GetMapping("/account-bindings") public Object bindings(){access.migrate(store);return access.bindings();}
    @DeleteMapping("/connections/{id}/bindings/{database}") public Object unbind(@PathVariable long id,@PathVariable String database)throws Exception {
        Map<String,Object> mapping=store.anyMapping(database);
        if(!mapping.isEmpty() && ((Number)mapping.get("connection_id")).longValue()==id) throw new IllegalArgumentException("该连接负责全局元数据维护，请使用逻辑库解除接入操作；这会影响全部关联账号。");
        access.unbind(id,database);accounts.synchronize();store.audit("ACCOUNT_DATABASE_DETACHED","connectionId="+id+",database="+database);
        return Collections.singletonMap("status","DETACHED");
    }
    @GetMapping("/permission-snapshots") public Object snapshots(){return access.snapshots();}
    @GetMapping("/connections/{id}/access") public Object objects(@PathVariable long id){return access.accessRows(id);}
    @PostMapping("/connections/{id}/refresh-permissions") public Object refresh(@PathVariable long id)throws Exception {service.refresh(id);return Collections.singletonMap("status","UPDATED");}
    @GetMapping("/user-output-policies") public Object policies(@RequestParam String username,@RequestParam String database){return access.policies(username,database);}
    @PostMapping("/user-output-policies") public Object save(@RequestBody Map<String,Object> r){service.savePolicy(r);return Collections.singletonMap("status","SAVED");}
    @DeleteMapping("/user-output-policies/{id}") public Object delete(@PathVariable long id){access.deletePolicy(id);store.audit("USER_OUTPUT_POLICY_DELETED","id="+id);return Collections.singletonMap("status","DELETED");}
    @PostMapping("/internal/sql/account") public ResponseEntity<?> resolve(@RequestHeader(value="X-Gateway-Lineage-Token",required=false) String token,@RequestBody Map<String,String> r)throws Exception {
        service.authenticate(token);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.resolve(r.get("username"),r.get("database")));
    }
    @PostMapping("/internal/sql/user-policies") public Object policy(@RequestHeader(value="X-Gateway-Lineage-Token",required=false) String token,@RequestBody Map<String,String> r) {
        service.authenticate(token);return service.policyPlan(r.get("username"),r.get("database"));
    }
    @PostMapping("/internal/sql/check-account") public Object check(@RequestHeader(value="X-Gateway-Lineage-Token",required=false) String token,@RequestBody Map<String,String> r)throws Exception {
        service.authenticate(token);Map<String,Object> resolved=service.resolve(r.get("username"),r.get("database"));
        Map<String,Object> versions=new LinkedHashMap<>();versions.put("epoch",resolved.getOrDefault("epoch",0));
        versions.put("permissionEpoch",resolved.getOrDefault("permissionEpoch",0));return versions;
    }
    @PostMapping("/internal/sql/audit") public Object audit(@RequestHeader(value="X-Gateway-Lineage-Token",required=false) String token,@RequestBody Map<String,String> r) {
        service.authenticate(token);
        String outcome=r.get("outcome");if(!Arrays.asList("STARTED","EXECUTED","FAILED","UNKNOWN").contains(outcome))throw new IllegalArgumentException("无效审计状态。");
        String requestId=r.getOrDefault("requestId","");
        if(!requestId.isEmpty()&&!requestId.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("无效请求编号。");
        store.recordExecutionAudit(requestId,outcome,"requestId="+requestId+",user="+r.get("username")+",database="+r.get("database")+",sqlDigest="+r.get("sqlDigest"));
        return Collections.singletonMap("status","RECORDED");
    }
    @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Object invalid(RuntimeException ex){return Collections.singletonMap("message",ex.getMessage());}
    @ExceptionHandler(SecurityException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
    public Object forbidden(SecurityException ex){return Collections.singletonMap("message",ex.getMessage());}
}
