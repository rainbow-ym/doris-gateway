package com.example.doris.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.*;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.security.web.session.HttpSessionEventPublisher;

@Configuration
@EnableWebSecurity
@SuppressWarnings("deprecation")
public class PlatformSecurity extends WebSecurityConfigurerAdapter {
    private final PlatformStore users;private final LoginThrottle throttle;
    public PlatformSecurity(PlatformStore users,LoginThrottle throttle){this.users=users;this.throttle=throttle;}
    @Bean public SessionRegistry sessionRegistry(){return new SessionRegistryImpl();}
    @Bean public ServletListenerRegistrationBean<HttpSessionEventPublisher> sessionEvents(){return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());}
    protected void configure(AuthenticationManagerBuilder a)throws Exception{a.userDetailsService(users).passwordEncoder(users.encoder);}
    protected void configure(HttpSecurity h)throws Exception{
        h.authorizeRequests().antMatchers("/login.html","/login","/api/session","/api/health").permitAll().anyRequest().hasRole("ADMIN")
            .and().formLogin().loginPage("/login.html").loginProcessingUrl("/login")
            .successHandler((r,s,a)->{users.audit(a.getName(),"LOGIN",null);s.setContentType("application/json");s.getWriter().write("{\"status\":\"OK\"}");})
            .failureHandler((r,s,e)->{s.setStatus(401);s.setContentType("application/json;charset=UTF-8");s.getWriter().write("{\"message\":\"账号或密码错误。\"}");})
            .and().logout().logoutUrl("/logout").invalidateHttpSession(true).deleteCookies("GATEWAY_PLATFORM_SESSION")
            .logoutSuccessHandler((r,s,a)->{if(a!=null)users.audit(a.getName(),"LOGOUT",null);s.setStatus(204);})
            .and().exceptionHandling().authenticationEntryPoint((r,s,e)->{
                if(r.getRequestURI().startsWith("/api/")){s.setStatus(401);s.setContentType("application/json;charset=UTF-8");s.getWriter().write("{\"message\":\"登录已过期，请重新登录。\"}");}
                else s.sendRedirect("/login.html");
            }).and().addFilterBefore(throttle,UsernamePasswordAuthenticationFilter.class);
        h.sessionManagement().sessionFixation().changeSessionId().maximumSessions(10).sessionRegistry(sessionRegistry());
        h.headers().frameOptions().disable().addHeaderWriter((request,response)->response.setHeader("X-Frame-Options",request.getRequestURI().startsWith("/history/")?"SAMEORIGIN":"DENY")).contentTypeOptions();
        h.csrf().ignoringRequestMatchers(HistoryController::readPost);
        // All mutations retain CSRF; the read-only Grafana query POST checks Origin and JSON in its controller.
    }
}
