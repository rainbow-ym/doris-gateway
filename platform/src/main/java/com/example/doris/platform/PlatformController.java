package com.example.doris.platform;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import javax.servlet.http.*;
import java.util.*;
import java.io.IOException;

@RestController
public class PlatformController {
    private final PlatformStore store;private final WorkerClient client;private final SessionRegistry sessions;
    public PlatformController(PlatformStore store,WorkerClient client,SessionRegistry sessions){this.store=store;this.client=client;this.sessions=sessions;}
    @GetMapping("/api/health") public Map<String,Object> health(){Map<String,Object> r=new LinkedHashMap<>();r.put("status","UP");r.put("version","2026.10.15-accounts.1");return r;}
    @GetMapping("/api/session") public Map<String,Object> session(HttpServletRequest request,Authentication auth){
        Map<String,Object> result=new LinkedHashMap<>();CsrfToken token=(CsrfToken)request.getAttribute(CsrfToken.class.getName());
        result.put("csrf",token.getToken());result.put("csrfHeader",token.getHeaderName());result.put("authenticated",auth!=null);
        if(auth!=null)result.put("username",auth.getName());return result;
    }
    @PostMapping("/api/admin/password") public Map<String,String> password(@RequestBody Map<String,String> body,Authentication auth,HttpServletRequest request){
        store.changePassword(auth.getName(),body.getOrDefault("oldPassword",""),body.getOrDefault("password",""));
        for(Object principal:sessions.getAllPrincipals())if(principal instanceof UserDetails&&((UserDetails)principal).getUsername().equals(auth.getName()))
            sessions.getAllSessions(principal,false).forEach(s->s.expireNow());
        request.getSession().invalidate();return Collections.singletonMap("message","密码已修改，请重新登录。");
    }
    @GetMapping("/api/clusters") public List<Map<String,Object>> clusters(){return store.clusters();}
    @PostMapping("/api/clusters") public Map<String,String> register(@RequestBody Map<String,String> body,Authentication auth)throws IOException {
        String url=WorkerClient.validateUrl(required(body,"workerUrl")),user=required(body,"workerUsername"),password=required(body,"workerPassword");
        Map<String,Object> identity=client.identity(url,user,password);
        String id=store.register(required(body,"name"),url,String.valueOf(identity.get("id")),user,password,required(body,"gatewayAddress"));
        store.audit(auth.getName(),"CLUSTER_REGISTERED",id);return Collections.singletonMap("id",id);
    }
    @RequestMapping(value="/api/clusters/{id}/relay/api/**",method={RequestMethod.GET,RequestMethod.POST,RequestMethod.PUT,RequestMethod.DELETE})
    public ResponseEntity<byte[]> relay(@PathVariable String id,HttpServletRequest request,Authentication auth)throws IOException {
        Map<String,Object> cluster=store.cluster(id);
        String prefix="/api/clusters/"+id+"/relay",path=request.getRequestURI().substring(prefix.length());
        if(path.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c).*"))throw new IllegalArgumentException("请求路径不允许。");
        if(request.getQueryString()!=null)path+="?"+request.getQueryString();
        String url=String.valueOf(cluster.get("worker_url")),user=String.valueOf(cluster.get("worker_username")),pass=store.password(cluster);
        Map<String,Object> actual=client.identity(url,user,pass);
        if(!cluster.get("worker_identity").equals(actual.get("id")))throw new IllegalArgumentException("管理实例身份发生变化，已阻止请求，避免访问错误集群。");
        byte[] body=WorkerClient.readBounded(request.getInputStream(),2*1024*1024);
        WorkerClient.Reply reply=client.call(url,user,pass,request.getMethod(),path,body);
        if(!"GET".equals(request.getMethod()))store.audit(auth.getName(),request.getMethod()+" "+path.split("\\?")[0]+" ["+reply.status+"]",id);
        return ResponseEntity.status(reply.status).contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore()).body(reply.body);
    }
    @PutMapping("/api/clusters/{id}") public Map<String,String> update(@PathVariable String id,@RequestBody Map<String,String> body,Authentication auth)throws IOException {
        Map<String,Object> old=store.cluster(id);String url=WorkerClient.validateUrl(required(body,"workerUrl")),user=required(body,"workerUsername");
        String pass=body.getOrDefault("workerPassword","");if(pass.isEmpty())pass=store.password(old);
        Map<String,Object> identity=client.identity(url,user,pass);
        if(!identity.get("id").equals(old.get("worker_identity")))throw new IllegalArgumentException("不能将已有集群替换为另一管理实例，请单独登记。");
        store.update(id,required(body,"name"),url,user,pass,required(body,"gatewayAddress"));store.audit(auth.getName(),"CLUSTER_UPDATED",id);return Collections.singletonMap("status","UPDATED");
    }
    @DeleteMapping("/api/clusters/{id}") public Map<String,String> unregister(@PathVariable String id,Authentication auth){store.unregister(id);store.audit(auth.getName(),"CLUSTER_UNREGISTERED",id);return Collections.singletonMap("message","已解除平台登记，独立网关及其数据未删除。");}
    static String required(Map<String,String> body,String key){String v=body.get(key);if(v==null||v.trim().isEmpty())throw new IllegalArgumentException("缺少配置项："+key);return v;}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String,String> invalid(IllegalArgumentException e){return Collections.singletonMap("message",e.getMessage());}
    @ExceptionHandler(IOException.class) @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String,String> unavailable(IOException e){return Collections.singletonMap("message","集群管理实例暂不可用或响应超限，请检查该实例；未转发到其他集群。");}
}
