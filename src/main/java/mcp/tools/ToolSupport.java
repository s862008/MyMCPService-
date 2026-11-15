package mcp.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.slf4j.Logger;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Общие помощники инструментов: сборка result-объектов MCP и типовая
 * обработка ошибок.
 *
 * <p>Формат ответа MCP ({@code content:[{type:"text",...}], isError}) раньше
 * собирался вручную в каждом из 12 инструментов — 12 копий одного кода.
 * Здесь единственные места сборки успешного и "сырого" ответа.</p>
 *
 * <p>Ошибки сюда не несут: бросок {@link RuntimeException} конвертирует
 * {@link mcp.McpServer} (Reason + Hint), поэтому инструменту достаточно
 * пробросить {@code wrapSql}.</p>
 */
public final class ToolSupport {

    /** Pretty-printing удобен для отладки; протоколу формат не мешает. */
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ToolSupport() {
    }

    /** Успешный текстовый ответ: { content:[{type:"text",text}], isError:false }. */
    public static JsonObject ok(String text) {
        JsonObject response = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", text);
        content.add(textContent);
        response.add("content", content);
        response.addProperty("isError", false);
        return response;
    }

    /** Успешный ответ с JSON-содержимым, сериализованным через GSON. */
    public static JsonObject okJson(Object obj) {
        return ok(GSON.toJson(obj));
    }

    /**
     * Типовое логирование + обёртка SQLException для проброса в McpServer.
     *
     * <p>Префикс ("X error: ") НЕ добавляем: McpServer сам формирует
     * структурированный ответ (tool + Reason + Hint), иначе причина дублируется.</p>
     *
     * @param logger логгер вызывающего инструмента
     * @param what   короткое название операции для лога (англ., единообразно с текстом ошибки)
     * @param detail контекст операции (SQL, имя объекта)
     */
    public static RuntimeException wrapSql(Logger logger, String what, String detail, SQLException e) {
        logger.error("{}: {} | {}", what, e.getMessage(), detail);
        return new RuntimeException(e.getMessage(), e);
    }

    /** Первый непустой список строк из результата executeQuery ("rows"). */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> rowsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("rows");
    }

    /** Список trimmed-значений колонки из результата executeQuery (CHAR-padding диалекта 1). */
    public static JsonArray columnValues(List<Map<String, Object>> rows, String column) {
        JsonArray values = new JsonArray();
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                Object v = row.get(column);
                if (v != null) {
                    values.add(v.toString().trim());
                }
            }
        }
        return values;
    }

    /** Строка с trim (CHAR-padding в диалекте 1); null пропускается. */
    public static String str(Object o) {
        return o == null ? null : o.toString().trim();
    }

    /** JSON-массив из обязательных полей схемы. */
    public static JsonArray required(String... names) {
        JsonArray array = new JsonArray();
        for (String name : names) {
            array.add(name);
        }
        return array;
    }

    /**
     * Билдер inputSchema (JSON Schema type=object) — заменяет ручную сборку
     * type/properties/required в каждом инструменте.
     */
    public static final class Schema {
        private final JsonObject properties = new JsonObject();
        private JsonArray required;

        /** Строковое поле с описанием. */
        public Schema str(String name, String description) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            p.addProperty("description", description);
            properties.add(name, p);
            return this;
        }

        /** Строковое поле со списком допустимых значений. */
        public Schema enumStr(String name, String description, String... values) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "string");
            p.addProperty("description", description);
            JsonArray e = new JsonArray();
            for (String v : values) {
                e.add(v);
            }
            p.add("enum", e);
            properties.add(name, p);
            return this;
        }

        /** Массив произвольных элементов (параметры процедур: числа/строки/null). */
        public Schema array(String name, String description) {
            JsonObject p = new JsonObject();
            p.addProperty("type", "array");
            p.add("items", new JsonObject());
            p.addProperty("description", description);
            properties.add(name, p);
            return this;
        }

        /** Обязательные поля (вызывать после добавления всех полей). */
        public Schema required(String... names) {
            required = ToolSupport.required(names);
            return this;
        }

        public JsonObject build() {
            JsonObject schema = new JsonObject();
            schema.addProperty("type", "object");
            schema.add("properties", properties);
            if (required != null) {
                schema.add("required", required);
            }
            return schema;
        }
    }

    /** Мини-билдер JSON-объекта: obj().put(...).put(...). */
    public static JsonObjectBuilder obj() {
        return new JsonObjectBuilder();
    }

    /** Цепочечная сборка JsonObject (GSON-совместимый результат). */
    public static final class JsonObjectBuilder {
        private final JsonObject object = new JsonObject();

        public JsonObjectBuilder put(String name, String value) {
            if (value != null) {
                object.addProperty(name, value);
            }
            return this;
        }

        public JsonObjectBuilder put(String name, Number value) {
            if (value != null) {
                object.addProperty(name, value);
            }
            return this;
        }

        public JsonObjectBuilder put(String name, Boolean value) {
            if (value != null) {
                object.addProperty(name, value);
            }
            return this;
        }

        public JsonObjectBuilder put(String name, JsonElement value) {
            if (value != null) {
                object.add(name, value);
            }
            return this;
        }

        public JsonObject build() {
            return object;
        }
    }
}
