package mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Централизованный валидатор SQL для защиты от инъекций и злоупотреблений.
 *
 * <p>Проверяет запросы, поступающие от ИИ-агента, до выполнения:</p>
 * <ul>
 *   <li>удаляет комментарии {@code --} и {@code /* *&#47;} (частый вектор обфускации);</li>
 *   <li>блокирует множественные инструкции через ';' ВНЕ PSQL-блоков BEGIN...END
 *       (теле триггеров/процедур содержат много ';' — это нормально);</li>
 *   <li>детектирует опасные паттерны чужих СУБД и DoS-функции;</li>
 *   <li>блокирует UPDATE/DELETE без WHERE (защита от «снести всю таблицу»);</li>
 *   <li>проверяет имена объектов на безопасный идентификатор (важно: инструменты
 *       схемы подставляют имя в SQL-литерал конкатенацией);</li>
 *   <li>ловит JDBC-параметры {@code ?} вне строковых литералов.</li>
 * </ul>
 */
public final class SqlSafety {

    private static final Logger logger = LoggerFactory.getLogger(SqlSafety.class);

    /** Безопасный идентификатор Firebird (без кавычек): буквы, цифры, $, _; до 31 символа. */
    private static final String SAFE_IDENTIFIER = "^[A-Za-z_][A-Za-z0-9_$]{0,30}$";

    /** Опасные подстроки чужих СУБД / DoS-векторы, недопустимые в запросах к Firebird. */
    private static final String[] DANGEROUS_PATTERNS = {
            "xp_cmdshell",          // MSSQL: исполнение команд ОС
            "sp_executesql",        // MSSQL: динамический SQL (вектор инъекции)
            "sysobjects",           // MSSQL: системные объекты
            "pg_sleep",             // Postgres: DoS через задержку
            "benchmark(",           // MySQL: DoS через тяжёлую функцию
            "waitfor delay",        // MSSQL time-based атака
            "load_file(",           // MySQL/MSSQL чтение файлов
            "into outfile",         // MySQL запись файлов
            "into dumpfile"         // MySQL запись файлов
    };

    private SqlSafety() {}

    // ==================== Политики (из [security]) ====================

    /** Требовать WHERE у UPDATE/DELETE. */
    private static boolean requireWhere = true;
    /** Блокировать любой DDL (CREATE/ALTER/DROP) в sql_execute. */
    private static boolean blockDdl = false;
    /** Блокировать только DROP (самая разрушительная операция). */
    private static boolean blockDrop = false;

    /** префиксы DDL-инструкций, распознаваемые политикой block_ddl */
    private static final String[] DDL_PREFIXES = {"CREATE", "ALTER", "DROP", "RECREATE"};

    /**
     * Установка политик из конфигурации. Вызывается ОДИН раз при старте сервера
     * (см. Main.loadConfig) до обработки первого запроса.
     */
    public static void configure(boolean requireWhere, boolean blockDdl, boolean blockDrop) {
        SqlSafety.requireWhere = requireWhere;
        SqlSafety.blockDdl = blockDdl;
        SqlSafety.blockDrop = blockDrop;
        logger.info("SqlSafety policies: require_where={}, block_ddl={}, block_drop={}",
                requireWhere, blockDdl, blockDrop);
    }

    // ==================== Публичные методы ====================

    /**
     * Проверка пользовательского запроса из sql_query / sql_execute.
     * Бросает {@link SecurityException} при обнаружении угрозы.
     *
     * @param sql исходный SQL от агента
     */
    public static void validateStatement(String sql) {
        String cleaned = validate(sql, false);
        // ? вне строковых литералов: sql_query/sql_execute исполняют SQL буквально,
        // без связывания параметров — наличие ? почти всегда ошибка агента.
        checkNoJdbcParams(cleaned);
        // Политики DDL/DROP из конфигурации (по умолчанию разрешены).
        checkDdlPolicy(cleaned);
    }

    /**
     * Полный прогон запроса через все проверки. Возвращает «очищенный» запрос
     * (без комментариев). Бросает {@link SecurityException} при обнаружении угрозы.
     *
     * @param sql      исходный SQL от агента
     * @param isSelect true — только чтение (пропускается проверка WHERE)
     */
    public static String validate(String sql, boolean isSelect) {
        if (sql == null || sql.trim().isEmpty()) {
            throw new SecurityException("Empty SQL statement");
        }

        // 1. Удаляем комментарии, сохраняя строковые литералы.
        String cleaned = stripComments(sql);

        // 2. Проверка на множественные инструкции вне PSQL-блоков.
        checkSingleStatement(cleaned);

        // 3. Опасные паттерны (реестр чужих СУБД / DoS).
        checkDangerousPatterns(cleaned);

        // 4. UPDATE/DELETE без WHERE — массовая операция.
        if (!isSelect && requireWhere) {
            checkMissingWhere(cleaned);
        }

        return cleaned;
    }

    /**
     * Проверка политик block_ddl / block_drop. Применяется к запросу целиком
     * (в т.ч. к телу инструментов sql_create_*), чтобы конфигурация реально
     * ограничивала разрушительные операции, а не только sql_execute.
     */
    public static void checkDdlPolicy(String sql) {
        String upper = sql.toUpperCase().trim();
        boolean isDrop = upper.startsWith("DROP");
        boolean isDdl = false;
        for (String p : DDL_PREFIXES) {
            if (upper.startsWith(p)) { isDdl = true; break; }
        }
        if (blockDrop && isDrop) {
            throw new SecurityException(
                    "DROP statements are disabled by [security] block_drop=true in mcp-config.ini. " +
                    "Use CREATE OR ALTER to replace an object, or set block_drop=false to allow DROP.");
        }
        if (blockDdl && isDdl) {
            throw new SecurityException(
                    "DDL statements (CREATE/ALTER/DROP) are disabled by [security] block_ddl=true " +
                    "in mcp-config.ini. Set block_ddl=false to allow schema changes.");
        }    }

    /** Проверка имени объекта для подстановки в SQL-литерал (describe_table и т.п.). */
    public static String safeObjectName(String name, String kind) {
        if (name == null || name.trim().isEmpty()) {
            throw new SecurityException(kind + " name is empty");
        }
        String trimmed = name.trim().toUpperCase();
        if (!trimmed.matches(SAFE_IDENTIFIER)) {
            throw new SecurityException(
                    "Invalid " + kind + " name: '" + name + "'. Allowed: letters, digits, " +
                    "underscore, $; max 31 chars; must start with letter or underscore.");
        }
        return trimmed;
    }

    /**
     * Проверка строки объявления параметров/RETURNS для CREATE PROCEDURE
     * (например {@code "IN_ID INTEGER, IN_NAME VARCHAR(50)"}).
     *
     * <p>Такая строка подставляется в DDL конкатенацией, поэтому допускает только
     * идентификаторы, имена типов, цифры, запятые и скобки. Запрещены кавычки,
     * {@code ;}, {@code -}, {@code /}, {@code *}, {@code :} и любые иные символы —
     * это исключает выход за пределы объявления.</p>
     */
    public static String safeParamDeclaration(String decl, String kind) {
        if (decl == null || decl.trim().isEmpty()) return "";
        String trimmed = decl.trim();
        // Разрешены: идентификатор, имена типов через пробел, запятые-разделители,
        // скобки и запятые ВНУТРИ скобок (DECIMAL(12,2)), точка, цифры.
        // Запрещены кавычки, ';', ':', '-', '/' и прочие — выход за пределы объявления невозможен.
        if (!trimmed.matches("(?i)[A-Za-z_][A-Za-z0-9_$]*(\\s+[A-Za-z0-9_$(),.]+)*" +
                "(\\s*,\\s*[A-Za-z_][A-Za-z0-9_$]*(\\s+[A-Za-z0-9_$(),.]+)*)*")) {
            throw new SecurityException(
                    "Invalid " + kind + " declaration: '" + decl + "'. Expected a plain list like " +
                    "'IN_ID INTEGER, IN_NAME VARCHAR(50)' — identifiers, types, commas and parentheses only.");
        }
        return trimmed;
    }

    /**
     * Проверка тела PSQL-процедуры (триггера/процедуры), вставляемого внутрь
     * {@code AS BEGIN ... END}.
     *
     * <p>Множественные {@code ;} здесь допустимы и ожидаемы, поэтому проверку
     * единственности инструкции пропускаем; остаются комментарии, опасные
     * паттерны и JDBC-параметры.</p>
     */
    public static void validatePsqlBody(String body, String kind) {
        if (body == null || body.trim().isEmpty()) {
            throw new SecurityException(kind + " body is empty");
        }
        String cleaned = stripComments(body);
        if (!cleaned.equals(body.trim())) {
            throw new SecurityException(
                    kind + " body must not contain SQL comments (-- or /* */). " +
                    "Comments are stripped for safety; remove them and retry.");
        }
        checkDangerousPatterns(cleaned);
        checkNoJdbcParams(cleaned);
    }

    /** Детект JDBC-параметров '?' вне строковых литералов. */
    public static void checkNoJdbcParams(String sql) {
        if (hasJdbcParams(sql)) {
            throw new SecurityException(
                    "Query contains JDBC parameter placeholder '?'. This tool executes SQL " +
                    "literally — replace '?' with concrete values (e.g. FIRST 30, " +
                    "DATE '2026-10-01', string literals in single quotes) before running.");
        }
    }

    /** true, если вне строковых литералов встречается JDBC-плейсхолдер '?'. */
    public static boolean hasJdbcParams(String sql) {
        boolean inString = false;
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                if (inString && i + 1 < n && sql.charAt(i + 1) == '\'') { i++; continue; }
                inString = !inString;
                continue;
            }
            if (!inString && c == '?') return true;
        }
        return false;
    }

    // ==================== Внутренние проверки ====================

    /** Удаляет комментарии (строчные {@code --} и блочные), не трогая строковые литералы. */
    public static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inString = false;
        int n = sql.length();

        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);

            // Одинарная кавычка — начало/конец литерала. '' внутри — экранированный апостроф.
            if (c == '\'') {
                if (inString && i + 1 < n && sql.charAt(i + 1) == '\'') {
                    out.append("''");
                    i++;
                    continue;
                }
                inString = !inString;
                out.append(c);
                continue;
            }

            if (inString) {
                out.append(c);
                continue;
            }

            // Комментарий -- до конца строки
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') i++;
                out.append('\n');
                continue;
            }

            // Блочный комментарий /* ... */
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i++;
                i++; // позиция на '/'
                out.append(' ');
                continue;
            }

            out.append(c);
        }
        return out.toString().trim();
    }

    /**
     * Запрещает несколько инструкций в одном запросе (';' вне литералов И вне
     * PSQL-блоков BEGIN...END).
     *
     * <p>Ключевая тонкость: тело триггера/процедуры
     * {@code CREATE TRIGGER ... AS BEGIN A=1; B=2; END} содержит несколько ';' —
     * это один DDL-оператор, а не стек запросов. Поэтому ведём счётчик глубины
     * BEGIN...END и учитываем ';' только на верхнем уровне.</p>
     */
    static void checkSingleStatement(String sql) {
        boolean inString = false;
        int depth = 0;      // вложенность BEGIN...END (PSQL-блоки)
        int semiTopLevel = 0;
        int n = sql.length();

        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);

            // Строковый литерал — пропускаем содержимое целиком
            if (c == '\'') {
                if (inString && i + 1 < n && sql.charAt(i + 1) == '\'') { i++; continue; }
                inString = !inString;
                continue;
            }
            if (inString) continue;

            // Двойные кавычки — идентификатор ("my table"); пропускаем до закрытия
            if (c == '"') {
                i++;
                while (i < n && sql.charAt(i) != '"') i++;
                continue;
            }

            // Распознаём слова BEGIN / END как целые токены (регистронезависимо)
            if (isWordAt(sql, i, "BEGIN")) {
                depth++;
                i += 4;
                continue;
            }
            if (isWordAt(sql, i, "END")) {
                if (depth > 0) depth--;
                i += 2;
                continue;
            }

            if (c == ';' && depth == 0) semiTopLevel++;
        }

        // Разрешаем ровно один завершающий ';' в конце запроса
        if (sql.trim().endsWith(";")) semiTopLevel--;

        if (semiTopLevel > 0) {
            throw new SecurityException(
                    "Multiple SQL statements are not allowed (detected " + semiTopLevel +
                    " top-level semicolon(s)). Execute statements one by one. " +
                    "For procedural code use EXECUTE BLOCK.");
        }
    }

    /** Проверка, что в позиции i стоит целое слово word (границы — не буквы/цифры/_/$). */
    private static boolean isWordAt(String sql, int i, String word) {
        int len = word.length();
        if (i + len > sql.length()) return false;
        for (int k = 0; k < len; k++) {
            if (Character.toUpperCase(sql.charAt(i + k)) != word.charAt(k)) return false;
        }
        boolean leftOk = i == 0 || !isIdentChar(sql.charAt(i - 1));
        int after = i + len;
        boolean rightOk = after >= sql.length() || !isIdentChar(sql.charAt(after));
        return leftOk && rightOk;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /** Детект реестра опасных подстрок. */
    static void checkDangerousPatterns(String sql) {
        String lower = sql.toLowerCase();
        for (String p : DANGEROUS_PATTERNS) {
            if (lower.contains(p)) {
                logger.warn("Dangerous SQL pattern blocked: {}", p);
                throw new SecurityException(
                        "Query rejected: contains disallowed pattern '" + p +
                        "'. This MCP server targets Firebird SQL only.");
            }
        }
    }

    /** UPDATE/DELETE без WHERE — массовая операция. Блокируется. */
    static void checkMissingWhere(String sql) {
        String upper = sql.toUpperCase();
        boolean isUpdate = upper.startsWith("UPDATE");
        boolean isDelete = upper.startsWith("DELETE");
        if (!isUpdate && !isDelete) return;

        // WHERE ищется как целое слово (не "WHERE" внутри литерала уже вычищен
        // stripComments, но литералы остаются — грубая эвристика допустима)
        if (!containsWord(upper, "WHERE")) {
            throw new SecurityException(
                    "Blocked " + (isUpdate ? "UPDATE" : "DELETE") + " without WHERE clause — " +
                    "this would affect ALL rows. Add a WHERE condition. If a mass operation " +
                    "is truly intended, use EXECUTE BLOCK with explicit confirmation.");
        }
    }

    /** Поиск целого слова в тексте (регистронезависимо, границы — не идентификаторы). */
    private static boolean containsWord(String upperText, String word) {
        int idx = 0;
        while ((idx = upperText.indexOf(word, idx)) >= 0) {
            boolean leftOk = idx == 0 || !isIdentChar(upperText.charAt(idx - 1));
            int after = idx + word.length();
            boolean rightOk = after >= upperText.length() || !isIdentChar(upperText.charAt(after));
            if (leftOk && rightOk) return true;
            idx = after;
        }
        return false;
    }

    // ==================== Подсказки по ошибкам Firebird ====================

    /**
     * Готовая подсказка «что делать» для текста ошибки Firebird.
     *
     * <p>Без этого агент видит лишь «Dynamic SQL Error; Column unknown; PD.NAME»
     * и повторяет тот же запрос. Подсказка направляет его на нужный инструмент
     * разведки схемы (sql_describe_table) или на правильный синтаксис.</p>
     *
     * @param errorText полный текст ошибки (из extractFullError)
     * @return подсказка или null, если ошибка не распознана
     */
    public static String hintFor(String errorText) {
        if (errorText == null) return null;
        String t = errorText.toLowerCase();

        // Извлекаем Firebird SQLCODE из текста вида "SQL error code = -104".
        // Текстовые проверки типа "sqlcode=-104" не работали: реальный формат —
        // "SQL error code = -104" (с пробелами), поэтому сверяемся по числу.
        int sqlCode = extractFirebirdSqlCode(t);

        // -206 Column unknown
        if (t.contains("column unknown") || sqlCode == -206) {
            return "Column does not exist. Call sql_describe_table for every table used in the query "
                 + "and reference ONLY real column names from its output. Do not guess column names.";
        }
        // -204 Table unknown
        if (t.contains("table unknown") || sqlCode == -204) {
            return "Table or view does not exist. Call sql_list_tables and use an exact name "
                 + "from its result (Firebird object names are UPPER_CASE by default unless quoted).";
        }
        // -104 / -105 syntax
        if (sqlCode == -104 || sqlCode == -105 || t.contains("invalid token")
                || t.contains("unexpected end of command")) {
            return "Syntax error. Common Firebird pitfalls: no LIMIT (use FIRST n), "
                 + "no CREATE TABLE IF NOT EXISTS, no CURRENT_DATE in SQL dialect 1 "
                 + "(use CURRENT_TIMESTAMP). Validate the fixed statement with sql_explain before re-running.";
        }
        // -303 / -305 conversion
        if (sqlCode == -303 || t.contains("conversion error")
                || sqlCode == -305 || t.contains("arithmetic exception")) {
            return "Data type conversion error. Check column types via sql_describe_table: "
                 + "compare numeric columns without quotes, string literals in single quotes, "
                 + "dates as DATE '2026-01-01' or CAST(...).";
        }
        // -802 uniqueness / constraint
        if (sqlCode == -802 || t.contains("violation of primary or unique key")) {
            return "PRIMARY KEY / UNIQUE violation — the value already exists. "
                 + "Check existing rows with sql_query before INSERT, or use a generated key.";
        }
        // -803 / -625 foreign key
        if (sqlCode == -803 || t.contains("foreign key")
                || sqlCode == -625 || t.contains("partner key")) {
            return "Foreign key violation: the referenced parent row does not exist, or you are "
                 + "deleting/updating a row that is still referenced. Check the lookup table first.";
        }
        // NOT NULL
        if (sqlCode == -625 || t.contains("validation error for column")
                || t.contains("attempt to store null")) {
            return "NULL stored into a NOT NULL column. Inspect the structure with sql_describe_table "
                 + "and provide values for all required columns.";
        }
        // already exists
        if (t.contains("already exists")) {
            return "Object already exists. Use CREATE OR ALTER (procedures/triggers/views) "
                 + "or ALTER TABLE (columns) instead of repeating CREATE.";
        }
        // object in use
        if (t.contains("object") && t.contains("is in use")) {
            return "Object is in use by an active request. Retry after the concurrent connection "
                 + "finishes (or restart the application holding it open).";
        }
        // deadlock / lock conflict
        if (t.contains("deadlock") || t.contains("lock conflict")
                || t.contains("update conflicts with concurrent update")) {
            return "Lock conflict: the row is being changed concurrently. Retry the statement; "
                 + "if it repeats, touch a different row or shorten the transaction.";
        }
        // timeout
        if (t.contains("timeout") || t.contains("cancelled")) {
            return "Query was cancelled by the server timeout (query_timeout_sec). Simplify it, "
                 + "add FIRST n and WHERE conditions on indexed columns.";
        }
        // permissions
        if (t.contains("no permission") || t.contains("insufficient privileges")) {
            return "Insufficient database privileges for this operation. It must be run by a user "
                 + "with the required GRANTs, or grant them to the current user.";
        }
        return null;
    }

    /**
     * Извлечение Firebird SQLCODE из текста ошибки вида
     * «Dynamic SQL Error; SQL error code = -206; ...».
     *
     * @return отрицательный SQLCODE или 0, если не найден
     */
    private static int extractFirebirdSqlCode(String lowerText) {
        java.util.regex.Matcher m = FIREBIRD_SQLCODE_PATTERN.matcher(lowerText);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static final java.util.regex.Pattern FIREBIRD_SQLCODE_PATTERN =
            java.util.regex.Pattern.compile("sql error code\\s*=\\s*(-?\\d+)");
}
