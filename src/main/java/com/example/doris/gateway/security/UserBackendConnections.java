package com.example.doris.gateway.security;

import com.example.doris.gateway.lineage.LineageEventEmitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.proxy.backend.session.ConnectionSession;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode;
import java.sql.*;
import java.util.*;

/** Credentials are selected from an explicit session, never from an executor thread. */
public final class UserBackendConnections {
    private static final Map<String,HikariDataSource> POOLS=new LinkedHashMap<>();
    private static final Map<String,long[]> EPOCHS=new HashMap<>();
    public static synchronized void observeEpoch(String user,long epoch) throws Exception {
        long[] prior=EPOCHS.get(user);
        if(prior!=null&&prior[0]==epoch)return;
        long cutoff=0;
        if(epoch!=0){
            // Pinned ShardingSphere 5.5.3 frontend API; avoids adding frontend dependencies to backend plugin.
            Class<?> type=Class.forName("org.apache.shardingsphere.proxy.frontend.connection.ConnectionIdGenerator");
            Object generator=type.getMethod("getInstance").invoke(null);
            cutoff=((Number)type.getMethod("nextId").invoke(generator)).longValue();
            synchronized(POOLS){Iterator<Map.Entry<String,HikariDataSource>> it=POOLS.entrySet().iterator();while(it.hasNext()){
                Map.Entry<String,HikariDataSource> e=it.next();if(e.getKey().contains("\u0000"+user+"\u0000")){e.getValue().close();it.remove();}
            }}
        }
        EPOCHS.put(user,new long[]{epoch,cutoff});
    }
    private static synchronized void checkEpoch(ConnectionSession session,Map<?,?> resolved)throws Exception {
        String user=session.getConnectionContext().getGrantee().getUsername();
        Object value=resolved.get("epoch");long epoch=value instanceof Number?((Number)value).longValue():0;
        observeEpoch(user,epoch);
        if(session.getConnectionId()<=EPOCHS.get(user)[1])throw new SQLException("账号权限或凭据已变更，请断开并重新连接网关。","28000");
    }
    public static Map<String,Long> poolMetrics(){
        Map<String,Long> result=new LinkedHashMap<>();long active=0,idle=0,pending=0,total=0,maximum=0;
        synchronized(POOLS){for(HikariDataSource pool:POOLS.values()){
            com.zaxxer.hikari.HikariPoolMXBean bean=pool.getHikariPoolMXBean();if(bean==null)continue;
            active+=bean.getActiveConnections();idle+=bean.getIdleConnections();pending+=bean.getThreadsAwaitingConnection();total+=bean.getTotalConnections();maximum+=pool.getMaximumPoolSize();
        }result.put("pools",(long)POOLS.size());}
        result.put("active",active);result.put("idle",idle);result.put("pending",pending);result.put("total",total);result.put("maximum",maximum);return result;
    }
    private UserBackendConnections() { }
    public static boolean enabled() { return "true".equalsIgnoreCase(System.getenv("GATEWAY_MULTI_USER_ENABLED")); }
    public static void validate(ConnectionSession session,String database) throws SQLException {
        if(!enabled())return;
        try {checkEpoch(session,new ObjectMapper().readValue(LineageEventEmitter.accountRequest("check-account",session.getConnectionContext().getGrantee().getUsername(),database),Map.class));}
        catch(Exception e){throw new SQLException("当前用户的数据库关联已失效，或管理服务不可用。","28000",e);}
    }

    public static List<Connection> open(ConnectionSession session,String database,String unit,int size,ConnectionMode mode) throws SQLException {
        if(!enabled()) return ProxyContext.getInstance().getBackendDataSource().getConnections(database.toLowerCase(Locale.ROOT),unit,size,mode);
        if(session.getConnectionContext()==null || session.getConnectionContext().getGrantee()==null) throw new SQLException("无法确定当前登录身份。","28000");
        String user=session.getConnectionContext().getGrantee().getUsername();
        try {
            Map<?,?> resolved=new ObjectMapper().readValue(LineageEventEmitter.accountRequest("account",user,database),Map.class);
            if("CONTROL".equals(resolved.get("mode"))) return ProxyContext.getInstance().getBackendDataSource().getConnections(database.toLowerCase(Locale.ROOT),unit,size,mode);
            checkEpoch(session,resolved);
            if(!"USER".equals(resolved.get("mode")) || !user.equals(resolved.get("username"))) throw new SQLException("数据库账号绑定校验失败。","28000");
            String url=String.valueOf(resolved.get("url")),password=String.valueOf(resolved.get("password"));
            HikariDataSource pool;
            String credentialVersion=Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String key=resolved.get("connectionId")+"\u0000"+url+"\u0000"+user+"\u0000"+credentialVersion;
            synchronized(POOLS) {
                pool=POOLS.get(key);
                if(pool==null) {
                    if(POOLS.size()>=256) throw new SQLException("用户连接池数量达到上限，请调整接入规模。","08004");
                    HikariConfig config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(user);config.setPassword(password);
                    config.setMinimumIdle(0);config.setMaximumPoolSize(8);config.setConnectionTimeout(5000);config.setIdleTimeout(60000);
                    config.setPoolName("gateway-account-"+resolved.get("connectionId")+"-"+POOLS.size());
                    pool=new HikariDataSource(config);POOLS.put(key,pool);
                }
            }
            List<Connection> connections=new ArrayList<>();
            try { for(int i=0;i<size;i++) connections.add(pool.getConnection());return connections; }
            catch(SQLException e) {for(Connection c:connections)try{c.close();}catch(SQLException close){e.addSuppressed(close);}throw e;}
        } catch(SQLException e) {throw e;}
        catch(Exception e) {throw new SQLException("无法取得当前用户的数据库连接；请检查账号关联和管理服务。","28000",e);}
    }
}
