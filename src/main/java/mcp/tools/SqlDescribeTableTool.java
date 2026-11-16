package mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mcp.McpServer;
import mcp.db.FbTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_describe_table — структура таблицы (колонки, типы, размеры).
 *
 * <p>Это ГЛАВНЫЙ инструмент разведки схемы: без знания колонок и типов
 * LLM не может корректно составить SQL. Модель обычно вызывает его
 * сразу после sql_list_tables.</p>
 *
 * <p>Источник — RDB$RELATION_FIELDS (колонки) + RDB$FIELDS (типы):
 * RDB$FIELD_TYPE — числовой код типа, RDB$FIELD_LENGTH — размер,
 * RDB$FIELD_PRECISION / RDB$FIELD_SCALE — для чисел.
 * CASE-шаблон и рендер имени типа вынесены в {@link FbTypes}
 * (общие с sql_describe_procedure).</p>
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * { "name": "sql_describe_table", "arguments": { "table": "USERS" } }
 * }</pre>
 */
public class SqlDescribeTableTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlDescribeTableTool.class);

    private final McpServer server;

    public SqlDescribeTableTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_describe_table");
        definition.addProperty("description", "Describe a table or view structure: column names, Firebird " +
                "data types, lengths, precision, scale, nullability and position. Use this BEFORE writing " +
                "queries against unknown tables. Table names in Firebird are UPPER_CASE by default " +
                "(unless created with quoted lowercase identifiers).");
        definition.add("inputSchema", new ToolSupport.Schema()
                .str("table", "Table or view name (e.g. 'USERS')")
                .required("table")
                .build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        // Имя подставляется в SQL-литерал конкатенацией — обязательна строгая валидация
        String table = Args.requireObjectName(arguments, "table");

        // Запрос связывает колонки (RDB$RELATION_FIELDS) с типами (RDB$FIELDS).
        // DECIMAL/NUMERIC в FB3 имеет тот же код 27, что и DOUBLE PRECISION —
        // различает их только scale != 0 (см. FbTypes.TYPE_CASE_SQL).
        String sql =
                "SELECT " +
                "  rf.RDB$FIELD_NAME AS COL_NAME, " +
                "  " + FbTypes.TYPE_CASE_SQL + " AS COL_TYPE, " +
                "  CASE " +
                "    WHEN f.RDB$FIELD_TYPE = 27 AND f.RDB$FIELD_SCALE != 0 THEN " +
                "      COALESCE(NULLIF(f.RDB$FIELD_PRECISION, 0), " +
                "               CASE f.RDB$FIELD_LENGTH WHEN 2 THEN 4 WHEN 4 THEN 9 ELSE 18 END) " +
                "    WHEN f.RDB$FIELD_TYPE IN (14, 37) THEN f.RDB$FIELD_LENGTH " +
                "    ELSE NULL " +
                "  END AS COL_SIZE, " +
                "  CASE WHEN f.RDB$FIELD_TYPE IN (7, 8, 16, 26, 27) THEN f.RDB$FIELD_SCALE ELSE NULL END AS COL_SCALE, " +
                "  rf.RDB$NULL_FLAG AS NOT_NULL, " +
                "  rf.RDB$DEFAULT_SOURCE AS DEFAULT_SRC, " +
                "  rf.RDB$FIELD_POSITION AS POS " +
                "FROM RDB$RELATION_FIELDS rf " +
                "JOIN RDB$FIELDS f ON rf.RDB$FIELD_SOURCE = f.RDB$FIELD_NAME " +
                "WHERE rf.RDB$RELATION_NAME = '" + table + "' " +
                "ORDER BY rf.RDB$FIELD_POSITION";

        try {
            Map<String, Object> result = server.getDb().executeQuery(sql);
            java.util.List<Map<String, Object>> rows = ToolSupport.rowsOf(result);

            if (rows == null || rows.isEmpty()) {
                throw new RuntimeException("Table or view not found: " + table +
                        ". Use sql_list_tables to see available tables.");
            }

            // Формируем человекочитаемое/машинночитаемое описание колонок
            JsonArray columns = new JsonArray();
            StringBuilder sb = new StringBuilder("Table " + table + " (" + rows.size() + " columns):\n");
            for (Map<String, Object> row : rows) {
                String name = ToolSupport.str(row.get("COL_NAME"));
                String fullType = renderColumnType(row);
                Object notNull = row.get("NOT_NULL");
                String def = ToolSupport.str(row.get("DEFAULT_SRC"));

                columns.add(ToolSupport.obj()
                        .put("name", name)
                        .put("type", fullType)
                        .put("nullable", notNull == null ? Boolean.TRUE : Boolean.FALSE)
                        .put("default", def)
                        .build());

                sb.append("  ").append(name).append("  ").append(fullType)
                  .append(notNull != null ? "  NOT NULL" : "")
                  .append(def != null ? "  " + def : "")
                  .append("\n");
            }

            return ToolSupport.ok(sb + "\nJSON:\n" + columns);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "Describe table error", "Table: " + table, e);
        }
    }

    /**
     * Имя типа для колонки таблицы: size у CHAR/VARCHAR — длина,
     * у DECIMAL — precision; отрицательный scale Firebird показывает как
     * дробную часть (DECIMAL(15,2) хранится scale=-2).
     */
    private static String renderColumnType(Map<String, Object> row) {
        String type = ToolSupport.str(row.get("COL_TYPE"));
        Object size = row.get("COL_SIZE");
        Object scaleObj = row.get("COL_SCALE");
        int scale = scaleObj == null ? 0 : ((Number) scaleObj).intValue();

        if ("CHAR".equals(type) || "VARCHAR".equals(type)) {
            return type + "(" + size + ")";
        }
        if ("DOUBLE PRECISION".equals(type) || "FLOAT".equals(type)) {
            return type;
        }
        if (size != null) {
            if (scale < 0) {
                return type + "(" + size + "," + (-scale) + ")";
            }
            return type + "(" + size + ")";
        }
        return type;
    }
}
