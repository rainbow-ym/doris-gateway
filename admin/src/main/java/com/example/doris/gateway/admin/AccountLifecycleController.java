package com.example.doris.gateway.admin;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController
@RequestMapping("/api")
public class AccountLifecycleController {
 private final AccountLifecycleService service;private final AccountAccessService auth;
 public AccountLifecycleController(AccountLifecycleService service,AccountAccessService auth){this.service=service;this.auth=auth;}
 @PostMapping("/internal/sql/account-command") public Object command(@RequestHeader(value="X-Gateway-Lineage-Token",required=false) String token,@RequestBody Map<String,String> r)throws Exception{
  auth.authenticate(token);return service.execute(r.get("actor"),r.get("database"),r.get("sql"));
 }
 @GetMapping("/account-lifecycle/tasks") public Object tasks(){return service.tasks();}
 @PostMapping("/account-lifecycle/tasks/{id}/retry") public Object retry(@PathVariable String id){service.synchronize(id);return service.task(id);}
 @PostMapping("/account-lifecycle/tasks/{id}/reconcile") public Object reconcile(@PathVariable String id)throws Exception{service.reconcile(id);return service.task(id);}
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
 @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
 public Object invalid(RuntimeException e){return Collections.singletonMap("message",e.getMessage());}
 @ExceptionHandler(SecurityException.class)
 @ResponseStatus(org.springframework.http.HttpStatus.FORBIDDEN)
 public Object forbidden(SecurityException e){return Collections.singletonMap("message",e.getMessage());}
}
