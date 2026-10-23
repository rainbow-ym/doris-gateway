package com.example.doris.platform;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.*;
import java.util.Locale;

/** Fixed loopback upstream. Browser credentials and identity headers never cross this boundary. */
@RestController
public class HistoryController {
    @Value("${GRAFANA_LOCAL_PORT:13000}") private int port;

    static boolean readPost(HttpServletRequest request) {
        String path=request.getRequestURI();
        return "POST".equals(request.getMethod()) && ("/history/api/ds/query".equals(path)||"/history/apis/features.grafana.app/v0alpha1/namespaces/default/ofrep/v1/evaluate/flags".equals(path));
    }
    static boolean sameOrigin(HttpServletRequest request) {
        try {
            URI origin=new URI(request.getHeader("Origin"));
            int expected=request.getServerPort(), actual=origin.getPort()<0?("https".equals(origin.getScheme())?443:80):origin.getPort();
            return origin.getUserInfo()==null && origin.getRawQuery()==null && origin.getFragment()==null
                && (origin.getPath()==null||origin.getPath().isEmpty()) && request.getScheme().equals(origin.getScheme())
                && request.getServerName().equalsIgnoreCase(origin.getHost()) && expected==actual;
        } catch(Exception invalid) { return false; }
    }
    @RequestMapping("/history/**")
    public void proxy(HttpServletRequest request,HttpServletResponse response)throws IOException {
        response.setHeader("Cache-Control","no-store");
        String path=request.getRequestURI();
        if(!path.startsWith("/history/")||path.contains("..")||path.contains("\\")||path.contains("//")||path.contains("%")||path.contains(";")){
            response.sendError(400);return;
        }
        boolean post=readPost(request);
        if(!"GET".equals(request.getMethod())&&!post){response.sendError(405);return;}
        // Grafana query POSTs are read-only, but require a same-origin JSON request in place of its missing platform CSRF token.
        if(post&&(!sameOrigin(request)||request.getContentType()==null||!request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))){response.sendError(403);return;}
        String query=request.getQueryString();
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path+(query==null?"":"?"+query)).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3000);connection.setReadTimeout(60000);
        connection.setRequestMethod(request.getMethod());
        connection.setRequestProperty("X-Gateway-Grafana-User","gateway-platform-observer");
        connection.setRequestProperty("Accept-Encoding","identity");
        try {
            if(post){byte[] body=WorkerClient.readBounded(request.getInputStream(),2*1024*1024);connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");connection.setFixedLengthStreamingMode(body.length);try(OutputStream out=connection.getOutputStream()){out.write(body);}}
            int status=connection.getResponseCode();
            if(status>=300&&status<400){
                URI location=URI.create(connection.getHeaderField("Location"));String target=location.getRawPath();
                if(target==null||!target.startsWith("/history/")||target.contains("..")||target.contains("%")||target.contains("//")){response.sendError(502);return;}
                response.setStatus(status);response.setHeader("Location",target+(location.getRawQuery()==null?"":"?"+location.getRawQuery()));return;
            }
            response.setStatus(status);String contentType=connection.getContentType();if(contentType!=null)response.setContentType(contentType);
            InputStream input=status<400?connection.getInputStream():connection.getErrorStream();
            if(input!=null)try(InputStream in=input){byte[] buffer=new byte[16384];long total=0;int count;while((count=in.read(buffer))!=-1){total+=count;if(total>32*1024*1024)throw new IOException("History response too large");response.getOutputStream().write(buffer,0,count);}}
        }catch(Exception unavailable){
            if(!response.isCommitted()){response.resetBuffer();response.setStatus(502);response.setContentType("text/plain;charset=UTF-8");response.getWriter().write("历史监控暂不可用，请检查 gateway-grafana 服务及集成配置。未切换到其他集群。");}
        }finally{connection.disconnect();}
    }
}
