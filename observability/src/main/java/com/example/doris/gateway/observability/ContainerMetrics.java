package com.example.doris.gateway.observability;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read only at scrape time. Missing or unlimited limits are omitted, never reported as zero. */
public final class ContainerMetrics {
 private ContainerMetrics(){}
 public static String scrape(String component){
  Path root=Paths.get("/sys/fs/cgroup");
  try{String[] memberships=read(Paths.get("/proc/self/cgroup")).split("\\n");for(String line:memberships)if(line.startsWith("0::")){
   Path own=root.resolve(line.substring(3).replaceFirst("^/", "")).normalize();
   if(!own.startsWith(root))return scrape(Paths.get("/nonexistent-gateway-cgroup"),component);
   return scrape(own,component);
  }
   // shortcut: v1 non-root cgroups need mountinfo resolution; omit rather than report host totals.
   for(String line:memberships){String[] parts=line.split(":",3);if(parts.length==3&&!"/".equals(parts[2]))return scrape(Paths.get("/nonexistent-gateway-cgroup"),component);}
  }catch(Exception ignored){}
  return scrape(root,component);
 }
 public static String scrape(Path root,String component){
  if(!"proxy".equals(component)&&!"worker".equals(component))throw new IllegalArgumentException("Invalid component");
  StringBuilder out=new StringBuilder();String labels="{component=\""+component+"\"} ";boolean available=false;
  try{
   if(Files.isRegularFile(root.resolve("memory.current"))){
    metric(out,"memory_used_bytes",labels,number(root.resolve("memory.current")));available=true;
    String limit=read(root.resolve("memory.max"));if(!"max".equals(limit))metric(out,"memory_limit_bytes",labels,Long.parseLong(limit));
    String[] cpu=read(root.resolve("cpu.max")).split("\\s+");if(cpu.length==2&&!"max".equals(cpu[0]))metric(out,"cpu_limit_cores",labels,Double.parseDouble(cpu[0])/Double.parseDouble(cpu[1]));
    Map<String,Long> stats=pairs(root.resolve("cpu.stat"));if(stats.containsKey("throttled_usec"))metric(out,"cpu_throttled_seconds_total",labels,stats.get("throttled_usec")/1e6);
    if(stats.containsKey("usage_usec"))metric(out,"cpu_usage_seconds_total",labels,stats.get("usage_usec")/1e6);
    Map<String,Long> events=pairs(root.resolve("memory.events"));if(events.containsKey("oom_kill"))metric(out,"oom_kills_total",labels,events.get("oom_kill"));
   }else if(Files.isRegularFile(root.resolve("memory/memory.usage_in_bytes"))){
    metric(out,"memory_used_bytes",labels,number(root.resolve("memory/memory.usage_in_bytes")));available=true;
    long limit=number(root.resolve("memory/memory.limit_in_bytes"));if(limit<(1L<<60))metric(out,"memory_limit_bytes",labels,limit);
    long quota=number(root.resolve("cpu/cpu.cfs_quota_us")),period=number(root.resolve("cpu/cpu.cfs_period_us"));if(quota>0&&period>0)metric(out,"cpu_limit_cores",labels,quota/(double)period);
    Map<String,Long> stats=pairs(root.resolve("cpu/cpu.stat"));if(stats.containsKey("throttled_time"))metric(out,"cpu_throttled_seconds_total",labels,stats.get("throttled_time")/1e9);
   }
  }catch(Exception ignored){ /* Partial resource coverage is distinct from zero usage. */ }
  metric(out,"memory_available",labels,available?1:0);return out.toString();
 }
 private static String read(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.US_ASCII).trim();}
 private static long number(Path path)throws Exception{return Long.parseLong(read(path));}
 private static Map<String,Long> pairs(Path path)throws Exception{Map<String,Long> result=new HashMap<>();for(String line:read(path).split("\\n")){String[] pair=line.trim().split("\\s+");if(pair.length==2)result.put(pair[0],Long.parseLong(pair[1]));}return result;}
 private static void metric(StringBuilder out,String name,String labels,double value){out.append("gateway_cgroup_").append(name).append(labels).append(value).append('\n');}
}
