package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_describe_procedure — параметры хранимой процедуры.
 *
 * <p>Показывает входные (INPUT) и выходные (OUTPUT) параметры:
 * имена, типы, порядок. Нужен перед sql_execute_procedure, чтобы
 * передать параметры в правильном порядке и типе.</p>
 *
 * <p>Источник — RDB$PROCEDURE_PARAMETERS + RDB$FIELDS.
 * RDB$PARAMETER_TYPE: 0 = входной, 1 = выходной.</p>
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * { "name": "sql_describe_procedure", "arguments": { "procedure": "P_GET_USER" } }
 * }</pre>
 */
public class SqlDescribeProcedureTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlDescribeProcedureTool.class);

    private final McpServer server;

    public SqlDescribeProcedureTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_describe_procedure");
        definition.addProperty("description", "Describe a stored procedure: input and output parameters " +
                "with names, types and positions. Use this BEFORE sql_execute_procedure to know what " +
                "arguments to pass. Procedure names are UPPER_CASE by default.");

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject procParam = new JsonObject();
        procParam.addProperty("type", "string");
        procParam.addProperty("description", "Procedure name (e.g. 'P_GET_USER')");
        properties.add("procedure", procParam);

        inputSchema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("procedure");
        inputSchema.add("required", required);

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("procedure")) {
            throw new IllegalArgumentException("Missing required parameter: procedure");
        }

        String proc = arguments.get("procedure").getAsString().trim().toUpperCase();
        if (!proc.matches("^[A-Z0-9_$]+$")) {
            throw new IllegalArgumentException("Invalid procedure name: " + proc);
        }

        // Тот же CASE по RDB$FIELD_TYPE, что и в describe_table — единое представление типов.
        // DECIMAL/NUMERIC в FB3 имеют код 27 (как DOUBLE) — различаются по scale != 0.
        String sql =
                "SELECT " +
                "  pp.RDB$PARAMETER_NAME AS P_NAME, " +
                "  pp.RDB$PARAMETER_TYPE AS P_TYPE, " +
                "  CASE " +
                "    WHEN f.RDB$FIELD_TYPE = 27 AND f.RDB$FIELD_SCALE != 0 THEN 'DECIMAL' " +
                "    WHEN f.RDB$FIELD_TYPE = 27 THEN 'DOUBLE PRECISION' " +
                "    WHEN f.RDB$FIELD_TYPE = 7 THEN 'SMALLINT' WHEN f.RDB$FIELD_TYPE = 8 THEN 'INTEGER' " +
                "    WHEN f.RDB$FIELD_TYPE = 10 THEN 'FLOAT' WHEN f.RDB$FIELD_TYPE = 12 THEN 'DATE' " +
                "    WHEN f.RDB$FIELD_TYPE = 13 THEN 'TIME' WHEN f.RDB$FIELD_TYPE = 14 THEN 'CHAR' " +
                "    WHEN f.RDB$FIELD_TYPE = 16 THEN 'BIGINT' WHEN f.RDB$FIELD_TYPE = 23 THEN 'BOOLEAN' " +
                "    WHEN f.RDB$FIELD_TYPE = 26 THEN 'QUAD' WHEN f.RDB$FIELD_TYPE = 35 THEN 'TIMESTAMP' " +
                "    WHEN f.RDB$FIELD_TYPE = 37 THEN 'VARCHAR' WHEN f.RDB$FIELD_TYPE = 40 THEN 'BLOB' " +
                "    ELSE 'TYPE_' || f.RDB$FIELD_TYPE END AS P_DTYPE, " +
                "  f.RDB$FIELD_LENGTH AS P_LEN, " +
                "  CASE WHEN f.RDB$FIELD_TYPE = 27 AND f.RDB$FIELD_SCALE != 0 THEN " +
                "       COALESCE(NULLIF(f.RDB$FIELD_PRECISION, 0), " +
                "                CASE f.RDB$FIELD_LENGTH WHEN 2 THEN 4 WHEN 4 THEN 9 ELSE 18 END) " +
                "       ELSE f.RDB$FIELD_PRECISION END AS P_PREC, " +
                "  f.RDB$FIELD_SCALE AS P_SCALE, " +
                "  pp.RDB$PARAMETER_NUMBER AS P_NUM " +
                "FROM RDB$PROCEDURE_PARAMETERS pp " +
                "JOIN RDB$FIELDS f ON pp.RDB$FIELD_SOURCE = f.RDB$FIELD_NAME " +
                "WHERE pp.RDB$PROCEDURE_NAME = '" + proc + "' " +
                "ORDER BY pp.RDB$PARAMETER_TYPE, pp.RDB$PARAMETER_NUMBER";

        try {
            Map<String, Object> result = server.getDb().executeQuery(sql);

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> rows =
                    (java.util.List<Map<String, Object>>) result.get("rows");

            if (rows == null || rows.isEmpty()) {
                throw new RuntimeException("Procedure not found: " + proc +
                        ". Use sql_list_procedures to see available procedures.");
            }

            JsonArray inputs = new JsonArray();
            JsonArray outputs = new JsonArray();
            StringBuilder sb = new StringBuilder("Procedure " + proc + ":\n");

            for (Map<String, Object> row : rows) {
                String name = str(row.get("P_NAME"));
                String type = renderType(row);
                boolean isInput = ((Number) row.get("P_TYPE")).intValue() == 0;

                JsonObject p = new JsonObject();
                p.addProperty("name", name);
                p.addProperty("type", type);
                (isInput ? inputs : outputs).add(p);

                sb.append(isInput ? "  IN  " : "  OUT ").append(name).append("  ").append(type).append("\n");
            }

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            JsonObject json = new JsonObject();
            json.add("input", inputs);
            json.add("output", outputs);
            textContent.addProperty("text", sb.toString() + "\nJSON:\n" + json.toString());
            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);
            return response;

        } catch (SQLException e) {
            logger.error("Describe procedure error: {}", e.getMessage());
            throw new RuntimeException("Error describing procedure: " + e.getMessage(), e);
        }
    }

    /** Сборка читаемого имени типа из кода + длины/precision/scale. */
    private static String renderType(Map<String, Object> row) {
        String type = str(row.get("P_DTYPE"));
        Object len = row.get("P_LEN");
        Object prec = row.get("P_PREC");
        Object scaleObj = row.get("P_SCALE");
        int scale = scaleObj == null ? 0 : ((Number) scaleObj).intValue();

        if (type.equals("CHAR") || type.equals("VARCHAR")) {
            return type + "(" + len + ")";
        }
        if ((type.equals("DOUBLE PRECISION") || type.equals("FLOAT"))) {
            return type;
        }
        if (prec != null && scale < 0) {
            return type + "(" + prec + "," + (-scale) + ")";
        }
        return type;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
