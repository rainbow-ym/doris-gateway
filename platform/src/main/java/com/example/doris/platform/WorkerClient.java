package com.example.doris.platform;

import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class WorkerClient {
    private final ObjectMapper mapper;
    public WorkerClient(ObjectMapper mapper){this.mapper=mapper;}
    static String validateUrl(String input) {
        try {
            URI u=new URI(input);
            if(u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||!(u.getPath().isEmpty()||u.getPath().equals("/")))throw new Exception();
            if(!"https".equals(u.getScheme())&&!("http".equals(u.getScheme())&&Arrays.asList("127.0.0.1","localhost","[::1]","::1").contains(u.getHost())))throw new Exception();
            return input.replaceAll("/+$","");
        }catch(Exception e){throw new IllegalArgumentException("管理实例地址必须为 HTTPS；仅本机回环地址允许 HTTP。不要填写路径、用户名或查询参数。");}
    }
    @SuppressWarnings("unchecked")
    public Map<String,Object> identity(String url,String user,String password)throws IOException {
        Reply r=call(url,user,password,"GET","/api/instance",new byte[0]);
        if(r.status!=200)throw new IllegalArgumentException("无法认证管理实例，请检查地址、版本及凭据。");
        Map<String,Object> result=mapper.readValue(r.body,Map.class);
        if(!String.valueOf(result.get("id")).matches("[a-f0-9-]{36}")||!Boolean.TRUE.equals(result.get("multiUserEnabled")))
            throw new IllegalArgumentException("管理实例未启用多用户隔离或版本不兼容。");
        return result;
    }
    public Reply call(String base,String user,String password,String method,String path,byte[] body)throws IOException {
        if(!path.startsWith("/api/")||path.startsWith("/api/internal/")||path.contains("..")||path.contains("\\")||path.contains("//"))throw new IllegalArgumentException("请求路径不允许。");
        HttpURLConnection c=(HttpURLConnection)new URL(validateUrl(base)+path).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(60000);c.setRequestMethod(method);
        c.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString((user+":"+password).getBytes(StandardCharsets.UTF_8)));
        c.setRequestProperty("Content-Type","application/json");
        try {
            if(body.length>0){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(body);}}
            int status=c.getResponseCode();if(status>=300&&status<400)throw new IOException("管理实例返回了重定向，已拒绝转发。");
            InputStream stream=status<400?c.getInputStream():c.getErrorStream();
            return new Reply(status,stream==null?new byte[0]:readBounded(stream,8*1024*1024));
        }finally{c.disconnect();}
    }
    static byte[] readBounded(InputStream input,int max)throws IOException {
        try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192];int n,total=0;
            while((n=in.read(buf))!=-1){total+=n;if(total>max)throw new IOException("数据超过请求上限，请缩小范围或分页查询。");out.write(buf,0,n);}return out.toByteArray();
        }
    }
    public static final class Reply{public final int status;public final byte[] body;Reply(int s,byte[] b){status=s;body=b;}}
}
