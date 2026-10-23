package com.example.doris.platform;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:history;MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1","PLATFORM_MASTER_KEY_B64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","PLATFORM_ADMIN_PASSWORD=Platform_Test_20261015"})
@AutoConfigureMockMvc
class HistoryProxyTest {
    static HttpServer upstream;
    static AtomicReference<String> identity=new AtomicReference<>(),cookie=new AtomicReference<>(),authorization=new AtomicReference<>();
    static {
        try {upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            upstream.createContext("/history/",exchange->{
                identity.set(exchange.getRequestHeaders().getFirst("X-Gateway-Grafana-User"));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                while(exchange.getRequestBody().read()!=-1){}
                if(exchange.getRequestURI().getPath().endsWith("redirect")){exchange.getResponseHeaders().set("Location","http://127.0.0.1:13000/history/d/gateway-capacity");exchange.sendResponseHeaders(302,-1);}
                else {byte[] data="test-body".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Set-Cookie","grafana_session=not-for-browser");exchange.getResponseHeaders().set("Content-Type","text/javascript");exchange.sendResponseHeaders(200,data.length);exchange.getResponseBody().write(data);}exchange.close();});upstream.start();
        } catch(Exception failure){throw new ExceptionInInitializerError(failure);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){registry.add("GRAFANA_LOCAL_PORT",()->upstream.getAddress().getPort());}
    @AfterAll static void close(){upstream.stop(0);}
    @Autowired MockMvc mvc;
    @Test void authenticationAndRolesApplyToAssetsAndQueries()throws Exception{
        mvc.perform(get("/history/public/test.js")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/history/public/test.js").with(user("reader").roles("READER"))).andExpect(status().isForbidden());
        mvc.perform(post("/history/api/ds/query").header("Origin","http://localhost").contentType("application/json").content("{}")).andExpect(status().is3xxRedirection());
    }
    @Test void upstreamIdentityCannotBeSpoofedAndCookiesNeverEscape()throws Exception{
        mvc.perform(get("/history/public/test.js").with(user("admin").roles("ADMIN")).header("X-Gateway-Grafana-User","admin").header("Cookie","secret=platform-session").header("Authorization","Basic attacker"))
            .andExpect(status().isOk()).andExpect(content().string("test-body")).andExpect(header().doesNotExist("Set-Cookie")).andExpect(header().string("X-Frame-Options","SAMEORIGIN"));
        assertEquals("gateway-platform-observer",identity.get());assertNull(cookie.get());assertNull(authorization.get());
    }
    @Test void queryAllowsOnlySameOriginJsonAndOtherMutationsStayBlocked()throws Exception{
        mvc.perform(post("/history/apis/features.grafana.app/v0alpha1/namespaces/default/ofrep/v1/evaluate/flags").with(user("admin").roles("ADMIN")).header("Origin","http://localhost").contentType("application/json").content("{}")).andExpect(status().isOk());
        mvc.perform(post("/history/api/ds/query").with(user("admin").roles("ADMIN")).header("Origin","http://localhost").contentType("application/json").content("{}")).andExpect(status().isOk());
        for(String origin:new String[]{"http://evil.example","null","http://localhost:9999",""})
            mvc.perform(post("/history/api/ds/query").with(user("admin").roles("ADMIN")).header("Origin",origin).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/history/api/ds/query").with(user("admin").roles("ADMIN")).header("Origin","http://localhost").contentType("text/plain").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/history/api/admin/users").with(user("admin").roles("ADMIN")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/clusters").with(user("admin").roles("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
    }
    @Test void redirectsRemainOnPlatformOrigin()throws Exception{
        mvc.perform(get("/history/redirect").with(user("admin").roles("ADMIN"))).andExpect(status().isFound()).andExpect(header().string("Location","/history/d/gateway-capacity"));
    }
}
