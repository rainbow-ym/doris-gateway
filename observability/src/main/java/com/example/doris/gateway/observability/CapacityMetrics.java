package com.example.doris.gateway.observability;

import java.util.*;
import static com.example.doris.gateway.observability.Observation.*;

/** Bounded local cumulative metrics, independent of request-history sampling and remote delivery. */
public final class CapacityMetrics {
    private static final double[] BOUNDS={.001,.005,.01,.025,.05,.1,.25,.5,1,2,5,10,20,30,60,120,300,600};
    private final Map<String,Totals> totals=new TreeMap<>();
    private static final class Totals {
        long count,nanos;long[] buckets=new long[BOUNDS.length+1];
        final EnumMap<Measure,Long> measures=new EnumMap<>(Measure.class);
        final EnumMap<Stage,long[]> stages=new EnumMap<>(Stage.class);
        long minute=-1,currentMax,previousMinute=-1,previousMax;
        void rotate(long now){if(minute!=now){previousMinute=minute;previousMax=currentMax;minute=now;currentMax=0;}}
    }
    public synchronized void record(Recorder.Record r,long nowMillis){
        String key="operation=\""+r.operation+"\",outcome=\""+r.reason.outcome+"\"";
        Totals t=totals.computeIfAbsent(key,k->new Totals());t.count++;t.nanos+=r.durationNanos;
        int b=0;while(b<BOUNDS.length&&r.durationNanos/1e9>BOUNDS[b])b++;t.buckets[b]++;
        t.rotate(nowMillis/60000);t.currentMax=Math.max(t.currentMax,r.durationNanos);
        r.measures.forEach((k,v)->t.measures.merge(k,v,Long::sum));
        r.stages.forEach((k,v)->{long[] a=t.stages.computeIfAbsent(k,s->new long[3]);a[0]+=v.calls;a[1]+=v.totalNanos;a[2]=Math.max(a[2],v.maxNanos);});
    }
    public synchronized String scrape(String component,long nowMillis){
        if(!"proxy".equals(component)&&!"worker".equals(component))throw new IllegalArgumentException("Invalid component");
        StringBuilder out=new StringBuilder("# TYPE gateway_export_duration_seconds histogram\n");
        for(String counter:Arrays.asList("measure_total","stage_calls_total","stage_seconds_total"))out.append("# TYPE gateway_export_").append(counter).append(" counter\n");
        for(String gauge:Arrays.asList("stage_lifetime_max_seconds","last_minute_max_seconds","last_complete_minute_timestamp_seconds"))out.append("# TYPE gateway_export_").append(gauge).append(" gauge\n");
        for(Map.Entry<String,Totals> entry:totals.entrySet()){
            String tags="component=\""+component+"\","+entry.getKey();Totals t=entry.getValue();long count=0;
            for(int i=0;i<t.buckets.length;i++){count+=t.buckets[i];line(out,"gateway_export_duration_seconds_bucket",tags+",le=\""+(i==BOUNDS.length?"+Inf":Double.toString(BOUNDS[i]))+"\"",count);}
            line(out,"gateway_export_duration_seconds_count",tags,t.count);line(out,"gateway_export_duration_seconds_sum",tags,t.nanos/1e9);
            t.rotate(nowMillis/60000);
            line(out,"gateway_export_last_minute_max_seconds",tags,t.previousMinute==nowMillis/60000-1?t.previousMax/1e9:0);
            for(Map.Entry<Measure,Long> m:t.measures.entrySet())line(out,"gateway_export_measure_total",tags+",measure=\""+m.getKey()+"\"",m.getValue());
            for(Map.Entry<Stage,long[]> s:t.stages.entrySet()){
                String stage=tags+",stage=\""+s.getKey()+"\"";long[] a=s.getValue();
                line(out,"gateway_export_stage_calls_total",stage,a[0]);line(out,"gateway_export_stage_seconds_total",stage,a[1]/1e9);
                line(out,"gateway_export_stage_lifetime_max_seconds",stage,a[2]/1e9);
            }
        }
        line(out,"gateway_export_last_complete_minute_timestamp_seconds","component=\""+component+"\"",(nowMillis/60000-1)*60);
        return out.toString();
    }
    private static void line(StringBuilder out,String name,String tags,double value){out.append(name).append('{').append(tags).append("} ").append(value).append('\n');}
}
