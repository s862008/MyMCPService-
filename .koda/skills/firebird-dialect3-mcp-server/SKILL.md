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

## Безопасность

- В `sql_query` проходит только SELECT (сервер отклонит остальное).
- DDL необратим — перед DROP/ALTER подтвердите у пользователя.
- Результаты SELECT ограничены 10000 строк.

## Ошибки

Ответ с `isError: true` содержит полный текст ошибки Firebird
(SQLCODE, GDSC, вложенные причины) — исправляйте SQL по тексту,
а не повторяйте тот же запрос.
