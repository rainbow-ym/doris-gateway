package com.example.doris.gateway.admin;

import org.springframework.stereotype.Component;
import java.sql.*;
import java.util.Map;

@Component
public final class DorisObjectIdentityProvider implements ObjectIdentityProvider {
    private final AdminStore store;
    public DorisObjectIdentityProvider(AdminStore store){this.store=store;}
    @Override public String identity(long connectionId,String database,String table)throws Exception {
        Map<String,Object> source=store.connection(connectionId);
        try(Connection c=DriverManager.getConnection(AccountAccessService.url(source,"information_schema"),String.valueOf(source.get("username")),store.connectionPassword(connectionId));
            PreparedStatement p=c.prepareStatement("SELECT CATALOG_ID,DATABASE_ID,TABLE_ID FROM information_schema.metadata_name_ids WHERE CATALOG_NAME='internal' AND DATABASE_NAME=? AND TABLE_NAME=?")) {
            p.setQueryTimeout(5);p.setString(1,database);p.setString(2,table);
            try(ResultSet r=p.executeQuery()) {
                if(!r.next())throw new IllegalStateException("未取得物理对象身份，可能不存在或当前维护账号不可见："+database+"."+table);
                String value=r.getString(1)+":"+r.getString(2)+":"+r.getString(3);
                if(!value.matches("[0-9]+:[0-9]+:[0-9]+")||r.next())throw new IllegalStateException("物理对象身份不唯一或无效。");
                return value;
            }
        }
    }
}
