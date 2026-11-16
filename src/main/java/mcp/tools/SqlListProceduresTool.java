package mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
        definition.add("inputSchema", new ToolSupport.Schema().build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        String sql = "SELECT RDB$PROCEDURE_NAME FROM RDB$PROCEDURES ORDER BY RDB$PROCEDURE_NAME";

        try {
            Map<String, Object> result = server.getDb().executeQuery(sql);
            JsonArray procedures = ToolSupport.columnValues(ToolSupport.rowsOf(result), "RDB$PROCEDURE_NAME");

            return ToolSupport.ok("Stored procedures in database (" + procedures.size() + "):\n" + procedures);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "List procedures error", sql, e);
        }
    }
}
