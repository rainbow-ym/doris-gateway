package com.example.doris.gateway.admin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import java.util.Base64;
@WebMvcTest(controllers=Sm4ExportController.class,properties={"ADMIN_USERNAME=admin","ADMIN_PASSWORD=local-test-only-12345"})
@Import(SecurityConfig.class)
class Sm4ExportSecurityTest {
 @Autowired MockMvc mvc;
 @MockBean AccountAccessService access;
 @MockBean Sm4ExportService service;
 @Test void settingsAndDecryptionRequireLogin()throws Exception{
  mvc.perform(get("/api/sm4-export/defaults")).andExpect(status().isUnauthorized());
  for(String path:new String[]{"/api/sm4-export/profiles/batch","/api/sm4-export/defaults","/api/sm4-export/decrypt"})mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
  mvc.perform(delete("/api/sm4-export/profiles").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
  verifyNoInteractions(service);
  String auth="Basic "+Base64.getEncoder().encodeToString("admin:local-test-only-12345".getBytes(java.nio.charset.StandardCharsets.UTF_8));
  mvc.perform(get("/api/sm4-export/defaults").header("Authorization",auth)).andExpect(status().isOk());
 }
 @Test void internalCaptureRequiresControlToken()throws Exception{
  doThrow(new SecurityException("invalid token")).when(access).authenticate("wrong");
  mvc.perform(post("/api/internal/sql/export-capture").header("X-Gateway-Lineage-Token","wrong").contentType("application/json").content("{}")).andExpect(status().isForbidden());verifyNoInteractions(service);
 }
}
