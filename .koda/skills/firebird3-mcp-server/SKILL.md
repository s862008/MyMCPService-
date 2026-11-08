---
name: firebird3-mcp-server
description: Работа с базой данных Firebird 3 (диалект 1) через MCP-сервер myMCPService. Использовать когда нужно читать/писать данные, изучать схему (таблицы, колонки, процедуры), выполнять DDL, создавать процедуры/представления/триггеры или запускать хранимые процедуры в подключённой Firebird-базе.
---

# Работа с Firebird MCP Server

MCP-сервер `firebird` предоставляет инструменты для Firebird 3 (SQL-диалект 1).
Все вызовы идут через протокол MCP (tools/call).

## Ключевые особенности Firebird (диалект 1)

- **Нет LIMIT** — используйте `FIRST n` / `SKIP n`: `SELECT FIRST 10 * FROM T`.
- **Имена в ВЕРХНЕМ регистре** по умолчанию: таблицы `USERS`, не `users`
  (если не создавались в кавычках).
- **CHAR дополняется пробелами** — сервер делает trim, но при сравнениях помните.
- **Нет BOOLEAN в диалекте 1** — используйте SMALLINT 0/1.
- **DDL автокоммитится** после выполнения — откат невозможен.
- **Пустая строка = NULL** в VARCHAR (Oracle-подобное поведение).

## Рабочий порядок (обязательно соблюдать)

1. **Разведка схемы** — никогда не пишите SQL вслепую:
   - `sql_list_tables` — какие таблицы есть;
   - `sql_describe_table` {table:"NAME"} — колонки и типы перед запросом к таблице.
2. **Валидация** сложного запроса: `sql_explain` {query:"SELECT ..."} —
   проверит синтаксис и имена объектов без возврата данных.
3. **Выполнение**:
   - чтение → `sql_query` {query:"SELECT ..."};
   - запись/DDL → `sql_execute` {query:"INSERT/UPDATE/CREATE ..."}.

## Инструменты

### Чтение
- `sql_query` {query} — только SELECT. Возвращает columns/rows/rowCount.

### Схема
- `sql_list_tables` {} — список таблиц.
- `sql_list_procedures` {} — список процедур.
- `sql_describe_table` {table} — колонки, типы, размеры, NOT NULL, DEFAULT.
- `sql_describe_procedure` {procedure} — входные/выходные параметры.

### Валидация
- `sql_explain` {query} — проверка SELECT + план выполнения (какие индексы).

### Запись и DDL
- `sql_execute` {query} — INSERT/UPDATE/DELETE/CREATE/ALTER/DROP/EXECUTE.

### Процедуры
- `sql_create_procedure` {name, parameters?, returns?, body} — CREATE OR ALTER PROCEDURE.
- `sql_execute_procedure` {procedure, parameters:[]} — вызов с параметрами по позиции.

### Объекты (обёртки)
- `sql_create_view` {name, query} — CREATE OR ALTER VIEW.
- `sql_create_trigger` {name, table, timing:BEFORE|AFTER, event:INSERT|UPDATE|DELETE, body}
  — в теле доступны NEW.col / OLD.col.

## Типичные задачи

### Пример: автоинкремент ID (диалект 1)
```
1. sql_execute: CREATE GENERATOR GEN_USERS_ID
2. sql_create_trigger:
   {name:"TR_USERS_BI", table:"USERS", timing:"BEFORE", event:"INSERT",
    body:"NEW.ID = GEN_ID(GEN_USERS_ID, 1)"}
```

### Пример: вызов процедуры с параметрами
```
1. sql_describe_procedure {procedure:"P_GET_USER"}  — узнать порядок параметров
2. sql_execute_procedure {procedure:"P_GET_USER", parameters:[42]}
```

## Безопасность (сервер блокирует автоматически)

- В `sql_query` проходит только SELECT (сервер отклонит остальное).
- **UPDATE/DELETE без WHERE блокируются** (`require_where = true`) — всегда
  пишите условие. Массовая операция осознанно: только через `EXECUTE BLOCK`
  либо временно `require_where = false` в `mcp-config.ini`.
- **Несколько инструкций через `;` запрещены** — выполняйте по одной.
  Исключение: тело `CREATE TRIGGER/PROCEDURE ... AS BEGIN ... END` (там `;` нормальны).
- **Комментарии `--` и `/* */` вырезаются**, паттерны чужих СУБД
  (`xp_cmdshell`, `pg_sleep`, `benchmark(`, `INTO OUTFILE` и т.п.) — блокируются.
- **`?`-плейсхолдеры запрещены** — сервер исполняет SQL буквально.
  Пишите значения сразу: `WHERE D >= DATE '2026-10-01'`, `FIRST 30`.
- **Имена объектов** (`table`, `procedure`, `name`) проверяются на
  `[A-Za-z_][A-Za-z0-9_$]{0,30}` — спецсимволы и кавычки в этих полях отвергаются.
- **Таймаут запроса** 30 сек (`query_timeout_sec`) — тяжёлый безындексный запрос
  прерывается; добавляйте `FIRST n` и индексы.
- **Лимит строк**: SELECT — `max_query_rows` (10000), процедура — `max_proc_rows` (1000).
- DDL необратим — перед DROP/ALTER подтвердите у пользователя.

## Запрещённый синтаксис (частые ошибки)

- **Нет `CREATE TABLE IF NOT EXISTS`** / `IF NOT EXISTS` вообще — его нет в Firebird.
  Используйте `CREATE OR ALTER TABLE` (FB4+) или создавайте таблицу один раз,
  а при ошибке «already exists» — работайте с существующей.
- **Нет `CURRENT_DATE` в диалекте 1** — ошибка -104. Используйте
  `CURRENT_TIMESTAMP` или `CAST('2026-10-01' AS DATE)`.
- **Нет `LIMIT`** — только `FIRST n [SKIP m]`.
- **Нет `BOOLEAN`** — SMALLINT 0/1.
- **Нет `AUTO_INCREMENT` / `IDENTITY` в диалекте 1** — генератор + BEFORE INSERT триггер.
- **Триггер/процедуру создавайте после таблицы** — иначе ошибка метаданных.

## Ошибки

Ответ с `isError: true` содержит текст ошибки Firebird (SQLCODE/GDSC) —
исправляйте SQL по тексту, а не повторяйте тот же запрос.
Ошибки вида «Security error» / «Blocked ...» — это валидатор сервера:
перепишите запрос согласно правилам выше, а не повторяйте его.
