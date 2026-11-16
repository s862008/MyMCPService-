package mcp.tools;

import com.google.gson.JsonObject;
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
        definition.add("inputSchema", new ToolSupport.Schema()
                .str("query", "SQL write statement to execute")
                .required("query")
                .build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        String query = Args.requireString(arguments, "query");

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
            return ToolSupport.okJson(result); // success/rowsAffected/...

        } catch (SQLException e) {
            // Без префикса: структурированный ответ формирует McpServer (Reason + Hint).
            throw ToolSupport.wrapSql(logger, "SQL execute error", "Statement: " + query, e);
        }
    }
}
