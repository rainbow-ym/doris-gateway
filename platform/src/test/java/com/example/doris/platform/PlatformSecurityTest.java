package com.example.doris.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:platform;MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1","PLATFORM_MASTER_KEY_B64=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","PLATFORM_ADMIN_PASSWORD=Platform_Test_20261015"})
@AutoConfigureMockMvc
class PlatformSecurityTest {
    @Autowired MockMvc mvc;@Autowired PlatformStore store;@MockBean WorkerClient client;
    @Test void rejectsUnauthenticatedAndMissingCsrf()throws Exception{
        mvc.perform(get("/api/clusters")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/clusters").with(user("admin").roles("ADMIN")).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/clusters").with(user("reader").roles("READER"))).andExpect(status().isForbidden());
    }
    @Test void loginAndLogoutInvalidateSession()throws Exception{
        mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","wrong")).andExpect(status().isUnauthorized());
        MockHttpSession session=(MockHttpSession)mvc.perform(post("/login").with(csrf()).param("username","admin").param("password","Platform_Test_20261015")).andExpect(status().isOk()).andReturn().getRequest().getSession(false);
        assertNotNull(session);mvc.perform(get("/api/clusters").session(session)).andExpect(status().isOk());
        mvc.perform(post("/logout").session(session).with(csrf())).andExpect(status().isNoContent());assertTrue(session.isInvalid());
    }
    @Test void clusterRegistrationDoesNotExposePasswordsAndRejectsDuplicate()throws Exception{
        String id=store.register("测试集群","http://127.0.0.1:18082",UUID.randomUUID().toString(),"worker","SecretOnlyHere!","host:3308");
        assertThrows(IllegalArgumentException.class,()->store.register("测试集群","http://127.0.0.1:18083",UUID.randomUUID().toString(),"worker","different","host:3309"));
        String body=mvc.perform(get("/api/clusters").with(user("admin").roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("SecretOnlyHere"));assertFalse(body.contains("password_enc"));assertEquals("SecretOnlyHere!",store.password(store.cluster(id)));
    }
    @Test void relayChecksIdentityAndNeverFallsBack()throws Exception{
        String identity=UUID.randomUUID().toString(),id=store.register("转发测试","http://127.0.0.1:19082",identity,"worker","secret","host:3398");
        when(client.identity("http://127.0.0.1:19082","worker","secret")).thenReturn(Collections.singletonMap("id",UUID.randomUUID().toString()));
        mvc.perform(get("/api/clusters/"+id+"/relay/api/connections").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        verify(client,never()).call(anyString(),anyString(),anyString(),anyString(),anyString(),any());
    }
    @Test void credentialsAndUrlsAreValidated(){
        CredentialVault vault=new CredentialVault("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");String a=vault.encrypt("secret"),b=vault.encrypt("secret");assertNotEquals(a,b);assertEquals("secret",vault.decrypt(a));
        assertThrows(IllegalArgumentException.class,()->WorkerClient.validateUrl("http://192.168.1.1:18080"));
        assertThrows(IllegalArgumentException.class,()->WorkerClient.validateUrl("https://user:pass@example.com"));
        assertEquals("http://127.0.0.1:18082",WorkerClient.validateUrl("http://127.0.0.1:18082/"));
        LoginThrottle throttle=new LoginThrottle();for(int i=0;i<12;i++)assertTrue(throttle.allowed("1"));assertFalse(throttle.allowed("1"));
    }
}
