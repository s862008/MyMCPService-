package mcp.tools;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Извлечение и валидация аргументов tools/call.
 *
 * <p>Ранее каждый инструмент вручную писал
 * {@code if (!arguments.has("x")) throw new IllegalArgumentException(...)} —
 * здесь единственная реализация этого шаблона. Тип выбрасываемых исключений
 * сохранён прежним: {@link mcp.McpServer} конвертирует
 * {@link IllegalArgumentException} в JSON-RPC -32602 / isError-ответ.</p>
 */
public final class Args {

    private Args() {
    }

    /** Обязательная строка (trim). Бросает IllegalArgumentException при отсутствии. */
    public static String requireString(JsonObject arguments, String name) {
        if (!arguments.has(name)) {
            throw new IllegalArgumentException("Missing required parameter: " + name);
        }
        return arguments.get(name).getAsString().trim();
    }

    /**
     * Обязательная строка, проверенная как безопасный идентификатор Firebird
     * (имена таблиц/процедур/объектов, подставляемые в SQL).
     */
    public static String requireObjectName(JsonObject arguments, String name) {
        return mcp.SqlSafety.safeObjectName(requireString(arguments, name), name);
    }

    /** Необязательная строка; null, если поле отсутствует или пустое. */
    public static String optionalString(JsonObject arguments, String name) {
        if (!arguments.has(name) || arguments.get(name).isJsonNull()) {
            return null;
        }
        String v = arguments.get(name).getAsString().trim();
        return v.isEmpty() ? null : v;
    }

    /** Есть ли обязательный параметр (для единого текста ошибки). */
    public static void require(JsonObject arguments, String... names) {
        for (String name : names) {
            if (!arguments.has(name)) {
                throw new IllegalArgumentException("Missing required parameter: " + name);
            }
        }
    }

    /** Значение поля как Object для JDBC-подстановки (null/число/строка). */
    public static Object toSqlValue(JsonElement el) {
        if (el == null || el.isJsonNull()) {
            return null;
        }
        if (el.getAsJsonPrimitive().isNumber()) {
            return el.getAsNumber();
        }
        if (el.getAsJsonPrimitive().isBoolean()) {
            // Firebird BOOLEAN (диалект 3) — в диалекте 1 не используется,
            // но передаём как 1/0 на случай смешанных баз
            return el.getAsBoolean() ? 1 : 0;
        }
        return el.getAsString();
    }
}
