package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_list_tables — список пользовательских таблиц.
 *
 * <p>Источник — системная таблица RDB$RELATIONS:
 * RDB$SYSTEM_FLAG = 0 (или NULL) означает пользовательскую таблицу,
 * системные таблицы Firebird имеют флаг = 1 и отфильтровываются.</p>
 *
 * <p>Параметры: отсутствуют.</p>
 */
public class SqlListTablesTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlListTablesTool.class);

    private final McpServer server;

    public SqlListTablesTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_list_tables");
        definition.addProperty("description", "List all user tables in the Firebird database " +
                "(reads RDB$RELATIONS, system tables are excluded). No parameters required.");

        // Схема без параметров — пустые properties обязателны по JSON Schema
        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        inputSchema.add("properties", new JsonObject());

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        // RDB$RELATIONS содержит и таблицы, и представления (VIEW).
        // Если нужны только таблицы — добавьте: AND RDB$VIEW_BLR IS NULL
        String sql = "SELECT RDB$RELATION_NAME FROM RDB$RELATIONS " +
                "WHERE RDB$SYSTEM_FLAG IS NULL OR RDB$SYSTEM_FLAG = 0 " +
                "ORDER BY RDB$RELATION_NAME";

        try {
            Map<String, Object> result = server.getDb().executeQuery(sql);

            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> rows =
                    (java.util.List<Map<String, Object>>) result.get("rows");

            JsonArray tables = new JsonArray();
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    Object name = row.get("RDB$RELATION_NAME");
                    if (name != null) {
                        // Имена в диалекте 1 padding'ятся пробелами до ширины CHAR поля
                        tables.add(name.toString().trim());
                    }
                }
            }

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();

            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text",
                    "Tables in database (" + tables.size() + "):\n" + tables.toString());

            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);

            return response;

        } catch (SQLException e) {
            logger.error("List tables error: {}", e.getMessage());
            throw new RuntimeException("Error listing tables: " + e.getMessage(), e);
        }
    }
}
