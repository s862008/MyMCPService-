package mcp.tools;

import com.google.gson.JsonObject;
import mcp.McpServer;
import mcp.SqlSafety;
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
        definition.add("inputSchema", new ToolSupport.Schema()
                .str("name", "Procedure name (letters, digits, underscore, $)")
                .str("parameters", "Input parameters declaration, e.g. 'IN_ID INTEGER, IN_NAME VARCHAR(50)'. Optional.")
                .str("returns", "RETURNS declaration, e.g. 'OUT_ID INTEGER'. Optional.")
                .str("body", "Procedure body SQL statements (without BEGIN/END)")
                .required("name", "body")
                .build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        Args.require(arguments, "name", "body");

        String name = Args.requireObjectName(arguments, "name");
        String parameters = SqlSafety.safeParamDeclaration(
                Args.optionalString(arguments, "parameters"), "parameter");
        String returns = SqlSafety.safeParamDeclaration(
                Args.optionalString(arguments, "returns"), "RETURNS");
        String body = Args.requireString(arguments, "body");

        // Тело идёт внутрь AS BEGIN ... END: множественные ';' допустимы,
        // но комментарии, ?-параметры и паттерны чужих СУБД запрещены.
        SqlSafety.validatePsqlBody(body, "procedure");

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

            // Возвращаем сгенерированный SQL — модель сможет свериться при ошибке
            return ToolSupport.ok("Procedure '" + name + "' created successfully.\nGenerated SQL:\n" + sql);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "Procedure creation error", "SQL: " + sql, e);
        }
    }
}
