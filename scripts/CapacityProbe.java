import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Bounded client-side query/decrypt benchmark. Never prints credentials, SQL or cell values. */
public final class CapacityProbe {
    private final Properties settings;
    private final AtomicLong offered=new AtomicLong(),success=new AtomicLong(),failed=new AtomicLong(),rejected=new AtomicLong(),rows=new AtomicLong();
    private final List<Long> latencies=Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentLinkedQueue<Connection> connections=new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Connection> connection=new ThreadLocal<>();
    private final ThreadLocal<Integer> slot=new ThreadLocal<>();
    private final AtomicInteger slots=new AtomicInteger();
    private final String mode,sql,body;
    private final int concurrency,seconds,expected;
    private final double qps;
    private final boolean encrypted;
    CapacityProbe(Properties settings,Path directory)throws Exception{
        this.settings=settings;mode=settings.getProperty("mode","query");concurrency=number("concurrency",5,1,64);seconds=number("seconds",60,1,3600);expected=number("expectedRows",180,1,1000);
        qps=Double.parseDouble(settings.getProperty("arrivalQps","0"));if(!Double.isFinite(qps)||qps<0||qps>10000)throw new IllegalArgumentException("arrivalQps must be 0..10000");
        encrypted=Boolean.parseBoolean(settings.getProperty("expectEncrypted","true"));
        if("query".equals(mode)){
            sql=new String(Files.readAllBytes(directory.resolve(required("sql"))),StandardCharsets.UTF_8).trim();body=null;
            if(!sql.toUpperCase(Locale.ROOT).startsWith("SELECT ")||sql.contains(";")||sql.toUpperCase(Locale.ROOT).matches("(?s).*\\b(INTO|OUTFILE|DUMPFILE)\\b.*"))throw new IllegalArgumentException("Use one read-only SELECT without semicolons or INTO");
            String url=required("url");if(!url.startsWith("jdbc:mysql:")||url.toLowerCase(Locale.ROOT).contains("allowmultiqueries=true"))throw new IllegalArgumentException("Invalid JDBC URL");
            Set<String> users=new HashSet<>();for(int i=1;i<=concurrency;i++){users.add(required("user."+i));required("password."+i);}if(users.size()!=concurrency)throw new IllegalArgumentException("Use distinct query users");
        }else if("decrypt".equals(mode)){
            sql=null;Path csv=directory.resolve(required("csv"));if(Files.size(csv)>1024*1024)throw new IllegalArgumentException("CSV exceeds 1 MiB");
            body="{\"csv\":"+quote(new String(Files.readAllBytes(csv),StandardCharsets.UTF_8))+"}";
            URI uri=URI.create(required("decryptUrl"));if(!"https".equals(uri.getScheme())&&!("http".equals(uri.getScheme())&&Arrays.asList("localhost","127.0.0.1","::1","[::1]").contains(uri.getHost())))throw new IllegalArgumentException("Use HTTPS or loopback for administrator credentials");
            required("adminUser");required("adminPassword");
        }else throw new IllegalArgumentException("mode must be query or decrypt");
    }
    private int number(String key,int fallback,int min,int max){int n=Integer.parseInt(settings.getProperty(key,String.valueOf(fallback)));if(n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;}
    private String required(String key){String s=settings.getProperty(key);if(s==null||s.isEmpty())throw new IllegalArgumentException("Missing "+key);return s;}
    static String quote(String value){StringBuilder b=new StringBuilder("\"");for(char c:value.toCharArray()){if(c=='"'||c=='\\')b.append('\\').append(c);else if(c<32)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}return b.append('"').toString();}
    private long query()throws Exception{
        if(slot.get()==null)slot.set(slots.incrementAndGet());int user=slot.get();Connection c=connection.get();
        if(c==null||c.isClosed()){
            Properties p=new Properties();p.setProperty("user",required("user."+user));p.setProperty("password",required("password."+user));p.setProperty("connectTimeout","10000");p.setProperty("socketTimeout","120000");
            p.setProperty("useServerPrepStmts",settings.getProperty("prepared","false"));c=DriverManager.getConnection(required("url"),p);c.setReadOnly(true);connection.set(c);connections.add(c);
        }
        boolean prepared=Boolean.parseBoolean(settings.getProperty("prepared","false"));
        try(Statement statement=prepared?c.prepareStatement(sql):c.createStatement()){
            statement.setQueryTimeout(120);long count=0;
            try(ResultSet result=prepared?((PreparedStatement)statement).executeQuery():statement.executeQuery(sql)){
                int columns=result.getMetaData().getColumnCount();if(encrypted&&!"__gateway_sm4".equals(result.getMetaData().getColumnLabel(columns)))throw new IllegalStateException("Missing encryption column");
                while(result.next()){count++;for(int i=1;i<=columns;i++)result.getObject(i);if(encrypted&&!result.getString(columns).startsWith("__GW_SM4_V1__:"))throw new IllegalStateException("Invalid encrypted row");}
            }
            if(count!=expected)throw new IllegalStateException("Unexpected row count");return count;
        }catch(Exception e){try{c.close();}catch(Exception ignored){}connection.remove();throw e;}
    }
    private long decrypt()throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(required("decryptUrl")).openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(120000);c.setRequestMethod("POST");c.setDoOutput(true);
        c.setRequestProperty("Authorization","Basic "+Base64.getEncoder().encodeToString((required("adminUser")+":"+required("adminPassword")).getBytes(StandardCharsets.UTF_8)));c.setRequestProperty("Content-Type","application/json");
        byte[] request=body.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(request.length);
        try{
            try(OutputStream out=c.getOutputStream()){out.write(request);}if(c.getResponseCode()!=200)throw new IOException("Decrypt rejected");
            ByteArrayOutputStream response=new ByteArrayOutputStream();try(InputStream in=c.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(response.size()+n>8*1024*1024)throw new IOException("Response exceeds bound");response.write(buffer,0,n);}}
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("\"rowCount\"\\s*:\\s*(\\d+)").matcher(new String(response.toByteArray(),StandardCharsets.UTF_8));
            if(!match.find()||Long.parseLong(match.group(1))!=expected)throw new IOException("Unexpected row count");return expected;
        }finally{c.disconnect();}
    }
    private void request(long scheduled){
        try{long n="query".equals(mode)?query():decrypt();rows.addAndGet(n);success.incrementAndGet();synchronized(latencies){if(latencies.size()<1000000)latencies.add(System.nanoTime()-scheduled);}}
        catch(Exception e){failed.incrementAndGet();}
    }
    void run()throws Exception{
        BlockingQueue<Runnable> queue=qps==0?new ArrayBlockingQueue<>(concurrency):new SynchronousQueue<>();
        ThreadPoolExecutor pool=new ThreadPoolExecutor(concurrency,concurrency,0,TimeUnit.SECONDS,queue,new ThreadPoolExecutor.AbortPolicy());pool.prestartAllCoreThreads();
        long start=System.nanoTime(),deadline=start+TimeUnit.SECONDS.toNanos(seconds);
        try{
            if(qps==0){CountDownLatch done=new CountDownLatch(concurrency);for(int i=0;i<concurrency;i++)pool.execute(()->{try{while(System.nanoTime()<deadline){offered.incrementAndGet();request(System.nanoTime());}}finally{done.countDown();}});done.await();}
            else{
                long interval=(long)(1e9/qps),ordinal=0;
                while(true){long due=start+ordinal++*interval;if(due>=deadline)break;long wait=due-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);offered.incrementAndGet();try{pool.execute(()->request(due));}catch(RejectedExecutionException e){rejected.incrementAndGet();}}
            }
        }finally{pool.shutdown();if(!pool.awaitTermination(150,TimeUnit.SECONDS)){pool.shutdownNow();throw new IllegalStateException("Incomplete requests; no capacity claim");}for(Connection c:connections)try{c.close();}catch(Exception ignored){}}
        double elapsed=(System.nanoTime()-start)/1e9;Collections.sort(latencies);long sum=0;for(long n:latencies)sum+=n;
        if(latencies.size()!=success.get())throw new IllegalStateException("Latency sample bound reached; shorten the run before drawing conclusions");
        System.out.printf(Locale.ROOT,"{\"mode\":\"%s\",\"concurrency\":%d,\"arrivalQps\":%.3f,\"offered\":%d,\"success\":%d,\"failed\":%d,\"clientRejected\":%d,\"successRows\":%d,\"elapsedSecondsIncludingDrain\":%.3f,\"completedQps\":%.3f,\"rowsPerSecond\":%.3f,\"meanMs\":%s,\"p95Ms\":%s,\"p99Ms\":%s,\"maxMs\":%s,\"latencySamples\":%d}%n",mode,concurrency,qps,offered.get(),success.get(),failed.get(),rejected.get(),rows.get(),elapsed,success.get()/elapsed,rows.get()/elapsed,latencies.isEmpty()?"null":Double.toString(sum/1e6/latencies.size()),percentile(.95),percentile(.99),percentile(1),latencies.size());
    }
    private String percentile(double p){return latencies.isEmpty()?"null":Double.toString(latencies.get((int)Math.ceil(latencies.size()*p)-1)/1e6);}
    public static void main(String[] args)throws Exception{
        try{if(args.length!=1)throw new IllegalArgumentException("Provide private properties file");Path file=Paths.get(args[0]).toAbsolutePath();Properties p=new Properties();try(Reader r=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(r);}new CapacityProbe(p,file.getParent()).run();}
        catch(Exception e){System.err.println("Probe failed: "+e.getClass().getSimpleName()+". SQL, credentials and values suppressed; no capacity conclusion.");System.exit(1);}
    }
}
