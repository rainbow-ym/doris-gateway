package com.example.doris.gateway.admin;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Existing administrator authentication protects preview and explicit legacy migration. */
@RestController
@RequestMapping("/api/object-identities")
public class ObjectIdentityController {
 private final ObjectIdentityService identities;
 public ObjectIdentityController(ObjectIdentityService identities){this.identities=identities;}
 @GetMapping("/{database}/preview") public Object preview(@PathVariable String database)throws Exception{return identities.preview(database);}
 @PostMapping("/{database}/confirm") public Object confirm(@PathVariable String database,@RequestBody List<Map<String,Object>> expected)throws Exception{identities.confirm(database,expected);return Collections.singletonMap("status","ENFORCED");}
 @GetMapping("/new-sources/{id}/preview") public Object previewNewSource(@PathVariable long id)throws Exception{return identities.previewNewSource(id);}
 @PostMapping("/new-sources/{id}/confirm") public Object confirmNewSource(@PathVariable long id,@RequestBody Map<String,Object> expected)throws Exception{
  identities.confirmNewSource(id,expected);
  return Collections.singletonMap("message","新来源身份已确认，尚未开放查询；请执行纳管审批以发布字段保护策略。");
 }
 @ExceptionHandler({IllegalArgumentException.class,IllegalStateException.class})
 @ResponseStatus(org.springframework.http.HttpStatus.CONFLICT)
 public Object conflict(RuntimeException error){return Collections.singletonMap("message",error.getMessage());}
}
