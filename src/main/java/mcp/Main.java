package mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Точка входа MCP-сервера для Firebird 3.
 *
 * <p>Сервер поддерживает два транспорта (выбирается в mcp-config.ini, секция [server]):</p>
 * <ul>
 *   <li><b>stdio</b> (по умолчанию) — JSON-RPC 2.0 через stdin/stdout.
 *       Используется при запуске сервера как дочернего процесса MCP-клиентом (Koda, Claude Desktop и т.п.).</li>
 *   <li><b>http</b> — сетевой режим:
 *       POST /mcp (Streamable HTTP) и GET /sse (Server-Sent Events)
 *       на встроенном com.sun.net.httpserver (доступен в JDK 11 без внешних зависимостей).</li>
 * </ul>
 *
 * <p>Источники конфигурации (по возрастанию приоритета):</p>
 * <ol>
 *   <li>Значения по умолчанию в коде (Config);</li>
 *   <li><b>mcp-config.ini</b> — основной пользовательский конфиг
 *       (рядом с jar; путь можно задать через -Dconfig.file=...);</li>
 *   <li>application.properties (classpath / внешний) — обратная совместимость;</li>
 *   <li>Переменные окружения DB_HOST, DB_PORT, DB_PATH, DB_USER, DB_PASSWORD,
 *       TRANSPORT, HTTP_PORT, HTTP_HOST.</li>
 * </ol>
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /** Имя и системное свойство для основного INI-конфига. */
    private static final String DEFAULT_INI = "mcp-config.ini";
    private static final String INI_PROPERTY = "config.file";

    public static void main(String[] args) {
        // Логи пишем в stderr (настроено в logback.xml), чтобы не засорять stdout,
        // по которому в stdio-режиме идёт протокольный трафик JSON-RPC.
        logger.info("Firebird MCP Server starting...");

        // 1. Загружаем конфигурацию: INI + properties + env
        Config config = loadConfig();

        // 2. Создаём ядро сервера: подключение к БД проверяется сразу,
        //    при неудаче — падаем с внятной ошибкой (fail-fast).
        McpServer server = new McpServer(config);

        // 3. Регистрируем graceful shutdown на Ctrl+C / kill:
        //    закрываем соединения с БД и HTTP-сервер.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received, stopping server...");
            server.shutdown();
        }));

        // 4. Запускаем выбранный транспорт
        if ("http".equals(config.transport)) {
            startHttpTransport(server, config);
        } else {
            startStdioTransport(server);
        }
    }

    /**
     * Загрузка конфигурации из всех источников.
     * Более поздние источники переопределяют более ранние.
     */
    private static Config loadConfig() {
        Config config = new Config();

        // --- Источник 1: mcp-config.ini (основной конфиг) ---
        // Ищем: -Dconfig.file=... → ./mcp-config.ini → рядом с jar
        loadIniConfig(config);

        // --- Источник 2: application.properties внутри jar (classpath) ---
        try (java.io.InputStream input = Main.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                java.util.Properties props = new java.util.Properties();
                props.load(input);
                applyProperties(config, props);
            }
        } catch (Exception e) {
            logger.error("Failed to load bundled config: {}", e.getMessage());
        }

        // --- Источник 3: внешний application.properties рядом с jar ---
        java.io.File external = new java.io.File("application.properties");
        if (external.exists()) {
            try (java.io.InputStream input = new java.io.FileInputStream(external)) {
                java.util.Properties props = new java.util.Properties();
                props.load(input);
                applyProperties(config, props);
                logger.info("External application.properties loaded from {}", external.getAbsolutePath());
            } catch (Exception e) {
                logger.error("Failed to load external config: {}", e.getMessage());
            }
        }

        // --- Источник 4: переменные окружения (высший приоритет) ---
        // Позволяют задавать креды без хранения пароля в файле.
        String envHost = System.getenv("DB_HOST");
        if (envHost != null) config.dbHost = envHost;
        String envPort = System.getenv("DB_PORT");
        if (envPort != null) config.dbPort = parseIntSafe(envPort, config.dbPort);
        String envPath = System.getenv("DB_PATH");
        if (envPath != null) config.dbPath = envPath;
        String envUser = System.getenv("DB_USER");
        if (envUser != null) config.dbUser = envUser;
        String envPassword = System.getenv("DB_PASSWORD");
        if (envPassword != null) config.dbPassword = envPassword;
        String envTransport = System.getenv("TRANSPORT");
        if (envTransport != null) config.transport = envTransport.trim().toLowerCase();
        String envHttpPort = System.getenv("HTTP_PORT");
        if (envHttpPort != null) config.httpPort = parseIntSafe(envHttpPort, config.httpPort);
        String envHttpHost = System.getenv("HTTP_HOST");
        if (envHttpHost != null) config.httpHost = envHttpHost;

        // Пароль в лог не пишем — только параметры подключения
        logger.info("Config loaded: transport={}, db={}:{}/{}, user={}",
                config.transport, config.dbHost, config.dbPort, config.dbPath, config.dbUser);
        return config;
    }

    /**
     * Чтение mcp-config.ini. Порядок поиска файла:
     * 1) -Dconfig.file=<путь>; 2) ./mcp-config.ini; 3) каталог рядом с jar.
     */
    private static void loadIniConfig(Config config) {
        String iniPath = System.getProperty(INI_PROPERTY, DEFAULT_INI);

        java.io.File ini = new java.io.File(iniPath);
        if (!ini.exists()) {
            // Попытка: каталог, где лежит jar (удобно при запуске из другого каталога)
            try {
                java.io.File jarDir = new java.io.File(
                        Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
                java.io.File candidate = new java.io.File(jarDir, DEFAULT_INI);
                if (candidate.exists()) ini = candidate;
            } catch (Exception ignored) {
                // в IDE getProtectionDomain может не дать файл — не критично
            }
        }

        if (!ini.exists()) {
            logger.info("INI config '{}' not found, using properties/env/defaults", iniPath);
            return;
        }

        IniReader ini1 = IniReader.load(ini.getAbsolutePath());

        // [server] — режим работы
        config.transport = ini1.get("server", "transport", config.transport).trim().toLowerCase();

        // [database] — подключение к Firebird
        config.dbHost     = ini1.get("database", "host", config.dbHost);
        config.dbPort     = ini1.getInt("database", "port", config.dbPort);
        config.dbPath     = ini1.get("database", "path", config.dbPath);
        config.dbUser     = ini1.get("database", "user", config.dbUser);
        config.dbPassword = ini1.get("database", "password", config.dbPassword);
        config.dbDialect  = ini1.getInt("database", "dialect", config.dbDialect);
        config.dbCharset  = ini1.get("database", "charset", config.dbCharset);

        // [http] — сетевой транспорт
        config.httpHost      = ini1.get("http", "host", config.httpHost);
        config.httpPort      = ini1.getInt("http", "port", config.httpPort);
        config.httpMcpPath   = ini1.get("http", "mcp_path", config.httpMcpPath);
        config.httpSsePath   = ini1.get("http", "sse_path", config.httpSsePath);
        config.httpHealthPath= ini1.get("http", "health_path", config.httpHealthPath);
        config.corsOrigins   = ini1.get("http", "cors_origins", config.corsOrigins);
        config.httpThreadPool= ini1.getInt("http", "thread_pool_size", config.httpThreadPool);

        // [app] — прочее
        config.maxQueryRows  = ini1.getInt("app", "max_query_rows", config.maxQueryRows);
        config.maxProcRows   = ini1.getInt("app", "max_proc_rows", config.maxProcRows);
    }

    /** Копирование значений из Properties в Config (обратная совместимость). */
    private static void applyProperties(Config config, java.util.Properties props) {
        config.dbHost = props.getProperty("db.host", config.dbHost);
        config.dbPort = parseIntSafe(props.getProperty("db.port"), config.dbPort);
        config.dbPath = props.getProperty("db.path", config.dbPath);
        config.dbUser = props.getProperty("db.user", config.dbUser);
        config.dbPassword = props.getProperty("db.password", config.dbPassword);
        config.dbDialect = parseIntSafe(props.getProperty("db.dialect"), config.dbDialect);
        config.transport = props.getProperty("transport", config.transport);
        config.httpPort = parseIntSafe(props.getProperty("http.port"), config.httpPort);
        config.httpHost = props.getProperty("http.host", config.httpHost);
    }

    /** Разбор числа с fallback — чтобы опечатка в конфиге не роняла сервер. */
    private static int parseIntSafe(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.warn("Invalid integer value '{}', using default {}", value, fallback);
            return fallback;
        }
    }

    /** HTTP-транспорт: REST/SSE endpoints + блокировка основного потока. */
    private static void startHttpTransport(McpServer server, Config config) {
        HttpServer httpServer = new HttpServer(server, config);
        httpServer.start();
        logger.info("HTTP/SSE transport started on {}:{} (endpoints: POST {}, GET {}, GET {})",
                config.httpHost, config.httpPort,
                config.httpMcpPath, config.httpSsePath, config.httpHealthPath);

        // Держим main-поток живым, пока сервер работает;
        // shutdownHook (см. main) остановит сервер по Ctrl+C.
        while (server.isRunning()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        httpServer.stop();
    }

    /** Stdio-транспорт: читает JSON-RPC запросы из stdin, ответы пишет в stdout. */
    private static void startStdioTransport(McpServer server) {
        logger.info("stdio transport started, reading from stdin...");
        server.startStdioLoop(System.in);
    }

    /**
     * Конфигурация сервера. Публичные поля — намеренно просто:
     * мутации только при загрузке конфигурации.
     */
    public static class Config {
        // --- Подключение к Firebird ---
        public String dbHost = "localhost";              // хост Firebird Server
        public int dbPort = 3050;                        // порт Firebird
        public String dbPath = "B:\\bdtest\\TESTBASE.FDB"; // путь к .FDB или alias
        public String dbUser = "SYSDBA";                 // пользователь
        public String dbPassword = "masterkey";          // пароль (лучше через env)
        public int dbDialect = 1;                        // SQL-диалект (0/1/2)
        public String dbCharset = "UTF8";                // кодировка соединения

        // --- Транспорт ---
        public String transport = "stdio";               // "stdio" или "http"

        // --- HTTP-транспорт ---
        public String httpHost = "0.0.0.0";              // интерфейс прослушивания
        public int httpPort = 8080;                      // TCP-порт
        public String httpMcpPath = "/mcp";              // путь JSON-RPC endpoint
        public String httpSsePath = "/sse";              // путь SSE endpoint
        public String httpHealthPath = "/health";        // путь health endpoint
        public String corsOrigins = "*";                 // CORS allow-origin
        public int httpThreadPool = 4;                   // потоки под HTTP/SSE

        // --- Прочее ---
        public int maxQueryRows = 10000;                 // лимит строк SELECT
        public int maxProcRows = 1000;                   // лимит строк RETURNING
    }
}
