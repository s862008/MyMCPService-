package mcp;

import com.google.gson.*;
import mcp.db.FirebirdDb;
import mcp.tools.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ядро MCP-сервера: реализует протокол JSON-RPC 2.0 поверх транспорта
 * (stdio или HTTP) и маршрутизирует вызовы к инструментам (tools).
 *
 * <p>Поддерживаемые методы протокола MCP:</p>
 * <ul>
 *   <li>{@code initialize} — рукопожатие, обмен capabilities;</li>
 *   <li>{@code notifications/initialized} — уведомление клиента (ответ не требуется);</li>
 *   <li>{@code tools/list} — список инструментов с JSON Schema параметров;</li>
 *   <li>{@code tools/call} — вызов инструмента с аргументами;</li>
 *   <li>{@code shutdown} — остановка сервера.</li>
 * </ul>
 *
 * <p>Важная особенность MCP: ошибки ВЫПОЛНЕНИЯ инструмента возвращаются
 * не как JSON-RPC error, а как успешный ответ с {@code isError: true}
 * внутри {@code result.content} — так клиент может показать текст ошибки модели.</li>
 */
public class McpServer {

    private static final Logger logger = LoggerFactory.getLogger(McpServer.class);

    /** Pretty-printing удобен для отладки; протоколу формат не мешает. */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Main.Config config;

    /** Флаг работы сервера: false — цикл stdio и keepalive SSE завершаются. */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /** Реестр инструментов: имя -> реализация. ConcurrentHashMap — потоки HTTP общие. */
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /** Единая точка доступа к Firebird (создает соединения по требованию). */
    private final FirebirdDb db;

    public McpServer(Main.Config config) {
        this.config = config;
        // Fail-fast: если БД недоступна, сервер не стартует вовсе —
        // клиент сразу увидит причину в логе вместо молчаливых ошибок инструментов.
        this.db = new FirebirdDb(config);
        registerTools();
        logger.info("MCP Server initialized with {} tools", tools.size());
    }

    /** Регистрация всех инструментов. Здесь же задаются их имена в протоколе. */
    private void registerTools() {
        // --- Чтение данных ---
        tools.put("sql_query", new SqlReadTool(this));
        // --- Разведка схемы ---
        tools.put("sql_list_tables", new SqlListTablesTool(this));
        tools.put("sql_list_procedures", new SqlListProceduresTool(this));
        tools.put("sql_describe_table", new SqlDescribeTableTool(this));
        tools.put("sql_describe_procedure", new SqlDescribeProcedureTool(this));
        // --- Валидация и план ---
        tools.put("sql_explain", new SqlExplainTool(this));
        // --- Запись данных и DDL ---
        tools.put("sql_execute", new SqlWriteTool(this));
        // --- Хранимые процедуры ---
        tools.put("sql_create_procedure", new SqlProcedureTool(this));
        tools.put("sql_execute_procedure", new SqlExecuteProcedureTool(this));
        // --- Объекты БД (обёртки DDL) ---
        tools.put("sql_create_view", new SqlCreateViewTool(this));
        tools.put("sql_create_trigger", new SqlCreateTriggerTool(this));
    }

    /**
     * Обработка одного JSON-RPC 2.0 сообщения.
     *
     * @param jsonRequest raw JSON-строка запроса от клиента
     * @return JSON-строка ответа, либо {@code null} для уведомлений (notifications),
     *         на которые протокол ответа не предусматривает
     */
    public String handleRequest(String jsonRequest) {
        JsonElement idElement = null;
        try {
            JsonObject request = JsonParser.parseString(jsonRequest).getAsJsonObject();
            String method = request.get("method").getAsString();
            // id может быть числом или строкой — сохраняем как есть (JsonElement),
            // чтобы в ответе вернуть в исходном виде.
            idElement = request.has("id") ? request.get("id") : null;
            JsonObject params = request.has("params") && request.get("params") != null
                    ? request.get("params").getAsJsonObject() : new JsonObject();

            logger.debug("Handling request: method={}, id={}", method, idElement);

            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            if (idElement != null) {
                response.add("id", idElement);
            }

            switch (method) {
                case "initialize":
                    response.add("result", handleInitialize(params));
                    break;

                case "tools/list":
                    response.add("result", handleToolsList());
                    break;

                case "notifications/initialized":
                    // Это notification (без id) — отвечаем null, транспорт ничего не пишет.
                    return null;

                case "tools/call":
                    response.add("result", handleToolCall(params));
                    break;

                case "shutdown":
                    response.add("result", JsonNull.INSTANCE);
                    break;

                default:
                    // -32601: Method not found (зарезервированный код JSON-RPC)
                    response.add("error", createError(-32601, "Method not found: " + method));
                    break;
            }

            if ("shutdown".equals(method)) {
                running.set(false);
            }

            return GSON.toJson(response);

        } catch (SecurityException e) {
            // -32603: Internal error — сюда попадают отказы безопасности (например SELECT в sql_execute)
            return GSON.toJson(createErrorResponse(idElement, -32603, "Security error: " + e.getMessage()));
        } catch (IllegalArgumentException e) {
            // -32602: Invalid params — некорректные аргументы запроса
            return GSON.toJson(createErrorResponse(idElement, -32602, "Invalid params: " + e.getMessage()));
        } catch (Exception e) {
            // -32700/-32603: ошибки парсинга и прочие внутренние сбои
            logger.error("JSON-RPC handling error", e);
            return GSON.toJson(createErrorResponse(idElement, -32603, "Internal error: " + e.getMessage()));
        }
    }

