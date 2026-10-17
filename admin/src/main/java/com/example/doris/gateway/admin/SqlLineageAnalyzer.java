package com.example.doris.gateway.admin;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.view.CreateView;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves column lineage for the deliberately limited SQL mutation set supported by the gateway. */
@Component
public final class SqlLineageAnalyzer {
    /** Includes sources which affect row existence but do not feed a projected column. */
    public List<String> referencedTables(final String sql) throws Exception {
        Statement statement = CCJSqlParserUtil.parse(quoteUnicodeIdentifiers(normalizeMaterializedView(SqlTextEnvelope.executableStatement(sql))));
        if (statement instanceof CreateView) statement = ((CreateView) statement).getSelect();
        return new net.sf.jsqlparser.util.TablesNamesFinder().getTableList(statement);
    }

    private static final Pattern RENAME_TABLE = Pattern.compile("(?is)^\\s*RENAME\\s+TABLE\\s+([^\\s,]+)\\s+TO\\s+([^\\s,;]+)\\s*;?\\s*$");
    private static final Pattern ALTER_RENAME = Pattern.compile("(?is)^\\s*ALTER\\s+TABLE\\s+([^\\s;]+)\\s+RENAME\\s+(?:TO\\s+)?([^\\s;]+)\\s*;?\\s*$");
    private static final Pattern DROP = Pattern.compile("(?is)^\\s*DROP\\s+(?:MATERIALIZED\\s+)?(?:TABLE|VIEW)\\s+(?:IF\\s+EXISTS\\s+)?([^\\s,;]+).*$");
    private static final Pattern MATERIALIZED_VIEW_FOR_PARSER = Pattern.compile(
            "(?is)^(\\s*CREATE\\s+MATERIALIZED\\s+VIEW\\s+(?:`[^`]+`|[\\p{L}\\p{N}_$]+)(?:\\.(?:`[^`]+`|[\\p{L}\\p{N}_$]+))?)(.*?)(\\bAS\\s+SELECT\\b.*)$");

    private final AdminStore store;

    public SqlLineageAnalyzer(final AdminStore store) {
        this.store = store;
    }

    public Analysis analyze(final String sql, final long connectionId, final String defaultDatabase) throws Exception {
        String executableSql = SqlTextEnvelope.executableStatement(sql);
        Matcher rename = RENAME_TABLE.matcher(executableSql);
        if (!rename.matches()) rename = ALTER_RENAME.matcher(executableSql);
        if (rename.matches()) {
            QualifiedName source = name(rename.group(1), defaultDatabase);
            QualifiedName target = name(rename.group(2), source.database);
            return Analysis.rename(source, target);
        }
        Matcher drop = DROP.matcher(executableSql);
        if (drop.matches()) return Analysis.drop(name(drop.group(1), defaultDatabase));
        Statement statement = CCJSqlParserUtil.parse(quoteUnicodeIdentifiers(normalizeMaterializedView(DorisTableProperties.parserView(executableSql))));
        if (statement instanceof net.sf.jsqlparser.statement.update.Update) {
            net.sf.jsqlparser.statement.update.Update update=(net.sf.jsqlparser.statement.update.Update)statement;
            QualifiedName target=table(update.getTable(),defaultDatabase);
            List<Source> sources=new ArrayList<>();
            addSource(sources,update.getTable(),connectionId,defaultDatabase,Collections.<String,List<Output>>emptyMap());
            if(update.getFromItem()!=null || (update.getJoins()!=null && !update.getJoins().isEmpty()))
                throw new IllegalArgumentException("暂不支持 UPDATE JOIN / FROM 的精确血缘，请使用已支持的 INSERT SELECT。");
            List<Edge> edges=new ArrayList<>();
            for(net.sf.jsqlparser.statement.update.UpdateSet set:update.getUpdateSets()) {
                for(int i=0;i<set.getColumns().size();i++) {
                    final List<Column> columns=new ArrayList<>();
                    Expression expression=set.getExpressions().get(i);
                    expression.accept(new ExpressionVisitorAdapter(){
                        @Override public void visit(Column column){columns.add(column);}
                        @Override public void visit(SubSelect select){throw new IllegalArgumentException("暂不支持 UPDATE 标量子查询的精确血缘。");}
                    });
                    for(Column column:columns)for(Origin origin:resolveColumn(sources,column))
                        edges.add(new Edge(origin,target,clean(set.getColumns().get(i).getColumnName()),expression instanceof Column?"DIRECT":"EXPRESSION"));
                }
            }
            return new Analysis("UPDATE",target,null,edges);
        }
        if (statement instanceof CreateTable) {
            CreateTable create = (CreateTable) statement;
            if (create.getSelect() == null) throw new IllegalArgumentException("Only CREATE TABLE ... AS SELECT is a lineage statement.");
            QualifiedName target = table(create.getTable(), defaultDatabase);
            List<String> targets = new ArrayList<>();
            if (create.getColumns() != null) targets.addAll(create.getColumns());
            if (targets.isEmpty() && create.getColumnDefinitions() != null) {
                for (ColumnDefinition each : create.getColumnDefinitions()) targets.add(each.getColumnName());
            }
            return selectAnalysis("CTAS", create.getSelect(), target, targets, connectionId, defaultDatabase);
        }
        if (statement instanceof CreateView) {
            CreateView create = (CreateView) statement;
            QualifiedName target = table(create.getView(), defaultDatabase);
            return selectAnalysis(create.isMaterialized() ? "CREATE_MATERIALIZED_VIEW" : "CREATE_VIEW",
                    create.getSelect(), target, null == create.getColumnNames() ? Collections.<String>emptyList() : create.getColumnNames(), connectionId, defaultDatabase);
        }
        if (statement instanceof Insert) {
            Insert insert = (Insert) statement;
            if (insert.getSelect() == null) throw new IllegalArgumentException("Only INSERT ... SELECT is a lineage statement.");
            QualifiedName target = table(insert.getTable(), defaultDatabase);
            List<String> targets = new ArrayList<>();
            if (insert.getColumns() != null) for (Column each : insert.getColumns()) targets.add(each.getColumnName());
            if (targets.isEmpty()) targets.addAll(store.tableColumns(connectionId, target.database, target.table));
            return selectAnalysis("INSERT_SELECT", insert.getSelect(), target, targets, connectionId, defaultDatabase);
        }
        throw new IllegalArgumentException("Unsupported lineage statement: " + statement.getClass().getSimpleName());
    }

