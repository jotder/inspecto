package com.gamma.analytics.model;

import com.gamma.util.SqlIdent;
import java.util.ArrayList;
import java.util.List;

/**
 * The DuckDB form of {@link KMeans.Model#assign}: the nearest centroid of a stored model, set-based, so scoring entities
 * against a named model version needs no JVM loop (ANALYTICS-CORE-1 C2). <b>Assignment only</b> — a fit is a Job /
 * offline concern (W7) and has no SQL form.
 *
 * <p>The cluster is the 0-based index of the smallest squared distance in standardised space, the lowest index on a tie
 * ({@code list_position} finds the first match), exactly as the JVM form. A row with a NULL or NaN in any input column
 * gets a NULL cluster (the JVM form refuses NaN; both therefore label the same rows). Every model number is printed
 * from its shortest round-trip text, so the SQL carries the model's exact doubles ({@code KMeansDuckDbParityTest}).
 */
public final class KMeansSql {
    private KMeansSql() {}

    /** The expression for the cluster index of the row's {@code columns} (in the model's column order). */
    public static String clusterExpression(List<String> columns, KMeans.Model model) {
        if (columns.size() != model.dimensions())
            throw new IllegalArgumentException("the model takes " + model.dimensions() + " columns, got " + columns.size());
        List<String> z = new ArrayList<>();
        List<String> present = new ArrayList<>();
        for (int j = 0; j < columns.size(); j++) {
            String x = "nullif(CAST(" + SqlIdent.q(columns.get(j)) + " AS DOUBLE), CAST('NaN' AS DOUBLE))";
            z.add("((" + x + " - " + number(model.means()[j]) + ") / " + number(model.scales()[j]) + ")");
            present.add(x + " IS NOT NULL");
        }
        List<String> dist = new ArrayList<>();
        for (int c = 0; c < model.k(); c++) {
            List<String> terms = new ArrayList<>();
            for (int j = 0; j < z.size(); j++) terms.add("(" + z.get(j) + " - " + number(model.centroids()[c][j]) + ") ^ 2");
            dist.add("(" + String.join(" + ", terms) + ")");
        }
        String list = "[" + String.join(", ", dist) + "]";
        return "CASE WHEN " + String.join(" AND ", present) + " THEN CAST(list_position(" + list + ", list_min(" + list
                + ")) - 1 AS INTEGER) END";
    }

    /** {@code SELECT *, <cluster> AS <clusterColumn> FROM relation} ({@code relation} is the caller's already-safe SQL). */
    public static String select(String relation, List<String> columns, KMeans.Model model, String clusterColumn) {
        return "SELECT *, " + clusterExpression(columns, model) + " AS " + SqlIdent.q(clusterColumn) + " FROM " + relation;
    }

    private static String number(double d) {
        if (!Double.isFinite(d)) throw new IllegalArgumentException("a SQL number literal must be finite, got " + d);
        return "CAST('" + d + "' AS DOUBLE)";
    }
}
