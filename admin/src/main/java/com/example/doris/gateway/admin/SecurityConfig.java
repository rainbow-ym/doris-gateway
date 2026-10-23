package com.example.doris.gateway.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

@Configuration
@EnableWebSecurity
@SuppressWarnings("deprecation")
public class SecurityConfig extends WebSecurityConfigurerAdapter {

    @Value("${ADMIN_USERNAME:admin}")
    private String username;

    @Value("${ADMIN_PASSWORD:}")
    private String password;

    @Value("${METRICS_PASSWORD:}") private String metricsPassword;

    @Override
    protected void configure(final AuthenticationManagerBuilder auth) throws Exception {
        if (password == null || password.length() < 12) {
            throw new IllegalStateException("ADMIN_PASSWORD must contain at least 12 characters.");
        }
        if(!metricsPassword.isEmpty()){
            if(metricsPassword.length()<24||"gateway_metrics".equals(username))throw new IllegalStateException("Invalid metrics credentials");
            auth.inMemoryAuthentication().withUser("gateway_metrics").password("{noop}"+metricsPassword).roles("METRICS");
        }
        auth.inMemoryAuthentication().withUser(username).password("{noop}" + password).roles("ADMIN");
    }

    @Override
    protected void configure(final HttpSecurity http) throws Exception {
        http.authorizeRequests().antMatchers("/api/health", "/api/internal/lineage/events", "/api/internal/sql/**").permitAll()
                .antMatchers(org.springframework.http.HttpMethod.GET,"/api/observability/metrics").hasAnyRole("METRICS","ADMIN")
                .antMatchers("/api/versions/*/recovery", "/api/observability/**", "/api/sm4-export/**").hasRole("ADMIN").anyRequest().hasRole("ADMIN")
                .and().httpBasic()
                .and().csrf().disable()
                .headers().frameOptions().deny().contentTypeOptions();
    }
}
