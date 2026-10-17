package com.example.doris.gateway.security;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OutputProtectionTest {
    @Test void publishedSnapshotDoesNotNeedMutableProxyRule() {
        Map<String,Object> snapshot=new HashMap<>();snapshot.put("version",7);
        Map<String,Object> rule=new HashMap<>();rule.put("table","people");rule.put("column","secret");
        rule.put("algorithmType","UNICODE_KEEP_FIRST_N_LAST_M");
        Map<String,String> props=new HashMap<>();props.put("first-n","1");props.put("last-m","1");props.put("replace-char","*");rule.put("props",props);
        snapshot.put("globalRules",Collections.singletonList(rule));
        UserOutputPlan.Decision decision=UserOutputPlan.publishedDecision(snapshot,"people","secret",true);
        assertEquals("MASK",decision.action);assertNotNull(decision.algorithm);
        assertEquals("CLEAR",UserOutputPlan.publishedDecision(snapshot,"people","plain",false).action);
        assertThrows(IllegalStateException.class,()->UserOutputPlan.publishedDecision(snapshot,"people","missing",true));
    }
    @Test void legacyResponseMustNotSilentlyFallBackToMutableRules() {
        assertThrows(IllegalStateException.class,()->UserOutputPlan.publishedDecision(Collections.emptyMap(),"people","secret",false));
    }
    @Test void missingPublishedProtectionMustFailClosed() {
        assertThrows(IllegalStateException.class,()->UserOutputPlan.requirePublishedProtection(Optional.empty(),true));
        assertFalse(UserOutputPlan.requirePublishedProtection(Optional.empty(),false).isPresent());
    }
    @Test void userEncryptionMustHaveKeyReferenceAndColumnSpecificAad() {
        Properties p=UserOutputPlan.algorithmProperties("OUTPUT_AES_GCM","db","people","id",Collections.emptyMap());
        assertEquals("GATEWAY_OUTPUT_AES_KEY_B64",p.getProperty("key-env"));
        assertEquals("output-key-v1",p.getProperty("key-id"));
        assertEquals("db.people.id",p.getProperty("aad"));
        assertNotEquals(p.getProperty("aad"),UserOutputPlan.algorithmProperties("OUTPUT_AES_GCM","db","people","name",Collections.emptyMap()).getProperty("aad"));
    }
}
