package com.example.doris.gateway.admin;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticPolicyDeltaTest {
    private final PolicyCatalog catalog=new PolicyCatalog();
    private static Map<String,Object> row(String... values){Map<String,Object> r=new LinkedHashMap<>();for(int i=0;i<values.length;i+=2)r.put(values[i],values[i+1]);return r;}
    private Map<String,Object> baseline() {
        Map<String,Object> s=new LinkedHashMap<>();s.put("columns",new ArrayList<>());s.put("globalRules",new ArrayList<>());s.put("schema",new ArrayList<>());
        s=AutomaticPolicyDelta.apply(s,"CREATE_TABLE","source","",Arrays.asList(row("columnName","secret","recommendedPolicy","ID_CARD"),row("columnName","plain","recommendedPolicy","NONE")),Collections.emptyList(),catalog,"db");
        return AutomaticPolicyDelta.apply(s,"CREATE_TABLE","unrelated","",Collections.singletonList(row("columnName","name","recommendedPolicy","PERSON_NAME")),Collections.emptyList(),catalog,"db");
    }
    private Map<String,Object> edge(String policy){return row("sourceTable","source","sourceColumn","secret","targetTable","copied","targetColumn","alias","inheritedPolicy",policy);}
    @Test void ctasInheritsActiveLabelAndPreservesUnrelatedRules() {
        Map<String,Object> old=baseline();String before=old.toString();
        Map<String,Object> next=AutomaticPolicyDelta.apply(old,"CTAS","copied","",Collections.singletonList(row("columnName","alias","recommendedPolicy","NONE","assignedPolicy","PERSON_NAME")),Collections.singletonList(edge("ID_CARD")),catalog,"db");
        assertEquals("ID_CARD",AutomaticPolicyDelta.label(next,"copied","alias"));
        assertEquals("PERSON_NAME",AutomaticPolicyDelta.label(next,"unrelated","name"));assertEquals(before,old.toString());
    }
    @Test void sameTableDraftAndRecommendationsCannotModifyExistingFields() {
        Map<String,Object> next=AutomaticPolicyDelta.apply(baseline(),"ALTER","source","",Arrays.asList(
                row("columnName","secret","recommendedPolicy","PERSON_NAME","assignedPolicy","NONE"),
                row("columnName","plain","recommendedPolicy","ID_CARD","assignedPolicy","ID_CARD"),
                row("columnName","phone","recommendedPolicy","MOBILE_PHONE")),Collections.emptyList(),catalog,"db");
        assertEquals("ID_CARD",AutomaticPolicyDelta.label(next,"source","secret"));
        assertEquals("",AutomaticPolicyDelta.label(next,"source","plain"));
        assertEquals("MOBILE_PHONE",AutomaticPolicyDelta.label(next,"source","phone"));
    }
    @Test void dropRemovesOnlyAffectedTable() {
        Map<String,Object> next=AutomaticPolicyDelta.apply(baseline(),"DROP","source","",Collections.emptyList(),Collections.emptyList(),catalog,"db");
        assertEquals("",AutomaticPolicyDelta.label(next,"source","secret"));assertEquals("PERSON_NAME",AutomaticPolicyDelta.label(next,"unrelated","name"));
    }
    @Test void renamePreservesProtectionAndDoesNotReadDraft() {
        Map<String,Object> next=AutomaticPolicyDelta.apply(baseline(),"RENAME","source","renamed",Collections.emptyList(),Collections.emptyList(),catalog,"db");
        assertEquals("",AutomaticPolicyDelta.label(next,"source","secret"));assertEquals("ID_CARD",AutomaticPolicyDelta.label(next,"renamed","secret"));
    }
    @Test void changedSourcePolicyCannotPublishStaleEdges() {
        assertThrows(IllegalStateException.class,()->AutomaticPolicyDelta.apply(baseline(),"CTAS","copied","",Collections.singletonList(row("columnName","alias")),Collections.singletonList(edge("PERSON_NAME")),catalog,"db"));
    }
    @Test void emptyPhysicalSchemaAndLegacySnapshotMustFailClosed() {
        assertThrows(IllegalStateException.class,()->AutomaticPolicyDelta.apply(baseline(),"ALTER","source","",Collections.emptyList(),Collections.emptyList(),catalog,"db"));
        Map<String,Object> old=baseline();old.remove("schema");
        assertThrows(IllegalStateException.class,()->AutomaticPolicyDelta.apply(old,"DROP","source","",Collections.emptyList(),Collections.emptyList(),catalog,"db"));
    }
    @Test void droppedColumnCannotRetainOldRule() {
        Map<String,Object> next=AutomaticPolicyDelta.apply(baseline(),"ALTER","source","",Collections.singletonList(row("columnName","plain","recommendedPolicy","NONE")),Collections.emptyList(),catalog,"db");
        assertEquals("",AutomaticPolicyDelta.label(next,"source","secret"));
    }
    @Test void inheritedConflictsStillRejectInsteadOfOverwriting() {
        assertThrows(IllegalStateException.class,()->AutomaticPolicyDelta.apply(baseline(),"CTAS","copied","",Collections.singletonList(row("columnName","alias","recommendedPolicy","PERSON_NAME")),Collections.singletonList(edge("ID_CARD")),catalog,"db"));
    }
    @Test void sensitiveColumnRenameMustNotBecomeUnprotectedNewColumn() {
        assertThrows(IllegalStateException.class,()->AutomaticPolicyDelta.apply(baseline(),"ALTER","source","",Arrays.asList(row("columnName","innocent","recommendedPolicy","NONE"),row("columnName","plain","recommendedPolicy","NONE")),Collections.emptyList(),catalog,"db"));
    }
}
