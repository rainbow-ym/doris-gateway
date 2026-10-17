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
        executions=new ManagedSqlExecutionService(store,new SqlLineageAnalyzer(store),null,policies,TOKEN);
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
}
