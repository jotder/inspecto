package {{packageName}};

import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineRel;
import com.gamma.pipeline.exec.PipelineNodeExecutor;
import com.gamma.pipeline.exec.RowShaper;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The <b>execution</b> half of {@code transform.{{typeSuffix}}}: keeps the first {@code keep} characters of
 * each listed column and replaces the rest with {@code mask}, so a subscriber identifier stays joinable
 * on its prefix without carrying the full value downstream.
 *
 * <p><b>Config the Step reads</b> (the operator's authored block, verbatim from the pipeline config):
 * <pre>
 * columns: ["msisdn", "imsi"]   # required — the columns to redact
 * keep:    5                    # optional, default 4 — leading characters preserved
 * mask:    "*"                  # optional, default "*" — the replacement character
 * </pre>
 *
 * <h2>The contract, and the three ways to get it wrong</h2>
 * <ul>
 *   <li>🔴 <b>Create the output tables yourself</b>, named {@code outPrefix + "__" + relationship}, and
 *       return one {@link RowShaper.Relation} per table. Nothing renames them afterwards — the caller
 *       reads exactly the names you return.</li>
 *   <li>🔴 <b>Quote every identifier.</b> Column names come from an operator's config and reach SQL
 *       directly; a name like {@code order} is a keyword and one containing a quote is an injection.
 *       {@link #quote} is the whole defence.</li>
 *   <li>⚠ <b>Fail by throwing.</b> A bad config should raise here rather than produce a silently wrong
 *       relation — the batch fails with your message, which is what an operator can act on.</li>
 * </ul>
 *
 * <p>⚠ <b>It runs on a SEALED connection in preview.</b> The component preview seals its DuckDB
 * (`enable_external_access=false`), so an executor that reads a file works in production and fails in
 * the editor's "Test this Step" — read nothing outside {@code input}.
 */
public final class {{className}}Executor implements PipelineNodeExecutor {

    private static final int DEFAULT_KEEP = 4;
    private static final String DEFAULT_MASK = "*";

    @Override
    public String type() {
        return {{className}}NodeType.TYPE;
    }

    @Override
    public List<RowShaper.Relation> shape(Connection conn, PipelineNode node, String input, String outPrefix,
                                          RowShaper.ReferenceResolver references) throws SQLException {
        List<String> columns = columnsOf(node);
        int keep = intOr(node.cfg("keep"), DEFAULT_KEEP);
        String mask = node.cfg("mask") == null ? DEFAULT_MASK : node.cfg("mask").toString();
        if (keep < 0) throw new IllegalArgumentException(
                "{{id}} node '" + node.id() + "': keep must be >= 0, got " + keep);

        // SELECT * EXCLUDE(<redacted>), <redacted expressions> — every other column travels untouched,
        // which is what keeps this composable with whatever Step follows.
        StringBuilder sel = new StringBuilder("SELECT * EXCLUDE (");
        for (int i = 0; i < columns.size(); i++) sel.append(i > 0 ? ", " : "").append(quote(columns.get(i)));
        sel.append(')');
        for (String col : columns) {
            String q = quote(col);
            sel.append(", CASE WHEN ").append(q).append(" IS NULL THEN NULL ELSE ")
               .append("left(CAST(").append(q).append(" AS VARCHAR), ").append(keep).append(") || ")
               .append("repeat(").append(literal(mask)).append(", ")
               .append("greatest(length(CAST(").append(q).append(" AS VARCHAR)) - ").append(keep).append(", 0))")
               .append(" END AS ").append(q);
        }
        sel.append(" FROM ").append(quote(input));

        String data = outPrefix + "__" + PipelineRel.DATA;
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE " + quote(data) + " AS " + sel);
        }
        return List.of(new RowShaper.Relation(PipelineRel.DATA, data));
    }

    /** The {@code columns} list, refused when absent — a redaction Step that redacts nothing is a typo. */
    private static List<String> columnsOf(PipelineNode node) {
        Object raw = node.cfg("columns");
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                String s = o == null ? "" : o.toString().trim();
                if (!s.isEmpty()) out.add(s);
            }
        } else if (raw != null && !raw.toString().isBlank()) {
            out.add(raw.toString().trim());               // a single column may be authored as a scalar
        }
        if (out.isEmpty()) throw new IllegalArgumentException(
                "{{id}} node '" + node.id() + "' needs a non-empty 'columns' list");
        return out;
    }

    private static int intOr(Object value, int fallback) {
        if (value == null || value.toString().isBlank()) return fallback;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("{{id}}: 'keep' must be a whole number, got: " + value, e);
        }
    }

    /** Quote a SQL identifier, escaping embedded double quotes — see the contract note above. */
    private static String quote(String ident) {
        return '"' + ident.replace("\"", "\"\"") + '"';
    }

    /** A single-quoted SQL string literal, escaping embedded quotes. */
    private static String literal(String s) {
        return "'" + s.replace("'", "''") + "'";
    }
}
