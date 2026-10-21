package com.example.doris.gateway.security;

import com.example.doris.gateway.observability.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Best effort, bounded, no retries, no payload values and no synchronous HTTP on query threads. */
public final class ProxyObservationReporter {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final AtomicLong DROPPED=new AtomicLong();
    private static final ThreadPoolExecutor QUEUE=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{Thread t=new Thread(r,"gateway-observation");t.setDaemon(true);return t;},(r,e)->DROPPED.incrementAndGet());
    static {
        Telemetry.enabled(!"false".equalsIgnoreCase(System.getenv("OBSERVABILITY_ENABLED")));
        Telemetry.RECORDER.onComplete(record->QUEUE.execute(()->send(record)));
    }
    private ProxyObservationReporter(){ }
    public static void initialize(){ }
    public static long dropped(){return DROPPED.get();}
    private static void send(Recorder.Record record){
        HttpURLConnection c=null;
        try {
            String base=System.getenv("GATEWAY_CONTROL_ENDPOINT"),token=System.getenv("GATEWAY_LINEAGE_TOKEN");
            if(base==null){String lineage=System.getenv("GATEWAY_LINEAGE_ENDPOINT");if(lineage!=null&&lineage.endsWith("/lineage/events"))base=lineage.substring(0,lineage.length()-15)+"/sql";}
            if(base==null||token==null||token.length()<24){DROPPED.incrementAndGet();return;}
            URI uri=URI.create(base);
            if(!"https".equalsIgnoreCase(uri.getScheme())&&!("http".equalsIgnoreCase(uri.getScheme())&&java.util.Arrays.asList("127.0.0.1","localhost","[::1]","::1").contains(uri.getHost()))){DROPPED.incrementAndGet();return;}
            c=(HttpURLConnection)new URL(base+"/observation").openConnection();c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(250);c.setReadTimeout(500);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("X-Gateway-Lineage-Token",token);
            java.util.Map<String,Object> payload=JSON.convertValue(record,new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String,Object>>(){});
            payload.put("proxyDroppedTotal",DROPPED.get());
            byte[] bytes=JSON.writeValueAsBytes(payload);c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            if(c.getResponseCode()!=200)DROPPED.incrementAndGet();
        }catch(Exception ignored){DROPPED.incrementAndGet();}finally{if(c!=null)c.disconnect();}
    }
}
