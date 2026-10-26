package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
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
 * RDB$FIELD_PRECISION / RDB$FIELD_SCALE — для чисел.</p>
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

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject tableParam = new JsonObject();
        tableParam.addProperty("type", "string");
        tableParam.addProperty("description", "Table or view name (e.g. 'USERS')");
        properties.add("table", tableParam);

        inputSchema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("table");
        inputSchema.add("required", required);

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("table")) {
            throw new IllegalArgumentException("Missing required parameter: table");
        }

        String table = arguments.get("table").getAsString().trim().toUpperCase();

        // Валидация имени — защита от инъекции (имя подставляется в литерал SQL)
        if (!table.matches("^[A-Z0-9_$]+$")) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }

        // Запрос связывает колонки (RDB$RELATION_FIELDS) с типами (RDB$FIELDS).
        // ВАЖНО: в Firebird 3 DECIMAL/NUMERIC хранится как RDB$FIELD_TYPE=27,
        // ТОТ ЖЕ КОД, что и DOUBLE PRECISION. Различие: у DECIMAL RDB$FIELD_SCALE != 0
        // (хранится как масштабированное целое), у DOUBLE PRECISION scale = 0.
        // RDB$FIELD_PRECISION для DECIMAL в диалекте 1 часто NULL/0 — поэтому
        // точность вычисляется по длине поля: 2->4, 4->9, 8->18 знаков.
        String sql =
                "SELECT " +
                "  rf.RDB$FIELD_NAME AS COL_NAME, " +
                "  CASE " +
                "    WHEN f.RDB$FIELD_TYPE = 27 AND f.RDB$FIELD_SCALE != 0 " +
                "      THEN 'DECIMAL' " +
                "    WHEN f.RDB$FIELD_TYPE = 27 THEN 'DOUBLE PRECISION' " +
                "    WHEN f.RDB$FIELD_TYPE = 7  THEN 'SMALLINT' " +
                "    WHEN f.RDB$FIELD_TYPE = 8  THEN 'INTEGER' " +
                "    WHEN f.RDB$FIELD_TYPE = 10 THEN 'FLOAT' " +
                "    WHEN f.RDB$FIELD_TYPE = 12 THEN 'DATE' " +
                "    WHEN f.RDB$FIELD_TYPE = 13 THEN 'TIME' " +
                "    WHEN f.RDB$FIELD_TYPE = 14 THEN 'CHAR' " +
                "    WHEN f.RDB$FIELD_TYPE = 16 THEN 'BIGINT' " +
                "    WHEN f.RDB$FIELD_TYPE = 23 THEN 'BOOLEAN' " +
                "    WHEN f.RDB$FIELD_TYPE = 26 THEN 'QUAD' " +
                "    WHEN f.RDB$FIELD_TYPE = 35 THEN 'TIMESTAMP' " +
                "    WHEN f.RDB$FIELD_TYPE = 37 THEN 'VARCHAR' " +
                "    WHEN f.RDB$FIELD_TYPE = 40 THEN 'BLOB' " +
                "    WHEN f.RDB$FIELD_TYPE = 93 THEN 'ARRAY' " +
                "    ELSE 'TYPE_' || f.RDB$FIELD_TYPE " +
                "  END AS COL_TYPE, " +
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

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> rows =
                    (java.util.List<Map<String, Object>>) result.get("rows");

            if (rows == null || rows.isEmpty()) {
                throw new RuntimeException("Table or view not found: " + table +
                        ". Use sql_list_tables to see available tables.");
            }

            // Формируем человекочитаемое/машинночитаемое описание колонок
            JsonArray columns = new JsonArray();
            StringBuilder sb = new StringBuilder("Table " + table + " (" + rows.size() + " columns):\n");
            for (Map<String, Object> row : rows) {
                String name = str(row.get("COL_NAME"));
                String type = str(row.get("COL_TYPE"));
                Object size = row.get("COL_SIZE");
                Object scale = row.get("COL_SCALE");
                Object notNull = row.get("NOT_NULL");
                Object def = row.get("DEFAULT_SRC");

                // Собираем полное имя типа: VARCHAR(60), DECIMAL(15,2), NUMERIC(3) ...
                StringBuilder fullType = new StringBuilder(type);
                if (size != null) {
                    int scaleVal = scale == null ? 0 : ((Number) scale).intValue();
                    if (type.equals("CHAR") || type.equals("VARCHAR")) {
                        fullType.append("(").append(size).append(")");
                    } else if (type.equals("DOUBLE PRECISION") || type.equals("FLOAT")) {
                        // для вещественных precision не показываем
                    } else if (scaleVal < 0) {
                        // Firebird хранит scale отрицательным: DECIMAL(15,2) -> scale = -2
                        fullType.append("(").append(size).append(",").append(-scaleVal).append(")");
                    } else {
                        fullType.append("(").append(size).append(")");
                    }
                }

                JsonObject col = new JsonObject();
                col.addProperty("name", name);
                col.addProperty("type", fullType.toString());
                col.addProperty("nullable", notNull == null);
                if (def != null) col.addProperty("default", str(def));
                columns.add(col);

                sb.append("  ").append(name).append("  ").append(fullType)
                  .append(notNull != null ? "  NOT NULL" : "")
                  .append(def != null ? "  " + str(def) : "")
                  .append("\n");
            }

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", sb.toString() + "\nJSON:\n" + columns.toString());
            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);
            return response;

        } catch (SQLException e) {
            logger.error("Describe table error: {}", e.getMessage());
            throw new RuntimeException("Error describing table: " + e.getMessage(), e);
        }
    }

    /** Приведение значения к строке с trim (CHAR-padding в диалекте 1). */
    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
