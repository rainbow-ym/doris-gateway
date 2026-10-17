package com.example.doris.platform;

import org.springframework.stereotype.Component;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
import java.util.*;

@Component
public class LoginThrottle extends OncePerRequestFilter {
    private final Map<String,long[]> attempts=new LinkedHashMap<>();
    synchronized boolean allowed(String address) {
        long now=System.currentTimeMillis();attempts.entrySet().removeIf(e->now-e.getValue()[0]>300000);
        if(!attempts.containsKey(address)&&attempts.size()>=4096)return false;
        long[] row=attempts.computeIfAbsent(address,k->new long[]{now,0});return ++row[1]<=12;
    }
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if("POST".equals(request.getMethod())&&"/login".equals(request.getServletPath())&&!allowed(request.getRemoteAddr())) {
            response.setStatus(429);response.setContentType("application/json;charset=UTF-8");response.getWriter().write("{\"message\":\"登录过于频繁，请稍后重试。\"}");return;
        }
        chain.doFilter(request,response);
    }
}
