# Firebird MCP Server

MCP (Model Context Protocol) сервер на Java 11 для работы с базой данных **Firebird 3 (SQL-диалект 1)**.
Позволяет LLM-клиентам (Koda, Claude Desktop, Cursor и другие) читать и изменять данные в Firebird через стандартизированный JSON-RPC 2.0 интерфейс.

## Возможности

| Инструмент | Описание |
|---|---|
| `sql_query` | Выполнение SELECT-запросов. Только чтение. |
| `sql_execute` | Выполнение DML/DDL (INSERT, UPDATE, DELETE, CREATE TABLE, ALTER, DROP, EXECUTE PROCEDURE). |
| `sql_list_tables` | Список пользовательских таблиц из RDB$RELATIONS. |
| `sql_list_procedures` | Список хранимых процедур из RDB$PROCEDURES. |
| `sql_describe_table` | Структура таблицы: колонки, типы, размеры, NOT NULL, DEFAULT. |
| `sql_describe_procedure` | Параметры процедуры: входные/выходные, имена, типы. |
| `sql_execute_procedure` | Вызов процедуры с параметрами, возврат RESULT SET. |
| `sql_explain` | Валидация SELECT без возврата данных + план выполнения. |
| `sql_create_procedure` | Обёртка CREATE OR ALTER PROCEDURE. |
| `sql_create_view` | Обёртка CREATE OR ALTER VIEW. |
| `sql_create_trigger` | Обёртка CREATE OR ALTER TRIGGER (BEFORE/AFTER, INSERT/UPDATE/DELETE). |

### Рекомендуемый рабочий порядок для LLM

1. `sql_list_tables` — что есть в базе;
2. `sql_describe_table` — структура нужных таблиц (КОЛОНКИ И ТИПЫ);
3. `sql_explain` — проверка сложного запроса перед выполнением;
4. `sql_query` / `sql_execute` — выполнение.

## Архитектура

```
myMCPService/
├── pom.xml                          — Maven: Jaybird 5.0.13.java11, Gson 2.10.1, Logback
├── mcp-config.ini                   — Основной конфигурационный файл (INI)
├── README.md                        — Полная документация
├── .gitignore
└── src/main/
    ├── java/
    │   └── mcp/
    │       ├── Main.java            — Точка входа, загрузка конфига, запуск транспорта
    │       ├── McpServer.java       — Ядро: JSON-RPC 2.0 маршрутизация
    │       ├── HttpServer.java      — HTTP/SSE транспорт (com.sun.net.httpserver)
    │       ├── IniReader.java       — Парсер INI-файлов (без внешних зависимостей)
    │       ├── db/
    │       │   └── FirebirdDb.java  — Слой доступа к Firebird (JDBC, Jaybird)
    │       └── tools/
    │           ├── Tool.java
    │           ├── SqlReadTool.java
    │           ├── SqlWriteTool.java
    │           ├── SqlProcedureTool.java
    │           ├── SqlExecuteProcedureTool.java
    │           ├── SqlListTablesTool.java
    │           ├── SqlListProceduresTool.java
    │           ├── SqlDescribeTableTool.java
    │           ├── SqlDescribeProcedureTool.java
    │           ├── SqlExplainTool.java
    │           ├── SqlCreateViewTool.java
    │           └── SqlCreateTriggerTool.java
    └── resources/
        ├── application.properties   — Обратная совместимость (properties-формат)
        └── logback.xml              — Логирование (в STDERR!)
```

## Поддерживаемые транспорты

### 1. Stdio (по умолчанию)
JSON-RPC сообщения передаются через stdin/stdout.
Подходит для MCP-клиентов (Koda, Claude Desktop, Cursor, VS Code и др.).

Каждый запрос — одна строка JSON. Ответ — одна строка JSON.

### 2. HTTP / SSE
Встроенный HTTP-сервер (без внешних зависимостей, чистый JDK 11).

| Эндпоинт | Метод | Описание |
|---|---|---|
| `/mcp` | POST | Streamable HTTP: JSON-RPC запрос в теле, ответ в теле. |
| `/sse` | GET  | Server-Sent Events: клиент получает endpoint и keepalive. |
| `/health` | GET | Проверка живости: `{"status":"ok"}`. |

## Установка

### Требования
- **Java 11** (OpenJDK или Oracle JDK)
- **Maven 3.6+**
- **Firebird 3.x** (локально или удалённо)
- Драйвер **Jaybird 4.x** (подключается через Maven)

### Сборка

```bash
cd D:\PROJECTS\test\myMCPService

# Сборка jar с зависимостями (maven-shade-plugin)
set JAVA_HOME=C:\Program Files\Java\jdk-11.0.13.8-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
mvn clean package

# Итоговый файл: target/firebird-mcp-server.jar (~15-20 MB с shaded зависимостями)
```

## Запуск

### Stdio-режим (для MCP-клиентов)

```bash
# Конфиг в mcp-config.ini рядом с jar
java -jar target/firebird-mcp-server.jar

# Или с явным заданием конфига
java -jar target/firebird-mcp-server.jar -Dconfig.file=C:\my\mcp-config.ini

# С паролями через переменные окружения (переопределяют INI)
DB_USER=MYUSER DB_PASSWORD=secret java -jar target/firebird-mcp-server.jar
```

