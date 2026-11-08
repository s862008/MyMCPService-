package mcp.db;

import mcp.Main;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Доступ к базе данных Firebird 3 (SQL-диалект 1).
 *
 * <p>Стратегия соединений: <b>connection-per-request</b> — на каждый запрос
 * открывается новое JDBC-соединение и сразу закрывается в try-with-resources.
 * Для локальной Firebird это дешевле и надёжнее собственного пула:
 * сервер сам кэширует page cache, а мы избегаем висячих/протухших соединений.</p>
 *
 * <p>Диалект 1 задаётся параметром {@code ?dialect=1} в JDBC URL (Jaybird 4.x).
 * Также включена кодировка UTF8 — при необходимости смените на WAVR/WIN1251
 * под вашу конкретную базу.</p>
 */
public class FirebirdDb {

    private static final Logger logger = LoggerFactory.getLogger(FirebirdDb.class);

    private final String url;
    private final String user;
    private final String password;
    private final int dialect;
    private final String charset;
    private final int queryTimeoutSec;
    private final int maxQueryRows;

    public FirebirdDb(Main.Config config) {
        // Формат URL Jaybird 4.x: jdbc:firebirdsql://host:port/path?параметры
        // dialect=N  — SQL-диалект (0=interop, 1=old standard, 2=modern)
        // charSet=   — кодировка соединения (подстройте под кодировку вашей БД)
        this.url = String.format("jdbc:firebirdsql://%s:%d/%s?dialect=%d&charSet=%s",
                config.dbHost, config.dbPort, config.dbPath, config.dbDialect, config.dbCharset);
        this.user = config.dbUser;
        this.password = config.dbPassword;
        this.dialect = config.dbDialect;
        this.charset = config.dbCharset;
        this.queryTimeoutSec = config.securityQueryTimeoutSec;
        this.maxQueryRows = config.maxQueryRows;

        // Явная загрузка драйвера не обязательна (JDBC 4 auto-discovery),
        // но оставлена для явности и понятных сообщений об отсутствии драйвера.
        try {
            Class.forName("org.firebirdsql.jdbc.FBDriver");
        } catch (ClassNotFoundException e) {
            logger.error("Firebird driver (Jaybird) not found on classpath", e);
            throw new RuntimeException("Firebird driver not found", e);
        }

        // Проверка подключения при старте (fail-fast):
        // неверный путь к .FDB, логин или выключенный сервер видны сразу.
        try (Connection conn = openConnection()) {
            logger.info("Successfully connected to Firebird database (dialect {}, charset {})", dialect, charset);
        } catch (SQLException e) {
            logger.error("Failed to connect to database: {}", e.getMessage());
            throw new RuntimeException("Database connection failed: " + e.getMessage(), e);
        }
    }

    /** Открытие нового соединения (используется внутри executeQuery/executeUpdate). */
    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * Публичный доступ к соединению для инструментов, которым нужен
     * CallableStatement (вызов хранимых процедур). Вызывающий код
     * ОБЯЗАН закрыть соединение (try-with-resources).
     */
    public Connection openConnectionForTool() throws SQLException {
        return openConnection();
    }

    /**
     * Применение защитных лимитов к созданному Statement.
     *
     * <p>ВАЖНО: вызывать ДО {@code executeQuery} — JDBC-параметры читаются
     * драйвером при постановке запроса в выполнение, после execute они
     * уже не действуют.</p>
     *
     * <ul>
     *   <li>{@code setQueryTimeout} — прерывает «вечный» запрос (Full Table Scan
     *       без индекса, декартово произведение) вместо зависания клиента;</li>
     *   <li>{@code setMaxRows} — ограничивает выборку на уровне драйвера,
     *       защищая heap от OOM на гигантских результатах.</li>
     * </ul>
     *
     * <p>Значения 0 отключают соответствующую защиту (JDBC-семантика).</p>
     *
     * @param stmt    готовый Statement/PreparedStatement
     * @param maxRows лимит строк; &lt;= 0 — взять maxQueryRows из конфига
     */
    public void applyLimits(Statement stmt, int maxRows) throws SQLException {
        if (queryTimeoutSec > 0) {
            stmt.setQueryTimeout(queryTimeoutSec);
        }
        int rows = maxRows > 0 ? maxRows : maxQueryRows;
        if (rows > 0) {
            stmt.setMaxRows(rows);
        }
    }

