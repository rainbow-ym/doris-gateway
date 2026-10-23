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
    private static LocalMetricsServer metrics;
    private static boolean attempted;
    private ProxyObservationReporter(){ }
    public static synchronized void initialize(){
        if(attempted)return;attempted=true;
        String password=System.getenv("METRICS_PASSWORD");if(password==null||password.isEmpty())return;
        try{int port=Integer.parseInt(System.getenv().getOrDefault("PROXY_METRICS_PORT","18092"));
            metrics=new LocalMetricsServer(port,password,()->{
                StringBuilder text=new StringBuilder(Telemetry.RECORDER.cumulative.scrape("proxy",System.currentTimeMillis()));
                Telemetry.RECORDER.status().forEach((k,v)->text.append("gateway_export_observation_").append(k).append("{component=\"proxy\"} ").append(v).append('\n'));
                text.append("gateway_export_detail_dropped_total ").append(DROPPED.get()).append('\n');
                text.append("gateway_export_detail_queue_size ").append(QUEUE.getQueue().size()).append('\n');
                java.lang.management.MemoryUsage heap=java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                text.append("gateway_proxy_heap_used_bytes ").append(heap.getUsed()).append('\n');
                text.append("gateway_proxy_heap_max_bytes ").append(heap.getMax()).append('\n');
                text.append("gateway_proxy_threads ").append(java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount()).append('\n');
                UserBackendConnections.poolMetrics().forEach((k,v)->text.append("gateway_proxy_pool_").append(k).append(' ').append(v).append('\n'));
                java.lang.management.OperatingSystemMXBean os=java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                if(os instanceof com.sun.management.OperatingSystemMXBean){double cpu=((com.sun.management.OperatingSystemMXBean)os).getProcessCpuLoad();if(cpu>=0)text.append("gateway_proxy_cpu_ratio ").append(cpu).append('\n');}
                long gcMillis=0,gcCount=0;for(java.lang.management.GarbageCollectorMXBean gc:java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()){gcMillis+=Math.max(0,gc.getCollectionTime());gcCount+=Math.max(0,gc.getCollectionCount());}
                text.append("gateway_proxy_gc_seconds_total ").append(gcMillis/1000.0).append('\n');text.append("gateway_proxy_gc_total ").append(gcCount).append('\n');
                return text.append(ContainerMetrics.scrape("proxy")).toString();});
        }catch(Exception failure){System.err.println("Gateway metrics endpoint unavailable; check loopback port and metrics configuration.");}
    }
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
