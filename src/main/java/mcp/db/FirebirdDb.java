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
     * Выполнение SELECT.
     *
     * @return map с ключами:
     *   columns  — String[] имён колонок,
     *   rows     — List&lt;Map&lt;String,Object&gt;&gt; (по строке на запись),
     *   rowCount — число возвращённых строк (не более 10000).
     */
    public Map<String, Object> executeQuery(String sql) throws SQLException {
        logger.debug("Executing query: {}", sql);

        // try-with-resources гарантированно закроет conn/stmt/rs при любой ветке,
        // включая SQLException из executeQuery.
        try (Connection conn = openConnection();
             Statement stmt = conn.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
             ResultSet rs = stmt.executeQuery(sql)) {

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
                if (rowCount >= 10000) {
                    // Защита от выедания памяти гигантскими выборками.
                    logger.warn("Query returned more than 10000 rows, truncating");
                    break;
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("columns", columnNames);
            result.put("rows", rows);
            result.put("rowCount", rowCount);

            logger.debug("Query returned {} rows", rowCount);
            return result;
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
