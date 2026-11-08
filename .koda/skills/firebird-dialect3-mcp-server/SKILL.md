---
name: firebird-dialect3-mcp-server
description: Работа с базой данных Firebird в SQL-диалекте 3 (FB 3.0/4.0/5.0) через MCP-сервер myMCPService. Использовать когда база настроена на dialect=3 — есть типы BOOLEAN, TIMESTAMP с временем, регистрозависимые имена в кавычках. Читать/писать данные, изучать схему, выполнять DDL, создавать процедуры/представления/триггеры, запускать хранимые процедуры.
---

# Работа с Firebird MCP Server (диалект 3)

MCP-сервер `firebird` предоставляет инструменты для Firebird 3–5 (SQL-диалект 3).
Все вызовы идут через протокол MCP (tools/call).

## Ключевые особенности диалекта 3 (отличия от диалекта 1)

- **Есть BOOLEAN**: `INSERT INTO T (FLAG) VALUES (TRUE)` / `WHERE FLAG = TRUE`.
  Не используйте 0/1 — это другой стиль, в диалекте 3 он вызовет ошибку приведения.
- **TIMESTAMP содержит дату И время**; отдельного DATE-типа для новых таблиц
  лучше не создавать — используйте TIMESTAMP.
- **Регистрозависимые имена**: `CREATE TABLE "myTable"` создаёт имя строго
  `"myTable"` и обращаться к нему надо только в кавычках. Без кавычек имена
  приводятся к ВЕРХНЕМУ регистру, как обычно. Не смешивайте стили.
- **VARCHAR до 32765 символов** (в UTF8 — меньше из-за байтов на символ).
- **BIGINT (INT64) полноценный**: `INTEGER` = INT32, `BIGINT` = INT64.
- **Нет LIMIT** — как и везде в Firebird: `FIRST n` / `SKIP n` / `ROWS n TO m`.
- **DDL автокоммитится** после выполнения — откат невозможен.
- **Пустая строка = NULL** в VARCHAR (сохранено и в диалекте 3).

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

### Пример: таблица с BOOLEAN и автоинкрементом (диалект 3)
```
1. sql_execute:
   CREATE TABLE APP_USERS (
     ID BIGINT NOT NULL,
     NAME VARCHAR(100) NOT NULL,
     IS_ACTIVE BOOLEAN DEFAULT TRUE,
     CREATED_AT TIMESTAMP DEFAULT CURRENT_TIMESTAMP
   )
2. sql_execute: CREATE GENERATOR GEN_APP_USERS_ID
3. sql_create_trigger:
   {name:"TR_APP_USERS_BI", table:"APP_USERS", timing:"BEFORE", event:"INSERT",
    body:"NEW.ID = GEN_ID(GEN_APP_USERS_ID, 1)"}
```

### Пример: запрос с BOOLEAN и диапазоном дат
```
sql_query:
SELECT FIRST 20 * FROM APP_USERS
WHERE IS_ACTIVE = TRUE AND CREATED_AT >= '2026-01-01 00:00:00'
ORDER BY CREATED_AT DESC
```

### Пример: вызов процедуры с параметрами
```
1. sql_describe_procedure {procedure:"P_GET_USER"}  — узнать порядок параметров
2. sql_execute_procedure {procedure:"P_GET_USER", parameters:[42]}
```

## Миграция диалект 1 → диалект 3 (если база старая)

- Проверить `sql_describe_table` по ключевым таблицам: DATE-колонки
  в диалекте 3 читаются как дата без времени.
- SMALLINT-флаги 0/1 можно оставить — BOOLEAN требует ALTER и перелинковку кода.
- Имена, созданные в кавычках нижним регистром, останутся регистрозависимыми.

## Безопасность (сервер блокирует автоматически)

- В `sql_query` проходит только SELECT (сервер отклонит остальное).
- **UPDATE/DELETE без WHERE блокируются** (`require_where = true`) — всегда
  пишите условие. Осознанная массовая операция: через `EXECUTE BLOCK`
  либо временно `require_where = false` в `mcp-config.ini`.
- **Несколько инструкций через `;` запрещены** — выполняйте по одной.
  Исключение: тело `CREATE TRIGGER/PROCEDURE ... AS BEGIN ... END`.
- **Комментарии `--` и `/* */` вырезаются**, паттерны чужих СУБД
  (`xp_cmdshell`, `pg_sleep`, `benchmark(`, `INTO OUTFILE`) — блокируются.
- **`?`-плейсхолдеры запрещены** — сервер исполняет SQL буквально:
  `WHERE D >= DATE '2026-10-01'`, `FIRST 30`, строки в одинарных кавычках.
- **Имена объектов** (`table`, `procedure`, `name`) проверяются как
  идентификатор `[A-Za-z_][A-Za-z0-9_$]{0,30}` — кавычки и спецсимволы отвергаются
  (регистрозависимые имена в кавычках через эти поля не передать).
- **Таймаут запроса** 30 сек (`query_timeout_sec`), лимит строк
  `max_query_rows` (SELECT) и `max_proc_rows` (процедуры).
- DDL необратим — перед DROP/ALTER подтвердите у пользователя.

## Запрещённый синтаксис (частые ошибки)

- **Нет `CREATE TABLE IF NOT EXISTS`** / `IF NOT EXISTS` — в Firebird его нет.
  Используйте `CREATE OR ALTER TABLE` (FB4+), либо создавайте таблицу один раз.
- **Нет `LIMIT`** — только `FIRST n [SKIP m]` или `ROWS n TO m`.
- **`CURRENT_DATE`** в диалекте 3 существует, но для колонок предпочтителен
  `CURRENT_TIMESTAMP`.
- Триггер/процедуру создавайте **после** таблицы, к которой они относятся.

## Ошибки

Ответ с `isError: true` содержит полный текст ошибки Firebird
(SQLCODE, GDSC, вложенные причины) — исправляйте SQL по тексту,
а не повторяйте тот же запрос. Сообщения «Security error» / «Blocked ...»
издаёт валидатор сервера — перепишите запрос по правилам выше.
