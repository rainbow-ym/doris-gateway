package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Uses ShardingSphere's persisted global-rule import; no generated business YAML files. */
@Service
public class GatewayAccounts {
    private final AdminStore store;
    private final DistSqlService proxy;
    private final AccountAccessStore access;
    public GatewayAccounts(AdminStore store,DistSqlService proxy,AccountAccessStore access) { this.store=store;this.proxy=proxy;this.access=access; }

    public synchronized void synchronize() throws Exception {
        access.migrate(store);
        try(Connection c=proxy.proxyConnection(null)) {
            Snapshot state=read(c);
            Map<String,Object> authority=state.authority;
            List<Map<String,Object>> users=(List<Map<String,Object>>)authority.get("users");
            if(users==null) throw new IllegalStateException("网关缺少账号配置，请检查控制账号。");
            boolean admin=false;
            for(Map<String,Object> u:users) if(String.valueOf(u.get("user")).startsWith(proxy.controlUsername()+"@")) admin=Boolean.TRUE.equals(u.get("admin"));
            if(!admin) throw new IllegalStateException("平台控制账号不是管理员。请配置独立的 Proxy 管理账号（admin=true），不能使用 gateway_test 等业务账号。");
            Map<String,Object> privilege=(Map<String,Object>)authority.get("privilege");
            if(privilege==null || !"DATABASE_PERMITTED".equals(privilege.get("type"))) throw new IllegalStateException("网关授权模式必须为 DATABASE_PERMITTED。");
            Map<String,Object> props=(Map<String,Object>)privilege.get("props");
            Set<String> managedNames=new HashSet<>();
            for(Map<String,Object> source:store.connections()) managedNames.add(String.valueOf(source.get("username")));
            if(managedNames.contains(proxy.controlUsername())) throw new IllegalStateException("数据源业务账号不能与平台控制账号同名。");
            List<String> grants=new ArrayList<>();
            String old=String.valueOf(props.getOrDefault("user-database-mappings",""));
            for(String g:old.split(",")) {
                int at=g.indexOf('@');
                if(at>0 && !managedNames.contains(g.substring(0,at).trim())) grants.add(g.trim());
            }
            Map<String,String> credentials=new LinkedHashMap<>();
            for(Map<String,Object> source:store.connections()) {
                String username=String.valueOf(source.get("username"));
                if(access.lifecycleManaged(username)&&access.lifecycleActive(username))credentials.put(username,store.connectionPassword(((Number)source.get("id")).longValue()));
            }
            for(Map<String,Object> mapping:access.bindings()) {
                if(!Arrays.asList("ACTIVE","SYNCING").contains(String.valueOf(mapping.get("status")))) continue;
                long id=((Number)mapping.get("connectionId")).longValue();
                Map<String,Object> source=store.connection(id);
                String user=String.valueOf(source.get("username")), pass=store.connectionPassword(id);
                if(!access.lifecycleActive(user))continue;
                if(credentials.containsKey(user) && !credentials.get(user).equals(pass)) throw new IllegalStateException("同名业务账号的连接密码存在冲突："+user);
                credentials.put(user,pass);
                String db=String.valueOf(mapping.get("logicalName"));
                if(db.contains(",") || db.contains("=")) throw new IllegalArgumentException("数据库名包含授权格式不支持的逗号或等号。");
                grants.add(user+"@%="+db);
                grants.add(user+"@%="+db.toLowerCase(Locale.ROOT));
            }
            for(Map<String,Object> u:users) {
                String name=String.valueOf(u.get("user")).split("@",2)[0];
                if(managedNames.contains(name) && Boolean.TRUE.equals(u.get("admin"))) throw new IllegalStateException("业务账号与已有管理账号冲突："+name);
            }
            users.removeIf(u->managedNames.contains(String.valueOf(u.get("user")).split("@",2)[0]));
            for(Map.Entry<String,String> e:credentials.entrySet()) {
                Map<String,Object> u=new LinkedHashMap<>();u.put("user",e.getKey()+"@%");u.put("password",e.getValue());u.put("admin",false);users.add(u);
            }
            // Provider requires a syntactically valid mapping even when no business DB is present.
            if(grants.isEmpty()) grants.add(proxy.controlUsername()+"@%=__gateway_control__");
            props.put("user-database-mappings",String.join(",",new LinkedHashSet<>(grants)));
            write(c,state);
        }
    }

