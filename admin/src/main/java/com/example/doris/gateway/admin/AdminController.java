package com.example.doris.gateway.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminController {

    private final AdminStore store;
    private final DorisMetadataService metadata;
    private final PolicyCatalog policies;
    private final DistSqlService distSql;
    private final LineageIngressService lineageIngress;
    private final ManagedSqlExecutionService managedSql;
    private final OnboardingService onboarding;
    @org.springframework.beans.factory.annotation.Autowired
    private AccountAccessStore accountAccess;

    public AdminController(final AdminStore store, final DorisMetadataService metadata, final PolicyCatalog policies,
                           final DistSqlService distSql, final LineageIngressService lineageIngress,
                           final ManagedSqlExecutionService managedSql, final OnboardingService onboarding) {
        this.store = store;
        this.metadata = metadata;
        this.policies = policies;
        this.distSql = distSql;
        this.lineageIngress = lineageIngress;
        this.managedSql = managedSql;
        this.onboarding = onboarding;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String,Object> result=new LinkedHashMap<>();result.put("status","UP");result.put("version","2026.10.15-accounts.1");return result;
    }

    @GetMapping("/connections")
    public List<Map<String, Object>> connections() {
        return store.connections();
    }

    @PostMapping("/connections")
    public Map<String, Object> createConnection(@RequestBody final Map<String, Object> request) throws Exception {
        metadata.testRequest(request);
        return Collections.<String, Object>singletonMap("id", store.saveConnection(request));
    }

    @org.springframework.web.bind.annotation.PutMapping("/connections/{id}")
    public Map<String,Object> editConnection(@PathVariable long id,@RequestBody Map<String,Object> request) {
        onboarding.updateConnection(id,request);return Collections.singletonMap("status","UPDATED");
    }
    @org.springframework.web.bind.annotation.DeleteMapping("/connections/{id}")
    public Map<String,Object> deleteConnection(@PathVariable long id) {
        onboarding.deleteConnection(id);return Collections.singletonMap("status","DELETED");
    }
    @GetMapping("/connections/{id}/databases")
    public List<String> discover(@PathVariable long id) throws Exception { return metadata.databases(id); }
    @PostMapping("/connections/{id}/onboard")
    public Map<String,Object> onboard(@PathVariable long id,@RequestBody OnboardRequest request) throws Exception {
        return Collections.singletonMap("jobs",onboarding.submit(id,request.databases));
    }
    @GetMapping("/onboarding/jobs")
    public List<Map<String,Object>> onboardingJobs() { return store.jobs(); }
    @org.springframework.web.bind.annotation.DeleteMapping("/logical-databases/{database}")
    public Map<String,Object> detach(@PathVariable String database) throws Exception {
        onboarding.detach(database);return Collections.singletonMap("status","DETACHED");
    }

    @PostMapping("/connections/{id}/test")
    public Map<String, Object> testConnection(@PathVariable final long id) throws Exception {
        metadata.test(id);
        return Collections.<String, Object>singletonMap("status", "OK");
    }

    @PostMapping("/connections/{id}/scan")
    public Map<String, Object> scan(@PathVariable final long id) throws Exception {
        java.util.Set<Long> owners=new java.util.LinkedHashSet<>();
        for(Map<String,Object> b:accountAccess.bindings())if(((Number)b.get("connectionId")).longValue()==id) {
            Map<String,Object> m=store.anyMapping(String.valueOf(b.get("logicalName")));
            if(!m.isEmpty())owners.add(((Number)m.get("connection_id")).longValue());
        }
        if(owners.isEmpty())owners.add(id);
        int count=0;for(Long owner:owners)count+=metadata.scan(owner);
        return Collections.<String,Object>singletonMap("columns",count);
    }

    @GetMapping("/columns")
    public List<Map<String, Object>> columns(@RequestParam final long connectionId,
                                              @RequestParam(required = false) final String database,
                                              @RequestParam(required = false) final String q) {
        java.util.Set<Long> seen=new java.util.HashSet<>();
        List<Map<String,Object>> rows=new java.util.ArrayList<>();
        for(Map<String,Object> b:accountAccess.bindings())if(((Number)b.get("connectionId")).longValue()==connectionId) {
            Map<String,Object> m=store.anyMapping(String.valueOf(b.get("logicalName")));
            if(m.isEmpty())continue;
            String physical=String.valueOf(m.get("physical_database"));
            if(database!=null&&!database.isEmpty()&&!physical.equals(database))continue;
            for(Map<String,Object> row:store.columns(((Number)m.get("connection_id")).longValue(),physical,q))
                if(seen.add(((Number)row.get("id")).longValue()))rows.add(row);
        }
        return rows.isEmpty()?store.columns(connectionId,database,q):rows;
    }

    @GetMapping("/policies")
    public List<PolicyCatalog.Policy> policies() {
        return policies.all();
    }

    @PostMapping("/logical-databases/initialize")
    public Map<String, Object> initializeLogicalDatabase(@RequestBody final Map<String, Object> request) throws Exception {
        long id=Long.parseLong(String.valueOf(request.get("connectionId")));
        String physical=String.valueOf(request.get("physicalDatabase"));
        if(!physical.equals(String.valueOf(request.get("logicalDatabase")))) throw new IllegalArgumentException("请使用数据源发现后的同名数据库批量接入。");
        return Collections.singletonMap("jobs",onboarding.submit(id,Collections.singletonList(physical)));
    }

    @GetMapping("/logical-databases")
    public List<Map<String, Object>> logicalDatabases() {
        return store.logicalDatabases();
    }

    @GetMapping("/logical-databases/{database}/storage-units")
    public List<Map<String, Object>> storageUnits(@PathVariable final String database) throws Exception {
        return distSql.storageUnits(database);
    }

    @PostMapping("/assignments/batch")
    public Map<String, Object> assign(@RequestBody final BatchAssignment request) {
        policies.get(request.policyId);
        return Collections.<String, Object>singletonMap("changed", store.assign(request.ids, request.policyId));
    }

    @PostMapping("/connections/{id}/confirm-recommendations")
    public Map<String, Object> confirmRecommendations(@PathVariable final long id) {
        return Collections.<String, Object>singletonMap("changed", store.confirmRecommendations(id));
    }

    @GetMapping("/changes")
    public Map<String, Object> changes(@RequestParam final String database) throws Exception {
        return distSql.preview(database);
    }

    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody final DatabaseRequest request) throws Exception {
        return distSql.publish(request.database);
    }

    @PostMapping("/upgrade/policies/verify")
    public Map<String,Object> verifyUpgrade(@RequestBody DatabaseRequest request) throws Exception {
        return distSql.bootstrapPolicies(request.database,false);
    }
    @PostMapping("/upgrade/policies/bootstrap")
    public Map<String,Object> bootstrapUpgrade(@RequestBody DatabaseRequest request) throws Exception {
        return distSql.bootstrapPolicies(request.database,true);
    }

    @PostMapping("/versions/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable final long id) throws Exception {
        return distSql.rollback(id);
    }

    @GetMapping("/versions")
    public List<Map<String, Object>> versions() {
        return store.versions();
    }

    @GetMapping("/versions/{id}/recovery")
    public Map<String,Object> recoveryPreview(@PathVariable long id) throws Exception {
        return distSql.recoveryPreview(id);
    }

    @PostMapping("/versions/{id}/recovery")
    public Map<String,Object> recoverPublication(@PathVariable long id,@RequestBody RecoveryRequest request,java.security.Principal principal) throws Exception {
        if(request.expectedActiveVersion==null||request.expectedActiveVersion<0)throw new IllegalArgumentException("请先核实恢复目标及生效版本。");
        return distSql.recoverPublication(id,request.expectedActiveVersion,principal.getName());
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return store.audits();
    }

    @PostMapping("/internal/lineage/events")
    public Map<String, Object> acceptLineage(@RequestHeader(value = "X-Gateway-Lineage-Token", required = false) final String token,
                                              @RequestBody final Map<String, Object> event) {
        return Collections.<String, Object>singletonMap("created", lineageIngress.accept(token, event));
    }

    @PostMapping("/internal/sql/prepare")
    public Map<String, Object> prepareSql(@RequestHeader(value = "X-Gateway-Lineage-Token", required = false) final String token,
                                          @RequestBody final Map<String, Object> request) {
        return managedSql.prepare(token, request);
    }

    @PostMapping("/internal/sql/{executionId}/abort")
    public Map<String, Object> abortSql(@RequestHeader(value = "X-Gateway-Lineage-Token", required = false) final String token,
                                        @PathVariable final String executionId,
                                        @RequestBody(required = false) final Map<String, Object> request) {
        managedSql.abort(token, executionId, request == null ? "Doris execution failed." : String.valueOf(request.get("message")),
                request != null && Boolean.parseBoolean(String.valueOf(request.get("uncertain"))));
        return Collections.<String, Object>singletonMap("status", "ABORTED");
    }

    @GetMapping("/managed-objects")
    public List<Map<String, Object>> managedObjects() {
        return store.managedObjects();
    }

    @GetMapping("/managed-executions")
    public List<Map<String, Object>> managedExecutions() {
        return store.managedExecutions();
    }

    @PostMapping("/managed-objects/{objectId}/approve")
    public Map<String, Object> approveManagedObject(@PathVariable final long objectId) {
        return managedSql.approveManagedObject(objectId);
    }

    @PostMapping("/managed-executions/{executionId}/reconcile")
    public Map<String, Object> reconcile(@PathVariable final String executionId) {
        return managedSql.reconcile(executionId);
    }

    @GetMapping("/lineage/events")
    public List<Map<String, Object>> lineageEvents() {
        return store.lineageEvents();
    }

    @GetMapping("/lineage/events/{eventId}/edges")
    public List<Map<String, Object>> lineageEdges(@PathVariable final String eventId) {
        return store.lineageEdges(eventId);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public Map<String, Object> badRequest(final RuntimeException ex) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", ex.getClass().getSimpleName());
        result.put("message", ex.getMessage());
        return result;
    }

    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ExceptionHandler(SecurityException.class)
    public Map<String, Object> unauthorized(final SecurityException ex) {
        return Collections.<String, Object>singletonMap("message", ex.getMessage());
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(Exception.class)
    public Map<String,Object> failure(final Exception ex) { return Collections.singletonMap("message",Errors.explain(ex)); }
    public static final class OnboardRequest { public List<String> databases; }

    public static final class BatchAssignment {
        public List<Long> ids = Collections.emptyList();
        public String policyId;
    }

    public static final class DatabaseRequest {
        public String database;
    }
    public static final class RecoveryRequest { public Long expectedActiveVersion; }
}
