package com.example.doris.gateway.lineage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Durable audit messages only; this queue never stores or replays business SQL. */
public final class AuditOutbox {
    @FunctionalInterface public interface Sender { void send(String payload); }
    private final Path directory;
    private final Sender sender;
    public AuditOutbox(Path directory,Sender sender) {this.directory=directory;this.sender=sender;}

    public synchronized void record(String id,String outcome,String payload) {
        if(!id.matches("[a-f0-9-]{36}")||!Arrays.asList("STARTED","EXECUTED","UNKNOWN").contains(outcome))
            throw new IllegalArgumentException("审计消息标识不合法。");
        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>16384)throw new IllegalStateException("审计消息超出大小限制。");
        try {
            Files.createDirectories(directory);
            if(Files.isSymbolicLink(directory))throw new IOException("unsafe directory");
            privateMode(directory,true);
            Path file=directory.resolve(id+"-"+outcome+".pending");
            if(Files.exists(file,LinkOption.NOFOLLOW_LINKS)) {
                if(Files.isSymbolicLink(file)||!Arrays.equals(bytes,Files.readAllBytes(file)))throw new IOException("message conflict");
            } else {
                try(java.util.stream.Stream<Path> paths=Files.list(directory)) {
                    if(paths.limit(10001).count()>=10000)throw new IOException("queue full");
                }
                Path temporary=Files.createTempFile(directory,"audit-",".tmp");privateMode(temporary,false);
                try(FileChannel channel=FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
                }
                Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);
                syncDirectory();
            }
            deliver(file);
        } catch(IOException error) {throw new IllegalStateException("审计持久队列写入或确认失败，需核实请求编号："+id,error);}
    }

    public synchronized void retry() {
        if(!Files.isDirectory(directory)||Files.isSymbolicLink(directory))return;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(directory,"*.pending")) {
            int count=0;for(Path file:files) {
                if(++count>32)break;
                try {deliver(file);}catch(Exception ignored) {break;} // bounded; next scheduled pass retries.
            }
        } catch(IOException ignored) { /* No permission to replay SQL; leave durable files untouched. */ }
    }
    private void deliver(Path file) throws IOException {
        if(Files.isSymbolicLink(file)||Files.size(file)>16384)throw new IOException("invalid queue entry");
        sender.send(new String(Files.readAllBytes(file),StandardCharsets.UTF_8));
        Files.delete(file);syncDirectory();
    }
    private void syncDirectory() throws IOException {
        try(FileChannel channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
    }
    private static void privateMode(Path file,boolean dir) throws IOException {
        if(Files.getFileStore(file).supportsFileAttributeView("posix"))
            Files.setPosixFilePermissions(file,java.nio.file.attribute.PosixFilePermissions.fromString(dir?"rwx------":"rw-------"));
    }
}
