package mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import mcp.McpServer;
import mcp.SqlSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
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
        definition.add("inputSchema", new ToolSupport.Schema()
                .str("procedure", "Procedure name (e.g. 'P_GET_USER')")
                .array("parameters", "Positional input parameters array (may be empty, may contain null)")
                .required("procedure")
                .build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        // Имя процедуры идёт в JDBC escape {call ...} — только безопасный идентификатор
        String proc = Args.requireObjectName(arguments, "procedure");

        // Входные параметры: JSON-массив -> Object[]
        List<Object> params = new ArrayList<>();
        if (arguments.has("parameters") && arguments.get("parameters").isJsonArray()) {
            for (JsonElement el : arguments.get("parameters").getAsJsonArray()) {
                params.add(Args.toSqlValue(el));
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

            // Таймаут + лимит строк результата — защита от «вечной» процедуры
            // и гигантского RETURNING-набора.
            server.getDb().applyLimits(call, server.getMaxProcRows());

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

            return ToolSupport.okJson(result);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "Procedure execution error", "Call: " + sql, e);
        }
    }
}
