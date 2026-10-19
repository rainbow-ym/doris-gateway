package com.example.doris.gateway.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.aop.framework.ProxyFactory;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PublishedPolicyTest {
    private JdbcTemplate jdbc;
    private AdminStore store;
    private PublishedPolicyStore published;
    private FakeProxy proxy;
    private final ObjectMapper mapper=new ObjectMapper();
    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource(SystemDataSourceConfig.safeUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_UPPER=FALSE;DB_CLOSE_DELAY=-1"),"sa","");
        jdbc=new JdbcTemplate(ds);
        store=new AdminStore(jdbc,mapper,null);store.initialize();
        ProxyFactory factory=new ProxyFactory(new PublishedPolicyStore(jdbc,mapper));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        published=(PublishedPolicyStore)factory.getProxy();published.initialize();
        store.saveLogicalDatabase(1,"db","physical","unit","ACTIVE","");
        store.upsertColumn(1,"physical","people","secret","varchar","","ID_CARD");store.confirmRecommendations(1);
        proxy=new FakeProxy();proxy.published=published;
    }
    private List<DistSqlService.RuleRow> rules(String id) {
        PolicyCatalog.Policy p=new PolicyCatalog().get(id);
        return Collections.singletonList(new DistSqlService.RuleRow("people","secret",p.algorithmType,p.props));
    }
    private long publish(String id) throws Exception {
        store.inheritPolicy(1,"physical","people","secret",id);
        proxy.next.add(rules(id));
        return ((Number)proxy.publish("db").get("version")).longValue();
    }
    @Test void missingSnapshotCannotUseDraft() {
        assertThrows(IllegalStateException.class,()->published.active("db"));
    }
    @Test void upgradeBootstrapCannotPublishDraftThatDiffersFromRuntime() throws Exception {
        proxy.runtime=rules("PERSON_NAME");
        assertThrows(IllegalStateException.class,()->proxy.bootstrapPolicies("db",true));assertFalse(published.hasActive("db"));
        proxy.runtime=rules("ID_CARD");assertEquals("READY_TO_BOOTSTRAP",proxy.bootstrapPolicies("db",false).get("status"));assertFalse(published.hasActive("db"));
        proxy.bootstrapPolicies("db",true);assertTrue(published.hasActive("db"));assertEquals("ALREADY_VERIFIED",proxy.bootstrapPolicies("db",true).get("status"));
    }
    @Test void draftEditCannotChangeActiveSnapshot() throws Exception {
        long version=publish("ID_CARD");String original=mapper.writeValueAsString(published.active("db"));
        store.inheritPolicy(1,"physical","people","secret","PERSON_NAME");
        assertEquals(original,mapper.writeValueAsString(published.active("db")));
        assertEquals(version,((Number)published.active("db").get("version")).longValue());
        assertEquals("PERSON_NAME",store.policyForColumn(1,"physical","people","secret"));
    }
    @Test void upgradeMustNotReplaceIncompleteActiveSnapshotWithDraft() throws Exception {
        publish("ID_CARD");
        Map<String,Object> active=published.active("db");active.remove("schema");
        jdbc.update("UPDATE active_policy_snapshots SET snapshot_json=? WHERE database_name='db'",mapper.writeValueAsString(active));
        store.inheritPolicy(1,"physical","people","secret","PERSON_NAME");proxy.runtime=rules("PERSON_NAME");
        assertThrows(IllegalStateException.class,()->proxy.bootstrapPolicies("db",true));
        assertEquals("ID_CARD",proxy.effectivePolicy(1,"physical","people","secret"));
    }
    @Test void automaticAlterMustNotPublishSameTableDraft() throws Exception {
        publish("ID_CARD");store.inheritPolicy(1,"physical","people","secret","PERSON_NAME");
        proxy.next.add(rules("ID_CARD"));proxy.publishManaged("db","ALTER","people","",Collections.emptyList());
        assertEquals("ID_CARD",proxy.effectivePolicy(1,"physical","people","secret"));
        assertEquals("PERSON_NAME",store.policyForColumn(1,"physical","people","secret"));
    }
    @Test void automaticInheritanceMustPreserveEditedDraft() throws Exception {
        publish("ID_CARD");store.inheritPolicy(1,"physical","people","secret","PERSON_NAME");
        assertTrue(store.inheritPolicyIfUnedited(1,"physical","people","secret","ID_CARD","ID_CARD"));
        assertEquals("PERSON_NAME",store.policyForColumn(1,"physical","people","secret"));
        assertFalse(store.inheritPolicyIfUnedited(1,"physical","missing","secret","ID_CARD",""));
    }
    @Test void rollbackCannotRemoveProtectionAcrossSchemaChanges() throws Exception {
        publish("ID_CARD");long second=publish("PERSON_NAME");
        store.upsertColumn(1,"physical","people","new_field","varchar","","NONE");
        proxy.next.add(rules("PERSON_NAME"));proxy.publishManaged("db","ALTER","people","",Collections.emptyList());
        long current=((Number)published.active("db").get("version")).longValue();
        assertThrows(IllegalStateException.class,()->proxy.rollback(second));
        assertEquals(current,((Number)published.active("db").get("version")).longValue());
    }
    @Test void publicationAndRollbackSwitchCompleteSnapshotsWithoutDestroyingDraft() throws Exception {
        publish("ID_CARD");long second=publish("PERSON_NAME");
        proxy.next.add(rules("ID_CARD"));proxy.rollback(second);
        assertTrue(mapper.writeValueAsString(published.active("db").get("columns")).contains("ID_CARD"));
        assertEquals("PERSON_NAME",store.policyForColumn(1,"physical","people","secret"));
        assertEquals("ROLLED_BACK",store.versions().get(0).get("status"));
    }
    @Test void failedPublishRestoresProxyAndKeepsOldSnapshot() throws Exception {
        long original=publish("ID_CARD");proxy.failNext=true;
        proxy.next.add(rules("PERSON_NAME"));proxy.next.add(rules("ID_CARD"));
        store.inheritPolicy(1,"physical","people","secret","PERSON_NAME");
        assertThrows(IllegalStateException.class,()->proxy.publish("db"));
        assertEquals(original,((Number)published.active("db").get("version")).longValue());
        assertEquals("FAILED",store.versions().get(0).get("status"));published.requireNoPending("db");
    }
    @Test void failedRecoveryBlocksFurtherPublishButDoesNotEraseActiveSnapshot() throws Exception {
        long original=publish("ID_CARD");proxy.failNext=true;proxy.next.add(rules("PERSON_NAME"));proxy.next.add(rules("PERSON_NAME"));
        store.inheritPolicy(1,"physical","people","secret","PERSON_NAME");
        assertThrows(IllegalStateException.class,()->proxy.publish("db"));
        assertEquals("RECOVERY_REQUIRED",store.versions().get(0).get("status"));
        assertThrows(IllegalStateException.class,()->proxy.publish("db"));
        assertEquals(original,((Number)published.active("db").get("version")).longValue());
    }
    @Test void unfinishedJournalSurvivesRepositoryRestart() throws Exception {
        publish("ID_CARD");
        published.begin(999,"db",published.active("db"));
        PublishedPolicyStore restarted=new PublishedPolicyStore(jdbc,mapper);restarted.initialize();
        assertThrows(IllegalStateException.class,()->restarted.requireNoPending("db"));
        assertNotNull(restarted.active("db"));
    }
    @Test void rollbackFailureRetainsCurrentActiveVersion() throws Exception {
        publish("ID_CARD");long current=publish("PERSON_NAME");
        proxy.failNext=true;proxy.next.add(rules("ID_CARD"));proxy.next.add(rules("PERSON_NAME"));
        assertThrows(IllegalStateException.class,()->proxy.rollback(current));
        assertEquals(current,((Number)published.active("db").get("version")).longValue());
        assertEquals("FAILED",store.versions().get(0).get("status"));
    }
    @Test void caseVariantCannotCreateSeparatePublicationDomain() throws Exception {
        proxy.next.add(rules("ID_CARD"));proxy.publish("DB");
        assertNotNull(published.active("db"));
        assertThrows(IllegalStateException.class,()->published.active("DB"));
    }
    @Test void concurrentAuditIdsBelongToTheirOwnInsert() throws Exception {
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            List<java.util.concurrent.Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<40;i++){final String db="db-"+i;tasks.add(pool.submit(()->{
                long id=store.saveVersion(db,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),"PUBLISHED","");
                assertEquals(db,store.version(id).get("database_name"));
            }));}
            for(java.util.concurrent.Future<?> task:tasks)task.get();
        } finally {pool.shutdownNow();}
    }
    @Test void firstVersionAndLegacyVersionsCannotRollbackToUnverifiedRules() throws Exception {
        long first=publish("ID_CARD");assertThrows(IllegalArgumentException.class,()->proxy.rollback(first));
        long legacy=store.saveVersion("db",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),"PUBLISHED","");
        assertThrows(IllegalArgumentException.class,()->proxy.rollback(legacy));
    }
    private long pending() {
        Map<String,Object> active=published.active("db");
        active.put("globalRules",rules("PERSON_NAME"));
        Map<String,Object> candidateColumn=new HashMap<>();candidateColumn.put("tableName","people");candidateColumn.put("columnName","secret");candidateColumn.put("assignedPolicy","PERSON_NAME");active.put("columns",Collections.singletonList(candidateColumn));
        long id=store.saveVersion("db",proxy.runtime,proxy.runtime,Collections.emptyList(),"APPLYING","");
        published.begin(id,"db",active);return id;
    }
    @Test void recoveryRestoresActiveRulesWithoutActivatingCandidateOrEditingDraft() throws Exception {
        long active=publish("ID_CARD");long id=pending();
        store.inheritPolicy(1,"physical","people","secret","PERSON_NAME");proxy.runtime=rules("PERSON_NAME");
        assertEquals(false,proxy.recoveryPreview(id).get("runtimeMatches"));proxy.next.add(rules("ID_CARD"));
        proxy.recoverPublication(id,active,"operator");
        assertEquals("RECOVERED_ABORTED",published.publication(id).get("status"));published.requireNoPending("db");
        assertEquals(active,((Number)published.active("db").get("version")).longValue());
        assertEquals("PERSON_NAME",store.policyForColumn(1,"physical","people","secret"));
        assertTrue(store.audits().stream().anyMatch(r->"POLICY_RECOVERY_COMPLETED".equals(r.get("action"))));
        proxy.failNext=true;proxy.recoverPublication(id,active,"operator");assertTrue(proxy.failNext);
    }
    @Test void recoveryRejectsStaleConfirmationAndSuccessfulVersions() throws Exception {
        long active=publish("ID_CARD");long id=pending();
        assertThrows(IllegalStateException.class,()->proxy.recoverPublication(id,active+1,"operator"));
        assertEquals("APPLYING",published.publication(id).get("status"));
        assertThrows(IllegalStateException.class,()->proxy.recoverPublication(active,active,"operator"));
    }
    @Test void failedRecoveryStaysBlockedAndCanBeRetried() throws Exception {
        long active=publish("ID_CARD");long id=pending();proxy.runtime=rules("PERSON_NAME");proxy.failNext=true;
        assertThrows(IllegalStateException.class,()->proxy.recoverPublication(id,active,"operator"));
        assertEquals("RECOVERY_REQUIRED",published.publication(id).get("status"));
        assertThrows(IllegalStateException.class,()->published.requireNoPending("db"));
        proxy.next.add(rules("ID_CARD"));proxy.recoverPublication(id,active,"operator");published.requireNoPending("db");
    }
    @Test void recoveryOfFirstPublicationDoesNotCreateAnActiveSnapshot() throws Exception {
        long id=store.saveVersion("db",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),"APPLYING","");
        Map<String,Object> candidate=new HashMap<>();candidate.put("globalRules",Collections.emptyList());published.begin(id,"db",candidate);
        proxy.recoverPublication(id,0,"operator");assertFalse(published.hasActive("db"));published.requireNoPending("db");
    }
    @Test void interruptedRecoveryCanBeRepeatedAfterRepositoryRestart() throws Exception {
        long active=publish("ID_CARD");long id=pending();published.beginRecovery(id,"operator");
        PublishedPolicyStore restarted=new PublishedPolicyStore(jdbc,mapper);restarted.initialize();
        assertThrows(IllegalStateException.class,()->restarted.requireNoPending("db"));
        proxy.recoverPublication(id,active,"operator");assertEquals("RECOVERED_ABORTED",restarted.publication(id).get("status"));
    }
    @Test void recoveryCompletionMustRollbackIfAuditCannotBeSaved() throws Exception {
        long active=publish("ID_CARD");long id=pending();published.beginRecovery(id,"operator");
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT reject_recovery_audit CHECK(action<>'POLICY_RECOVERY_COMPLETED')");
        assertThrows(RuntimeException.class,()->published.finishRecovery(id,active,"operator"));
        assertEquals("RECOVERING",published.publication(id).get("status"));
    }
    @Test void failedActivationTransactionMustNotMovePointer() throws Exception {
        long first=publish("ID_CARD");published.begin(999,"db",published.active("db"));
        jdbc.execute("ALTER TABLE publish_versions ADD CONSTRAINT reject_rollback CHECK(status<>'ROLLED_BACK')");
        // Existing version has a matching journal to make the final update fail after MERGE.
        long next=store.saveVersion("db",Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),"APPLYING","");
        jdbc.update("UPDATE policy_publications SET version_id=? WHERE version_id=999",next);
        assertThrows(RuntimeException.class,()->published.activate(next,"ROLLED_BACK"));
        assertEquals(first,((Number)published.active("db").get("version")).longValue());
    }
    @Test void renameActivationMustNotMovePointerWithoutExecutionMarker() throws Exception {
        long first=publish("ID_CARD");long next=pending();
        assertThrows(IllegalStateException.class,()->published.activateRename(next,"PUBLISHED","missing-task"));
        assertEquals(first,((Number)published.active("db").get("version")).longValue());
        assertEquals("APPLYING",published.publication(next).get("status"));
    }

    @Test void renameRetryUsesRecordedSnapshotAndRejectsLaterPolicyVersion() throws Exception {
        publish("ID_CARD");
        store.upsertManagedObject(1,"physical","people","TABLE","ACTIVE",null,"");
        store.reserveManagedExecution("rename-test",1,"unit","db","physical","RENAME","people","renamed","ALTER TABLE people RENAME renamed","TABLE");
        store.acknowledgeExecution("rename-test");store.applyRenameRegistry("rename-test");
        PolicyCatalog.Policy p=new PolicyCatalog().get("ID_CARD");
        List<DistSqlService.RuleRow> renamed=Collections.singletonList(new DistSqlService.RuleRow("renamed","secret",p.algorithmType,p.props));
        proxy.next.add(renamed);proxy.publishRename("db","rename-test","people","renamed");
        long recorded=((Number)store.managedExecution("rename-test").get("rename_policy_version")).longValue();
        assertEquals(recorded,((Number)published.active("db").get("version")).longValue());
        int versions=store.versions().size();
        proxy.publishRename("db","rename-test","people","renamed");
        assertEquals(versions,store.versions().size());
        proxy.next.add(renamed);proxy.publishManaged("db","ALTER","renamed","",Collections.emptyList());
        assertThrows(IllegalStateException.class,()->proxy.publishRename("db","rename-test","people","renamed"));
    }

    private class FakeProxy extends DistSqlService {
        List<RuleRow> runtime=new ArrayList<>();Deque<List<RuleRow>> next=new ArrayDeque<>();boolean failNext;
        FakeProxy(){super(store,new PolicyCatalog(),mapper,"unused",1,"control","");}
        @Override List<RuleRow> readRules(String db){return new ArrayList<>(runtime);}
        @Override void execute(String db,List<String> sql) {
            if(!next.isEmpty())runtime=next.remove();
            if(failNext){failNext=false;throw new IllegalStateException("模拟规则应用后失败");}
        }
    }
}
