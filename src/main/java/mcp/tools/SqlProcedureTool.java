package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

/**
 * Инструмент sql_create_procedure — удобная обёртка над CREATE PROCEDURE.
 *
 * <p>Генерирует DDL вида:</p>
 * <pre>
 * CREATE OR ALTER PROCEDURE P_NAME (IN_ID INTEGER)
 *  RETURNS (OUT_NAME VARCHAR(50))
 * AS
 * BEGIN
 *   &lt;body&gt;;
 * END
 * </pre>
 *
 * <p>CREATE OR ALTER (доступен в Firebird 3) позволяет перезаписывать
 * процедуру без предварительного DROP — повторный вызов не упадёт с "already exists".</p>
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * {
 *   "name": "sql_create_procedure",
 *   "arguments": {
 *     "name": "P_GET_COUNT",
 *     "returns": "CNT INTEGER",
 *     "body": "SELECT COUNT(*) FROM MY_TABLE INTO :CNT"
 *   }
 * }
 * }</pre>
 */
public class SqlProcedureTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlProcedureTool.class);

    private final McpServer server;

    public SqlProcedureTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_create_procedure");
        definition.addProperty("description", "Create (or replace) a stored procedure in the Firebird 3 database " +
                "(SQL dialect 1). Uses CREATE OR ALTER, so re-running replaces the existing procedure. " +
                "Body should contain SQL statements WITHOUT the surrounding BEGIN/END.");

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();

        // Имя процедуры
        JsonObject nameParam = new JsonObject();
        nameParam.addProperty("type", "string");
        nameParam.addProperty("description", "Procedure name (letters, digits, underscore, $)");
        properties.add("name", nameParam);

        // Входные параметры — строка объявления целиком
        JsonObject paramsParam = new JsonObject();
        paramsParam.addProperty("type", "string");
        paramsParam.addProperty("description",
                "Input parameters declaration, e.g. 'IN_ID INTEGER, IN_NAME VARCHAR(50)'. Optional.");
        properties.add("parameters", paramsParam);

        // Выходные параметры (для возвращающих процедур)
        JsonObject returnsParam = new JsonObject();
        returnsParam.addProperty("type", "string");
        returnsParam.addProperty("description",
                "RETURNS declaration, e.g. 'OUT_ID INTEGER'. Optional.");
        properties.add("returns", returnsParam);

        // Тело — обязательно
        JsonObject bodyParam = new JsonObject();
        bodyParam.addProperty("type", "string");
        bodyParam.addProperty("description", "Procedure body SQL statements (without BEGIN/END)");
        properties.add("body", bodyParam);

        inputSchema.add("properties", properties);
        inputSchema.add("required", createArray("name", "body"));

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("name")) {
            throw new IllegalArgumentException("Missing required parameter: name");
        }
        if (!arguments.has("body")) {
            throw new IllegalArgumentException("Missing required parameter: body");
        }

        String name = arguments.get("name").getAsString().trim();
        String body = arguments.get("body").getAsString().trim();
        String parameters = arguments.has("parameters") ? arguments.get("parameters").getAsString().trim() : "";
        String returns = arguments.has("returns") ? arguments.get("returns").getAsString().trim() : "";

        // Имя идёт в DDL без кавычек — разрешаем только безопасный идентификатор,
        // чтобы исключить SQL-инъекцию через поле name.
        if (!name.matches("^[A-Za-z_][A-Za-z0-9_$]*$")) {
            throw new IllegalArgumentException("Invalid procedure name: " + name);
        }

        // Сборка DDL по частям
        StringBuilder sql = new StringBuilder();
        sql.append("CREATE OR ALTER PROCEDURE ").append(name);

        if (!parameters.isEmpty()) {
            sql.append(" (").append(parameters).append(")");
        }

        if (!returns.isEmpty()) {
            sql.append(" RETURNS (").append(returns).append(")");
        }

        // Тело: каждая инструкция должна заканчиваться ';' — доразмечаем при необходимости
        sql.append("\nAS\nBEGIN\n").append(body);
        if (!body.endsWith(";")) {
            sql.append(";");
        }
        sql.append("\nEND");

        logger.info("Creating procedure: {}", name);

        try {
            server.getDb().executeUpdate(sql.toString());

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();

            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            // Возвращаем сгенерированный SQL — модель сможет свериться при ошибке
            textContent.addProperty("text",
                    "Procedure '" + name + "' created successfully.\nGenerated SQL:\n" + sql);

            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);

            return response;

        } catch (SQLException e) {
            logger.error("Procedure creation error: {}", e.getMessage());
            throw new RuntimeException("Procedure creation error: " + e.getMessage(), e);
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
