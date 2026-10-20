package mcp.tools;

import com.google.gson.JsonObject;

/**
 * Контракт инструмента MCP.
 *
 * <p>Каждый инструмент отвечает за два протокольных сообщения:</p>
 * <ul>
 *   <li>{@code tools/list} — описание инструмента (name, description, inputSchema);</li>
 *   <li>{@code tools/call} — выполнение с аргументами из {@code params.arguments}.</li>
 * </ul>
 *
 * <p>Соглашение об ошибках: бросать {@link SecurityException} (запрещённая операция)
 * и {@link IllegalArgumentException} (некорректные аргументы) —
 * {@link mcp.McpServer} конвертирует их в ответ {@code isError: true}.</p>
 */
public interface Tool {

    /**
     * JSON-определение инструмента для tools/list.
     * Обязательные поля: name, description, inputSchema (JSON Schema type=object).
     */
    JsonObject getToolDefinition();

    /**
     * Выполнение инструмента.
     *
     * @param arguments объект из params.arguments вызова tools/call
     * @return result-объект MCP: { content: [{type:"text", text:"..."}], isError: false }
     */
    JsonObject execute(JsonObject arguments);
}
