package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import mcp.SqlSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Инструмент sql_create_view — создание или замена представления.
 *
 * <p>Генерирует CREATE OR ALTER VIEW name (cols) AS select-statement.
 * CREATE OR ALTER (Firebird 3+) позволяет перезаписывать представление
 * без предварительного DROP.</p>
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * {
 *   "name": "sql_create_view",
 *   "arguments": {
 *     "name": "V_ACTIVE_USERS",
 *     "query": "SELECT ID, NAME FROM USERS WHERE ACTIVE = 1"
 *   }
 * }
 * }</pre>
 */
public class SqlCreateViewTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlCreateViewTool.class);

    private final McpServer server;

    public SqlCreateViewTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_create_view");
        definition.addProperty("description", "Create or replace a view in the Firebird 3 database " +
                "(SQL dialect 1). Uses CREATE OR ALTER, so re-running replaces the existing view. " +
                "The query must be a SELECT statement. Column aliases in the SELECT define view column names.");

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject nameParam = new JsonObject();
        nameParam.addProperty("type", "string");
        nameParam.addProperty("description", "View name (letters, digits, underscore, $)");
        properties.add("name", nameParam);

        JsonObject queryParam = new JsonObject();
        queryParam.addProperty("type", "string");
        queryParam.addProperty("description", "SELECT statement that defines the view (without trailing semicolon)");
        properties.add("query", queryParam);

        inputSchema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("name");
        required.add("query");
        inputSchema.add("required", required);

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("name")) {
            throw new IllegalArgumentException("Missing required parameter: name");
        }
        if (!arguments.has("query")) {
            throw new IllegalArgumentException("Missing required parameter: query");
        }

        String query = arguments.get("query").getAsString().trim();

        // Имя идёт в DDL без кавычек — только безопасный идентификатор
        String name = SqlSafety.safeObjectName(arguments.get("name").getAsString(), "view");

        if (!query.toUpperCase().startsWith("SELECT")) {
            throw new SecurityException("View definition must be a SELECT statement");
        }
        // Валидация SELECT-запроса для определения представления
        SqlSafety.validate(query, true);

        // Убираем завершающую точку с запятой — она ломает DDL-обёртку
        if (query.endsWith(";")) {
            query = query.substring(0, query.length() - 1).trim();
        }

        String sql = "CREATE OR ALTER VIEW " + name + " AS\n" + query;
        logger.info("Creating view: {}", name);

        try {
            server.getDb().executeUpdate(sql);

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text",
                    "View '" + name + "' created successfully.\nGenerated SQL:\n" + sql);
            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);
            return response;

        } catch (SQLException e) {
            logger.error("View creation error: {} | SQL: {}", e.getMessage(), sql);
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