    /**
     * Выполнение SELECT с защитными лимитами из конфигурации.
     *
     * @return map с ключами:
     *   columns  — String[] имён колонок,
     *   rows     — List&lt;Map&lt;String,Object&gt;&gt; (по строке на запись),
     *   rowCount — число возвращённых строк (не более max_query_rows),
     *   truncated — true, если результат обрезан лимитом.
     */
    public Map<String, Object> executeQuery(String sql) throws SQLException {
        logger.debug("Executing query: {}", sql);

        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {

            // Лимиты применяем ДО выполнения запроса — иначе setMaxRows/таймаут
            // не успели бы повлиять на выполнение.
            // +1 строка к лимиту: драйвер вернёт на одну строку больше, чем отдаём
            // наружу, — это позволяет честно сообщить агенту о truncation.
            applyLimits(stmt, maxQueryRows > 0 ? maxQueryRows + 1 : 0);

            int limit = maxQueryRows > 0 ? maxQueryRows : Integer.MAX_VALUE;
            boolean truncated = false;

            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                // Имена колонок: getColumnLabel учитывает AS-алиасы.
                // trim() — в диалекте 1 имена системных таблиц (RDB$...) padding'ятся пробелами.
                String[] columnNames = new String[columnCount];
                for (int i = 1; i <= columnCount; i++) {
                    columnNames[i - 1] = metaData.getColumnLabel(i).trim();
                }

                List<Map<String, Object>> rows = new ArrayList<>();
                int rowCount = 0;
                while (rs.next()) {
                    if (rowCount >= limit) {
                        // Одна лишняя попытка next() ниже покажет, что строк больше.
                        truncated = true;
                        break;
                    }
                    Map<String, Object> row = new HashMap<>();
                    for (int i = 0; i < columnCount; i++) {
                        Object value = rs.getObject(i + 1);
                        if (value instanceof String) {
                            // CHAR(n) в Firebird дополняется пробелами до n символов — срезаем.
                            value = ((String) value).trim();
                        }
                        row.put(columnNames[i], value);
                    }
                    rows.add(row);
                    rowCount++;
                }

                if (truncated) {
                    logger.warn("Query result truncated at {} rows (max_query_rows)", limit);
                }

                Map<String, Object> result = new HashMap<>();
                result.put("columns", columnNames);
                result.put("rows", rows);
                result.put("rowCount", rowCount);
                result.put("truncated", truncated);

                logger.debug("Query returned {} rows", rowCount);
                return result;
            }
        }
    }

    /**
     * Выполнение записывающего запроса: DML (INSERT/UPDATE/DELETE) или DDL
     * (CREATE/ALTER/DROP/EXECUTE PROCEDURE...).
     *
     * <p>Особенности Firebird:</p>
     * <ul>
     *   <li>DDL фиксируется вместе с коммитом транзакции — после commit() откат невозможен;</li>
     *   <li>statement.execute() используется вместо executeUpdate(), т.к. DDL и
     *       EXECUTE PROCEDURE с возвращаемыми строками требуют именно execute().</li>
     * </ul>
     *
     * @return map с ключами success, rowsAffected, message, (опц.) rows/rowCount.
     */
    public Map<String, Object> executeUpdate(String sql) throws SQLException {
        logger.debug("Executing update: {}", sql);

        try (Connection conn = openConnection()) {
            // Управляем транзакцией вручную, чтобы явно commit/rollback.
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                // Только таймаут: setMaxRows здесь неприменим — DML обязан
                // изменить все подходящие строки, а не их часть.
                if (queryTimeoutSec > 0) {
                    stmt.setQueryTimeout(queryTimeoutSec);
                }
                boolean hasResults = stmt.execute(sql);
                int rowsAffected = stmt.getUpdateCount(); // -1 для DDL/без счётчика

                conn.commit(); // DDL после этого уже не откатить — это ожидаемое поведение

                Map<String, Object> result = new HashMap<>();
                result.put("success", true);
                result.put("rowsAffected", rowsAffected);
                result.put("message", "Execution successful");

                // Некоторые запросы (EXECUTE PROCEDURE c RETURNING ...) возвращают строки
                if (hasResults) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        if (rs != null) {
                            ResultSetMetaData metaData = rs.getMetaData();
                            int columnCount = metaData.getColumnCount();
                            String[] columnNames = new String[columnCount];
                            for (int i = 1; i <= columnCount; i++) {
                                columnNames[i - 1] = metaData.getColumnLabel(i).trim();
                            }
                            List<Map<String, Object>> rows = new ArrayList<>();
                            int rowCount = 0;
                            while (rs.next() && rowCount < 1000) {
                                Map<String, Object> row = new HashMap<>();
                                for (int i = 0; i < columnCount; i++) {
                                    row.put(columnNames[i], rs.getObject(i + 1));
                                }
                                rows.add(row);
                                rowCount++;
                            }
                            result.put("rows", rows);
                            result.put("rowCount", rowCount);
                        }
                    }
                }

                logger.debug("Update successful, rows affected: {}", rowsAffected);
                return result;
            } catch (SQLException e) {
                // Ошибка DML — откатываем транзакцию, чтобы не оставить половину изменений.
                try { conn.rollback(); } catch (SQLException ignored) {}
                logger.error("Update failed, rolled back: {}", e.getMessage());
                throw e;
            }
        }
    }

    /** Соединения открываются/закрываются на запрос — постоянных ресурсов нет. */
    public void close() {
        logger.info("Database connections closed on demand (no persistent pool)");
    }

    public String getUrl() {
        return url;
    }
}
