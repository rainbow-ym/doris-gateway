package com.example.doris.gateway.admin;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;

/** Bound the internal telemetry input before Jackson allocation. No changes to business endpoints. */
@Component
public class ObservationHttpBoundary extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        if(request.getRequestURI().startsWith("/api/observability/"))response.setHeader("Cache-Control","no-store");
        if(!"/api/internal/sql/observation".equals(request.getRequestURI())){chain.doFilter(request,response);return;}
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[2048];int n;
        try(InputStream input=request.getInputStream()){while((n=input.read(buffer))!=-1){if(out.size()+n>16384){response.sendError(413);return;}out.write(buffer,0,n);}}
        final byte[] bytes=out.toByteArray();
        chain.doFilter(new HttpServletRequestWrapper(request){
            @Override public ServletInputStream getInputStream(){ByteArrayInputStream in=new ByteArrayInputStream(bytes);return new ServletInputStream(){
                public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}
                public void setReadListener(ReadListener listener){throw new UnsupportedOperationException("Synchronous telemetry endpoint");}
            };}
        },response);
    }
}
