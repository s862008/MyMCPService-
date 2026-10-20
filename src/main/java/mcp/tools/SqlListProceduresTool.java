package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_list_procedures — список хранимых процедур.
 *
 * <p>Источник — системная таблица RDB$PROCEDURES (содержит только
 * пользовательские процедуры, системных в ней нет).</p>
 *
 * <p>Параметры: отсутствуют.</p>
 */
public class SqlListProceduresTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlListProceduresTool.class);

    private final McpServer server;

    public SqlListProceduresTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_list_procedures");
        definition.addProperty("description", "List all stored procedures in the Firebird database " +
                "(reads RDB$PROCEDURES). No parameters required.");

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        inputSchema.add("properties", new JsonObject());

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        String sql = "SELECT RDB$PROCEDURE_NAME FROM RDB$PROCEDURES ORDER BY RDB$PROCEDURE_NAME";

        try {
            Map<String, Object> result = server.getDb().executeQuery(sql);

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> rows =
                    (java.util.List<Map<String, Object>>) result.get("rows");

            JsonArray procedures = new JsonArray();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object name = row.get("RDB$PROCEDURE_NAME");
                    if (name != null) {
                        // Trim против CHAR-padding имён в диалекте 1
                        procedures.add(name.toString().trim());
                    }
                }
            }

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();

            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text",
                    "Stored procedures in database (" + procedures.size() + "):\n" + procedures.toString());

            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);

            return response;

        } catch (SQLException e) {
            logger.error("List procedures error: {}", e.getMessage());
            throw new RuntimeException("Error listing procedures: " + e.getMessage(), e);
        }
    }
}
