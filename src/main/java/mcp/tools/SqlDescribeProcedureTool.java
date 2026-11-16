package mcp.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import mcp.McpServer;
import mcp.db.FbTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_describe_procedure — параметры хранимой процедуры.
 *
 * <p>Показывает входные (INPUT) и выходные (OUTPUT) параметры:
 * имена, типы, порядок. Нужен перед sql_execute_procedure, чтобы
 * передать параметры в правильном порядке и типе.</p>
 *
 * <p>Источник — RDB$PROCEDURE_PARAMETERS + RDB$FIELDS.
 * RDB$PARAMETER_TYPE: 0 = входной, 1 = выходной.
 * CASE-шаблон типов общий с describe_table (см. {@link FbTypes}).</p>
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * { "name": "sql_describe_procedure", "arguments": { "procedure": "P_GET_USER" } }
 * }</pre>
 */
public class SqlDescribeProcedureTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlDescribeProcedureTool.class);

    private final McpServer server;

    public SqlDescribeProcedureTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_describe_procedure");
        definition.addProperty("description", "Describe a stored procedure: input and output parameters " +
                "with names, types and positions. Use this BEFORE sql_execute_procedure to know what " +
                "arguments to pass. Procedure names are UPPER_CASE by default.");
        definition.add("inputSchema", new ToolSupport.Schema()
                .str("procedure", "Procedure name (e.g. 'P_GET_USER')")
                .required("procedure")
                .build());
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        // Имя подставляется в SQL-литерал конкатенацией — обязательна строгая валидация
        String proc = Args.requireObjectName(arguments, "procedure");

        String sql =
                "SELECT " +
                "  pp.RDB$PARAMETER_NAME AS P_NAME, " +
                "  pp.RDB$PARAMETER_TYPE AS P_TYPE, " +
                "  " + FbTypes.TYPE_CASE_SQL + " AS P_DTYPE, " +
                "  f.RDB$FIELD_LENGTH AS P_LEN, " +
                "  CASE WHEN f.RDB$FIELD_TYPE = 27 AND f.RDB$FIELD_SCALE != 0 THEN " +
                "       COALESCE(NULLIF(f.RDB$FIELD_PRECISION, 0), " +
                "                CASE f.RDB$FIELD_LENGTH WHEN 2 THEN 4 WHEN 4 THEN 9 ELSE 18 END) " +
                "       ELSE f.RDB$FIELD_PRECISION END AS P_PREC, " +
                "  f.RDB$FIELD_SCALE AS P_SCALE, " +
                "  pp.RDB$PARAMETER_NUMBER AS P_NUM " +
                "FROM RDB$PROCEDURE_PARAMETERS pp " +
                "JOIN RDB$FIELDS f ON pp.RDB$FIELD_SOURCE = f.RDB$FIELD_NAME " +
                "WHERE pp.RDB$PROCEDURE_NAME = '" + proc + "' " +
                "ORDER BY pp.RDB$PARAMETER_TYPE, pp.RDB$PARAMETER_NUMBER";

        try {
            Map<String, Object> result = server.getDb().executeQuery(sql);
            java.util.List<Map<String, Object>> rows = ToolSupport.rowsOf(result);

            if (rows == null || rows.isEmpty()) {
                throw new RuntimeException("Procedure not found: " + proc +
                        ". Use sql_list_procedures to see available procedures.");
            }

            JsonArray inputs = new JsonArray();
            JsonArray outputs = new JsonArray();
            StringBuilder sb = new StringBuilder("Procedure " + proc + ":\n");

            for (Map<String, Object> row : rows) {
                String name = ToolSupport.str(row.get("P_NAME"));
                String type = FbTypes.renderType(row, "P_DTYPE", "P_LEN", "P_PREC", "P_SCALE");
                boolean isInput = ((Number) row.get("P_TYPE")).intValue() == 0;

                JsonObject p = ToolSupport.obj().put("name", name).put("type", type).build();
                (isInput ? inputs : outputs).add(p);

                sb.append(isInput ? "  IN  " : "  OUT ").append(name).append("  ").append(type).append("\n");
            }

            JsonObject json = ToolSupport.obj().put("input", inputs).put("output", outputs).build();
            return ToolSupport.ok(sb + "\nJSON:\n" + json);

        } catch (SQLException e) {
            throw ToolSupport.wrapSql(logger, "Describe procedure error", "Procedure: " + proc, e);
        }
    }
}
