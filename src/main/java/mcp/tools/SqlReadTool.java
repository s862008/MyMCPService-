package mcp.tools;

import com.google.gson.*;
import mcp.McpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Map;

/**
 * Инструмент sql_query — чтение данных (только SELECT).
 *
 * <p>Пример вызова:</p>
 * <pre>{@code
 * {
 *   "name": "sql_query",
 *   "arguments": { "query": "SELECT FIRST 5 * FROM RDB$RELATIONS" }
 * }
 * }</pre>
 *
 * <p>Замечание про диалект 1: в Firebird нет LIMIT — вместо него
 * используются FIRST/SKIP (SELECT FIRST 10 ...), что и стоит указывать модели-клиенту.</p>
 */
public class SqlReadTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(SqlReadTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final McpServer server;

    public SqlReadTool(McpServer server) {
        this.server = server;
    }

    @Override
    public JsonObject getToolDefinition() {
        JsonObject definition = new JsonObject();
        definition.addProperty("name", "sql_query");
        // Описание читает LLM — оно должно объяснять ограничения и диалект
        definition.addProperty("description", "Execute a SELECT query to read data from the Firebird 3 database (SQL dialect 1). " +
                "Only SELECT statements are allowed. Note: Firebird uses FIRST/SKIP instead of LIMIT " +
                "(e.g. 'SELECT FIRST 10 * FROM TABLE').");

        // JSON Schema входных параметров — по ней клиент валидирует arguments
        JsonObject inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");

        JsonObject properties = new JsonObject();
        JsonObject queryParam = new JsonObject();
        queryParam.addProperty("type", "string");
        queryParam.addProperty("description", "SQL SELECT query to execute");
        properties.add("query", queryParam);

        inputSchema.add("properties", properties);
        inputSchema.add("required", createArray("query"));

        definition.add("inputSchema", inputSchema);
        return definition;
    }

    @Override
    public JsonObject execute(JsonObject arguments) {
        if (!arguments.has("query")) {
            throw new IllegalArgumentException("Missing required parameter: query");
        }

        String query = arguments.get("query").getAsString().trim();

        // Простейшая защита на уровне первого ключевого слова.
        // (Полноценный SQL-парсер избыточен: у сервера и так отдельный
        // инструмент sql_execute, а подключение — под учёткой с нужными правами.)
        if (!query.toUpperCase().startsWith("SELECT")) {
            throw new SecurityException("Only SELECT queries are allowed in sql_query tool. " +
                    "Use sql_execute for write operations.");
        }

        try {
            Map<String, Object> result = server.getDb().executeQuery(query);

            // Формат ответа MCP: content[] с текстовым блоком
            JsonObject response = new JsonObject();
            JsonArray content = new JsonArray();

            JsonObject textContent = new JsonObject();
            textContent.addProperty("type", "text");
            textContent.addProperty("text", GSON.toJson(result)); // columns/rows/rowCount

            content.add(textContent);
            response.add("content", content);
            response.addProperty("isError", false);

            return response;

        } catch (SQLException e) {
            // Текст ошибки Firebird полезнее скрыть за общим сообщением нельзя —
            // модель чинит SQL именно по тексту ошибки (SQLCODE/GDSC).
            logger.error("SQL query error: {}", e.getMessage());
            throw new RuntimeException("SQL error: " + e.getMessage(), e);
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
