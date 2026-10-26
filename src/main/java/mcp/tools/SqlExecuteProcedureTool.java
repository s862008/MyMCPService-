package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Инструмент sql_execute_procedure — вызов хранимой процедуры с параметрами.
 *
 * <p>Использует JDBC escape-синтаксис {call PROC(?,?,...)} — драйвер сам
 * обрабатывает передачу параметров и возврат RESULT SET процедур Firebird
 * (процедуры с RETURNS возвращают курсор).</p>
 *
 * <p>Параметры передаются как JSON-массив в порядке объявления входных
 * параметров процедуры (см. sql_describe_procedure). null допустим.</p>
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * {
 *   "name": "sql_execute_procedure",
 *   "arguments": {
 *     "procedure": "P_GET_USER",
 *     "parameters": [42]
 *   }
 * }
 * }</pre>
 */
public class SqlExecuteProcedureTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlExecuteProcedureTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final McpServer server;

    public SqlExecuteProcedureTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_execute_procedure");
        definition.addProperty("description", "Execute a stored procedure with parameters and return its " +
                "result set (for procedures with RETURNS) or success status (for executable procedures). " +
                "Call sql_describe_procedure first to get parameter order and types. " +
                "Parameters are passed positionally as a JSON array; use null for NULL.");

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject procParam = new JsonObject();
        procParam.addProperty("type", "string");
        procParam.addProperty("description", "Procedure name (e.g. 'P_GET_USER')");
        properties.add("procedure", procParam);

        JsonObject paramsParam = new JsonObject();
        paramsParam.addProperty("type", "array");
        JsonObject items = new JsonObject();
        // Типы разные (числа/строки/даты) — anyOf усложняет схемы, оставляем без строгой типизации
        paramsParam.add("items", items);
        paramsParam.addProperty("description", "Positional input parameters array (may be empty, may contain null)");
        properties.add("parameters", paramsParam);

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

        // Входные параметры: JSON-массив -> Object[]
        List<Object> params = new ArrayList<>();
        if (arguments.has("parameters") && arguments.get("parameters").isJsonArray()) {
            for (JsonElement el : arguments.get("parameters").getAsJsonArray()) {
                if (el.isJsonNull()) {
                    params.add(null);
                } else if (el.getAsJsonPrimitive().isNumber()) {
                    params.add(el.getAsNumber());
                } else if (el.getAsJsonPrimitive().isBoolean()) {
                    // Firebird BOOLEAN (диалект 3) — в диалекте 1 не используется,
                    // но передаём как 1/0 на случай смешанных баз
                    params.add(el.getAsBoolean() ? 1 : 0);
                } else {
                    params.add(el.getAsString());
                }
            }
        }

        // JDBC escape {call PROC(?,?)}. Jaybird транслирует его в EXECUTE PROCEDURE
        // и возвращает RESULT SET процедур с RETURNS.
        StringBuilder sql = new StringBuilder("{call ").append(proc).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")}");
        logger.info("Executing procedure: {} with {} parameter(s)", proc, params.size());

        try (Connection conn = server.getDb().openConnectionForTool();
             CallableStatement call = conn.prepareCall(sql.toString())) {

            // Установка параметров по порядку
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p == null) {
                    call.setNull(i + 1, Types.NULL);
                } else if (p instanceof Number) {
                    call.setObject(i + 1, p);
                } else {
                    call.setObject(i + 1, p.toString());
                }
            }

            boolean hasResultSet = call.execute();
            conn.commit();

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("procedure", proc);

            if (hasResultSet) {
                // Процедура с RETURNS — читаем выходной набор
                try (ResultSet rs = call.getResultSet()) {
                    ResultSetMetaData md = rs.getMetaData();
                    int cols = md.getColumnCount();
                    String[] names = new String[cols];
                    for (int i = 1; i <= cols; i++) names[i - 1] = md.getColumnLabel(i).trim();

                    List<Map<String, Object>> rows = new ArrayList<>();
                    int count = 0;
                    while (rs.next() && count < server.getMaxProcRows()) {
                        Map<String, Object> row = new HashMap<>();
                        for (int i = 0; i < cols; i++) {
                            Object v = rs.getObject(i + 1);
                            row.put(names[i], v instanceof String ? ((String) v).trim() : v);
                        }
                        rows.add(row);
                        count++;
                    }
                    result.put("rows", rows);
                    result.put("rowCount", count);
                }
            } else {
                result.put("message", "Procedure executed successfully (no result set)");
            }

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", GSON.toJson(result));
            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);
            return response;

        } catch (SQLException e) {
            logger.error("Procedure execution error: {}", e.getMessage());
            throw new RuntimeException("Procedure execution error: " + e.getMessage(), e);
        }
    }
}
