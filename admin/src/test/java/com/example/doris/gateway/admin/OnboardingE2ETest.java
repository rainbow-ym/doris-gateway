package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.io.*;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_ONBOARDING_E2E",matches="true")
class OnboardingE2ETest {
    final ObjectMapper json=new ObjectMapper();
    final String base=System.getenv().getOrDefault("GATEWAY_ADMIN_URL","http://127.0.0.1:18080");
    String rootPassword(){return System.getenv("DORIS_PASSWORD");}
    Object request(String method,String path,Object body,int expected) throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(5000);c.setReadTimeout(60000);
        c.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString((System.getenv("ADMIN_USERNAME")+":"+System.getenv("ADMIN_PASSWORD")).getBytes(StandardCharsets.UTF_8)));
        if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(json.writeValueAsBytes(body));}}
        int code=c.getResponseCode();try(InputStream in=code>=400?c.getErrorStream():c.getInputStream()){Object result=json.readValue(in,Object.class);assertEquals(expected,code,String.valueOf(result));return result;}finally{c.disconnect();}
    }
    Connection doris() throws Exception {return DriverManager.getConnection("jdbc:mysql://127.0.0.1:9030/?useSSL=false&allowPublicKeyRetrieval=true","root",rootPassword());}
    Connection gateway(String db,String user,String pass) throws Exception {return DriverManager.getConnection("jdbc:mysql://192.168.232.132:3308/"+db+"?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8",user,pass);}
    @SuppressWarnings("unchecked")
    @Test void completeBrowserApiWorkflowAndCredentialBoundary() throws Exception {
        String prefix="gateway_onboard_"+System.currentTimeMillis();String a=prefix+"_a",b=prefix+"_b";Long id=null;
        try(Connection d=doris();Statement s=d.createStatement()){
            for(String db:Arrays.asList(a,b)){
                s.execute("CREATE DATABASE `"+db+"`");
                s.execute("CREATE TABLE `"+db+"`.people (id BIGINT, id_card VARCHAR(32), name VARCHAR(64)) DUPLICATE KEY(id) DISTRIBUTED BY HASH(id) BUCKETS 1 PROPERTIES('replication_num'='1')");
                s.execute("INSERT INTO `"+db+"`.people VALUES (1,'110101199001011234','张三')");
            }
            Map<String,Object> source=new LinkedHashMap<>();source.put("name",prefix);source.put("host","127.0.0.1");source.put("port",9030);source.put("username","root");source.put("password",rootPassword());source.put("databasePattern",prefix+"%");source.put("tablePattern","%");
            id=((Number)((Map)request("POST","/api/connections",source,200)).get("id")).longValue();
            request("POST","/api/connections",source,400);
            source.put("name",prefix+" edited");source.put("password","");request("PUT","/api/connections/"+id,source,200);
            List<String> found=(List<String>)request("GET","/api/connections/"+id+"/databases",null,200);assertTrue(found.containsAll(Arrays.asList(a,b)));
            request("POST","/api/connections/"+id+"/onboard",Collections.singletonMap("databases",Collections.singletonList("outside_scope")),400);
            Map first=(Map)request("POST","/api/connections/"+id+"/onboard",Collections.singletonMap("databases",Arrays.asList(a,b)),200);
            request("POST","/api/connections/"+id+"/onboard",Collections.singletonMap("databases",Arrays.asList(a,b)),200);
            List<String> jobs=(List<String>)first.get("jobs");assertEquals(2,jobs.size());
            long deadline=System.currentTimeMillis()+90000;boolean finished=false;
            while(System.currentTimeMillis()<deadline){
                List<Map<String,Object>> current=(List)request("GET","/api/onboarding/jobs",null,200);
                List<Map<String,Object>> targets=new ArrayList<>();for(Map<String,Object> row:current)if(jobs.contains(row.get("id")))targets.add(row);
                for(Map<String,Object> row:targets)assertNotEquals("FAILED",row.get("status"),row.toString());
                if(targets.size()==2&&targets.stream().allMatch(r->"SUCCEEDED".equals(r.get("status")))){finished=true;break;}Thread.sleep(500);
            }
            assertTrue(finished,"onboarding timed out");
            Map again=(Map)request("POST","/api/connections/"+id+"/onboard",Collections.singletonMap("databases",Arrays.asList(a,b)),200);assertTrue(((List)again.get("jobs")).isEmpty());
            request("DELETE","/api/connections/"+id,null,400);
            for(String db:Arrays.asList(a,b))try(Connection g=gateway(db,"root",rootPassword());Statement query=g.createStatement()){
                try(ResultSet rs=query.executeQuery("SELECT x.credential,x.person FROM (SELECT id_card AS credential,name AS person FROM people WHERE id_card LIKE '110101%') x")){
                    assertTrue(rs.next());assertEquals("110101********1234",rs.getString(1));assertEquals("张*",rs.getString(2));
                }
                assertThrows(SQLException.class,()->query.execute("EXPORT METADATA"));
            }
            assertThrows(SQLException.class,()->gateway(a,"gateway_test",System.getenv("GATEWAY_PASSWORD")));
            for(String db:Arrays.asList(a,b)){
                request("DELETE","/api/logical-databases/"+db,null,200);
                try(ResultSet rs=s.executeQuery("SELECT COUNT(*) FROM `"+db+"`.people")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
            }
            request("DELETE","/api/connections/"+id,null,200);id=null;
        } finally {
            if(id!=null) for(String db:Arrays.asList(a,b))try{request("DELETE","/api/logical-databases/"+db,null,200);}catch(Exception ignored){}
            if(id!=null)try{request("DELETE","/api/connections/"+id,null,200);}catch(Exception ignored){}
            try(Connection d=doris();Statement s=d.createStatement()){for(String db:Arrays.asList(a,b))s.execute("DROP DATABASE IF EXISTS `"+db+"`");}
        }
    }
}
