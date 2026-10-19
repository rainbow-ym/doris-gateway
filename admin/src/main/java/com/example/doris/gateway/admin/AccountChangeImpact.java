package com.example.doris.gateway.admin;

/** Identity changes and authorization changes have independent invalidation domains. */
final class AccountChangeImpact {
    final boolean credentials;
    final boolean permissions;
    private AccountChangeImpact(boolean credentials,boolean permissions) {
        this.credentials=credentials;this.permissions=permissions;
    }
    static AccountChangeImpact of(String operation) {
        switch(operation) {
            case "CREATE": case "PASSWORD": case "DROP": return new AccountChangeImpact(true,false);
            case "GRANT": case "REVOKE": return new AccountChangeImpact(false,true);
            default: throw new IllegalArgumentException("未定义账号操作的失效范围："+operation);
        }
    }
}
