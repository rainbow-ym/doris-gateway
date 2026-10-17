package com.example.doris.gateway.admin;

import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DorisMetadataService {

    private final AdminStore store;
    private final PolicyCatalog policies;

    public DorisMetadataService(final AdminStore store, final PolicyCatalog policies) {
        this.store = store;
        this.policies = policies;
    }

    public void test(final long connectionId) throws Exception {
        Map<String, Object> source = store.connection(connectionId);
        try (Connection ignored = DriverManager.getConnection(url(source), String.valueOf(source.get("username")), store.connectionPassword(connectionId))) {
            // Connection close is the test.
        }
    }

    public void testRequest(final Map<String,Object> source) throws Exception {
        try (Connection ignored = DriverManager.getConnection(url(source), String.valueOf(source.get("username")), String.valueOf(source.get("password")))) { }
    }

    public List<String> databases(final long connectionId) throws Exception {
        Map<String,Object> source=store.connection(connectionId);
        List<String> result=new ArrayList<>();
        try(Connection c=DriverManager.getConnection(url(source),String.valueOf(source.get("username")),store.connectionPassword(connectionId));
            PreparedStatement s=c.prepareStatement("SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME LIKE ? AND LOWER(SCHEMA_NAME) NOT IN ('information_schema','mysql','__internal_schema','sys','performance_schema') ORDER BY SCHEMA_NAME")) {
            s.setString(1,String.valueOf(source.get("database_pattern")));
            try(ResultSet rs=s.executeQuery()) { while(rs.next()) result.add(rs.getString(1)); }
        }
        return result;
    }

    public List<String> tables(final long connectionId, final String database) throws Exception {
        Map<String,Object> source=store.connection(connectionId);
        List<String> result=new ArrayList<>();
        try(Connection c=DriverManager.getConnection(url(source),String.valueOf(source.get("username")),store.connectionPassword(connectionId));
            PreparedStatement s=c.prepareStatement("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME LIKE ? ORDER BY TABLE_NAME")) {
            s.setString(1,database); s.setString(2,String.valueOf(source.get("table_pattern")));
            try(ResultSet rs=s.executeQuery()) { while(rs.next()) result.add(rs.getString(1)); }
        }
        return result;
    }

    public int scan(final long connectionId) throws Exception {
        Map<String, Object> source = store.connection(connectionId);
        String sql = "SELECT TABLE_SCHEMA,TABLE_NAME,COLUMN_NAME,DATA_TYPE,COALESCE(COLUMN_COMMENT,'') COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA LIKE ? AND TABLE_NAME LIKE ? "
                + "AND LOWER(TABLE_SCHEMA) NOT IN ('information_schema','mysql','__internal_schema','sys','performance_schema') ORDER BY TABLE_SCHEMA,TABLE_NAME,ORDINAL_POSITION";
        int count = 0;
        Map<String, List<AdminStore.ManagedColumn>> discovered = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection(url(source), String.valueOf(source.get("username")), store.connectionPassword(connectionId));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, String.valueOf(source.get("database_pattern")));
            statement.setString(2, String.valueOf(source.get("table_pattern")));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String column = resultSet.getString("COLUMN_NAME");
                    String comment = resultSet.getString("COLUMN_COMMENT");
                    store.upsertColumn(connectionId, resultSet.getString("TABLE_SCHEMA"), resultSet.getString("TABLE_NAME"), column,
                            resultSet.getString("DATA_TYPE"), comment, policies.recommend(resultSet.getString("TABLE_SCHEMA"), resultSet.getString("TABLE_NAME"), column, comment));
                    String database = resultSet.getString("TABLE_SCHEMA");
                    String table = resultSet.getString("TABLE_NAME");
                    String key = database + '\u0000' + table;
                    discovered.computeIfAbsent(key, ignored -> new ArrayList<AdminStore.ManagedColumn>())
                            .add(new AdminStore.ManagedColumn(column, resultSet.getString("DATA_TYPE")));
                    count++;
                }
            }
        }
        for (Map.Entry<String, List<AdminStore.ManagedColumn>> entry : discovered.entrySet()) {
            int delimiter = entry.getKey().indexOf('\u0000');
            String database = entry.getKey().substring(0, delimiter);
            String table = entry.getKey().substring(delimiter + 1);
            store.discoverManagedObject(connectionId, database, table, "TABLE");
            Map<String, Object> object = store.managedObject(connectionId, database, table);
            String status = object.isEmpty() ? "UNMANAGED" : String.valueOf(mapValue(object, "status"));
            if ("ACTIVE".equals(status) && sameColumns(store.activeManagedColumns(connectionId, database, table), entry.getValue())) {
                store.replaceManagedColumns(connectionId, database, table, entry.getValue(), "ACTIVE");
            } else {
                if ("ACTIVE".equals(status)) {
                    store.upsertManagedObject(connectionId, database, table, "TABLE", "DRIFTED", null,
                            "Physical columns differ from the approved registry; explicit approval is required.");
                } else if (!"PREPARED".equals(status) && !"SYNCING".equals(status)) {
                    store.upsertManagedObject(connectionId, database, table, "TABLE", "UNMANAGED", null,
                            "Physical object was discovered outside an active gateway DDL lifecycle; explicit approval is required.");
                }
                store.replaceManagedColumns(connectionId, database, table, entry.getValue(), "UNMANAGED");
            }
        }
        store.audit("METADATA_SCANNED", "connectionId=" + connectionId + ",columns=" + count);
        return count;
    }

    public int scanTable(final long connectionId, final String database, final String table) throws Exception {
        Map<String, Object> source = store.connection(connectionId);
        String sql = "SELECT TABLE_SCHEMA,TABLE_NAME,COLUMN_NAME,DATA_TYPE,COALESCE(COLUMN_COMMENT,'') COLUMN_COMMENT "
                + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=? AND TABLE_NAME=? ORDER BY ORDINAL_POSITION";
        int count = 0;
        List<AdminStore.ScannedColumn> columns = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url(source), String.valueOf(source.get("username")), store.connectionPassword(connectionId));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String column = resultSet.getString("COLUMN_NAME");
                    String comment = resultSet.getString("COLUMN_COMMENT");
                    columns.add(new AdminStore.ScannedColumn(column, resultSet.getString("DATA_TYPE"), comment, policies.recommend(database, table, column, comment)));
                    count++;
                }
            }
        }
        if (count > 0) store.replaceTableMetadata(connectionId, database, table, columns);
        return count;
    }

    public boolean tableExists(final long connectionId, final String database, final String table) throws Exception {
        Map<String, Object> source = store.connection(connectionId);
        String sql = "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_NAME=?";
        try (Connection connection = DriverManager.getConnection(url(source), String.valueOf(source.get("username")), store.connectionPassword(connectionId));
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        }
    }

    private static Object mapValue(final Map<String, Object> row, final String key) {
        if (row.containsKey(key)) return row.get(key);
        if (row.containsKey(key.toUpperCase())) return row.get(key.toUpperCase());
        return row.get(key.toLowerCase());
    }

    private static boolean sameColumns(final List<String> approved, final List<AdminStore.ManagedColumn> discovered) {
        if (approved.size() != discovered.size()) return false;
        for (int i = 0; i < approved.size(); i++) {
            if (!approved.get(i).toLowerCase(Locale.ROOT).equals(discovered.get(i).name.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
    }

    private String url(final Map<String, Object> source) {
        return "jdbc:mysql://" + source.get("host") + ":" + source.get("port")
                + "/information_schema?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&connectTimeout=5000&socketTimeout=30000";
    }
}