### HTTP-режим

```bash
# В mcp-config.ini секция [server]: transport = http
# Затем запустить:
java -jar target/firebird-mcp-server.jar
```

## Конфигурация

### Основной конфиг: `mcp-config.ini`

Расположите файл `mcp-config.ini` рядом с jar-файлом.
Путь можно указать явно через системное свойство: `-Dconfig.file=C:\path\to\mcp-config.ini`

Подробное описание всех параметров — в комментариях внутри файла `mcp-config.ini`.

### Источники конфигурации (по возрастанию приоритета)

1. **Значения по умолчанию** (в коде `Main.Config`)
2. **mcp-config.ini** — основной пользовательский конфиг (рядом с jar)
3. **application.properties** (classpath / внешний) — обратная совместимость
4. **Переменные окружения** (высший приоритет)

### Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `DB_HOST` | `localhost` | Хост Firebird Server |
| `DB_PORT` | `3050` | Порт Firebird |
| `DB_PATH` | `B:\bdtest\TESTBASE.FDB` | Путь к .FDB файлу |
| `DB_USER` | `SYSDBA` | Пользователь БД |
| `DB_PASSWORD` | `masterkey` | Пароль (задавайте через env!) |
| `DB_DIALECT` | `1` | SQL-диалект |
| `TRANSPORT` | `stdio` | `stdio` или `http` |
| `HTTP_PORT` | `8080` | Порт HTTP-транспорта |
| `HTTP_HOST` | `0.0.0.0` | Интерфейс HTTP-транспорта |

## Примеры вызова

### Инициализация

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "method": "initialize",
  "params": {}
}
```

### Получить список инструментов

```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "method": "tools/list"
}
```

### Выполнить SELECT

```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "method": "tools/call",
  "params": {
    "name": "sql_query",
    "arguments": {
      "query": "SELECT FIRST 5 RDB$RELATION_NAME FROM RDB$RELATIONS"
    }
  }
}
```

### Выполнить INSERT

```json
{
  "jsonrpc": "2.0",
  "id": "4",
  "method": "tools/call",
  "params": {
    "name": "sql_execute",
    "arguments": {
      "query": "INSERT INTO MY_TABLE (ID, NAME) VALUES (1, 'test')"
    }
  }
}
```

### Создать хранимую процедуру

```json
{
  "jsonrpc": "2.0",
  "id": "5",
  "method": "tools/call",
  "params": {
    "name": "sql_create_procedure",
    "arguments": {
      "name": "P_TEST",
      "parameters": "IN_ID INTEGER",
      "returns": "OUT_NAME VARCHAR(100)",
      "body": "SELECT FIRST 1 NAME FROM MY_TABLE WHERE ID = :IN_ID INTO :OUT_NAME"
    }
  }
}
```

## Безопасность

| Мера | Описание |
|---|---|
| **Разделение read/write** | `sql_query` принимает ТОЛЬКО SELECT-запросы. Писать через него нельзя. |
| **Валидация имён** | `sql_create_procedure` проверяет, что имя процедуры — безопасный идентификатор. |
| **Пароли в env** | Рекомендуется передавать DB_PASSWORD через переменную окружения, не хранить в файле. |
| **Логи в stderr** | Все логи пишутся в STDERR (logback target=System.err). STDOUT чист для протокольного JSON. |
| **Ограничение выборки** | SELECT не вернёт больше 10 000 строк за раз (защита от OOM). |

## Логирование

- **stdout** — только JSON-RPC протокольные сообщения (в stdio-режиме).
- **stderr** — все логи (logback ConsoleAppender с target=System.err).
- **Файл** — `logs/firebird-mcp-server.log` (ежедневное ротирование, макс 7 файлов по 10 МБ).

## Структура ответов

### Успешный результат инструмента

```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"columns\":[\"RDB$RELATION_NAME\"],\"rows\":[{\"RDB$RELATION_NAME\":\"USERS\"}],\"rowCount\":1}"
      }
    ],
    "isError": false
  }
}
```

### Ошибка инструмента (возвращается как result с isError=true)

```json
{
  "jsonrpc": "2.0",
  "id": "4",
  "result": {
    "content": [
      {
        "type": "text",
        "text": "SQL error: Dynamic SQL Error, SQL error code = -104, Invalid token"
      }
    ],
    "isError": true
  }
}
```

### Ошибка протокола (JSON-RPC error)

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "error": {
    "code": -32601,
    "message": "Method not found: unknown_method"
  }
}
```

## Зависимости

| Зависимость | Версия | Назначение |
|---|---|---|
| `jaybird` | 5.0.13.java11 | JDBC-драйвер Firebird (Jaybird) |
| `gson` | 2.10.1 | JSON-сериализация/десериализация |
| `slf4j-api` | 2.0.9 | API логирования |
| `logback-classic` | 1.4.14 | Реализация логирования |

## Возможные расширения

- Поддержка нескольких баз данных (multi-datasource)
- Кэширование метаданных (список таблиц/колонок)
- Валидация SQL перед выполнением
- Поддержка транзакций для DML-операций (ROLLBACK)
- WebSocket-транспорт вместо HTTP
- Аутентификация через API-токен для HTTP-режима
- Rate limiting для запросов

## Лицензия

MIT
