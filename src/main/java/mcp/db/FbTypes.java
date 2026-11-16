package mcp.db;

import mcp.tools.ToolSupport;

import java.util.Map;

/**
 * Общие типы Firebird (диалект 1): SQL-шаблон CASE и рендер имени типа.
 *
 * <p>SQL-шаблон и логика {@code renderType} раньше дублировались в
 * {@code SqlDescribeTableTool} и {@code SqlDescribeProcedureTool} —
 * DECIMAL (RDB$FIELD_TYPE=27, scale≠0) vs DOUBLE PRECISION (scale=0)
 * — одинаковый код, одинаковые скобки/запятые. Здесь единственная реализация.</p>
 *
 * <p>Все числовые коды типов — из Jaybird (RDB$FIELD_TYPE), не зависят от версии драйвера.</p>
 */
public final class FbTypes {

    /**
     * SQL-фрагмент CASE для рендера читаемого имени типа из RDB$FIELD_TYPE.
     * <p>
     * Используется в sql_describe_table и sql_describe_procedure.
     */
    public static final String TYPE_CASE_SQL =
            "CASE " +
            "  WHEN f.RDB$FIELD_TYPE = 27 AND f.RDB$FIELD_SCALE != 0 THEN 'DECIMAL' " +
            "  WHEN f.RDB$FIELD_TYPE = 27 THEN 'DOUBLE PRECISION' " +
            "  WHEN f.RDB$FIELD_TYPE = 7 THEN 'SMALLINT' " +
            "  WHEN f.RDB$FIELD_TYPE = 8 THEN 'INTEGER' " +
            "  WHEN f.RDB$FIELD_TYPE = 10 THEN 'FLOAT' " +
            "  WHEN f.RDB$FIELD_TYPE = 12 THEN 'DATE' " +
            "  WHEN f.RDB$FIELD_TYPE = 13 THEN 'TIME' " +
            "  WHEN f.RDB$FIELD_TYPE = 14 THEN 'CHAR' " +
            "  WHEN f.RDB$FIELD_TYPE = 16 THEN 'BIGINT' " +
            "  WHEN f.RDB$FIELD_TYPE = 23 THEN 'BOOLEAN' " +
            "  WHEN f.RDB$FIELD_TYPE = 26 THEN 'QUAD' " +
            "  WHEN f.RDB$FIELD_TYPE = 35 THEN 'TIMESTAMP' " +
            "  WHEN f.RDB$FIELD_TYPE = 37 THEN 'VARCHAR' " +
            "  WHEN f.RDB$FIELD_TYPE = 40 THEN 'BLOB' " +
            "  WHEN f.RDB$FIELD_TYPE = 93 THEN 'ARRAY' " +
            "  ELSE 'TYPE_' || f.RDB$FIELD_TYPE " +
            "END";

    /**
     * Сборка читаемого имени типа из результата описания таблицы/процедуры.
     *
     * @param row  строка результата (col_name → value)
     * @param dtypeCol имя колонки с именем типа (P_DTYPE / COL_TYPE)
     * @param lenCol   имя колонки с длиной (P_LEN / COL_SIZE)
     * @param precCol  имя колонки с точностью (P_PREC / COL_SIZE)
     * @param scaleCol имя колонки с масштабностью (P_SCALE / COL_SCALE)
     * @return читаемый тип: VARCHAR(50), DECIMAL(15,2), DOUBLE PRECISION и т.п.
     */
    public static String renderType(Map<String, Object> row,
                                    String dtypeCol,
                                    String lenCol,
                                    String precCol,
                                    String scaleCol) {
        String type = ToolSupport.str(row.get(dtypeCol));
        Object len = row.get(lenCol);
        Object prec = row.get(precCol);
        Object scaleObj = row.get(scaleCol);
        int scale = scaleObj == null ? 0 : ((Number) scaleObj).intValue();

        if ("CHAR".equals(type) || "VARCHAR".equals(type)) {
            return type + "(" + ToolSupport.str(len) + ")";
        }
        if ("DOUBLE PRECISION".equals(type) || "FLOAT".equals(type)) {
            return type;
        }
        if (prec != null && scale < 0) {
            return type + "(" + ToolSupport.str(prec) + "," + (-scale) + ")";
        }
        if (prec != null) {
            return type + "(" + ToolSupport.str(prec) + ")";
        }
        return type;
    }

    private FbTypes() {
    }
}
