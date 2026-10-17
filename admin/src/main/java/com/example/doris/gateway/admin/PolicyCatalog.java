package com.example.doris.gateway.admin;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public final class PolicyCatalog {

    public static final String NONE = "NONE";
    private final Map<String, Policy> policies = new LinkedHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private FieldMappingRules mappingRules;

    public PolicyCatalog() {
        add(new Policy("ID_CARD", "证件号码脱敏", "LENGTH_BASED_MASK", props("rules", "15=6,4;18=6,4;*=0,0", "replace-char", "*"),
                names("证件号码", "身份证号码", "身份证号", "公民身份号码", "id_card", "idcard", "cert_no", "certificate_no")));
        add(new Policy("PERSON_NAME", "姓名脱敏", "LENGTH_BASED_MASK", props("rules", "1=0,0;2=1,0;3=1,1;*=1,1", "replace-char", "*"),
                names("姓名", "人员姓名", "客户姓名", "用户姓名", "name", "person_name", "customer_name", "user_name")));
        add(new Policy("MOBILE_PHONE", "手机号脱敏", "UNICODE_KEEP_FIRST_N_LAST_M", props("first-n", "3", "last-m", "4", "replace-char", "*"),
                names("手机号", "手机号码", "联系电话", "phone", "mobile", "mobile_phone", "phone_number")));
        add(new Policy("ADDRESS", "地址脱敏", "UNICODE_KEEP_FIRST_N_LAST_M", props("first-n", "6", "last-m", "0", "replace-char", "*"),
                names("地址", "家庭地址", "居住地址", "详细地址", "home_address", "address")));
        add(new Policy("OUTPUT_AES_GCM", "返回AES-GCM密文", "OUTPUT_AES_GCM", Collections.<String, String>emptyMap(), Collections.<String>emptyList()));
    }

    private void add(final Policy policy) {
        policies.put(policy.id, policy);
    }

    public List<Policy> all() {
        return new ArrayList<>(policies.values());
    }

    public Policy get(final String id) {
        if (NONE.equals(id)) {
            return null;
        }
        Policy result = policies.get(id);
        if (result == null) {
            throw new IllegalArgumentException("Unknown policy: " + id);
        }
        return result;
    }

    public String recommend(final String columnName, final String comment) {
        return recommend("", "", columnName, comment);
    }

    public String recommend(final String database, final String table, final String columnName, final String comment) {
        String normalized = columnName == null ? "" : columnName.toLowerCase(Locale.ROOT);
        String normalizedComment = comment == null ? "" : comment.toUpperCase(Locale.ROOT);
        for (Policy each : policies.values()) if (normalizedComment.contains("SECURITY:" + each.id)) return each.id;
        if (mappingRules != null) {
            String mapped = mappingRules.recommend(database, table, normalized);
            if (mapped != null) return mapped;
        }
        for (Policy each : policies.values()) {
            if (each.columnNames.contains(normalized) || normalizedComment.contains("SECURITY:" + each.id)) {
                return each.id;
            }
        }
        return NONE;
    }

    private static List<String> names(final String... values) {
        List<String> result = new ArrayList<>();
        for (String each : values) {
            result.add(each.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private static Map<String, String> props(final String... pairs) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    public static final class Policy {
        public final String id;
        public final String name;
        public final String algorithmType;
        public final Map<String, String> props;
        private final List<String> columnNames;

        public List<String> getColumnNames() {
            return Collections.unmodifiableList(columnNames);
        }

        Policy(final String id, final String name, final String algorithmType, final Map<String, String> props, final List<String> columnNames) {
            this.id = id;
            this.name = name;
            this.algorithmType = algorithmType;
            this.props = props;
            this.columnNames = columnNames;
        }
    }
}
