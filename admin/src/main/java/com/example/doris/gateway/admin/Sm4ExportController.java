package com.example.doris.gateway.admin;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import java.util.*;
import java.security.Principal;

@RestController
public class Sm4ExportController {
    private final Sm4ExportService service;private final AccountAccessService access;
    public Sm4ExportController(Sm4ExportService service,AccountAccessService access){this.service=service;this.access=access;}
    @PostMapping("/api/internal/sql/export-policy") public Object policy(@RequestHeader("X-Gateway-Lineage-Token") String token,@RequestBody Map<String,String> body){access.authenticate(token);return service.profile(body.get("username"),body.get("database"));}
    @PostMapping("/api/internal/sql/export-capture") public Object capture(@RequestHeader("X-Gateway-Lineage-Token") String token,@RequestBody Map<String,Object> body)throws Exception{access.authenticate(token);return service.capture(body);}
    @GetMapping("/api/sm4-export/profiles") public Object profiles(){return service.profiles();}
    @PostMapping("/api/sm4-export/profiles") public Object save(@RequestBody Map<String,Object> body)throws Exception{service.save(body);return Collections.singletonMap("message","末行加密配置已保存，下次查询生效。");}
    @GetMapping("/api/sm4-export/receipts") public Object receipts(){return service.receipts();}
    @PostMapping("/api/sm4-export/receipts/{id}/revoke") public Object revoke(@PathVariable String id){service.revoke(id);return Collections.singletonMap("message","该文件已撤销解密资格。");}
    @PostMapping("/api/sm4-export/decrypt") public ResponseEntity<Object> decrypt(@RequestBody Map<String,String> body,Principal principal)throws Exception{return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.decrypt(body.get("csv"),principal.getName()));}
    @ExceptionHandler(Exception.class) public ResponseEntity<Object> failure(Exception e){
        String message=e instanceof IllegalArgumentException||e instanceof IllegalStateException||e instanceof SecurityException?e.getMessage():"加密/解密校验失败；请检查密钥、文件完整性和原账号的当前权限。";
        return ResponseEntity.status(e instanceof SecurityException?HttpStatus.FORBIDDEN:HttpStatus.BAD_REQUEST).cacheControl(CacheControl.noStore()).body(Collections.singletonMap("message",message));
    }
}
