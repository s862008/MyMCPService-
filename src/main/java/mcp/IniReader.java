package mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Простейший парсер INI-файлов без внешних зависимостей.
 *
 * <p>Поддерживаемый синтаксис:</p>
 * <pre>
 * ; комментарий (также # в начале строки)
 * [section]
 * key = value
 * key2=value2        ; пробелы вокруг '=' необязательны
 * </pre>
 *
 * <p>Значения ключей вне секции сохраняются с пустым именем секции "".</p>
 */
public class IniReader {

    private static final Logger logger = LoggerFactory.getLogger(IniReader.class);

    /** section -> (key -> value) */
    private final Map<String, Map<String, String>> sections = new HashMap<>();

    /**
     * Читает INI-файл. Если файла нет — молча возвращает пустой ридер
     * (тогда применяются значения из других источников).
     */
    public static IniReader load(String path) {
        IniReader reader = new IniReader();
        Path file = Paths.get(path);
        if (!Files.exists(file)) {
            logger.debug("INI file not found: {}", path);
            return reader;
        }
        reader.parse(file);
        logger.info("INI config loaded from {}", file.toAbsolutePath());
        return reader;
    }

    private void parse(Path file) {
        String currentSection = "";
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();

                // Пустые строки и комментарии
                if (line.isEmpty() || line.startsWith(";") || line.startsWith("#")) {
                    continue;
                }

                // Секция [name]
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                    sections.computeIfAbsent(currentSection, k -> new HashMap<>());
                    continue;
                }

                // Пары key = value
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    logger.warn("Skipping malformed INI line {}:{} '{}'", file, lineNo, line);
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

                // Снятие обрамляющих кавычек: key = "value with ; semicolon"
                if (value.length() >= 2 &&
                        ((value.startsWith("\"") && value.endsWith("\"")) ||
                         (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1);
                } else {
                    // Инлайн-комментарий: value ; comment (только если ; не в кавычках)
                    int semi = indexOfInlineComment(value);
                    if (semi >= 0) {
                        value = value.substring(0, semi).trim();
                    }
                }

                sections.computeIfAbsent(currentSection, k -> new HashMap<>()).put(key, value);
            }
        } catch (IOException e) {
            logger.error("Failed to read INI file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Поиск позиции инлайн-комментария " ;" (пробел перед точкой с запятой),
     * чтобы не резать значения путей, где ';' встречается как часть значения.
     */
    private static int indexOfInlineComment(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == ';' && Character.isWhitespace(value.charAt(i - 1))) {
                return i;
            }
        }
        return -1;
    }

    // ==================== Доступ к значениям ====================

    public String get(String section, String key, String defaultValue) {
        Map<String, String> sec = sections.get(section);
        if (sec == null) return defaultValue;
        String v = sec.get(key);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }

    public int getInt(String section, String key, int defaultValue) {
        String v = get(section, key, null);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            logger.warn("Invalid int value [{}] {}={} : '{}', using default {}", section, key, v, defaultValue);
            return defaultValue;
        }
    }

    public boolean getBoolean(String section, String key, boolean defaultValue) {
        String v = get(section, key, null);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v) || "1".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    /** Есть ли вообще прочитанный INI (хотя бы одна секция). */
    public boolean isEmpty() {
        return sections.isEmpty();
    }
}
