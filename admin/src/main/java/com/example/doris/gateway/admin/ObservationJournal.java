package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.*;

/** Optional dedicated numeric telemetry journal. Never stores SQL, cells or exception messages. */
final class ObservationJournal implements AutoCloseable {
    private final Path directory;private final long limit;private final ObjectMapper json=new ObjectMapper();
    private final AtomicLong written=new AtomicLong(),dropped=new AtomicLong(),errors=new AtomicLong();
    private final ThreadPoolExecutor executor;
    ObservationJournal(Path directory,long limit,int capacity){
        if(limit<1024||limit>64*1024*1024||capacity<1||capacity>256)throw new IllegalArgumentException("Invalid journal bounds");
        this.directory=directory.toAbsolutePath().normalize();this.limit=limit;
        executor=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),r->{Thread t=new Thread(r,"observation-journal");t.setDaemon(true);return t;},(r,e)->dropped.incrementAndGet());
    }
    void offer(CryptoObservations.Event event){executor.execute(()->{
        try{
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory))throw new java.io.IOException("symlink directory");
            byte[] bytes=(json.writeValueAsString(event)+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(bytes.length>16384||bytes.length>limit){dropped.incrementAndGet();return;}
            Path current=directory.resolve("requests.jsonl"),previous=directory.resolve("requests.previous.jsonl");
            if(Files.isSymbolicLink(current)||Files.isSymbolicLink(previous))throw new java.io.IOException("symlink file");
            if(Files.exists(current)&&Files.size(current)+bytes.length>limit)Files.move(current,previous,StandardCopyOption.REPLACE_EXISTING);
            Files.write(current,bytes,StandardOpenOption.CREATE,StandardOpenOption.APPEND);written.incrementAndGet();
        }catch(Exception ignored){errors.incrementAndGet();dropped.incrementAndGet();}
    });}
    Map<String,Long> status(){Map<String,Long> result=new LinkedHashMap<>();result.put("written",written.get());result.put("dropped",dropped.get());result.put("errors",errors.get());result.put("queued",(long)executor.getQueue().size());return result;}
    public void close(){executor.shutdown();try{if(!executor.awaitTermination(2,TimeUnit.SECONDS))dropped.addAndGet(executor.shutdownNow().size());}catch(InterruptedException e){Thread.currentThread().interrupt();dropped.addAndGet(executor.shutdownNow().size());}}
}
