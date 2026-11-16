package mcp.tools;

import com.google.gson.JsonObject;
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
        definition.add("inputSchema", new ToolSupport.Schema()
                .str("name", "View name (letters, digits, underscore, $)")
                .str("query", "SELECT statement that defines the view (without trailing semicolon)")
                .required("name", "query")
                .build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Args.require(arguments, "name", "query");

        String query = Args.requireString(arguments, "query");

        // Имя идёт в DDL без кавычек — только безопасный идентификатор
        String name = Args.requireObjectName(arguments, "name");

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

            return ToolSupport.ok("View '" + name + "' created successfully.\nGenerated SQL:\n" + sql);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "View creation error", "SQL: " + sql, e);
        }
    }
}
