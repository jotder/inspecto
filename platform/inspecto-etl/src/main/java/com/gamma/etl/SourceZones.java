package com.gamma.etl;

import com.gamma.config.spec.SourceZoneGrammar;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The <b>source</b> time zone of a temporal column, and the SQL that normalises it.
 *
 * <p><b>The problem.</b> Every temporal value this engine parses is naive: {@code TRY_STRPTIME}
 * returns a zone-less {@code TIMESTAMP}, partitions and {@code __event_time} derive from the same
 * naive expression, and no connection ever issues {@code SET TimeZone}. That is correct and
 * consistent as long as nothing ever interprets those values as instants — but DuckDB's session zone
 * is the <em>host</em>, so the moment one is cast to {@code TIMESTAMPTZ} it silently acquires the
 * server's offset, not the data's.
 *
 * <p><b>The policy.</b> A column's source zone may be declared three ways, most specific first:
 * <ol>
 *   <li>{@code raw.fields[].timezone_column} — a sibling raw column holding a zone name per row;</li>
 *   <li>{@code raw.fields[].timezone} — one fixed IANA zone for the column;</li>
 *   <li>{@code parsing.source_timezone} — one fixed IANA zone for the whole pipeline.</li>
 * </ol>
 * With none of them the column stays exactly as it is today — wall-clock, untouched. <b>That is the
 * default, so an existing pipeline compiles byte-for-byte identically.</b>
 *
 * <p><b>The compiled shape</b> is {@code timezone('UTC', timezone(Z, <naive-parse>))}, which reads
 * the naive text <em>as</em> a wall clock in {@code Z} and hands back the same instant expressed as
 * naive UTC. Downstream stays naive and therefore stays session-independent: partitions, BI grains
 * and dedup keys all keep comparing like with like. A {@code TIMESTAMPTZ} target instead keeps the
 * real instant ({@link #toInstant}).
 *
 * <p><b>Zones a date does not have.</b> Only {@code TIMESTAMP} and {@code TIMESTAMPTZ} are shifted.
 * A {@code DATE} carries no instant, so shifting it would move a calendar day across midnight for
 * any negative-offset zone — see {@code TransformCompiler#filenameDate} for the same call.
 *
 * <h2>Measured against DuckDB 1.5.2.1 — do not re-derive these by reasoning</h2>
 * <ul>
 *   <li>The {@code icu} extension is <b>already loaded</b> on a bare connection and named zones work
 *       under the {@code SqlSandbox} seal, so nothing here needs an extension load.</li>
 *   <li>🔴 <b>Offset forms are rejected</b>: {@code timezone('+05:30', …)} and {@code timezone('Z', …)}
 *       both raise <i>Unknown TimeZone</i>. Only region ids work, which is why {@link #validateZone}
 *       gates on {@link ZoneId#getAvailableZoneIds()} — a set measured to be a strict subset of
 *       DuckDB's {@code pg_timezone_names()}, pinned by {@code SourceZonesTest}.</li>
 *   <li>🔴 <b>An unknown zone is a hard error, and {@code TRY()} does NOT catch it</b> (it is a
 *       <i>Not implemented</i> error). One bad value in a {@code timezone_column} would otherwise
 *       kill the whole batch — hence the {@code pg_timezone_names()} lookup in
 *       {@link #zoneArg}, which yields NULL for an unknown or NULL zone instead. NULL zone ⇒ NULL
 *       result, which is the same "bad value becomes NULL" contract every other coercion here has,
 *       and the cast-failure audit already counts it.</li>
 *   <li>The guarded per-row form cost ~2µs/row on 200k rows; the fixed-zone form is a literal and
 *       costs nothing. Only a configured {@code timezone_column} pays.</li>
 * </ul>
 */
public final class SourceZones {

    /** No zone declared anywhere — every expression compiles exactly as it did before this existed. */
    public static final SourceZones NONE = new SourceZones(Map.of(), Map.of(), null);

    private final Map<String, String> fixedByField;
    private final Map<String, String> columnByField;
    private final String pipelineZone;

    private SourceZones(Map<String, String> fixedByField,
                        Map<String, String> columnByField,
                        String pipelineZone) {
        this.fixedByField  = fixedByField;
        this.columnByField = columnByField;
        this.pipelineZone  = pipelineZone;
    }

    /**
     * Read the policy off a schema's {@code raw.fields[]} plus the pipeline-level default.
     *
     * <p>Values are assumed already validated at config load ({@code Identifiers.validateSchema} and
     * {@code PipelineConfigParser}); this is a pure projection, not a second gate.
     */
    @SuppressWarnings("unchecked")
    public static SourceZones of(Map<String, Object> schemaConfig, String pipelineZone) {
        Map<String, String> fixed  = new LinkedHashMap<>();
        Map<String, String> column = new LinkedHashMap<>();
        Object raw = schemaConfig == null ? null : schemaConfig.get("raw");
        if (raw instanceof Map<?, ?> rawMap && rawMap.get("fields") instanceof List<?> fields) {
            for (Object f : fields) {
                if (!(f instanceof Map<?, ?> fm)) continue;
                String name = str(fm.get("name"));
                if (name == null) continue;
                String tz  = str(fm.get("timezone"));
                String tzc = str(fm.get("timezone_column"));
                if (tz  != null) fixed.put(name, tz);
                if (tzc != null) column.put(name, tzc);
            }
        }
        String pipe = str(pipelineZone);
        if (fixed.isEmpty() && column.isEmpty() && pipe == null) return NONE;
        return new SourceZones(Map.copyOf(fixed), Map.copyOf(column), pipe);
    }

    /** Whether any zone policy applies at all — {@code true} means every expression is unchanged. */
    public boolean isEmpty() {
        return fixedByField.isEmpty() && columnByField.isEmpty() && pipelineZone == null;
    }

    /**
     * The SQL <b>zone argument</b> for {@code field}, or {@code null} when no zone applies to it and
     * the expression must stay untouched.
     *
     * <p>Precedence is row-column &gt; column &gt; pipeline &gt; none. The row-column form resolves
     * through {@code pg_timezone_names()} so an unknown or NULL zone becomes NULL rather than a
     * batch-killing error (see the class javadoc).
     *
     * @param sourceTable the table a {@code timezone_column} is read from — the same table the
     *                    caller's {@code FROM} names, so the correlated lookup resolves
     */
    public String zoneArg(String field, String sourceTable) {
        String zoneCol = columnByField.get(field);
        if (zoneCol != null) {
            // ⚠ CAST to VARCHAR first. The zone column is text in the CSV lane but an already-typed
            // column in the plugin lane, and `lower()` only binds to VARCHAR — without this, a
            // non-text zone column is a BINDER error that kills the batch, which is the exact
            // failure this lookup exists to prevent. Same re-stringify rule as SchemaFieldTypes.
            String ref = "CAST(\"" + sourceTable + "\".\"" + zoneCol + "\" AS VARCHAR)";
            return "(SELECT __tz.name FROM pg_timezone_names() __tz WHERE lower(__tz.name) = lower("
                    + ref + "))";
        }
        String fixed = fixedByField.get(field);
        if (fixed == null) fixed = pipelineZone;
        return fixed == null ? null : "'" + fixed + "'";
    }

    /**
     * Reinterpret a naive expression as a wall clock in {@code zoneArg} and return the same instant
     * as <b>naive UTC</b> — session-independent, and still a plain {@code TIMESTAMP} so everything
     * downstream keeps comparing naive to naive.
     *
     * <p>{@code zoneArg == null} returns {@code naiveExpr} unchanged, which is what makes the
     * no-policy default a literal no-op rather than a differently-spelled equivalent.
     */
    public static String toNaiveUtc(String naiveExpr, String zoneArg) {
        if (zoneArg == null) return naiveExpr;
        return "timezone('UTC', timezone(" + zoneArg + ", " + naiveExpr + "))";
    }

    /**
     * Reinterpret a naive expression as a wall clock in {@code zoneArg} and keep the real instant as
     * {@code TIMESTAMPTZ} — the only correct compilation of a declared {@code TIMESTAMPTZ} field.
     *
     * <p>⛔ There is deliberately no {@code zoneArg == null} fallback: a {@code TIMESTAMPTZ} with no
     * zone source is refused at config load, because the fallback DuckDB would otherwise apply is
     * the <em>host's</em> zone, which is never what the data meant.
     */
    public static String toInstant(String naiveExpr, String zoneArg) {
        if (zoneArg == null)
            throw new IllegalArgumentException(
                    "a TIMESTAMPTZ column needs a source time zone; this must be refused at config "
                    + "load (see PipelineConfigParser's TIMESTAMPTZ check)");
        return "timezone(" + zoneArg + ", " + naiveExpr + ")";
    }

    /**
     * Fail-closed gate for an authored zone name.
     *
     * <p>Accepts exactly {@link ZoneId#getAvailableZoneIds()}. That set is <b>measured</b> to be a
     * strict subset of DuckDB's accepted names, so anything passing here is guaranteed to evaluate;
     * it also excludes the offset forms ({@code +05:30}, {@code Z}) DuckDB rejects and the
     * lower-case spellings it would accept but that no other config key allows.
     *
     * @throws IllegalArgumentException with {@code origin} named, so the operator sees which key
     */
    public static void validateZone(String zone, String origin) {
        String refusal = SourceZoneGrammar.zoneRefusal(zone, origin);
        if (refusal != null) throw new IllegalArgumentException(refusal);
    }

    /**
     * Fail-closed gate for an authored {@code date_formats}/{@code timestamp_formats} list: no format
     * may carry a <b>zone directive</b>.
     *
     * <p><b>Why this is refused rather than honoured.</b> {@code TRY_STRPTIME} with a zone directive
     * returns {@code TIMESTAMP WITH TIME ZONE} — it reads the offset correctly — but every caller here
     * finishes with {@code SqlBuilder.appendCoalesce}'s trailing {@code ::TIMESTAMP}, which renders
     * that instant in the <b>session zone, i.e. the host</b>, and throws the offset away. Measured on
     * DuckDB 1.5.2.1: {@code 2026-03-01 10:00:00+00:00} lands as {@code 15:30} / {@code 11:00} /
     * {@code 10:00} / {@code 05:00} under {@code Asia/Calcutta} / {@code Europe/Berlin} / {@code UTC} /
     * {@code America/New_York}. That is exactly the host-dependence this class exists to remove, so a
     * zone directive today is silent corruption, not a feature.
     *
     * <p>⚠ It is worse with a declared source zone than without: the host render happens <em>first</em>,
     * so {@link #toNaiveUtc} then reinterprets an already-wrong wall clock and the value can land on a
     * different calendar day per host. A {@code date_formats} entry moves {@code DATE_*} partition keys
     * the same way.
     *
     * <p>🔴 <b>There are exactly TWO such directives, and both were found by sweeping every ASCII letter
     * against the live engine rather than by reading the strptime docs</b> — {@code %z} (numeric offset)
     * and {@code %Z} (zone name). Every other accepted directive returns a naive {@code TIMESTAMP}
     * ({@code %n} returns {@code TIMESTAMP_NS}, also naive). {@code SourceZonesTest} re-runs that sweep,
     * so a DuckDB upgrade that adds a third one fails the build instead of shipping the corruption.
     *
     * <p>⚠ {@code %%} is an escaped literal percent, so {@code '%%z'} is the two characters {@code %z}
     * in the input text and stays naive — measured, and accepted here.
     *
     * <p>The predicate itself lives in {@link SourceZoneGrammar}, one module down, so the control
     * plane's authoring-time {@code CrossFieldRule} and this load-time refusal are the SAME code
     * rather than two copies that can drift.
     *
     * <p><b>This is a refusal, not the feature.</b> Making the data's own offset win — a tier above
     * {@code timezone_column} — is a deliberate build, not something to slip in by relaxing this gate.
     *
     * @param formats the authored list ({@code null} or empty is fine — nothing to check)
     * @param origin  the config key, so the operator sees which list to fix
     * @throws IllegalArgumentException naming the offending format and directive
     */
    public static void assertNoZoneDirective(List<String> formats, String origin) {
        String refusal = SourceZoneGrammar.formatRefusal(formats, origin);
        if (refusal != null) throw new IllegalArgumentException(refusal);
    }

    private static String str(Object o) {
        if (!(o instanceof String s)) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
