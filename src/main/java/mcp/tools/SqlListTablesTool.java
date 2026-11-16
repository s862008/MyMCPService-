package mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
        // Схема без параметров — пустые properties обязательны по JSON Schema
        definition.add("inputSchema", new ToolSupport.Schema().build());
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
            JsonArray tables = ToolSupport.columnValues(ToolSupport.rowsOf(result), "RDB$RELATION_NAME");

            return ToolSupport.ok("Tables in database (" + tables.size() + "):\n" + tables);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "List tables error", sql, e);
        }
    }
}
