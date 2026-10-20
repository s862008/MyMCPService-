package mcp;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * HTTP-транспорт сервера: Streamable HTTP + SSE.
 *
 * <p>Реализован на встроенном в JDK {@code com.sun.net.httpserver.HttpServer},
 * поэтому не требует ни Jetty, ни Spring — достаточно Java 11.</p>
 *
 * <p>Эндпоинты берутся из конфигурации (mcp-config.ini, секция [http]):
 * POST {@code http.mcp_path} (по умолчанию /mcp) — Streamable HTTP;
 * GET {@code http.sse_path} (/sse) — SSE;
 * GET {@code http.health_path} (/health) — проверка живости.</p>
 *
 * <p>CORS origin задаётся параметром {@code cors_origins}
 * (по умолчанию * — для локальной разработки).</p>
 */
public class HttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HttpServer.class);

    private final McpServer mcpServer;
    private final Main.Config config;
    private com.sun.net.httpserver.HttpServer server;

    public HttpServer(McpServer mcpServer, Main.Config config) {
        this.mcpServer = mcpServer;
        this.config = config;
    }

    /** Старт HTTP-сервера и регистрация обработчиков эндпоинтов из конфига. */
    public void start() {
        try {
            server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(config.httpHost, config.httpPort), 0);

            // Пути эндпоинтов настраиваются в mcp-config.ini ([http] mcp_path/sse_path/health_path)
            server.createContext(config.httpMcpPath, this::handleMcp);
            server.createContext(config.httpSsePath, this::handleSse);
            server.createContext(config.httpHealthPath, this::handleHealth);

            // Пул потоков из конфига: SSE-соединения занимают поток надолго (keepalive-цикл)
            server.setExecutor(Executors.newFixedThreadPool(config.httpThreadPool));

            server.start();
            logger.info("HTTP server started on {}:{}", config.httpHost, config.httpPort);
        } catch (IOException e) {
            logger.error("Failed to start HTTP server", e);
            throw new RuntimeException("Failed to start HTTP server", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0); // 0 — немедленная остановка, без ожидания обработки
            logger.info("HTTP server stopped");
        }
    }

    /** GET /health — {"status":"ok"} для проверки живости. */
    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1); // 405 без тела
            return;
        }
        sendResponse(exchange, 200, "{\"status\":\"ok\"}", "application/json");
    }

    /** POST /mcp — приём JSON-RPC запроса, ответ результатом McpServer.handleRequest. */
    private void handleMcp(HttpExchange exchange) throws IOException {
        // CORS preflight от браузеров
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            String error = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32600,\"message\":\"Only POST is allowed\"}}";
            sendResponse(exchange, 405, error, "application/json");
            return;
        }

        String body = readBody(exchange);
        logger.debug("Received MCP request: {}", body);

        // Вся логика протокола — в McpServer; транспорт только читает/пишет HTTP
        String response = mcpServer.handleRequest(body);

        if (response == null) {
            // Notification без ответа — 204 No Content
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        sendResponse(exchange, 200, response, "application/json");
    }

    /**
     * GET /sse — установка SSE-соединения.
     * Поток удерживается в занятом потоке пула и каждые 15 сек шлёт keepalive;
     * обрыв соединения обнаруживается через PrintWriter.checkError().
     */
    private void handleSse(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        // Стандартный набор SSE-заголовков
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        addCorsHeaders(exchange);

        exchange.sendResponseHeaders(200, 0); // 0 = chunked, длина ответа неизвестна

        OutputStream os = exchange.getResponseBody();
        PrintWriter out = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

        String sessionId = UUID.randomUUID().toString();
        try {
            // Сообщаем клиенту, куда слать JSON-RPC сообщения
            out.write("event: endpoint\ndata: " + config.httpMcpPath + "?sessionId=" + sessionId + "\n\n");
            out.flush();

            // Цикл keepalive: держим соединение живым, пока работает сервер
            while (mcpServer.isRunning()) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                out.write("event: keepalive\ndata: \n\n");
                out.flush();
                // checkError() == true, если запись не удалась — клиент отвалился
                if (out.checkError()) {
                    logger.debug("SSE client disconnected: {}", sessionId);
                    break;
                }
            }
        } catch (Exception e) {
            logger.debug("SSE connection closed: {}", e.getMessage());
        } finally {
            try { out.close(); } catch (Exception ignored) {}
            try { os.close(); } catch (Exception ignored) {}
        }
    }

    /** Полное чтение тела запроса в строку (UTF-8). */
    private String readBody(HttpExchange exchange) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }

    /** Отправка полного HTTP-ответа с заданным кодом, телом и Content-Type. */
    private void sendResponse(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        addCorsHeaders(exchange);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** CORS: origin берётся из конфига (cors_origins). По умолчанию * — для разработки. */
    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", config.corsOrigins);
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }
}
