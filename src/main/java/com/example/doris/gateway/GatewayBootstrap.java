package com.example.doris.gateway;

/** Minimal launcher: start durable audit delivery even before the first client connects. */
public final class GatewayBootstrap {
    private GatewayBootstrap() { }
    public static void main(String[] args) throws Exception {
        com.example.doris.gateway.security.ProxyObservationReporter.initialize();
        if(Boolean.parseBoolean(System.getenv("GATEWAY_MULTI_USER_ENABLED")))
            Class.forName("com.example.doris.gateway.lineage.LineageEventEmitter");
        try {
            Class.forName("org.apache.shardingsphere.proxy.Bootstrap").getMethod("main",String[].class).invoke(null,(Object)args);
        } catch(java.lang.reflect.InvocationTargetException error) {
            Throwable cause=error.getCause();
            if(cause instanceof Exception)throw (Exception)cause;
            if(cause instanceof Error)throw (Error)cause;
            throw error;
        }
    }
}