    static final class Snapshot { Map<String,Object> authority; String props; }
    @SuppressWarnings("unchecked")
    static Snapshot read(Connection c) throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        try(Statement s=c.createStatement()) {
            s.execute("EXPORT METADATA");
            ResultSet rs=s.getResultSet();
            if(!rs.next()) throw new IllegalStateException("无法读取网关配置。");
            Map<String,Object> doc=mapper.readValue(Base64.getDecoder().decode(rs.getString("cluster_info")),Map.class);
            Map<String,Object> metadata=(Map<String,Object>)doc.get("meta_data");
            if(metadata==null) metadata=(Map<String,Object>)doc.get("metaData");
            String rules=String.valueOf(metadata.get("rules"));
            java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?ms)^- !AUTHORITY\\s*\\n(.*?)(?=^- !|\\z)").matcher(rules);
            if(!m.find()) throw new IllegalStateException("无法找到网关 AUTHORITY 配置。");
            Snapshot state=new Snapshot();
            state.authority=new Yaml(new SafeConstructor()).load(m.group(1));
            state.props=String.valueOf(metadata.get("props"));
            return state;
        }
    }
    static void write(Connection c,Snapshot state) throws Exception {
        String yaml=new Yaml().dump(state.authority);
        StringBuilder rules=new StringBuilder("rules:\n- !AUTHORITY\n");
        for(String line:yaml.split("\n")) rules.append("  ").append(line).append('\n');
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("databases",Collections.emptyMap());metadata.put("props",state.props);metadata.put("rules",rules.toString());
        String encoded=Base64.getEncoder().encodeToString(new ObjectMapper().writeValueAsBytes(Collections.singletonMap("meta_data",metadata)));
        try(Statement s=c.createStatement()) { s.execute("IMPORT METADATA '"+encoded+"'"); }
    }

    /** One-time deployment migration. Values come from protected environment, never logged. */
    public static void main(String[] args) throws Exception {
        String host=System.getenv("PROXY_HOST"), port=System.getenv("PROXY_PORT");
        try(Connection c=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/?useSSL=false&allowPublicKeyRetrieval=true",System.getenv("PROXY_ADMIN_USER"),System.getenv("PROXY_ADMIN_PASSWORD"))) {
            Snapshot state=read(c);
            List<Map<String,Object>> users=(List<Map<String,Object>>)state.authority.get("users");
            String name=System.getenv("NEW_PROXY_ADMIN_USER"), password=System.getenv("NEW_PROXY_ADMIN_PASSWORD");
            if(name==null || password==null || password.length()<20) throw new IllegalStateException("Missing control credential.");
            boolean exists=false;
            for(Map<String,Object> u:users) if(String.valueOf(u.get("user")).startsWith(name+"@")) exists=true;
            if(!exists) {
                Map<String,Object> user=new LinkedHashMap<>();user.put("user",name+"@%");user.put("password",password);user.put("admin",true);users.add(user);
                write(c,state);
            }
            try(Connection verify=DriverManager.getConnection("jdbc:mysql://"+host+":"+port+"/?useSSL=false&allowPublicKeyRetrieval=true",name,password)) { System.out.println("Control account provisioned and verified."); }
            if(args.length==1) {
                java.nio.file.Path path=java.nio.file.Paths.get(args[0]);
                java.nio.file.Path backup=java.nio.file.Paths.get(args[0]+".pre-onboarding");
                if(!java.nio.file.Files.exists(backup)) java.nio.file.Files.copy(path,backup,java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                List<String> lines=java.nio.file.Files.readAllLines(path,StandardCharsets.UTF_8);
                lines.removeIf(line->line.startsWith("PROXY_ADMIN_USER=")||line.startsWith("PROXY_ADMIN_PASSWORD="));
                lines.add("PROXY_ADMIN_USER="+name);lines.add("PROXY_ADMIN_PASSWORD="+password);
                java.nio.file.Files.write(path,lines,StandardCharsets.UTF_8);
                System.out.println("Protected admin environment updated; previous configuration backed up.");
            }
        }
    }
}
