package com.gamma.query;

import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates one scalar Measure over a Dataset (BI-5 measure alerts): parses {@code count} /
 * {@code sum(amount)}-style measure text, compiles it with {@link MeasureCompiler} (validated
 * identifiers only), resolves the dataset like {@code /bi/query} does, and runs it in the ephemeral
 * DuckDB sandbox. Returns empty — never throws — when the value cannot be computed (no write root,
 * unknown dataset, SQL failure): an alert sweep must degrade, not disturb ingest.
 *
 * <p>Roots are suppliers because both are wiring-time-unknown (the write root is a {@code -D}
 * property that tests set per-case; the data root is per-space).
 */
public final class DatasetMeasureProbe {

    private static final Logger log = LoggerFactory.getLogger(DatasetMeasureProbe.class);
    /**
     * ⚠ The aggregation alternation is BUILT FROM {@link MeasureCompiler#AGGS} rather than spelled out,
     * so adding an aggregate cannot leave this validator rejecting what the compiler accepts
     * (`MEASURE-SHORTHAND-ONE-HOME-1` — it was a fifth independent statement of the same grammar, and
     * the only one expressed as a regex). Same package, so the package-private list is reachable; the
     * produced pattern is byte-identical to the literal it replaced.
     */
    private static final Pattern MEASURE = Pattern.compile(
            "(count)|(" + String.join("|", MeasureCompiler.AGGS) + ")\\(([A-Za-z_][A-Za-z0-9_]*)\\)");

    private final Supplier<Path> writeRoot;
    private final Supplier<Path> dataRoot;

    public DatasetMeasureProbe(Supplier<Path> writeRoot, Supplier<Path> dataRoot) {
        this.writeRoot = writeRoot;
        this.dataRoot = dataRoot;
    }

    /** Parse-check a measure expression ({@code count} or {@code agg(field)}); used by rule validation. */
    public static boolean validMeasure(String text) {
        return text != null && MEASURE.matcher(text.trim()).matches();
    }

    /** The longest {@code description} {@link #label} will put in an alert title (see there). */
    static final int MAX_LABEL = 60;

    /**
     * The Dataset's readable name for alert TEXT (R2-05 follow-up), or {@code null} when it has none —
     * the caller then names it by its id. Read from the same registry {@link #value} resolves against.
     *
     * <p>The rule: its {@code description}, trailing period dropped, when that is at most
     * {@value #MAX_LABEL} characters — a title is one line, and a paragraph-length description would
     * bury the breach; else {@code null}. ⚠ Not its {@code name}: a Dataset's {@code name} IS its id
     * ({@link ComponentStore#write} stamps it so), so there is no separate display name to prefer.
     * Never throws: an unreadable registry is just "no label".
     */
    public String label(String datasetId) {
        try {
            Path root = writeRoot.get();
            if (root == null || datasetId == null) return null;
            Map<String, Object> dataset = new ComponentStore(root.resolve("registry")).get("dataset", datasetId)
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (dataset == null) return null;
            Object raw = dataset.get("description");
            String description = raw == null ? "" : raw.toString().trim();
            if (description.endsWith(".")) description = description.substring(0, description.length() - 1).trim();
            return description.isEmpty() || description.length() > MAX_LABEL ? null : description;
        } catch (Exception e) {
            log.debug("dataset label for '{}' unavailable: {}", datasetId, e.getMessage());
            return null;
        }
    }

    /** The measure's current value over the dataset, or empty when it cannot be computed (see class doc). */
    public OptionalDouble value(String datasetId, String measureText) {
        try {
            Path root = writeRoot.get();
            if (root == null) {
                log.debug("measure probe: no write root — cannot resolve dataset '{}'", datasetId);
                return OptionalDouble.empty();
            }
            Matcher m = MEASURE.matcher(measureText.trim());
            if (!m.matches()) return OptionalDouble.empty();
            MeasureCompiler.Measure measure = m.group(1) != null
                    ? new MeasureCompiler.Measure("count", null)
                    : new MeasureCompiler.Measure(m.group(2), m.group(3));

            MeasureCompiler.Spec spec = new MeasureCompiler.Spec(
                    datasetId, List.of(measure), List.of(), Map.of(), List.of(), List.of(), 1);
            String sql = MeasureCompiler.compile(spec);

            ComponentStore store = new ComponentStore(root.resolve("registry"));
            Map<String, Object> dataset = store.get("dataset", datasetId)
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (dataset == null) {
                log.warn("measure probe: unknown dataset '{}'", datasetId);
                return OptionalDouble.empty();
            }
            String relationSql = DatasetRelation.relationSql(dataset, dataRoot.get(),
                    new ViewStore(root.resolve("views")));

            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    datasetId, relationSql, sql, 1, 0, List.of(), List.of()));
            if (r.rows().isEmpty()) return OptionalDouble.empty();
            Object v = r.rows().get(0).get(measure.id());
            return v instanceof Number n ? OptionalDouble.of(n.doubleValue()) : OptionalDouble.empty();
        } catch (Exception e) {
            log.warn("measure probe failed for {} over dataset '{}': {}", measureText, datasetId, e.getMessage());
            return OptionalDouble.empty();
        }
    }
}
