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

class ProtectionBoundaryTest {
    private AdminStore store;
    private JdbcTemplate jdbc;
    private ManagedSqlExecutionService executions;
    private static final String TOKEN="unit-test-internal-token-123456";

    @BeforeEach void setup() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=FALSE","sa","");
        jdbc=new JdbcTemplate(ds);
        AdminStore target=new AdminStore(jdbc,new ObjectMapper(),null);
        ProxyFactory factory=new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        store=(AdminStore)factory.getProxy();store.initialize();
        new AccountAccessStore(jdbc).initialize();
        store.saveLogicalDatabase(1,"db","db","unit","ACTIVE","");
        store.upsertColumn(1,"db","src","secret","varchar","","ID_CARD");
        store.confirmRecommendations(1);
        store.upsertManagedObject(1,"db","src","TABLE","ACTIVE",null,"");
        store.upsertManagedObject(1,"db","v","VIEW","ACTIVE",null,"");
        DistSqlService policies=new DistSqlService(store,new PolicyCatalog(),new ObjectMapper(),"unused",1,"control","") {
            @Override public void requireAutomaticBaseline(String database) { }
            @Override public String effectivePolicy(long id,String database,String table,String column) {
                return store.policyForColumn(id,database,table,column);
            }
        };
        executions=new ManagedSqlExecutionService(store,new SqlLineageAnalyzer(store),null,policies,TOKEN,org.mockito.Mockito.mock(ObjectIdentityService.class));
    }

    private Map<String,Object> prepare(String sql) {
        Map<String,Object> r=new HashMap<>();r.put("storageUnit","unit");r.put("rawSql",sql);
        return executions.prepare(TOKEN,r);
    }

    @Test void replacementMustNotBecomeNoop() {
        assertThrows(IllegalArgumentException.class,()->prepare("CREATE OR REPLACE VIEW v AS SELECT secret AS innocent FROM src"));
        assertTrue(store.pendingManagedExecutions().isEmpty());
    }

    @Test void ordinaryViewCreationMustStillResolveSource() {
        assertEquals("CREATE_VIEW",prepare("CREATE VIEW fresh_view AS SELECT secret FROM src").get("statementType"));
    }

    @Test void incompleteEventMustRollbackAndAllowRetry() {
        Map<String,Object> event=new HashMap<>();event.put("eventId","edge-failure");
        Map<String,Object> edge=new HashMap<>();edge.put("sourceColumn",String.join("",Collections.nCopies(300,"x")));
        event.put("edges",Collections.singletonList(edge));
        assertThrows(RuntimeException.class,()->store.saveLineageEvent(event));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM lineage_events",Integer.class));
        edge.put("sourceColumn","secret");
        assertTrue(store.saveLineageEvent(event));
        assertEquals(1,store.lineageEdges("edge-failure").size());
        assertFalse(store.saveLineageEvent(event));
        assertEquals(1,store.lineageEdges("edge-failure").size());
    }

    @Test void metadataLiteralMustNotMakeUnknownTableReadable() {
        assertThrows(ManagedSqlExecutionService.ManagedObjectAccessException.class,
                ()->prepare("SELECT 'information_schema.' FROM unknown_table"));
    }

    @Test void constantProjectionMustStillCheckItsSourceTable() {
        assertThrows(ManagedSqlExecutionService.ManagedObjectAccessException.class,
                ()->prepare("CREATE TABLE copied AS SELECT 1 AS harmless FROM unknown_table"));
        assertTrue(store.managedObject(1,"db","copied").isEmpty());
    }

    @Test void unsupportedScalarMutationMustNotReserveTarget() {
        assertThrows(IllegalArgumentException.class,()->prepare("CREATE TABLE copied AS SELECT (SELECT secret FROM src LIMIT 1) AS innocent"));
        assertTrue(store.managedObject(1,"db","copied").isEmpty());
    }

    @Test void inheritedPolicyMustNotOverwriteExistingTargetProtection() {
        store.upsertColumn(1,"db","target","value","varchar","","PERSON_NAME");
        store.confirmRecommendations(1);
        store.upsertManagedObject(1,"db","target","TABLE","ACTIVE",null,"");
        assertThrows(IllegalStateException.class,()->prepare("INSERT INTO target (value) SELECT secret FROM src"));
        assertEquals("PERSON_NAME",store.policyForColumn(1,"db","target","value"));
        assertTrue(store.isActiveManagedObject(1,"db","target"));
        assertTrue(store.pendingManagedExecutions().isEmpty());
    }

    @Test void lineageColumnsMustUsePhysicalOrderInsteadOfHistoricalIds() {
        store.upsertColumn(1,"db","src","added","varchar","","NONE");
        store.replaceManagedColumns(1,"db","src",Arrays.asList(new AdminStore.ManagedColumn("added","varchar"),new AdminStore.ManagedColumn("secret","varchar")),"ACTIVE");
        assertEquals(Arrays.asList("added","secret"),store.tableColumns(1,"db","src"));
    }

    @Test void missingSuccessCallbackMustNotBeReconciledAsSuccess() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        assertThrows(IllegalStateException.class,()->executions.reconcile(id));
        assertEquals("UNKNOWN",store.managedExecution(id).get("status"));
        assertFalse(store.isActiveManagedObject(1,"db","copied"));
        assertTrue(store.pendingManagedExecutions().isEmpty());
    }

    @Test void uncertainFailureMustNotEnterAutomaticSuccessRecovery() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        executions.abort(TOKEN,id,"socket timeout",true);
        assertEquals("UNKNOWN",store.managedExecution(id).get("status"));
        assertThrows(IllegalStateException.class,()->executions.reconcile(id));
        assertTrue(store.pendingManagedExecutions().isEmpty());
    }

    @Test void schemaSnapshotMustRemoveDroppedFieldsAndTheirExactExceptions() {
        AccountAccessStore access=new AccountAccessStore(jdbc);
        access.savePolicy("u","db","src","secret","*","CLEAR","");
        access.savePolicy("u","db","*","*","ID_CARD","DENY","");
        store.replaceTableMetadata(1,"db","src",Collections.singletonList(new AdminStore.ScannedColumn("newcol","varchar","","NONE")));
        assertEquals(Collections.singletonList("newcol"),store.tableColumns(1,"db","src"));
        assertEquals("",store.policyForColumn(1,"db","src","secret"));
        assertEquals(1,access.policies("u","db").size());
        assertEquals("*",access.policies("u","db").get(0).get("columnName"));
    }

    @Test void invalidSchemaSnapshotMustRollbackItsEarlierColumns() {
        List<AdminStore.ScannedColumn> columns=Arrays.asList(
                new AdminStore.ScannedColumn("newcol","varchar","","NONE"),
                new AdminStore.ScannedColumn(String.join("",Collections.nCopies(300,"x")),"varchar","","NONE"));
        assertThrows(RuntimeException.class,()->store.replaceTableMetadata(1,"db","src",columns));
        assertEquals(Collections.singletonList("secret"),store.tableColumns(1,"db","src"));
        assertEquals("ID_CARD",store.policyForColumn(1,"db","src","secret"));
    }

    @Test void reservationAndTaskMustRollbackTogether() {
        String id=String.valueOf(prepare("CREATE TABLE first_table AS SELECT secret FROM src").get("executionId"));
        assertThrows(RuntimeException.class,()->store.reserveManagedExecution(id,1,"unit","db","db","CTAS","second_table","","SELECT 1","TABLE"));
        assertTrue(store.managedObject(1,"db","second_table").isEmpty());
        assertEquals(id,store.managedObject(1,"db","first_table").get("pending_execution_id"));
    }

    @Test void unresolvedExecutionCannotBeStolenByAnotherCreateOrInitialization() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        executions.abort(TOKEN,id,"lost connection",true);
        assertThrows(IllegalStateException.class,()->prepare("CREATE TABLE copied AS SELECT secret FROM src"));
        assertThrows(IllegalStateException.class,()->store.upsertManagedObject(1,"db","copied","TABLE","ACTIVE",null,"initialize"));
        assertEquals(id,store.managedObject(1,"db","copied").get("pending_execution_id"));
    }

    @Test void oldTaskCannotAffectNewGenerationEvenWithSameNameAndColumns() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        String old=String.valueOf(store.managedObject(1,"db","copied").get("generation_id"));
        executions.abort(TOKEN,id,"definitively rejected",false);
        String newer=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        Map<String,Object> object=new LinkedHashMap<>(store.managedObject(1,"db","copied"));
        assertNotEquals(old,object.get("generation_id"));
        // Simulate a legacy retry record surviving import/restart; never replay business SQL.
        jdbc.update("UPDATE managed_sql_executions SET status='FAILED',success_received=TRUE WHERE execution_id=?",id);
        assertThrows(IllegalStateException.class,()->executions.reconcile(id));
        assertEquals("STALE_EXECUTION",store.managedExecution(id).get("status"));
        assertEquals(object,store.managedObject(1,"db","copied"));
        assertEquals(newer,store.managedObject(1,"db","copied").get("pending_execution_id"));
    }

    @Test void oldFailedTaskWithoutReceiptCannotBeReplayedAsSuccess() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        store.updateManagedExecution(id,"FAILED","legacy error");
        assertThrows(IllegalStateException.class,()->executions.reconcile(id));
        assertEquals("UNKNOWN",store.managedExecution(id).get("status"));
    }

    @Test void successReceiptSurvivesFailedSynchronizationAndRestart() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        // No metadata transport in fixture: fail strictly after receipt persistence.
        assertThrows(IllegalStateException.class,()->executions.commit(TOKEN,id,Collections.emptyMap()));
        AdminStore reopened=new AdminStore(jdbc,new ObjectMapper(),null);reopened.initialize();
        assertEquals(Boolean.TRUE,reopened.managedExecution(id).get("success_received"));
        assertEquals("FAILED",reopened.managedExecution(id).get("status"));
        assertEquals(1,((Number)reopened.managedExecution(id).get("recovery_attempts")).intValue());
        assertThrows(IllegalStateException.class,()->executions.reconcile(id));
        assertEquals(2,((Number)store.managedExecution(id).get("recovery_attempts")).intValue());
    }

    @Test void retryBudgetKeepsObjectBlocked() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        jdbc.update("UPDATE managed_sql_executions SET status='FAILED',success_received=TRUE,recovery_attempts=10 WHERE execution_id=?",id);
        assertThrows(IllegalStateException.class,()->executions.reconcile(id));
        assertEquals("MANUAL_REQUIRED",store.managedExecution(id).get("status"));
        assertFalse(store.isActiveManagedObject(1,"db","copied"));assertTrue(store.pendingManagedExecutions().isEmpty());
    }

    @Test void finalProjectionFailureRollsBackActivationAndCompletion() {
        String id=String.valueOf(prepare("CREATE TABLE copied AS SELECT secret FROM src").get("executionId"));
        store.acknowledgeExecution(id);
        assertThrows(IllegalStateException.class,()->store.completeOwnedExecution(id,"done",()->{throw new IllegalStateException("projection failure");}));
        assertEquals("SYNCING",store.managedExecution(id).get("status"));
        assertEquals(id,store.managedObject(1,"db","copied").get("pending_execution_id"));
        assertFalse(store.isActiveManagedObject(1,"db","copied"));
    }
    @Test void renamedRegistryCanResumeAfterRestartWithoutMovingAgain() {
        String id=String.valueOf(prepare("ALTER TABLE src RENAME renamed").get("executionId"));
        Object generation=store.managedObject(1,"db","src").get("generation_id");
        store.acknowledgeExecution(id);store.applyRenameRegistry(id);
        store.finishOwnedExecution(id,"FAILED","injected publication failure");
        store.initialize();store.acknowledgeExecution(id);store.applyRenameRegistry(id);
        assertTrue(store.managedObject(1,"db","src").isEmpty());
        assertEquals(generation,store.managedObject(1,"db","renamed").get("generation_id"));
        assertEquals("ID_CARD",store.policyForColumn(1,"db","renamed","secret"));
        store.completeOwnedExecution(id,"done",()->{});
        assertTrue(store.isActiveManagedObject(1,"db","renamed"));
    }

    @Test void renameRegistryAndProgressMarkerRollbackTogether() {
        String id=String.valueOf(prepare("ALTER TABLE src RENAME renamed").get("executionId"));
        store.acknowledgeExecution(id);
        jdbc.execute("ALTER TABLE managed_sql_executions ADD CONSTRAINT reject_rename_stage CHECK(rename_registry_applied=FALSE)");
        assertThrows(RuntimeException.class,()->store.applyRenameRegistry(id));
        assertEquals(Collections.singletonList("secret"),store.tableColumns(1,"db","src"));
        assertEquals(id,store.managedObject(1,"db","src").get("pending_execution_id"));
        assertEquals(Boolean.FALSE,store.managedExecution(id).get("rename_registry_applied"));
    }

    @Test void reusedOldNamePreventsRenameRecoveryFromTouchingNewObject() {
        String id=String.valueOf(prepare("ALTER TABLE src RENAME renamed").get("executionId"));
        store.acknowledgeExecution(id);store.applyRenameRegistry(id);
        store.upsertManagedObject(1,"db","src","TABLE","ACTIVE",null,"new object");
        Map<String,Object> replacement=new LinkedHashMap<>(store.managedObject(1,"db","src"));
        assertThrows(IllegalStateException.class,()->store.requireExecutionOwner(id));
        assertEquals("STALE_EXECUTION",store.managedExecution(id).get("status"));
        assertEquals(replacement,store.managedObject(1,"db","src"));
        assertFalse(store.isActiveManagedObject(1,"db","renamed"));
    }

    @Test void auditRedeliveryIsIdempotentAndConflictRejected() {
        store.recordExecutionAudit("request-1","EXECUTED","same");
        store.recordExecutionAudit("request-1","EXECUTED","same");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action='USER_SQL_EXECUTED'",Integer.class));
        assertThrows(IllegalArgumentException.class,()->store.recordExecutionAudit("request-1","EXECUTED","changed"));
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT test_no_unknown CHECK (action <> 'USER_SQL_UNKNOWN')");
        assertThrows(RuntimeException.class,()->store.recordExecutionAudit("request-2","UNKNOWN","detail"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM audit_receipts WHERE request_id='request-2'",Integer.class));
    }
}
