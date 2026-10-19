package com.example.doris.gateway.admin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
class SystemDataSourceTest {
 @Test void legacyUrlIsNormalizedBeforeAllPooledConnectionsAndConcurrentInserts()throws Exception {
  DataSourceProperties properties=new DataSourceProperties();
  properties.setUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MYSQL;DATABASE_TO_UPPER=FALSE");properties.setUsername("sa");properties.setPassword("");properties.setDriverClassName("org.h2.Driver");
  try(HikariDataSource ds=new SystemDataSourceConfig().dataSource(properties)) {
   ds.setMaximumPoolSize(8);JdbcTemplate jdbc=new JdbcTemplate(ds);
   assertTrue(ds.getJdbcUrl().contains("MODE=REGULAR"));
   jdbc.execute("CREATE TABLE counters(id BIGINT AUTO_INCREMENT PRIMARY KEY,detail VARCHAR(64))");
   ExecutorService pool=Executors.newFixedThreadPool(8);
   try {
    List<Future<?>> futures=new ArrayList<>();
    for(int thread=0;thread<8;thread++){final int number=thread;futures.add(pool.submit(()->{for(int i=0;i<2000;i++)jdbc.update("INSERT INTO counters(detail) VALUES(?)",number+":"+i);}));}
    for(Future<?> future:futures)future.get(60,TimeUnit.SECONDS);
   }finally{pool.shutdownNow();}
   assertEquals(16000,jdbc.queryForObject("SELECT COUNT(*) FROM counters",Integer.class));
   assertEquals("REGULAR",jdbc.queryForObject("SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME='MODE'",String.class));
  }
 }
}
