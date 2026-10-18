package com.gamma.analytics.model;

import com.gamma.util.SqlIdent;
import java.util.ArrayList;
import java.util.List;

/**
 * The DuckDB form of {@link LogisticRegression.Model#probability}: score entities against a stored model version
 * set-based, with no JVM loop (ANALYTICS-CORE-1 C3). <b>Scoring only</b> — a fit is a Job / offline concern (W7) and has
 * no SQL form. A row with a NULL or NaN input gets a NULL probability (the JVM form refuses NaN). Every model number is
 * printed from its shortest round-trip text, so the SQL carries the model's exact doubles; the two forms agree to
 * floating-point rounding of {@code exp} ({@code LogisticDuckDbParityTest}).
 */
public final class LogisticSql {
    private LogisticSql() {}

    /** The expression for {@code P(positive)} of the row's {@code columns} (in the model's input order). */
    public static String probabilityExpression(List<String> columns, LogisticRegression.Model model) {
        if (columns.size() != model.features())
            throw new IllegalArgumentException("the model takes " + model.features() + " columns, got " + columns.size());
        List<String> present = new ArrayList<>();
        StringBuilder logOdds = new StringBuilder(number(model.intercept()));
        for (int j = 0; j < columns.size(); j++) {
            String x = "nullif(CAST(" + SqlIdent.q(columns.get(j)) + " AS DOUBLE), CAST('NaN' AS DOUBLE))";
            present.add(x + " IS NOT NULL");
            logOdds.append(" + ").append(number(model.coefficients()[j])).append(" * ((").append(x).append(" - ")
                    .append(number(model.means()[j])).append(") / ").append(number(model.scales()[j])).append(")");
        }
        // the numerically stable form: exp of a non-positive number only
        String s = "(" + logOdds + ")";
        return "CASE WHEN " + String.join(" AND ", present) + " THEN CASE WHEN " + s + " >= 0 THEN 1.0 / (1.0 + exp(-" + s
                + ")) ELSE exp(" + s + ") / (1.0 + exp(" + s + ")) END END";
    }

    /** {@code SELECT *, <probability> AS <column> FROM relation} ({@code relation} is the caller's already-safe SQL). */
    public static String select(String relation, List<String> columns, LogisticRegression.Model model, String probabilityColumn) {
        return "SELECT *, " + probabilityExpression(columns, model) + " AS " + SqlIdent.q(probabilityColumn) + " FROM " + relation;
    }

    private static String number(double d) {
        if (!Double.isFinite(d)) throw new IllegalArgumentException("a SQL number literal must be finite, got " + d);
        return "CAST('" + d + "' AS DOUBLE)";
    }
}
