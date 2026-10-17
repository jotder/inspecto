package com.gamma.analytics.distribution;

import com.gamma.util.SqlIdent;

import java.math.BigDecimal;
import java.util.List;

/** SQL pieces the distribution builders share: the numeric reading of a column, number literals, the group window. */
final class DistributionSql {

    private DistributionSql() {}

    /**
     * The numeric reading the distribution forms use: the column as {@code DOUBLE}, with NaN read as NULL (missing).
     * The JVM forms refuse NaN instead; both therefore count the same values. The caller checks the column is a number.
     */
    static String numeric(String column) {
        return "nullif(CAST(" + SqlIdent.q(column) + " AS DOUBLE), CAST('NaN' AS DOUBLE))";
    }

    /** A finite double as a DuckDB {@code DOUBLE} literal, parsed from its shortest round-trip text (no exponent). */
    static String number(double d) {
        if (!Double.isFinite(d)) throw new IllegalArgumentException("a SQL number literal must be finite, got " + d);
        return "CAST('" + BigDecimal.valueOf(d).toPlainString() + "' AS DOUBLE)";
    }

    /** {@code OVER (PARTITION BY g…)} — or {@code OVER ()} for the whole relation. NULL keys form one group. */
    static String over(List<String> groupBy) {
        return groupBy.isEmpty() ? " OVER ()"
                : " OVER (PARTITION BY " + String.join(", ", groupBy.stream().map(SqlIdent::q).toList()) + ")";
    }
}
