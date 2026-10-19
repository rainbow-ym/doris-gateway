package com.example.doris.gateway.admin;

/** Physical identity is independent of field names, masking policies and business users. */
public interface ObjectIdentityProvider {
    String identity(long connectionId,String database,String table) throws Exception;
}
