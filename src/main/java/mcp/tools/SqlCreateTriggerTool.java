package mcp.tools;

import com.google.gson.JsonObject;
import mcp.McpServer;
import mcp.SqlSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Инструмент sql_create_trigger — создание триггера на таблицу.
 *
 * <p>Генерирует CREATE OR ALTER TRIGGER для указанного события
 * (BEFORE/AFTER, INSERT/UPDATE/DELETE). В диалекте 1 доступны
 * корпоративные переменные NEW/OLD.</p>
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * {
 *   "name": "sql_create_trigger",
 *   "arguments": {
 *     "name": "TR_USERS_BI",
 *     "table": "USERS",
 *     "event": "INSERT",
 *     "timing": "BEFORE",
 *     "body": "NEW.ID = GEN_ID(GEN_USERS_ID, 1)"
 *   }
 * }
 * }</pre>
 */
public class SqlCreateTriggerTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlCreateTriggerTool.class);

    /** Разрешённые времена срабатывания. */
    private static final String[] TIMINGS = {"BEFORE", "AFTER"};
    /** Разрешённые события. */
    private static final String[] EVENTS = {"INSERT", "UPDATE", "DELETE"};

    private final McpServer server;

    public SqlCreateTriggerTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_create_trigger");
        definition.addProperty("description", "Create or replace a table trigger in the Firebird 3 database " +
                "(SQL dialect 1). Uses CREATE OR ALTER. In the body use NEW.column / OLD.column to access " +
                "row values. For BEFORE INSERT generation of IDs use GEN_ID(generator, 1). " +
                "Body should contain statements WITHOUT BEGIN/END.");
        definition.add("inputSchema", new ToolSupport.Schema()
                .str("name", "Trigger name")
                .str("table", "Target table name")
                .enumStr("timing", "Trigger timing: BEFORE or AFTER", TIMINGS)
                .enumStr("event", "Trigger event: INSERT, UPDATE or DELETE", EVENTS)
                .str("body", "Trigger body statements (without BEGIN/END)")
                .required("name", "table", "event", "body")
                .build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Args.require(arguments, "name", "table", "event", "body");

        String name = Args.requireObjectName(arguments, "name");
        String table = Args.requireObjectName(arguments, "table");
        String event = Args.requireString(arguments, "event").toUpperCase();
        String timing = arguments.has("timing")
                ? Args.requireString(arguments, "timing").toUpperCase() : "AFTER";
        String body = Args.requireString(arguments, "body");

        // Тело — PSQL-код внутри AS BEGIN ... END: множественные ';' здесь норма,
        // поэтому validatePsqlBody (без проверки единственности инструкции).
        SqlSafety.validatePsqlBody(body, "trigger");

        // Жёсткая проверка перечислений — иначе DDL соберётся некорректно
        boolean timingOk = false;
        for (String t : TIMINGS) if (t.equals(timing)) { timingOk = true; break; }
        if (!timingOk) throw new IllegalArgumentException("Invalid timing: " + timing + " (use BEFORE or AFTER)");

        boolean eventOk = false;
        for (String e : EVENTS) if (e.equals(event)) { eventOk = true; break; }
        if (!eventOk) throw new IllegalArgumentException("Invalid event: " + event + " (use INSERT, UPDATE or DELETE)");

        // Сборка DDL триггера
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE OR ALTER TRIGGER ").append(name).append(" FOR ").append(table).append("\n")
           .append(timing).append(" ").append(event).append("\n")
           .append("AS\nBEGIN\n").append(body);
        if (!body.endsWith(";")) sql.append(";");
        sql.append("\nEND");

        logger.info("Creating trigger: {} ON {} ({})", name, table, timing + " " + event);

        try {
            server.getDb().executeUpdate(sql.toString());

            return ToolSupport.ok("Trigger '" + name + "' created successfully on " + table +
                    ".\nGenerated SQL:\n" + sql);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "Trigger creation error", "SQL: " + sql, e);
        }
    }
}
