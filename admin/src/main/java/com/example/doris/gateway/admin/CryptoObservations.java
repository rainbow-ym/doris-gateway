package com.example.doris.gateway.admin;

import com.example.doris.gateway.observability.*;
import static com.example.doris.gateway.observability.Observation.*;
import io.micrometer.prometheus.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.jvm.*;
import io.micrometer.core.instrument.binder.system.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import javax.annotation.*;
import java.lang.management.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Only numeric/enum telemetry. Bounded independent memory; never writes to business/system DB. */
@Component
public class CryptoObservations {
    private final PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    private final Deque<Event> history = new ArrayDeque<>();
    private final Map<String,Bucket[]> windows = new HashMap<>();
    private final LinkedHashMap<String,Boolean> recentIds = new LinkedHashMap<>();
    private long evicted, rejected, lastProxyAt, proxyDropped, sampledOut, sequence;
    private static final long[] BOUNDS = {1,5,10,25,50,100,250,500,1000,5000,30000,120000,600000};
    private final JvmGcMetrics gc = new JvmGcMetrics();
    @Value("${OBSERVABILITY_ENABLED:true}") private boolean enabled = true;
    @Value("${OBSERVABILITY_JOURNAL_DIR:}") private String journalDirectory = "";
    private ObservationJournal journal;
    @Value("${OBSERVABILITY_SAMPLE_EVERY:1}") private int sampleEvery=1;
    @PostConstruct public void start() {
        Telemetry.enabled(enabled);
        if(sampleEvery<1||sampleEvery>1000)sampleEvery=1;
        if(enabled&&!journalDirectory.trim().isEmpty())journal=new ObservationJournal(java.nio.file.Paths.get(journalDirectory),10*1024*1024,64);
        new JvmMemoryMetrics().bindTo(registry); gc.bindTo(registry); new JvmThreadMetrics().bindTo(registry);
        new ProcessorMetrics().bindTo(registry); new UptimeMetrics().bindTo(registry);
        Gauge.builder("gateway_observation_active",Telemetry.RECORDER,r->r.status().get("active")).register(registry);
        Gauge.builder("gateway_observation_dropped",Telemetry.RECORDER,r->r.status().get("dropped")).register(registry);
        Telemetry.RECORDER.onComplete(r->accept(Event.from(r),false));
    }
    @PreDestroy public void stop() { Telemetry.RECORDER.onComplete(r->{ }); if(journal!=null)journal.close(); gc.close(); registry.close(); }
    public String scrape() { return registry.scrape(); }
    public static class Timing { public long calls,totalNanos,maxNanos; }
    public static class Event {
        public int schemaVersion=1;
        public String requestId,spanId,parentSpanId,component="worker";
        public Operation operation; public Reason reason;
        public long durationNanos,startedAtMillis;
        public long proxyDroppedTotal;
        @com.fasterxml.jackson.annotation.JsonIgnore public long receivedAtMillis;
        public int unfinishedScopes;
        public Map<Stage,Timing> stages=new EnumMap<>(Stage.class);
        public Map<Measure,Long> measures=new EnumMap<>(Measure.class);
        static Event from(Recorder.Record r) {
            Event e=new Event(); e.requestId=r.requestId;e.spanId=r.spanId;e.parentSpanId=r.parentSpanId;
            e.operation=r.operation;e.reason=r.reason;e.durationNanos=r.durationNanos;e.startedAtMillis=r.startedAtMillis;
            e.unfinishedScopes=r.unfinishedScopes;e.measures.putAll(r.measures);
            r.stages.forEach((stage,value)->{Timing t=new Timing();t.calls=value.calls;t.totalNanos=value.totalNanos;t.maxNanos=value.maxNanos;e.stages.put(stage,t);});
            return e;
        }
    }
    private static boolean uuid(String s) {return s!=null&&s.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static boolean bounded(long n) {return n>=0&&n<=3600000000000L;}
    public synchronized void accept(Event e, boolean remote) {
        if(!enabled)return;
        if(e==null||e.schemaVersion!=1||!uuid(e.requestId)||!uuid(e.spanId)||e.parentSpanId!=null&&!uuid(e.parentSpanId)
                ||e.operation==null||e.reason==null||!bounded(e.durationNanos)||e.unfinishedScopes<0||e.unfinishedScopes>256
                ||e.stages==null||e.stages.size()>Stage.values().length||e.measures==null||e.measures.size()>Measure.values().length) {rejected++;return;}
        for(Map.Entry<Stage,Timing> entry:e.stages.entrySet()) {
            Timing t=entry.getValue();if(entry.getKey()==null||t==null||t.calls<0||t.calls>1000000||!bounded(t.totalNanos)||!bounded(t.maxNanos)){rejected++;return;}
        }
        for(Map.Entry<Measure,Long> entry:e.measures.entrySet())if(entry.getKey()==null||entry.getValue()==null||entry.getValue()<0||entry.getValue()>1073741824L){rejected++;return;}
        e.component=remote?"proxy":"worker"; // Never trust supplied component/cluster.
        String dedupe=e.component+e.spanId;if(recentIds.containsKey(dedupe))return;
        recentIds.put(dedupe,true);if(recentIds.size()>4000)recentIds.remove(recentIds.keySet().iterator().next());
        long now=System.currentTimeMillis();e.receivedAtMillis=now;if(remote){lastProxyAt=now;proxyDropped=Math.max(0,e.proxyDroppedTotal);}
        if(e.startedAtMillis<now-86400000L||e.startedAtMillis>now+120000L)e.startedAtMillis=0;
        boolean retain=e.reason!=Reason.OK||sequence++%sampleEvery==0;
        purge(now);if(retain){if(history.size()>=2000){history.removeFirst();evicted++;}history.addLast(e);}else sampledOut++;
        String key=e.component+":"+e.operation;Bucket[] ring=windows.computeIfAbsent(key,k->new Bucket[60]);
        long minute=now/60000;int index=(int)(minute%60);if(ring[index]==null||ring[index].minute!=minute)ring[index]=new Bucket(minute);
        ring[index].add(e);
        String[] tags={"component",e.component,"operation",e.operation.name()};
        io.micrometer.core.instrument.Timer.builder("gateway_crypto_duration").tags(tags).publishPercentileHistogram().maximumExpectedValue(Duration.ofMinutes(10)).register(registry).record(e.durationNanos,TimeUnit.NANOSECONDS);
        registry.counter("gateway_crypto_operations","component",e.component,"operation",e.operation.name(),"reason",e.reason.name(),"outcome",e.reason.outcome.name()).increment();
        e.stages.forEach((stage,t)->{
            registry.counter("gateway_crypto_stage_calls","component",e.component,"operation",e.operation.name(),"stage",stage.name()).increment(t.calls);
            // Per-request stage sum, NOT individual call latency distribution.
            io.micrometer.core.instrument.Timer.builder("gateway_crypto_stage_batch_duration").tags(tags).tag("stage",stage.name()).publishPercentileHistogram().register(registry).record(t.totalNanos,TimeUnit.NANOSECONDS);
        });
        e.measures.forEach((measure,value)->registry.counter("gateway_crypto_measure","component",e.component,"operation",e.operation.name(),"measure",measure.name()).increment(value));
        if(journal!=null&&retain)journal.offer(e);
    }
    private void purge(long now) { history.removeIf(e->now-e.receivedAtMillis>3600000L); }
    public synchronized Map<String,Object> status() {
        Map<String,Object> m=new LinkedHashMap<>();m.put("enabled",enabled);m.put("mode","LIVE");m.put("retained",history.size());m.put("evicted",evicted);m.put("rejected",rejected);
        m.put("worker",Telemetry.RECORDER.status());m.put("lastProxyAt",lastProxyAt==0?null:lastProxyAt);
        m.put("journalEnabled",journal!=null);m.put("journal",journal==null?null:journal.status());
        m.put("sampleEvery",sampleEvery);m.put("sampledOut",sampledOut);m.put("lastProxyDropped",proxyDropped);
        m.put("retention","内存最多 2000 条 / 1 小时，重启清空；趋势为最近 60 个分钟桶");
        m.put("boundary","Proxy 为加密结果准备到消费释放；Worker 为加解密服务完成（含事务提交），均不代表客户端接收完成");
        m.put("coverage","系统库计数仅覆盖已埋点调用；GC 等完整 JVM 指标见 metrics。容器内存与连接池未接入。Proxy 为异步尽力上报，缺失不能视作成功。");
        m.put("containerMemory",null);m.put("connectionPool",null);
        MemoryUsage heap=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();m.put("heapUsedBytes",heap.getUsed());m.put("heapMaxBytes",heap.getMax());
        m.put("threads",ManagementFactory.getThreadMXBean().getThreadCount());
        java.lang.management.OperatingSystemMXBean os=ManagementFactory.getOperatingSystemMXBean();
        double cpu=os instanceof com.sun.management.OperatingSystemMXBean?((com.sun.management.OperatingSystemMXBean)os).getProcessCpuLoad():-1;
        m.put("processCpu",cpu<0?null:cpu);return m;
    }
    private static void validate(String component,Operation operation,int minutes){if(!Arrays.asList("worker","proxy").contains(component)||operation==null||minutes<1||minutes>60)throw new IllegalArgumentException("监控筛选参数无效");}
    public synchronized Map<String,Object> summary(String component,Operation operation,int minutes) {
        validate(component,operation,minutes);long now=System.currentTimeMillis()/60000;Bucket sum=new Bucket(now);
        Bucket[] ring=windows.get(component+":"+operation);List<Map<String,Object>> trend=new ArrayList<>();
        for(long minute=now-minutes+1;minute<=now;minute++) {
            Bucket b=ring==null?null:ring[(int)(minute%60)];if(b==null||b.minute!=minute)b=new Bucket(minute);sum.merge(b);
            Map<String,Object> point=new LinkedHashMap<>();point.put("time",minute*60000);point.put("count",b.count);point.put("meanMs",b.count==0?null:b.nanos/1000000.0/b.count);trend.add(point);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("count",sum.count);result.put("reasons",sum.reasons);result.put("measures",sum.measures);
        result.put("p50Ms",sum.quantile(.5));result.put("p95Ms",sum.quantile(.95));result.put("p99Ms",sum.quantile(.99));
        result.put("percentileMethod","直方图桶上界估计，超过 10 分钟或无样本显示不可用");result.put("stages",sum.stages);result.put("trend",trend);
        result.put("windowMinutes",minutes);result.put("component",component);return result;
    }
    public synchronized Map<String,Object> requests(String component,Operation operation,Outcome outcome,int page,int size) {
        validate(component,operation,1);if(page<1||page>100000||size<1||size>100)throw new IllegalArgumentException("分页参数无效");purge(System.currentTimeMillis());
        List<Event> rows=new ArrayList<>();Iterator<Event> it=history.descendingIterator();while(it.hasNext()){Event e=it.next();if(e.component.equals(component)&&e.operation==operation&&(outcome==null||e.reason.outcome==outcome))rows.add(e);}
        int start=Math.min(rows.size(),(page-1)*size);Map<String,Object> result=new LinkedHashMap<>();result.put("total",rows.size());result.put("items",new ArrayList<>(rows.subList(start,Math.min(rows.size(),start+size))));return result;
    }
    public synchronized List<Event> detail(String id) {if(!uuid(id))throw new IllegalArgumentException("请求编号无效");purge(System.currentTimeMillis());List<Event> result=new ArrayList<>();for(Event e:history)if(e.requestId.equals(id))result.add(e);return result;}
    private static class Bucket {
        final long minute;long count,nanos;long[] histogram=new long[BOUNDS.length+1];
        Map<Reason,Long> reasons=new EnumMap<>(Reason.class);Map<Measure,Long> measures=new EnumMap<>(Measure.class);Map<Stage,Long> stages=new EnumMap<>(Stage.class);
        Bucket(long minute){this.minute=minute;}
        void add(Event e){count++;nanos+=e.durationNanos;int b=0;while(b<BOUNDS.length&&e.durationNanos>BOUNDS[b]*1000000)b++;histogram[b]++;reasons.merge(e.reason,1L,Long::sum);e.measures.forEach((k,v)->measures.merge(k,v,Long::sum));e.stages.forEach((k,v)->stages.merge(k,v.totalNanos,Long::sum));}
        void merge(Bucket b){count+=b.count;nanos+=b.nanos;for(int i=0;i<histogram.length;i++)histogram[i]+=b.histogram[i];b.reasons.forEach((k,v)->reasons.merge(k,v,Long::sum));b.measures.forEach((k,v)->measures.merge(k,v,Long::sum));b.stages.forEach((k,v)->stages.merge(k,v,Long::sum));}
        Long quantile(double p){if(count==0)return null;long target=(long)Math.ceil(count*p),seen=0;for(int i=0;i<histogram.length;i++){seen+=histogram[i];if(seen>=target)return i<BOUNDS.length?BOUNDS[i]:null;}return null;}
    }
}
