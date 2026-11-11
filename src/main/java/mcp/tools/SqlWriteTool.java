package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import mcp.SqlSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_execute — запись и DDL.
 *
 * <p>Примеры вызовов:</p>
 * <pre>{@code
 * { "name": "sql_execute", "arguments": { "query": "INSERT INTO T1 (ID) VALUES (1)" } }
 * { "name": "sql_execute", "arguments": { "query": "CREATE TABLE T1 (ID INTEGER)" } }
 * { "name": "sql_execute", "arguments": { "query": "EXECUTE PROCEDURE P_CLEAR" } }
 * }</pre>
 *
 * <p>Важно: изменения фиксируются коммитом сразу после выполнения.
 * Для DDL в Firebird откат после коммита невозможен в принципе.</p>
 */
public class SqlWriteTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlWriteTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final McpServer server;

    public SqlWriteTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_execute");
        definition.addProperty("description", "Execute a write SQL statement on the Firebird 3 database (SQL dialect 1): " +
                "INSERT, UPDATE, DELETE, MERGE; DDL: CREATE/ALTER/DROP for TABLE, INDEX, VIEW, " +
                "TRIGGER, PROCEDURE, FUNCTION, PACKAGE, SEQUENCE, GENERATOR; " +
                "GRANT/REVOKE; EXECUTE PROCEDURE/BLOCK. " +
                "Multi-statement DDL such as CREATE OR ALTER TRIGGER ... AS BEGIN ... END is supported " +
                "(pass the whole statement, it is executed as-is). " +
                "This tool is also the universal fallback when a specialized tool is unavailable. " +
                "Changes are committed immediately. " +
                "IMPORTANT: DDL operations cannot be rolled back.");

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject queryParam = new JsonObject();
        queryParam.addProperty("type", "string");
        queryParam.addProperty("description", "SQL write statement to execute");
        properties.add("query", queryParam);

        inputSchema.add("properties", properties);
        inputSchema.add("required", createArray("query"));

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("query")) {
            throw new IllegalArgumentException("Missing required parameter: query");
        }

        String query = arguments.get("query").getAsString().trim();

        // SELECT отправляем в sql_query — разделение read/write делает аудит понятным.
        // Сообщение адресовано ИИ-агенту: он должен сразу понять, какой инструмент звать.
        String head = query.toUpperCase();
        if (head.startsWith("SELECT") || head.startsWith("WITH")) {
            throw new SecurityException(
                    "sql_execute accepts write statements only, but received a read query. " +
                    "Call the sql_query tool instead with the same query text. " +
                    "sql_execute is for INSERT, UPDATE, DELETE, MERGE, CREATE, ALTER, DROP, EXECUTE.");
        }

        // Единый валидатор: множественные инструкции, комментарии, инъекции,
        // UPDATE/DELETE без WHERE (защита от mass-update).
        SqlSafety.validateStatement(query);

        try {
            Map<String, Object> result = server.getDb().executeUpdate(query);

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();

            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", GSON.toJson(result)); // success/rowsAffected/...

            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);

            return response;

        } catch (SQLException e) {
            logger.error("SQL execute error: {} | Statement: {}", e.getMessage(), query);
            throw new RuntimeException("SQL error: " + e.getMessage(), e);
        }
    }

    private JsonArray createArray(String... items) {
        JsonArray array = new JsonArray();
        for (String item : items) {
            array.add(item);
        }
        return array;
    }
}
