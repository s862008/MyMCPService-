package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
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

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        JsonObject nameParam = new JsonObject();
        nameParam.addProperty("type", "string");
        nameParam.addProperty("description", "Trigger name");
        properties.add("name", nameParam);

        JsonObject tableParam = new JsonObject();
        tableParam.addProperty("type", "string");
        tableParam.addProperty("description", "Target table name");
        properties.add("table", tableParam);

        JsonObject timingParam = new JsonObject();
        timingParam.addProperty("type", "string");
        timingParam.addProperty("description", "Trigger timing: BEFORE or AFTER");
        JsonArray timingEnum = new JsonArray();
        for (String t : TIMINGS) timingEnum.add(t);
        timingParam.add("enum", timingEnum);
        properties.add("timing", timingParam);

        JsonObject eventParam = new JsonObject();
        eventParam.addProperty("type", "string");
        eventParam.addProperty("description", "Trigger event: INSERT, UPDATE or DELETE");
        JsonArray eventEnum = new JsonArray();
        for (String e : EVENTS) eventEnum.add(e);
        eventParam.add("enum", eventEnum);
        properties.add("event", eventParam);

        JsonObject bodyParam = new JsonObject();
        bodyParam.addProperty("type", "string");
        bodyParam.addProperty("description", "Trigger body statements (without BEGIN/END)");
        properties.add("body", bodyParam);

        inputSchema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("name");
        required.add("table");
        required.add("event");
        required.add("body");
        inputSchema.add("required", required);

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("name")) throw new IllegalArgumentException("Missing required parameter: name");
        if (!arguments.has("table")) throw new IllegalArgumentException("Missing required parameter: table");
        if (!arguments.has("event")) throw new IllegalArgumentException("Missing required parameter: event");
        if (!arguments.has("body")) throw new IllegalArgumentException("Missing required parameter: body");

        String name = arguments.get("name").getAsString().trim();
        String table = arguments.get("table").getAsString().trim();
        String event = arguments.get("event").getAsString().trim().toUpperCase();
        String timing = arguments.has("timing")
                ? arguments.get("timing").getAsString().trim().toUpperCase() : "AFTER";
        String body = arguments.get("body").getAsString().trim();

        // Имена идут в DDL без кавычек — только безопасные идентификаторы
        if (!name.matches("^[A-Za-z_][A-Za-z0-9_$]*$")) {
            throw new IllegalArgumentException("Invalid trigger name: " + name);
        }
        if (!table.matches("^[A-Za-z_][A-Za-z0-9_$]*$")) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }

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

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text",
                    "Trigger '" + name + "' created successfully on " + table + ".\nGenerated SQL:\n" + sql);
            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);
            return response;

        } catch (SQLException e) {
            logger.error("Trigger creation error: {}", e.getMessage());
            throw new RuntimeException("Trigger creation error: " + e.getMessage(), e);
        }
    }
}
