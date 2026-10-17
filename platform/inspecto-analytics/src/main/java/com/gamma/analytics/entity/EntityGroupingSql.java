package com.gamma.analytics.entity;

import static com.gamma.util.SqlIdent.q;

import com.gamma.analytics.similarity.SimilaritySql;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The DuckDB form of {@link EntityGrouping} (ANALYTICS-CORE-1 S2), set-based: normalisation, blocking and match cells
 * are window functions over the input; the connected components are found by <b>min-label propagation with pointer
 * jumping</b>, one set-based statement pair per round, until no label changes. It is the kernel's one entry that
 * executes statements rather than returning a string, because the number of rounds is only known by running them.
 *
 * <p>Every record's label starts as its own key text; each round a record takes the smallest label among itself and
 * the records it shares a match cell with, then the label of its label. Labels only fall and stay inside the
 * component, so the fixed point is the component's smallest key text — {@link EntityGrouping}'s group id. More than
 * {@link #MAX_ROUNDS} rounds fails closed. {@code EntityGroupingParityTest} holds the two forms equal.
 *
 * <p>Column names are the caller's, validated by the caller; this class quotes every one ({@code SqlIdent.q}). The
 * threshold is a parsed number re-printed. No authored text reaches the SQL as text.
 */
public final class EntityGroupingSql {

    private EntityGroupingSql() {
    }

    /** The most propagation rounds before grouping fails closed. */
    public static final int MAX_ROUNDS = 1_000;

    /** The output column names. */
    public record Columns(String group, String candidateScore, String candidateGroup, String candidateReview) {
        public Columns {
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(candidateScore, "candidateScore");
            Objects.requireNonNull(candidateGroup, "candidateGroup");
            Objects.requireNonNull(candidateReview, "candidateReview");
        }

        /** {@code resolution_group}, {@code candidate_score}, {@code candidate_group}, {@code candidate_review}. */
        public static final Columns DEFAULT =
                new Columns("resolution_group", "candidate_score", "candidate_group", "candidate_review");

        /** The columns a spec adds, in order. */
        public List<String> added(EntityGrouping.Spec spec) {
            return spec.candidate() == null ? List.of(group)
                    : List.of(group, candidateScore, candidateGroup, candidateReview);
        }
    }

    /**
     * Group {@code input} into a new table {@code output}: every input row plus the group id (the key's own type) and,
     * with a similarity review, the candidate score (DOUBLE), the candidate's group (the key's type) and the review flag
     * (BOOLEAN). Scratch tables are named {@code scratch + "_…"} and dropped before returning.
     *
     * @return the propagation rounds run
     * @throws IllegalStateException a key, cap or round refusal — nothing is written then
     */
    public static int run(Connection conn, EntityGrouping.Spec spec, Columns columns, String input, String output,
                          String scratch) throws SQLException {
        String src = q(scratch + "_src"), edges = q(scratch + "_edges"), labels = q(scratch + "_labels");
        String next = q(scratch + "_next"), jumped = q(scratch + "_jumped"), cand = q(scratch + "_cand");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + src + " AS " + sourceSql(spec, input));
            String refusal;
            try (ResultSet rs = st.executeQuery("SELECT count(*) FILTER (WHERE __k IS NULL), count(__k) - count(DISTINCT __k) FROM " + src)) {
                rs.next();
                refusal = spec.keyRefusal(rs.getLong(1), rs.getLong(2));
            }
            if (refusal == null)
                try (ResultSet rs = st.executeQuery(checkSql(spec, src))) {
                    rs.next();
                    refusal = spec.capRefusal(rs.getLong(1), rs.getLong(2));
                }
            if (refusal != null) throw new IllegalStateException(refusal);

            // both directions of every star edge, plus a loop on every record so each one is in every round
            st.execute("CREATE TABLE " + edges + " AS WITH s AS (" + starsSql(spec, src) + ") SELECT DISTINCT a, b FROM ("
                    + "SELECT a, b FROM s UNION ALL SELECT b, a FROM s UNION ALL SELECT __k, __k FROM " + src + ")");
            st.execute("CREATE TABLE " + labels + " AS SELECT __k AS n, __k AS g FROM " + src);
            int rounds = 0;
            while (true) {
                if (++rounds > MAX_ROUNDS)
                    throw new IllegalStateException("the groups did not settle within " + MAX_ROUNDS + " rounds");
                st.execute("CREATE OR REPLACE TABLE " + next + " AS SELECT e.a AS n, min(l.g) AS g FROM " + edges
                        + " e JOIN " + labels + " l ON l.n = e.b GROUP BY e.a");
                st.execute("CREATE OR REPLACE TABLE " + jumped + " AS SELECT x.n, least(x.g, y.g) AS g FROM " + next
                        + " x JOIN " + next + " y ON y.n = x.g");
                long changed;
                try (ResultSet rs = st.executeQuery("SELECT count(*) FROM " + jumped + " j JOIN " + labels
                        + " l ON l.n = j.n WHERE j.g <> l.g")) {
                    rs.next();
                    changed = rs.getLong(1);
                }
                st.execute("CREATE OR REPLACE TABLE " + labels + " AS SELECT n, g FROM " + jumped);
                if (changed == 0) break;
            }
            if (spec.candidate() != null) st.execute("CREATE TABLE " + cand + " AS " + candidateSql(spec, src, labels));
            st.execute("CREATE TABLE " + q(output) + " AS " + outputSql(spec, columns, input, labels, cand));
            return rounds;
        } finally {
            try (Statement st = conn.createStatement()) {
                for (String t : List.of(src, edges, labels, next, jumped, cand)) st.execute("DROP TABLE IF EXISTS " + t);
            }
        }
    }

    /** {@code __k} the key text; {@code __b<i>} the normalised blocking keys; {@code __c<i>_<j>} the conditions'; {@code __t} the review text. */
    static String sourceSql(EntityGrouping.Spec spec, String input) {
        List<String> cols = new ArrayList<>();
        cols.add(text(spec.keyColumn()) + " AS __k");
        for (int i = 0; i < spec.blocking().size(); i++) cols.add(normalised(spec.blocking().get(i)) + " AS __b" + i);
        for (int i = 0; i < spec.conditions().size(); i++) {
            List<KeyColumn> cs = spec.conditions().get(i).columns();
            for (int j = 0; j < cs.size(); j++) cols.add(normalised(cs.get(j)) + " AS __c" + i + "_" + j);
        }
        SimilarityCandidate c = spec.candidate();
        if (c != null) cols.add((c.normalize() ? SimilaritySql.normalize(text(c.column())) : text(c.column())) + " AS __t");
        return "SELECT " + String.join(", ", cols) + " FROM " + q(input);
    }

    /** One row: the largest match cell or review block, and the candidate pairs. */
    static String checkSql(EntityGrouping.Spec spec, String src) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < spec.conditions().size(); i++) {
            List<String> by = cellColumns(spec, i);
            parts.add("SELECT count(*) AS n FROM " + src + " WHERE " + present(by) + " GROUP BY " + String.join(", ", by));
        }
        if (spec.candidate() != null) {
            List<String> by = blockColumns(spec);
            parts.add("SELECT count(*) AS n FROM " + src + " WHERE " + present(by) + " GROUP BY " + String.join(", ", by));
        }
        return "SELECT coalesce(max(n), 0), CAST(coalesce(sum(n * (n - 1)), 0) // 2 AS BIGINT) FROM ("
                + String.join(" UNION ALL ", parts) + ")";
    }

    /** Each record in a match cell paired with the cell's smallest key text: (a, b). */
    static String starsSql(EntityGrouping.Spec spec, String src) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < spec.conditions().size(); i++) {
            List<String> by = cellColumns(spec, i);
            parts.add("SELECT __k AS a, min(__k) OVER (PARTITION BY " + String.join(", ", by) + ") AS b FROM " + src
                    + " WHERE " + present(by));
        }
        return String.join(" UNION ALL ", parts);
    }

    /** Per record of a review block: the best score against a record of another group, and that group's key text. */
    static String candidateSql(EntityGrouping.Spec spec, String src, String labels) {
        SimilarityCandidate c = spec.candidate();
        List<String> on = new ArrayList<>();
        for (String b : blockColumns(spec)) on.add("a." + b + " = o." + b);
        String pairs = "SELECT a.__k AS n, " + c.method().sql("a.__t", "o.__t") + " AS score, lo.g AS grp FROM " + src
                + " a JOIN " + src + " o ON " + String.join(" AND ", on) + " JOIN " + labels + " la ON la.n = a.__k JOIN "
                + labels + " lo ON lo.n = o.__k WHERE la.g <> lo.g AND a.__t IS NOT NULL AND o.__t IS NOT NULL";
        return "SELECT n, score, grp FROM (SELECT n, score, grp, row_number() OVER (PARTITION BY n ORDER BY score DESC, grp)"
                + " AS rk FROM (" + pairs + ")) WHERE rk = 1";
    }

    /** Every input row with the added columns; group ids are mapped back to the key's own type. */
    static String outputSql(EntityGrouping.Spec spec, Columns columns, String input, String labels, String cand) {
        String key = q(spec.keyColumn());
        String native_ = "(SELECT " + text(spec.keyColumn()) + " AS __t, " + key + " AS __v FROM " + q(input) + ")";
        StringBuilder sql = new StringBuilder("SELECT i.*, gk.__v AS ").append(q(columns.group()));
        SimilarityCandidate c = spec.candidate();
        if (c != null)
            sql.append(", c.score AS ").append(q(columns.candidateScore()))
                    .append(", ck.__v AS ").append(q(columns.candidateGroup()))
                    .append(", coalesce(c.score >= CAST(").append(c.threshold()).append(" AS DOUBLE), false) AS ")
                    .append(q(columns.candidateReview()));
        sql.append(" FROM ").append(q(input)).append(" i JOIN ").append(labels).append(" l ON l.n = CAST(i.")
                .append(key).append(" AS VARCHAR) JOIN ").append(native_).append(" gk ON gk.__t = l.g");
        if (c != null)
            sql.append(" LEFT JOIN ").append(cand).append(" c ON c.n = l.n LEFT JOIN ").append(native_)
                    .append(" ck ON ck.__t = c.grp");
        return sql.toString();
    }

    private static List<String> blockColumns(EntityGrouping.Spec spec) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < spec.blocking().size(); i++) out.add("__b" + i);
        return out;
    }

    private static List<String> cellColumns(EntityGrouping.Spec spec, int condition) {
        List<String> out = blockColumns(spec);
        for (int j = 0; j < spec.conditions().get(condition).columns().size(); j++) out.add("__c" + condition + "_" + j);
        return out;
    }

    private static String present(List<String> columns) {
        return String.join(" AND ", columns.stream().map(c -> c + " IS NOT NULL").toList());
    }

    private static String text(String column) {
        return "CAST(" + q(column) + " AS VARCHAR)";
    }

    private static String normalised(KeyColumn k) {
        return k.normalization().sql(text(k.column()));
    }
}
