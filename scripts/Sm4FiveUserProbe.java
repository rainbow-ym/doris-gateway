import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Closed-loop five-user read-only probe. Never prints SQL, passwords or result values. */
public final class Sm4FiveUserProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Provide the private probe.properties path");
        Path config=Paths.get(args[0]).toAbsolutePath();
        Properties settings=new Properties();
        try(java.io.Reader reader=Files.newBufferedReader(config,StandardCharsets.UTF_8)){settings.load(reader);}
        int iterations=Integer.parseInt(settings.getProperty("iterations","10"));
        if(iterations<1||iterations>100)throw new IllegalArgumentException("iterations must be 1..100");
        String[] urls=new String[5],users=new String[5],passwords=new String[5],sql=new String[5];
        Set<String> uniqueUsers=new HashSet<>();
        for(int i=0;i<5;i++){
            String suffix="."+ (i+1);
            urls[i]=required(settings,"url"+suffix);users[i]=required(settings,"user"+suffix);
            passwords[i]=required(settings,"password"+suffix);uniqueUsers.add(users[i]);
            sql[i]=new String(Files.readAllBytes(config.getParent().resolve(required(settings,"sql"+suffix))),StandardCharsets.UTF_8).trim();
            if(sql[i].endsWith(";"))sql[i]=sql[i].substring(0,sql[i].length()-1);
            if(!sql[i].toUpperCase(Locale.ROOT).startsWith("SELECT ")||sql[i].contains(";"))
                throw new IllegalArgumentException("Only one SELECT statement per file is allowed");
            if(!urls[i].startsWith("jdbc:mysql:")||urls[i].toLowerCase(Locale.ROOT).contains("allowmultiqueries=true"))
                throw new IllegalArgumentException("Use a MySQL JDBC URL without multi-statements");
        }
        if(uniqueUsers.size()!=5)throw new IllegalArgumentException("Five distinct database users are required");
        int expected=Integer.parseInt(settings.getProperty("expectedRows","180"));
        if(expected<1||expected>1000)throw new IllegalArgumentException("expectedRows must be 1..1000");
        Class.forName("com.mysql.cj.jdbc.Driver");
        ExecutorService pool=Executors.newFixedThreadPool(5);
        CyclicBarrier start=new CyclicBarrier(5);
        List<Future<List<Double>>> futures=new ArrayList<>();
        long began=System.nanoTime();
        try{
            for(int index=0;index<5;index++){
                final int user=index;
                futures.add(pool.submit(()->{
                    List<Double> elapsed=new ArrayList<>();
                    Properties credentials=new Properties();credentials.setProperty("user",users[user]);credentials.setProperty("password",passwords[user]);
                    credentials.setProperty("connectTimeout","10000");credentials.setProperty("socketTimeout","120000");
                    try(Connection connection=DriverManager.getConnection(urls[user],credentials)){
                        start.await(30,TimeUnit.SECONDS);
                        for(int run=0;run<iterations;run++){
                            long before=System.nanoTime();int rows=0;
                            try(Statement statement=connection.createStatement()){
                                statement.setQueryTimeout(120);
                                try(ResultSet result=statement.executeQuery(sql[user])){
                                    int columns=result.getMetaData().getColumnCount();
                                    if(!"__gateway_sm4".equals(result.getMetaData().getColumnLabel(columns)))
                                        throw new IllegalStateException("Encrypted column is missing");
                                    while(result.next()){
                                        rows++;String envelope=result.getString(columns);
                                        if(envelope==null||!envelope.startsWith("__GW_SM4_V1__:"))
                                            throw new IllegalStateException("Encrypted cell is missing");
                                    }
                                }
                            }
                            if(rows!=expected)throw new IllegalStateException("Unexpected result row count");
                            double ms=(System.nanoTime()-before)/1000000.0;elapsed.add(ms);
                            System.out.printf(Locale.ROOT,"slot=%d query=%d rows=%d latency_ms=%.2f%n",user+1,run+1,rows,ms);
                        }
                    }
                    return elapsed;
                }));
            }
            List<Double> times=new ArrayList<>();
            for(Future<List<Double>> future:futures)times.addAll(future.get());
            double seconds=(System.nanoTime()-began)/1000000000.0;
            Collections.sort(times);
            System.out.printf(Locale.ROOT,"PASS concurrency=5 successes=%d elapsed_s=%.3f QPS_including_connect=%.3f p50_ms=%.2f p95_ms=%.2f max_ms=%.2f%n",
                    times.size(),seconds,times.size()/seconds,times.get((times.size()-1)/2),
                    times.get((int)Math.ceil(times.size()*0.95)-1),times.get(times.size()-1));
        }catch(Exception failure){
            System.err.println("FAIL: five-user probe did not complete; no QPS claim. Check server logs at this time. Credentials, SQL and data are suppressed.");
            System.exit(1);
        }finally{pool.shutdownNow();}
    }
    private static String required(Properties p,String key){
        String value=p.getProperty(key);if(value==null||value.isEmpty())throw new IllegalArgumentException("Missing "+key);return value;
    }
}