    private Analysis selectAnalysis(final String type, final Select select, final QualifiedName target, final List<String> explicitTargets,
                                    final long connectionId, final String defaultDatabase) {
        Map<String, List<Output>> ctes = new LinkedHashMap<>();
        if (select.getWithItemsList() != null) {
            for (WithItem each : select.getWithItemsList()) {
                ctes.put(lower(each.getName()), resolve(each.getSubSelect().getSelectBody(), connectionId, defaultDatabase, ctes));
            }
        }
        List<Output> outputs = resolve(select.getSelectBody(), connectionId, defaultDatabase, ctes);
        List<String> targetColumns = explicitTargets == null ? Collections.<String>emptyList() : explicitTargets;
        if (!targetColumns.isEmpty() && targetColumns.size() != outputs.size()) {
            throw new IllegalStateException("Target column count does not match SELECT output count.");
        }
        List<Edge> edges = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            Output output = outputs.get(i);
            String targetColumn = targetColumns.isEmpty() ? output.name : clean(targetColumns.get(i));
            if (targetColumn.isEmpty()) throw new IllegalStateException("A derived SELECT expression must have an alias for exact lineage.");
            for (Origin source : output.origins) edges.add(new Edge(source, target, targetColumn, output.transformType));
        }
        return new Analysis(type, target, null, edges);
    }

    private List<Output> resolve(final SelectBody body, final long connectionId, final String defaultDatabase,
                                 final Map<String, List<Output>> ctes) {
        if (body instanceof PlainSelect) return resolvePlain((PlainSelect) body, connectionId, defaultDatabase, ctes);
        if (body instanceof SetOperationList) {
            List<Output> result = new ArrayList<>();
            for (SelectBody each : ((SetOperationList) body).getSelects()) {
                List<Output> branch = resolve(each, connectionId, defaultDatabase, ctes);
                if (result.isEmpty()) {
                    for (Output output : branch) result.add(output.copy());
                } else {
                    if (result.size() != branch.size()) throw new IllegalStateException("Set-operation branches have different column counts.");
                    for (int i = 0; i < result.size(); i++) result.get(i).origins.addAll(branch.get(i).origins);
                }
            }
            return result;
        }
        throw new IllegalStateException("Unsupported SELECT body for exact lineage: " + body.getClass().getSimpleName());
    }

    private List<Output> resolvePlain(final PlainSelect select, final long connectionId, final String defaultDatabase,
                                      final Map<String, List<Output>> ctes) {
        List<Source> sources = new ArrayList<>();
        addSource(sources, select.getFromItem(), connectionId, defaultDatabase, ctes);
        if (select.getJoins() != null) for (Join each : select.getJoins()) addSource(sources, each.getRightItem(), connectionId, defaultDatabase, ctes);
        List<Output> result = new ArrayList<>();
        for (SelectItem item : select.getSelectItems()) {
            if (item instanceof AllColumns) {
                for (Source source : sources) result.addAll(source.expand());
            } else if (item instanceof AllTableColumns) {
                String qualifier = ((AllTableColumns) item).getTable().getName();
                result.addAll(findSource(sources, qualifier).expand());
            } else if (item instanceof SelectExpressionItem) {
                SelectExpressionItem expressionItem = (SelectExpressionItem) item;
                Expression expression = expressionItem.getExpression();
                List<Column> columns = new ArrayList<>();
                expression.accept(new ExpressionVisitorAdapter() {
                    @Override
                    public void visit(final SubSelect subquery) {
                        throw new IllegalArgumentException("暂不支持 SELECT 表达式中的标量子查询血缘；已在执行前拒绝，请改用明确的 FROM 子查询。");
                    }

                    @Override
                    public void visit(final Column column) {
                        columns.add(column);
                    }
                });
                LinkedHashSet<Origin> origins = new LinkedHashSet<>();
                for (Column column : columns) origins.addAll(resolveColumn(sources, column));
                String outputName = expressionItem.getAlias() == null ? (expression instanceof Column ? ((Column) expression).getColumnName() : "") : expressionItem.getAlias().getName();
                result.add(new Output(clean(outputName), origins, expression instanceof Column ? "DIRECT" : "EXPRESSION"));
            } else {
                throw new IllegalStateException("Unsupported SELECT item for exact lineage: " + item.getClass().getSimpleName());
            }
        }
        return result;
    }

    private void addSource(final List<Source> sources, final FromItem item, final long connectionId, final String defaultDatabase,
                           final Map<String, List<Output>> ctes) {
        if (item instanceof Table) {
            Table table = (Table) item;
            String tableName = clean(table.getName());
            String alias = table.getAlias() == null ? tableName : clean(table.getAlias().getName());
            List<Output> cte = ctes.get(lower(tableName));
            if (cte != null) sources.add(Source.derived(alias, tableName, cte));
            else {
                QualifiedName name = table(table, defaultDatabase);
                sources.add(Source.table(alias, name, store.tableColumns(connectionId, name.database, name.table)));
            }
        } else if (item instanceof SubSelect) {
            SubSelect subquery = (SubSelect) item;
            if (subquery.getAlias() == null) throw new IllegalStateException("A subquery in FROM must have an alias for exact lineage.");
            sources.add(Source.derived(clean(subquery.getAlias().getName()), "", resolve(subquery.getSelectBody(), connectionId, defaultDatabase, ctes)));
        } else if (item != null) {
            throw new IllegalStateException("Unsupported FROM item for exact lineage: " + item.getClass().getSimpleName());
        }
    }

    private List<Origin> resolveColumn(final List<Source> sources, final Column column) {
        String qualifier = column.getTable() == null ? "" : clean(column.getTable().getName());
        String columnName = clean(column.getColumnName());
        if (!qualifier.isEmpty()) return findSource(sources, qualifier).origins(columnName);
        List<Origin> matches = new ArrayList<>();
        int matchedSources = 0;
        for (Source each : sources) {
            List<Origin> candidate = each.origins(columnName);
            if (!candidate.isEmpty()) {
                matches.addAll(candidate);
                matchedSources++;
            }
        }
        if (matchedSources != 1) throw new IllegalStateException("Unqualified column is missing or ambiguous: " + columnName);
        return matches;
    }

    private static Source findSource(final List<Source> sources, final String qualifier) {
        for (Source each : sources) if (each.matches(qualifier)) return each;
        throw new IllegalStateException("Unknown table or subquery alias: " + qualifier);
    }

    private static QualifiedName table(final Table table, final String defaultDatabase) {
        return new QualifiedName(clean(table.getSchemaName() == null ? defaultDatabase : table.getSchemaName()), clean(table.getName()));
    }

    private static QualifiedName name(final String raw, final String defaultDatabase) {
        String value = clean(raw);
        int dot = value.lastIndexOf('.');
        return dot < 0 ? new QualifiedName(defaultDatabase, value) : new QualifiedName(clean(value.substring(0, dot)), clean(value.substring(dot + 1)));
    }

    private static String clean(final String value) {
        if (value == null) return "";
        String result = value.trim();
        if (result.length() >= 2 && ((result.startsWith("`") && result.endsWith("`")) || (result.startsWith("\"") && result.endsWith("\"")))) {
            result = result.substring(1, result.length() - 1);
        }
        return result;
    }

    private static String lower(final String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    static String quoteUnicodeIdentifiers(final String sql) {
        StringBuilder result = new StringBuilder(sql.length() + 32);
        char quote = 0;
        for (int i = 0; i < sql.length();) {
            char ch = sql.charAt(i);
            if (quote != 0) {
                result.append(ch);
                if (ch == quote) {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                        result.append(sql.charAt(++i));
                    } else {
                        quote = 0;
                    }
                } else if (ch == '\\' && i + 1 < sql.length()) {
                    result.append(sql.charAt(++i));
                }
                i++;
                continue;
            }
            if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
                result.append(ch);
                i++;
                continue;
            }
            if (Character.isJavaIdentifierStart(ch) || ch == '$') {
                int end = i + 1;
                boolean unicode = ch > 127;
                while (end < sql.length() && (Character.isJavaIdentifierPart(sql.charAt(end)) || sql.charAt(end) == '$')) {
                    unicode |= sql.charAt(end) > 127;
                    end++;
                }
                String token = sql.substring(i, end);
                if (unicode) result.append('`').append(token).append('`');
                else result.append(token);
                i = end;
                continue;
            }
            result.append(ch);
            i++;
        }
        return result.toString();
    }

    private static String normalizeMaterializedView(final String sql) {
        Matcher matcher = MATERIALIZED_VIEW_FOR_PARSER.matcher(sql);
        return matcher.matches() ? matcher.group(1) + ' ' + matcher.group(3) : sql;
    }

    public static final class Analysis {
        public final String statementType;
        public final QualifiedName target;
        public final QualifiedName renameTarget;
        public final List<Edge> edges;

        private Analysis(final String statementType, final QualifiedName target, final QualifiedName renameTarget, final List<Edge> edges) {
            this.statementType = statementType;
            this.target = target;
            this.renameTarget = renameTarget;
            this.edges = edges;
        }

        static Analysis rename(final QualifiedName source, final QualifiedName target) {
            return new Analysis("RENAME", source, target, Collections.<Edge>emptyList());
        }

        static Analysis drop(final QualifiedName target) {
            return new Analysis("DROP", target, null, Collections.<Edge>emptyList());
        }

        static Analysis simple(final String statementType, final QualifiedName target) {
            return new Analysis(statementType, target, null, Collections.<Edge>emptyList());
        }
    }

    public static final class Edge {
        public final Origin source;
        public final QualifiedName target;
        public final String targetColumn;
        public final String transformType;

        Edge(final Origin source, final QualifiedName target, final String targetColumn, final String transformType) {
            this.source = source;
            this.target = target;
            this.targetColumn = targetColumn;
            this.transformType = transformType;
        }
    }

    public static final class QualifiedName {
        public final String database;
        public final String table;

        QualifiedName(final String database, final String table) {
            this.database = database;
            this.table = table;
        }
    }

    public static final class Origin {
        public final String database;
        public final String table;
        public final String column;

        Origin(final String database, final String table, final String column) {
            this.database = database;
            this.table = table;
            this.column = column;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Origin)) return false;
            Origin that = (Origin) other;
            return database.equalsIgnoreCase(that.database) && table.equalsIgnoreCase(that.table) && column.equalsIgnoreCase(that.column);
        }

        @Override
        public int hashCode() {
            return lower(database).hashCode() * 31 * 31 + lower(table).hashCode() * 31 + lower(column).hashCode();
        }
    }

    private static final class Output {
        private final String name;
        private final LinkedHashSet<Origin> origins;
        private final String transformType;

        private Output(final String name, final Set<Origin> origins, final String transformType) {
            this.name = name;
            this.origins = new LinkedHashSet<>(origins);
            this.transformType = transformType;
        }

        private Output copy() {
            return new Output(name, origins, transformType);
        }
    }

    private static final class Source {
        private final String alias;
        private final String sourceName;
        private final QualifiedName table;
        private final List<String> columns;
        private final List<Output> outputs;

        private Source(final String alias, final String sourceName, final QualifiedName table, final List<String> columns, final List<Output> outputs) {
            this.alias = alias;
            this.sourceName = sourceName;
            this.table = table;
            this.columns = columns;
            this.outputs = outputs;
        }

        static Source table(final String alias, final QualifiedName table, final List<String> columns) {
            return new Source(alias, table.table, table, columns, null);
        }

        static Source derived(final String alias, final String sourceName, final List<Output> outputs) {
            return new Source(alias, sourceName, null, null, outputs);
        }

        boolean matches(final String qualifier) {
            return alias.equalsIgnoreCase(clean(qualifier)) || sourceName.equalsIgnoreCase(clean(qualifier));
        }

        List<Origin> origins(final String column) {
            if (table != null) {
                for (String each : columns) if (each.equalsIgnoreCase(column)) return Collections.singletonList(new Origin(table.database, table.table, each));
                return Collections.emptyList();
            }
            for (Output each : outputs) if (each.name.equalsIgnoreCase(column)) return new ArrayList<>(each.origins);
            return Collections.emptyList();
        }

        List<Output> expand() {
            if (table == null) {
                List<Output> result = new ArrayList<>();
                for (Output each : outputs) result.add(each.copy());
                return result;
            }
            List<Output> result = new ArrayList<>();
            for (String column : columns) result.add(new Output(column, Collections.singleton(new Origin(table.database, table.table, column)), "DIRECT"));
            return result;
        }
    }
}