    /**
     * Обработка initialize: сообщаем клиенту версию протокола,
     * свои возможности (только tools) и метаданные сервера.
     */
    private JsonObject handleInitialize(JsonObject params) {
        JsonObject result = new JsonObject();

        // capabilities.tools — пустой объект, но сам факт наличия включает tools-поддержку
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "firebird-mcp-server");
        serverInfo.addProperty("version", "1.0.0");

        // Версия протокола MCP; клиенты (Koda/Claude) сверяют совместимость по ней
        result.addProperty("protocolVersion", "2024-11-05");
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);

        logger.info("Initialize response sent");
        return result;
    }

    /** tools/list: собираем определения всех инструментов (name + description + inputSchema). */
    private JsonObject handleToolsList() {
        JsonObject result = new JsonObject();
        JsonArray toolsArray = new JsonArray();

        for (Map.Entry<String, Tool> entry : tools.entrySet()) {
            toolsArray.add(entry.getValue().getToolDefinition());
        }

        result.add("tools", toolsArray);
        return result;
    }

    /**
     * tools/call: делегируем выполнение конкретному инструменту.
     * Любая ошибка инструмента превращается в result с isError=true —
     * так того требует спецификация MCP (текст ошибки доходит до модели).
     */
    private JsonObject handleToolCall(JsonObject params) {
        String toolName = params.get("name").getAsString();
        JsonObject arguments = params.has("arguments")
                ? params.get("arguments").getAsJsonObject() : new JsonObject();

        Tool tool = tools.get(toolName);
        if (tool == null) {
            throw new RuntimeException("Unknown tool: " + toolName);
        }

        try {
            return tool.execute(arguments);
        } catch (SecurityException e) {
            return toolError(e.getMessage());
        } catch (IllegalArgumentException e) {
            return toolError(e.getMessage());
        } catch (Exception e) {
            logger.error("Tool execution error: {}", toolName, e);
            return toolError(extractFullError(e));
        }
    }

    /**
     * Извлечение полного текста ошибки из SQLException (включая вложенные причины и SQLCODE).
     * Для Firebird это критично — основная ошибка часто лежит в cause.
     */
    private String extractFullError(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null && !msg.trim().isEmpty()) {
                sb.append(msg);
            }
            // Добавляем SQLCODE если доступен
            if (current instanceof java.sql.SQLException) {
                int sqlCode = ((java.sql.SQLException) current).getErrorCode();
                if (sqlCode != 0) {
                    sb.append("; SQLCODE=").append(sqlCode);
                }
            }
            current = current.getCause();
            if (current != null && current != e) {
                sb.append(" -> ");
            }
        }
        // Префикс: имя инструмента и тип ошибки
        return e.getClass().getSimpleName() + ": " + sb.toString();
    }

    /** Формат ошибки инструмента: content[].text + isError=true. */
    private JsonObject toolError(String message) {
        JsonObject response = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", message);
        content.add(textContent);
        response.add("content", content);
        response.addProperty("isError", true);
        return response;
    }

    private JsonObject createError(int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        return error;
    }

    /** Полноценный JSON-RPC error-ответ (для ошибок протокола, не инструментов). */
    private JsonObject createErrorResponse(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        if (id != null) {
            response.add("id", id);
        }
        response.add("error", createError(code, message));
        return response;
    }

    // ==================== Stdio транспорт ====================

    /**
     * Основной цикл stdio-транспорта.
     *
     * <p>Формат кадрирования: "line-delimited JSON" — одно сообщение = одна строка.
     * Такой формат принимает большинство MCP-клиентов (в т.ч. Koda/Claude Desktop).
     * ВАЖНО: в stdout пишется ТОЛЬКО протокольный JSON; логи идут в stderr (logback).</p>
     */
    public void startStdioLoop(InputStream stdin) {
        logger.info("Starting stdio transport loop...");
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        String line;

        try {
            // Читаем построчно до EOF (клиент закрыл stdin) или shutdown
            while (running.get() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String response = handleRequest(line);
                if (response == null) continue; // уведомления не требуют ответа

                byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
                System.out.write(responseBytes);
                System.out.write('\n'); // перевод строки заверщает сообщение
                System.out.flush();      // flush обязателен — иначе клиент зависнет в ожидании
            }
        } catch (IOException e) {
            logger.error("Stdio loop error", e);
        }

        logger.info("Stdio loop ended");
        stop();
    }

    // ==================== Управление жизненным циклом ====================

    /** Остановка сервера (вызывается shutdownHook-ом и методом shutdown). */
    public void shutdown() {
        running.set(false);
        stop();
    }

    /** Освобождение ресурсов. Соединения с FB открываются/закрываются на каждый запрос,
     *  поэтому здесь только закрываем пул-обёртку. */
    private void stop() {
        db.close();
    }

    public boolean isRunning() {
        return running.get();
    }

    /** Доступ к БД для инструментов. */
    public FirebirdDb getDb() {
        return db;
    }

    /** Лимит строк для результатов процедур (из [app] max_proc_rows). */
    public int getMaxProcRows() {
        return config.maxProcRows;
    }

    /** Лимит строк для SELECT (из [app] max_query_rows). */
    public int getMaxQueryRows() {
        return config.maxQueryRows;
    }
}
