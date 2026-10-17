package com.example.doris.gateway.admin;

import java.util.*;

/** Applies only the successful object's change to an active snapshot, never a database draft. */
final class AutomaticPolicyDelta {
    @SuppressWarnings("unchecked")
    static Map<String,Object> apply(Map<String,Object> active,String type,String table,String renamed,
            List<Map<String,Object>> physical,List<Map<String,Object>> edges,PolicyCatalog catalog,String database) {
        if(!(active.get("schema") instanceof List)) throw new IllegalStateException("生效快照缺少字段基线，请先手动发布策略再执行结构变更。");
        List<Map<String,Object>> oldSchema=(List<Map<String,Object>>)active.get("schema");
        List<Map<String,Object>> columns=copy((List<Map<String,Object>>)active.get("columns"));
        List<Map<String,Object>> rules=copy((List<Map<String,Object>>)active.get("globalRules"));
        List<Map<String,Object>> schema=copy(oldSchema);
        String target="RENAME".equals(type)?renamed:table;
        if("DROP".equals(type)) {
            removeTable(columns,table,"tableName");removeTable(rules,table,"table");removeTable(schema,table,"tableName");
        } else if("RENAME".equals(type)) {
            if(schema.stream().anyMatch(r->eq(r.get("tableName"),target)))throw new IllegalStateException("重命名目标已存在于生效快照，拒绝覆盖。");
            for(Map<String,Object> r:columns)if(eq(r.get("tableName"),table))r.put("tableName",target);
            for(Map<String,Object> r:schema)if(eq(r.get("tableName"),table))r.put("tableName",target);
            for(Map<String,Object> r:rules)if(eq(r.get("table"),table)) {
                r.put("table",target);
                Map<String,Object> props=new LinkedHashMap<>((Map<String,Object>)r.get("props"));
                if(props.containsKey("aad"))props.put("aad",database+"."+target+"."+r.get("column"));r.put("props",props);
            }
        } else {
            Set<String> present=new HashSet<>();for(Map<String,Object> r:physical)present.add(key(r.get("columnName")));
            if(present.isEmpty())throw new IllegalStateException("未取得目标表完整字段列表，拒绝自动发布。");
            boolean removedProtected=columns.stream().anyMatch(r->eq(r.get("tableName"),table)&&!present.contains(key(r.get("columnName"))));
            boolean addedColumn=physical.stream().anyMatch(f->oldSchema.stream().noneMatch(r->eq(r.get("tableName"),table)&&eq(r.get("columnName"),String.valueOf(f.get("columnName")))));
            if(removedProtected&&addedColumn)
                throw new IllegalStateException("检测到受保护字段消失且新增字段，可能涉及字段改名；无法证明血缘，保持阻断并请管理员核实。");
            columns.removeIf(r->eq(r.get("tableName"),table)&&!present.contains(key(r.get("columnName"))));
            rules.removeIf(r->eq(r.get("table"),table)&&!present.contains(key(r.get("column"))));
            removeTable(schema,table,"tableName");
            for(Map<String,Object> field:physical) {
                String column=String.valueOf(field.get("columnName"));
                Map<String,Object> descriptor=new LinkedHashMap<>();descriptor.put("tableName",table);descriptor.put("columnName",column);schema.add(descriptor);
                boolean existed=oldSchema.stream().anyMatch(r->eq(r.get("tableName"),table)&&eq(r.get("columnName"),column));
                // Only a genuinely new column can acquire an automatic name recommendation.
                String recommendation=String.valueOf(field.get("recommendedPolicy"));
                if(!existed&&!recommendation.equals("null")&&!recommendation.equals("NONE")&&!recommendation.isEmpty())
                    put(columns,rules,table,column,recommendation,catalog,database);
            }
            for(Map<String,Object> edge:edges) {
                if(!eq(edge.get("targetTable"),table))throw new IllegalStateException("自动发布包含其他目标表，拒绝应用。");
                String column=String.valueOf(edge.get("targetColumn"));
                if(!present.contains(key(column)))throw new IllegalStateException("血缘目标字段不在当前表结构中。");
                String policy=label(active,String.valueOf(edge.get("sourceTable")),String.valueOf(edge.get("sourceColumn")));
                if(!policy.equals(String.valueOf(edge.get("inheritedPolicy"))))throw new IllegalStateException("血缘来源的生效策略已变化，拒绝使用过期的继承结果。");
                if(!policy.isEmpty())put(columns,rules,table,column,policy,catalog,database);
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("columns",columns);result.put("globalRules",rules);result.put("schema",schema);return result;
    }
    @SuppressWarnings("unchecked") static String label(Map<String,Object> active,String table,String column) {
        for(Map<String,Object> r:(List<Map<String,Object>>)active.get("columns"))
            if(eq(r.get("tableName"),table)&&eq(r.get("columnName"),column))return String.valueOf(r.get("assignedPolicy"));
        return "";
    }
    private static void put(List<Map<String,Object>> columns,List<Map<String,Object>> rules,String table,String column,String policy,PolicyCatalog catalog,String database) {
        for(Map<String,Object> r:columns)if(eq(r.get("tableName"),table)&&eq(r.get("columnName"),column)) {
            if(!policy.equals(r.get("assignedPolicy")))throw new IllegalStateException("自动继承与已有生效保护冲突："+table+"."+column);
            return;
        }
        Map<String,Object> label=new LinkedHashMap<>();label.put("tableName",table);label.put("columnName",column);label.put("assignedPolicy",policy);columns.add(label);
        PolicyCatalog.Policy p=catalog.get(policy);Map<String,String> props=new LinkedHashMap<>(p.props);
        if("OUTPUT_AES_GCM".equals(policy)){props.put("key-env","GATEWAY_OUTPUT_AES_KEY_B64");props.put("key-id","output-key-v1");props.put("aad",database+"."+table+"."+column);}
        Map<String,Object> rule=new LinkedHashMap<>();rule.put("table",table);rule.put("column",column);rule.put("algorithmType",p.algorithmType);rule.put("props",props);rules.add(rule);
    }
    private static List<Map<String,Object>> copy(List<Map<String,Object>> rows){List<Map<String,Object>> result=new ArrayList<>();for(Map<String,Object> r:rows)result.add(new LinkedHashMap<>(r));return result;}
    private static void removeTable(List<Map<String,Object>> rows,String table,String field){rows.removeIf(r->eq(r.get(field),table));}
    private static String key(Object v){return String.valueOf(v).toLowerCase(Locale.ROOT);}
    private static boolean eq(Object a,String b){return a!=null&&String.valueOf(a).equalsIgnoreCase(b);}
}
