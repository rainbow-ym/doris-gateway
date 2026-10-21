package com.example.doris.gateway.admin;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Base64;
@WebMvcTest(controllers=ObservationController.class,properties={"ADMIN_USERNAME=observer","ADMIN_PASSWORD=local-test-only-12345"})
@Import({SecurityConfig.class,CryptoObservations.class,ObservationHttpBoundary.class})
class ObservationSecurityTest {
 @Autowired MockMvc mvc;
 @MockBean AccountAccessService access;
 String auth(){return "Basic "+Base64.getEncoder().encodeToString("observer:local-test-only-12345".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 @Test void managementAndMetricsRequireAdmin()throws Exception{
  mvc.perform(get("/api/observability/status")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/observability/metrics")).andExpect(status().isUnauthorized());
  mvc.perform(get("/api/observability/status").header("Authorization",auth())).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
  mvc.perform(get("/api/observability/summary").header("Authorization",auth()).param("component","another-cluster")).andExpect(status().isBadRequest());
 }
 @Test void telemetryRequiresInternalTokenAndRejectsLargeBody()throws Exception{
  doThrow(new SecurityException()).when(access).authenticate("wrong");
  mvc.perform(post("/api/internal/sql/observation").header("X-Gateway-Lineage-Token","wrong").contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/internal/sql/observation").header("X-Gateway-Lineage-Token","wrong").contentType("application/json").content(new byte[17000])).andExpect(status().isPayloadTooLarge());
 }
}
