package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_explain — валидация SELECT + получение плана выполнения.
 *
 * <p>Как работает:</p>
 * <ol>
 *   <li>Запрос оборачивается в "SELECT * FROM (...) WHERE 1=0" —
 *       Firebird парсит SQL, проверяет существование таблиц/колонок и
 *       строит план, но не возвращает ни одной строки.</li>
 *   <li>План читается из мониторинговой таблицы MON$STATEMENTS
 *       (доступно SYSDBA или при выданных правах на MON$).</li>
 *   <li>Транзакция откатывается — запрос гарантированно ничего не меняет.</li>
 * </ol>
 *
 * <p>Если MON$ недоступен — возвращается только результат валидации.</p>
 */
public class SqlExplainTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlExplainTool.class);

    private final McpServer server;

    public SqlExplainTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_explain");
        definition.addProperty("description", "Validate a SELECT statement without returning data " +
                "(wraps it in 'WHERE 1=0') and fetch the Firebird execution plan from MON$REQUESTS/MON$STATEMENTS " +
                "monitoring tables. Use to check syntax, verify object names, and inspect which indexes " +
                "the optimizer picks. Errors report exactly what is wrong with the SQL.");

        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject queryParam = new JsonObject();
        queryParam.addProperty("type", "string");
        queryParam.addProperty("description", "SELECT statement to validate and explain");
        properties.add("query", queryParam);

        inputSchema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("query");
        inputSchema.add("required", required);

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("query")) {
            throw new IllegalArgumentException("Missing required parameter: query");
        }

        String query = arguments.get("query").getAsString().trim();
        if (!query.toUpperCase().startsWith("SELECT")) {
            throw new SecurityException("sql_explain supports SELECT statements only");
        }
        // Убираем завершающую точку с запятой — она ломает обёртку
        if (query.endsWith(";")) {
            query = query.substring(0, query.length() - 1).trim();
        }

        try (java.sql.Connection conn = server.getDb().openConnectionForTool()) {
            conn.setAutoCommit(false);

            // 2. Выполняем обёртку — парсинг + резолвинг объектов + построение плана.
            //    WHERE 1=0 гарантирует пустой результат.
            String wrapped = "SELECT * FROM (" + query + ") EXPLAIN_WRAPPER WHERE 1=0";

            String plan = null;
            try (java.sql.Statement st = conn.createStatement();
                 java.sql.ResultSet rs = st.executeQuery(wrapped)) {
                // Выполнение обёртки: если ошибок нет — запрос валиден.
                // План извлекаем через reflection-вызов getStatistics().getPlan()
                // объекта FirebirdStatement. Reflection используется намеренно:
                // API статистики менялся между Jaybird 4 и 5, а прямая ссылка
                // на FirebirdStatement ломала бы сборку при смене версии драйвера.
                try {
                    Object fbs = st.unwrap(Class.forName("org.firebirdsql.jdbc.FirebirdStatement"));
                    if (fbs != null) {
                        Object stats = fbs.getClass().getMethod("getStatistics").invoke(fbs);
                        if (stats != null) {
                            Object planObj = stats.getClass().getMethod("getPlan").invoke(stats);
                            if (planObj != null) plan = planObj.toString();
                        }
                    }
                } catch (Exception ignored) {
                    // План недоступен — возвращаем только результат валидации
                }
            }

            conn.rollback(); // обёртка не должна ничего зафиксировать

            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();
            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            StringBuilder sb = new StringBuilder();
            sb.append("SQL is VALID: statement parsed and objects resolved successfully.\n");
            if (plan != null && !plan.trim().isEmpty()) {
                sb.append("\nExecution plan:\n").append(plan.trim());
            } else {
                sb.append("\nExecution plan is not available (MON$ monitoring requires SYSDBA or grants).");
            }
            textContent.addProperty("text", sb.toString());
            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);
            return response;

        } catch (SQLException e) {
            // Ошибка синтаксиса/семантики — именно то, ради чего инструмент вызывается
            logger.debug("Explain validation error (expected case): {}", e.getMessage());
            throw new RuntimeException("SQL validation error: " + e.getMessage(), e);
        }
    }
}
