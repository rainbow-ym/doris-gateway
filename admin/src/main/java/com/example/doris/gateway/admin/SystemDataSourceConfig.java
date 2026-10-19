package com.example.doris.gateway.admin;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** H2 2.1.214 MySQL compatibility mode has a concurrent identity update race. */
@Configuration
public class SystemDataSourceConfig {
    static String safeUrl(String url) {
        if(url==null||!url.startsWith("jdbc:h2:"))throw new IllegalArgumentException("系统库目前仅支持独占 H2 实例。");
        return url.replaceAll("(?i);MODE=MySQL(?=;|$)",";MODE=REGULAR");
    }
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        // Normalize before opening any connection, including legacy env overrides.
        properties.setUrl(safeUrl(properties.getUrl()));
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
