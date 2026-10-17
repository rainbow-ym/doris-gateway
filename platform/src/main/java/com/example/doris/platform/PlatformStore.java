package com.example.doris.platform;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.core.userdetails.*;
import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;

@Service
public class PlatformStore implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final CredentialVault vault;
    private final String initialUser,initialPassword;
    final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(12);
    public PlatformStore(JdbcTemplate jdbc,CredentialVault vault,@Value("${PLATFORM_ADMIN_USER:admin}") String user,
                         @Value("${PLATFORM_ADMIN_PASSWORD:}") String password) {
        this.jdbc=jdbc;this.vault=vault;initialUser=user;initialPassword=password;
    }
    @PostConstruct public void initialize() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_users(username VARCHAR(128) PRIMARY KEY,password_hash VARCHAR(255) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS clusters(id VARCHAR(36) PRIMARY KEY,name VARCHAR(128) NOT NULL UNIQUE,worker_url VARCHAR(512) NOT NULL UNIQUE,worker_identity VARCHAR(64) NOT NULL UNIQUE,worker_username VARCHAR(128) NOT NULL,password_enc VARCHAR(2048) NOT NULL,gateway_address VARCHAR(255) NOT NULL,created_at VARCHAR(64) NOT NULL)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS platform_audit(id BIGINT AUTO_INCREMENT PRIMARY KEY,actor VARCHAR(128),action VARCHAR(128),cluster_id VARCHAR(36),created_at VARCHAR(64))");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM platform_users",Integer.class)==0) {
            validatePassword(initialPassword);
            jdbc.update("INSERT INTO platform_users VALUES(?,?)",initialUser,encoder.encode(initialPassword));
        }
    }
    public UserDetails loadUserByUsername(String name) {
        List<String> hashes=jdbc.queryForList("SELECT password_hash FROM platform_users WHERE username=?",String.class,name);
        if(hashes.isEmpty())throw new UsernameNotFoundException("账号或密码错误");
        return User.withUsername(name).password(hashes.get(0)).roles("ADMIN").build();
    }
    public void changePassword(String user,String old,String replacement) {
        validatePassword(replacement);
        if(!encoder.matches(old,loadUserByUsername(user).getPassword()))throw new IllegalArgumentException("当前密码不正确。");
        jdbc.update("UPDATE platform_users SET password_hash=? WHERE username=?",encoder.encode(replacement),user);
        audit(user,"PASSWORD_CHANGED",null);
    }
    static void validatePassword(String value) {
        if(value==null||value.length()<12||value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("管理员密码至少 12 个字符，UTF-8 编码不得超过 72 字节。");
    }
    public List<Map<String,Object>> clusters() {return jdbc.queryForList("SELECT id,name,worker_url AS workerUrl,worker_username AS workerUsername,gateway_address AS gatewayAddress,created_at AS createdAt FROM clusters ORDER BY created_at,id");}
    public Map<String,Object> cluster(String id) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM clusters WHERE id=?",id);
        if(rows.isEmpty())throw new IllegalArgumentException("集群不存在。");return rows.get(0);
    }
    public String password(Map<String,Object> row) {return vault.decrypt(String.valueOf(row.get("password_enc")));}
    public synchronized String register(String name,String url,String identity,String user,String password,String address) {
        if(name.trim().isEmpty()||name.length()>128||user.isEmpty()||address.isEmpty())throw new IllegalArgumentException("请填写集群名称、管理账号和网关地址。");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM clusters WHERE name=? OR worker_url=? OR worker_identity=?",Integer.class,name,url,identity)>0)
            throw new IllegalArgumentException("集群名称或管理实例已登记，不能重复加入。");
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO clusters VALUES(?,?,?,?,?,?,?,?)",id,name,url,identity,user,vault.encrypt(password),address,Instant.now().toString());return id;
    }
    public void audit(String actor,String action,String cluster) {jdbc.update("INSERT INTO platform_audit(actor,action,cluster_id,created_at) VALUES(?,?,?,?)",actor,action,cluster,Instant.now().toString());}
    public void update(String id,String name,String url,String user,String password,String address) {
        if(name.trim().isEmpty()||name.length()>128||user.isEmpty()||address.isEmpty())throw new IllegalArgumentException("请填写集群名称、管理账号和网关地址。");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM clusters WHERE id<>? AND (name=? OR worker_url=?)",Integer.class,id,name,url)>0)throw new IllegalArgumentException("集群名称或管理地址已被使用。");
        jdbc.update("UPDATE clusters SET name=?,worker_url=?,worker_username=?,password_enc=?,gateway_address=? WHERE id=?",name,url,user,vault.encrypt(password),address,id);
    }
    public void unregister(String id){cluster(id);jdbc.update("DELETE FROM clusters WHERE id=?",id);}
}
