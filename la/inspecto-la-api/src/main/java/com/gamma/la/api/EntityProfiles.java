package com.gamma.la.api;

import com.gamma.analytics.ColumnProfiler;
import com.gamma.analytics.QuantileMode;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LaLimits;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InputFingerprint;
import com.gamma.spi.http.ApiContext;
import com.gamma.sql.SqlSandboxPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.gamma.util.SqlIdent.q;

/**
 * The Entity Type profile and the Entity profile (plan {@code la-entity-profile-plan.md} §3, §4.1, §4.5): aggregates over every
 * Dataset column bound to an Entity Type ({@link EntityTypeBindings}), computed on demand in DuckDB and cached by the bound
 * Datasets' input-file fingerprint (D-EP2), so a new File invalidates a profile and nothing else does.
 *
 * <p><b>One union relation per profile.</b> Every readable binding contributes
 * {@code SELECT <i> AS b, raw, <normalised key> AS k, <time> AS ts FROM (<its Dataset's relation>)} to one {@code UNION ALL},
 * passed as the RELATION (registered before the sandbox seals, as the overlap profile's pair union is), so distinct Entities,
 * value shapes and the per-day timeline are measured ACROSS bindings - an Entity in two Datasets is one Entity (D-EP5). The
 * per-binding counts are the shared {@link ColumnProfile} pass, one per Dataset.
 *
 * <p><b>Aggregates only</b> (D-EP3): the type profile carries counts, shares, quantiles and shapes. A built-in sentinel is shown
 * by value (it is not an identifier); a dominant value only as {@code kind:"dominant", value:null}; the prefix histogram is at
 * most the normaliser's declared safe prefix ({@link EntityKeySql#prefixLength}); hubs are counted, never named.
 *
 * <p><b>Never zero for the unmeasured.</b> Every section carries {@code assessed} and {@code reason}; a read that failed, timed
 * out or had nothing to read says {@code assessed:false} with why. A read cut by a limit ({@code entity_profile.*}) is partial and
 * says {@code truncated:true}.
 */
final class EntityProfiles {

    private EntityProfiles() {}

    static final List<String> SECTIONS = List.of("bindings", "volume", "shapes", "time", "lineage", "links", "relationships", "measures");

    /** D-EP8: the built-in sentinels, matched on the trimmed value case-insensitively and shown by value. */
    static final List<String> BUILTIN_SENTINELS = List.of("", "-99", "0", "NULL", "null", "unknown", "n/a");
    private static final String SENTINEL_SQL = "lower(trim(raw)) IN ('', '-99', '0', 'null', 'unknown', 'n/a')";

    /** D-EP4: asked in order, the first answer wins. The index source goes first once the manifest carries link statistics. */
    static final List<LinkStatsSource> LINK_SOURCES = List.of(new IndexLinkStats(), new DatasetLinkStats());

    /** Days and instants are read in UTC (DuckDB's session zone is otherwise the host's); DuckDB knows "UTC", not "Z". */
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String KEYS = "__entity_keys";
    static final String LINKS = "__entity_links";
    private static final String PAIRS = "__entity_pairs";

    /** The {@code entity_profile.*} limits in force (plan §4.5), plus the Space's hub threshold. */
    record Limits(int timeoutS, int maxDatasets, long sampleRows, int topN, int sentinelSharePct, int timelineDays,
                  int relationshipThreshold, int hubThreshold, int measureSampleEntities) {
        static Limits of(Path writeRoot) {
            LaLimitValues v = SpaceLimits.of(writeRoot);
            return new Limits(SpaceLimits.get(v, LaLimit.ENTITY_PROFILE_TIMEOUT_S), SpaceLimits.get(v, LaLimit.ENTITY_PROFILE_MAX_DATASETS),
                    v.get(LaLimit.ENTITY_PROFILE_SHAPE_SAMPLE_ROWS), SpaceLimits.get(v, LaLimit.ENTITY_PROFILE_TOP_N),
                    SpaceLimits.get(v, LaLimit.ENTITY_PROFILE_SENTINEL_SHARE), SpaceLimits.get(v, LaLimit.ENTITY_PROFILE_TIMELINE_DAYS),
                    SpaceLimits.get(v, LaLimit.ENTITY_PROFILE_RELATIONSHIP_THRESHOLD),
                    LinkAnalysisSettings.forRoot(writeRoot).effectiveHubThreshold(),
                    SpaceLimits.get(v, LaLimit.ENTITY_PROFILE_MEASURE_SAMPLE_ENTITIES));
        }
    }

    /**
     * One profile request's frame: the type, its bindings (capped at {@code entity_profile.max_datasets} Datasets), each bound
     * Dataset's relation, and the union relation over the readable bindings. Built once per request; no query has run yet
     * except the zero-row column probe of each bound Dataset.
     */
    static final class Frame {
        final ApiContext api;
        final Path writeRoot;
        final EntityTypes.EntityType type;
        final EntityTypeBindings.Resolved resolved;
        final Limits lim;
        final SqlSandboxPolicy policy;
        final String maskMode;
        /** The type's bindings within the Dataset cap. */
        final List<EntityTypeBindings.Binding> bindings = new ArrayList<>();
        /** The readable ones, in the order their index {@code b} appears in the union relation. */
        final List<EntityTypeBindings.Binding> usable = new ArrayList<>();
        /** Why a binding is not readable. */
        final Map<EntityTypeBindings.Binding, String> unusable = new LinkedHashMap<>();
        final Map<String, String> relationSql = new LinkedHashMap<>();
        final Map<String, List<String>> columns = new LinkedHashMap<>();
        private final Map<String, String> problems = new LinkedHashMap<>();
        boolean datasetsTruncated;
        String keysRelation;

        Frame(ApiContext api, Path writeRoot, EntityTypes.EntityType type, EntityTypeBindings.Resolved resolved) {
            this.api = api;
            this.writeRoot = writeRoot;
            this.type = type;
            this.resolved = resolved;
            this.lim = Limits.of(writeRoot);
            SqlSandboxPolicy d = SqlSandboxPolicy.defaultPolicy();
            this.policy = SqlSandboxPolicy.withCaps(memoryLimitMb() + "MiB", d.maxThreads(), lim.timeoutS());
            this.maskMode = LinkAnalysisSettings.forRoot(writeRoot).effectiveMaskingMode();

            Set<String> datasets = new LinkedHashSet<>();
            for (EntityTypeBindings.Binding b : resolved.of(type.id())) {
                if (!datasets.contains(b.dataset()) && datasets.size() >= lim.maxDatasets()) {
                    datasetsTruncated = true;
                    continue;
                }
                datasets.add(b.dataset());
                bindings.add(b);
            }
            for (String ds : datasets) relation(ds);
            List<String> parts = new ArrayList<>();
            for (EntityTypeBindings.Binding b : bindings) {
                String why = columnProblem(b.dataset(), b.column());
                if (why != null) {
                    unusable.put(b, why);
                    continue;
                }
                String c = q(column(b.dataset(), b.column()));
                String t = b.timeColumn() != null && column(b.dataset(), b.timeColumn()) != null
                        ? "TRY_CAST(" + q(column(b.dataset(), b.timeColumn())) + " AS TIMESTAMP)" : "CAST(NULL AS TIMESTAMP)";
                parts.add("SELECT " + usable.size() + " AS b, CAST(" + c + " AS VARCHAR) AS raw, " + EntityKeySql.key(type.normaliser(), c)
                        + " AS k, " + t + " AS ts FROM (" + relationSql.get(b.dataset()) + ") AS __r WHERE " + c + " IS NOT NULL");
                usable.add(b);
            }
            keysRelation = parts.isEmpty() ? null : String.join(" UNION ALL ", parts);
        }

        /** The relation SQL of {@code ds} (null when it cannot be bound or probed; the reason is kept). */
        String relation(String ds) {
            if (relationSql.containsKey(ds)) return relationSql.get(ds);
            Map<String, Object> content = resolved.datasets().get(ds);
            try {
                String sql = DatasetProviders.require().relationSql(content, api.dataRoot(), writeRoot);
                columns.put(ds, InvRoutes.relationColumns(ds, sql));
                relationSql.put(ds, sql);
                return sql;
            } catch (RuntimeException unusableDataset) {
                relationSql.put(ds, null);
                columns.put(ds, null);
                problems.put(ds, DatasetReadFault.scrub(String.valueOf(unusableDataset.getMessage())));
                return null;
            }
        }

        /** The relation's own spelling of {@code col} (case-insensitive), or null. */
        String column(String ds, String col) {
            List<String> cols = columns.get(ds);
            if (cols == null || col == null) return null;
            for (String c : cols) if (c.equalsIgnoreCase(col)) return c;
            return null;
        }

        String columnProblem(String ds, String col) {
            if (relation(ds) == null) return "the Dataset cannot be read: " + problems.get(ds);
            if (column(ds, col) == null) return "column '" + col + "' is not in the Dataset's relation";
            return null;
        }

        /** Whether this type's keys leave masked under the Space's masking mode. */
        boolean masked() {
            return maskedUnder(maskMode, type);
        }

        DatasetProvider.Result run(String relationName, String relation, String sql, int limit, List<String> binds)
                throws SQLException, IOException {
            return DatasetProviders.require().run(new DatasetProvider.Request(relationName, relation, sql, limit, 0, List.of(), List.of(), binds),
                    policy, UTC);
        }

        DatasetProvider.Result keys(String sql, int limit, List<String> binds) throws SQLException, IOException {
            return run(KEYS, keysRelation, sql, limit, binds);
        }

        List<String> datasets() {
            List<String> out = new ArrayList<>();
            for (EntityTypeBindings.Binding b : bindings) if (!out.contains(b.dataset())) out.add(b.dataset());
            return out;
        }
    }

    static boolean maskedUnder(String mode, EntityTypes.EntityType type) {
        return switch (mode) {
            case "none" -> false;
            case "all" -> true;
            default -> type.masked();
        };
    }

    // ── the Entity Type profile ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The requested sections of the type's profile, served from the cache when the fingerprint stamp still matches. A
     * {@code window} ({@code {from, to}}, either null; null = whole history) scopes the {@code measures} section only, which is
     * cached per window.
     */
    static Map<String, Object> typeProfile(Frame f, List<String> sections, String[] window) {
        String stamp = stamp(f);
        Cache.Key key = new Cache.Key(f.writeRoot, f.type.id(), bindingSignature(f));
        Map<String, Section> have = stamp == null ? Map.of() : Cache.sections(key, stamp);
        java.util.function.UnaryOperator<String> slot = s -> "measures".equals(s) && window != null ? s + "@" + window[0] + "/" + window[1] : s;
        boolean cached = !sections.isEmpty() && sections.stream().map(slot).allMatch(have::containsKey);
        Map<String, Section> computed = new LinkedHashMap<>();
        for (String s : sections) {
            String at = slot.apply(s);
            computed.put(s, have.containsKey(at) ? have.get(at) : new Section("measures".equals(s) ? typeMeasures(f, window) : compute(f, s), Instant.now()));
        }
        // A section whose read FAILED is answered but never cached, so the next read retries it (a cached failure would
        // answer "not assessed" until the input files change).
        Map<String, Section> keep = new LinkedHashMap<>();
        for (var e : computed.entrySet()) if (!failed(e.getValue().value())) keep.put(slot.apply(e.getKey()), e.getValue());
        if (stamp != null) Cache.put(key, stamp, keep);
        Section volume = keep.get("volume"), time = keep.get("time");
        if (volume != null) Cache.headline(key, headline(volume, time == null ? null : time.value(), f));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityType", typeMap(f.type));
        Instant at = computed.values().stream().map(Section::computedAt).min(Instant::compareTo).orElse(Instant.now());
        out.put("computedAt", at.toString());
        out.put("cached", cached);
        if (window != null && sections.contains("measures")) {
            Map<String, Object> echo = new LinkedHashMap<>();
            echo.put("from", window[0]);
            echo.put("to", window[1]);
            out.put("window", echo);
        }
        boolean truncated = f.datasetsTruncated;
        for (var e : computed.entrySet()) {
            out.put(e.getKey(), e.getValue().value());
            truncated |= truncated(e.getValue().value());
        }
        out.put("truncated", truncated);
        Map<String, Object> masking = new LinkedHashMap<>();
        masking.put("mode", f.maskMode);
        masking.put("masked", f.masked());
        out.put("masking", masking);
        return out;
    }

    private record Section(Object value, Instant computedAt) {}

    private static boolean truncated(Object section) {
        if (section instanceof Map<?, ?> m) return Boolean.TRUE.equals(m.get("truncated")) || truncated(m.get("distribution"));
        if (section instanceof List<?> l) for (Object o : l) if (truncated(o)) return true;
        return false;
    }

    private static Object compute(Frame f, String section) {
        return switch (section) {
            case "bindings" -> bindings(f);
            case "volume" -> guarded(f, () -> volume(f));
            case "shapes" -> guarded(f, () -> shapes(f));
            case "time" -> guarded(f, () -> time(f));
            case "lineage" -> lineage(f);
            case "links" -> links(f);
            case "relationships" -> relationships(f);
            default -> throw new IllegalArgumentException(section);
        };
    }

    private interface Read {
        Map<String, Object> get() throws Exception;
    }

    /** A section over the union relation: not assessed when nothing is readable, or when its read failed. */
    private static Map<String, Object> guarded(Frame f, Read r) {
        if (f.keysRelation == null)
            return notAssessed(f.bindings.isEmpty() ? "no Dataset column you can view is bound to this Entity Type"
                    : "none of the type's bound columns could be read");
        try {
            return r.get();
        } catch (Exception e) {
            return failed(e);
        }
    }

    static Map<String, Object> notAssessed(String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assessed", false);
        m.put("reason", reason);
        return m;
    }

    /**
     * Not assessed because a read FAILED (as opposed to a section that does not apply): {@code retryable}, so the cache
     * never keeps it and the next read tries again.
     */
    static Map<String, Object> failed(Exception e) {
        Map<String, Object> m = notAssessed(reason(e));
        m.put("retryable", true);
        return m;
    }

    /** True when {@code section} (a section map, or a list of them, or a Measure row's distribution) holds a {@link #failed} read. */
    static boolean failed(Object section) {
        if (section instanceof Map<?, ?> m) return Boolean.TRUE.equals(m.get("retryable")) || failed(m.get("distribution"));
        if (section instanceof List<?> l) for (Object o : l) if (failed(o)) return true;
        return false;
    }

    static String reason(Exception e) {
        String m = DatasetReadFault.scrub(com.gamma.util.DuckDbUtil.withoutPendingQueryPreamble(String.valueOf(e.getMessage())));
        if (m == null) return null;
        if (isTimeout(m)) return "the read ran past entity_profile.timeout_s";
        if (isOutOfMemory(m))
            return "the read ran out of its memory budget (" + memoryLimitMb() + " MiB) on this Dataset; a system admin can "
                    + "raise entity_profile.memory_limit_mb";
        return m;
    }

    /** The statement timeout interrupts the query; DuckDB reports it as an interrupt. */
    static boolean isTimeout(String message) {
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("interrupt");
    }

    static boolean isOutOfMemory(String message) {
        return message != null && message.contains("Out of Memory");
    }

    /** {@code entity_profile.memory_limit_mb}: an install-wide item, read per profile so a change applies to the next one. */
    static long memoryLimitMb() {
        return LaLimits.forSpace(null).get(LaLimit.ENTITY_PROFILE_MEMORY_LIMIT_MB);
    }

    private static void assessed(Map<String, Object> m) {
        m.put("assessed", true);
        m.put("reason", null);
    }

    /** Where it appears: one {@link ColumnProfile} pass per Dataset (distinct over the normalised key) plus the normaliser failures. */
    private static List<Map<String, Object>> bindings(Frame f) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<EntityTypeBindings.Binding>> byDataset = new LinkedHashMap<>();
        for (EntityTypeBindings.Binding b : f.usable) byDataset.computeIfAbsent(b.dataset(), k -> new ArrayList<>()).add(b);
        Map<EntityTypeBindings.Binding, Map<String, Object>> rows = new LinkedHashMap<>();
        for (var e : byDataset.entrySet()) {
            String ds = e.getKey(), rel = f.relationSql.get(ds);
            List<String> cols = e.getValue().stream().map(b -> f.column(ds, b.column())).toList();
            try {
                List<ColumnProfile.Stats> stats = ColumnProfile.stats(ds, rel, cols,
                        c -> "NULLIF(" + EntityKeySql.key(f.type.normaliser(), c) + ", '')", f.policy);
                StringBuilder sql = new StringBuilder("SELECT 0 AS z");
                for (int i = 0; i < cols.size(); i++) {
                    String c = q(cols.get(i));
                    sql.append(", COUNT(*) FILTER (WHERE ").append(c).append(" IS NOT NULL AND ")
                       .append(EntityKeySql.key(f.type.normaliser(), c)).append(" = '') AS f_").append(i);
                }
                Map<String, Object> fails = f.run(ds, rel, sql.append(" FROM ").append(q(ds)).toString(), 1, List.of()).rows().get(0);
                for (int i = 0; i < cols.size(); i++) {
                    ColumnProfile.Stats s = stats.get(i);
                    Map<String, Object> m = binding(f, e.getValue().get(i));
                    m.put("rows", s.rows());
                    m.put("nonNull", s.nonNull());
                    m.put("fillRate", s.rows() == 0 ? null : round((double) s.nonNull() / s.rows()));
                    m.put("distinct", s.distinct());
                    m.put("approximate", true);
                    m.put("failNormaliser", ColumnProfile.num(fails.get("f_" + i)));
                    assessed(m);
                    rows.put(e.getValue().get(i), m);
                }
            } catch (Exception ex) {
                for (EntityTypeBindings.Binding b : e.getValue()) {
                    Map<String, Object> m = binding(f, b);
                    m.putAll(failed(ex));
                    rows.put(b, m);
                }
            }
        }
        for (EntityTypeBindings.Binding b : f.bindings) {
            if (rows.containsKey(b)) {
                out.add(rows.get(b));
            } else {
                Map<String, Object> m = binding(f, b);
                m.putAll(notAssessed(f.unusable.get(b)));
                out.add(m);
            }
        }
        return out;
    }

    private static Map<String, Object> binding(Frame f, EntityTypeBindings.Binding b) {
        Map<String, Object> m = b.toMap();
        m.put("timeColumn", b.timeColumn());
        return m;
    }

    /** Volume and quality: distinct Entities across every binding, and the sentinels of each column (D-EP8). */
    private static Map<String, Object> volume(Frame f) throws Exception {
        Map<String, Object> total = f.keys("SELECT LEAST(APPROX_COUNT_DISTINCT(NULLIF(k, '')), COUNT(*)) AS d, COUNT(*) AS n FROM " + KEYS, 1, List.of())
                .rows().get(0);
        Map<Integer, Long> nonNull = new TreeMap<>();
        for (Map<String, Object> r : f.keys("SELECT b, COUNT(*) AS n FROM " + KEYS + " GROUP BY b", f.usable.size(), List.of()).rows())
            nonNull.put((int) ColumnProfile.num(r.get("b")), ColumnProfile.num(r.get("n")));

        List<Map<String, Object>> sentinels = new ArrayList<>();
        DatasetProvider.Result builtin = f.keys("SELECT b, trim(raw) AS v, COUNT(*) AS n FROM " + KEYS + " WHERE " + SENTINEL_SQL
                + " GROUP BY b, v ORDER BY b, n DESC, v", f.usable.size() * BUILTIN_SENTINELS.size() * 4, List.of());
        for (Map<String, Object> r : builtin.rows()) {
            int b = (int) ColumnProfile.num(r.get("b"));
            sentinels.add(sentinel(f.usable.get(b), "builtin", String.valueOf(r.get("v")), ColumnProfile.num(r.get("n")), nonNull.get(b)));
        }
        // A value the normaliser rejects (a name in a number column) counts by its raw text, so it can still dominate.
        // A dominant value groups every distinct key: above shape_sample_rows that group-by does not fit a profile's memory
        // budget (7.4 M distinct IMSIs over 132 M rows ran out of 1 GB), so it is read from a row reservoir instead. A row
        // sample, not an Entity sample: a value holding a fifth of a column is certain to be in it.
        boolean sampled = ColumnProfile.num(total.get("n")) > f.lim.sampleRows();
        String from = sampled ? "(SELECT b, k, raw FROM " + KEYS + " USING SAMPLE reservoir(" + f.lim.sampleRows() + " ROWS) REPEATABLE (42)) AS s" : KEYS;
        DatasetProvider.Result dominant = f.keys("SELECT b, MAX(n) FILTER (WHERE ok) AS n, SUM(n) AS of FROM (SELECT b, v, (v <> 'r:' AND NOT "
                + SENTINEL_SQL + ") AS ok, COUNT(*) AS n FROM (SELECT b, raw, CASE WHEN k <> '' THEN 'k:' || k ELSE 'r:' || "
                + "COALESCE(trim(raw), '') END AS v FROM " + from + ") AS kv GROUP BY b, v, ok) AS g GROUP BY b ORDER BY b", f.usable.size(), List.of());
        for (Map<String, Object> r : dominant.rows()) {
            int b = (int) ColumnProfile.num(r.get("b"));
            long n = ColumnProfile.num(r.get("n")), seen = ColumnProfile.num(r.get("of")), of = nonNull.getOrDefault(b, 0L);
            if (of > 0 && seen > 0 && n * 100.0 / seen > f.lim.sentinelSharePct())   // D-EP3: never the value itself
                sentinels.add(sentinel(f.usable.get(b), "dominant", null, sampled ? Math.round((double) n / seen * of) : n, of));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        if (sampled) {
            out.put("dominantSampled", true);
            out.put("sampleRows", f.lim.sampleRows());
        }
        out.put("distinct", ColumnProfile.num(total.get("d")));
        out.put("approximate", true);
        out.put("rows", ColumnProfile.num(total.get("n")));
        out.put("sentinels", sentinels);
        out.put("sentinelShare", f.lim.sentinelSharePct());
        out.put("truncated", builtin.truncated());
        assessed(out);
        return out;
    }

    private static Map<String, Object> sentinel(EntityTypeBindings.Binding b, String kind, String value, long rows, Long of) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", b.dataset());
        m.put("column", b.column());
        m.put("kind", kind);
        m.put("value", value);
        m.put("rows", rows);
        m.put("share", of == null || of == 0 ? null : round((double) rows / of));
        return m;
    }

    /** Value shapes: length, the safe prefix, character classes - over a reservoir sample above {@code shape_sample_rows}. */
    private static Map<String, Object> shapes(Frame f) throws Exception {
        long n = ColumnProfile.num(f.keys("SELECT COUNT(*) AS n FROM " + KEYS + " WHERE k <> ''", 1, List.of()).rows().get(0).get("n"));
        boolean sampled = n > f.lim.sampleRows();
        String from = sampled
                ? "(SELECT k FROM " + KEYS + " USING SAMPLE reservoir(" + f.lim.sampleRows() + " ROWS) REPEATABLE (42)) AS s WHERE k <> ''"
                : KEYS + " WHERE k <> ''";
        DatasetProvider.Result length = f.keys("SELECT length(k) AS len, COUNT(*) AS n FROM " + from
                + " GROUP BY len ORDER BY n DESC, len", f.lim.topN(), List.of());
        List<Map<String, Object>> lengths = new ArrayList<>();
        for (Map<String, Object> r : length.rows()) lengths.add(row("length", ColumnProfile.num(r.get("len")), "rows", ColumnProfile.num(r.get("n"))));
        lengths.sort((a, b) -> Long.compare((Long) a.get("length"), (Long) b.get("length")));

        int p = EntityKeySql.prefixLength(f.type.normaliser());
        List<Map<String, Object>> prefixes = new ArrayList<>();
        boolean prefixCut = false;
        if (p > 0) {
            DatasetProvider.Result prefix = f.keys("SELECT left(ltrim(k, '+'), " + p + ") AS pre, COUNT(*) AS n FROM " + from
                    + " GROUP BY pre ORDER BY n DESC, pre", f.lim.topN(), List.of());
            for (Map<String, Object> r : prefix.rows()) prefixes.add(row("prefix", String.valueOf(r.get("pre")), "rows", ColumnProfile.num(r.get("n"))));
            prefixCut = prefix.truncated();
        }
        Map<String, Object> cls = f.keys("SELECT COUNT(*) FILTER (WHERE regexp_full_match(k, '\\+?[0-9]+')) AS digits, "
                + "COUNT(*) FILTER (WHERE NOT regexp_matches(k, '[0-9]')) AS letters, COUNT(*) AS n FROM " + from, 1, List.of()).rows().get(0);
        long digits = ColumnProfile.num(cls.get("digits")), letters = ColumnProfile.num(cls.get("letters"));
        Map<String, Object> charClass = new LinkedHashMap<>();
        charClass.put("digits", digits);
        charClass.put("letters", letters);
        charClass.put("mixed", ColumnProfile.num(cls.get("n")) - digits - letters);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("length", lengths);
        out.put("prefix", prefixes);
        out.put("prefixLength", p);
        out.put("charClass", charClass);
        out.put("sampled", sampled);
        out.put("sampleRows", sampled ? f.lim.sampleRows() : null);
        out.put("truncated", length.truncated() || prefixCut);
        assessed(out);
        return out;
    }

    /** Time: the span, rows and distinct Entities per day (newest {@code timeline_days}), missing days, and data as of. */
    private static Map<String, Object> time(Frame f) throws Exception {
        List<Map<String, Object>> asOf = dataAsOf(f, f.datasets());
        boolean anyTime = f.usable.stream().anyMatch(b -> b.timeColumn() != null && f.column(b.dataset(), b.timeColumn()) != null);
        if (!anyTime) {
            Map<String, Object> out = notAssessed("no bound Dataset names a time column (its eventDate, or an index mapping's time column)");
            out.put("dataAsOf", asOf);
            return out;
        }
        Map<String, Object> span = f.keys("SELECT strftime(MIN(ts), '%Y-%m-%dT%H:%M:%SZ') AS mn, strftime(MAX(ts), '%Y-%m-%dT%H:%M:%SZ') AS mx FROM "
                + KEYS + " WHERE ts IS NOT NULL AND k <> ''", 1, List.of()).rows().get(0);
        DatasetProvider.Result days = f.keys("SELECT CAST(CAST(ts AS DATE) AS VARCHAR) AS day, COUNT(*) AS n, LEAST(APPROX_COUNT_DISTINCT(k), COUNT(*)) AS d FROM "
                + KEYS + " WHERE ts IS NOT NULL AND k <> '' GROUP BY day ORDER BY day DESC", f.lim.timelineDays(), List.of());
        List<Map<String, Object>> perDay = new ArrayList<>();
        for (Map<String, Object> r : days.rows()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("day", String.valueOf(r.get("day")));
            d.put("rows", ColumnProfile.num(r.get("n")));
            d.put("distinct", ColumnProfile.num(r.get("d")));
            perDay.add(d);
        }
        Collections.reverse(perDay);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("earliest", span.get("mn"));
        out.put("latest", span.get("mx"));
        out.put("perDay", perDay);
        out.put("approximate", true);
        out.put("missingDays", missingDays(perDay));
        out.put("dataAsOf", asOf);
        out.put("truncated", days.truncated());
        assessed(out);
        return out;
    }

    /** The days between the first and the last listed day that carry no row. */
    static List<String> missingDays(List<Map<String, Object>> perDay) {
        List<String> out = new ArrayList<>();
        if (perDay.size() < 2) return out;
        Set<String> have = new TreeSet<>();
        for (Map<String, Object> d : perDay) have.add(String.valueOf(d.get("day")));
        LocalDate end = LocalDate.parse(String.valueOf(perDay.get(perDay.size() - 1).get("day")));
        for (LocalDate d = LocalDate.parse(String.valueOf(perDay.get(0).get("day"))); d.isBefore(end); d = d.plusDays(1))
            if (!have.contains(d.toString())) out.add(d.toString());
        return out;
    }

    /** Data as of per Dataset ({@code GET /datasets/{id}/freshness}'s reading); null where the Dataset cannot say. */
    static List<Map<String, Object>> dataAsOf(Frame f, List<String> datasets) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ds : datasets) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataset", ds);
            String at = null;
            try {
                var ms = DatasetProviders.require().dataAsOf(f.resolved.datasets().get(ds), ds, f.api.dataRoot(), f.writeRoot);
                if (ms.isPresent()) at = Instant.ofEpochMilli(ms.getAsLong()).toString();
            } catch (RuntimeException unreadable) {
                // a Dataset whose freshness cannot be read has no data-as-of: null, never "now"
            }
            m.put("dataAsOf", at);
            out.add(m);
        }
        return out;
    }

    /** Lineage per bound Dataset: the {@code GET /inv/datasets/{id}/files} reading, summarised. */
    private static List<Map<String, Object>> lineage(Frame f) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ds : f.datasets()) {
            Map<String, Object> files = DatasetFilesRoutes.read(f.api, f.writeRoot, f.resolved.datasets().get(ds), ds, Integer.MAX_VALUE, 0, null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataset", ds);
            if (!Boolean.TRUE.equals(files.get("assessed"))) {
                m.putAll(notAssessed(String.valueOf(files.get("note"))));
                out.add(m);
                continue;
            }
            Set<String> pipelines = new TreeSet<>(), collectors = new TreeSet<>();
            if (files.get("items") instanceof List<?> items)
                for (Object o : items)
                    if (o instanceof Map<?, ?> i) {
                        if (i.get("pipeline") != null) pipelines.add(String.valueOf(i.get("pipeline")));
                        if (i.get("collector") != null) collectors.add(String.valueOf(i.get("collector")));
                    }
            Map<?, ?> s = (Map<?, ?>) files.get("summary");
            m.put("pipelines", List.copyOf(pipelines));
            m.put("collectors", List.copyOf(collectors));
            m.put("files", s.get("files"));
            m.put("rows", s.get("rows"));
            m.put("latestIngestedAt", s.get("latestIngestedAt"));
            m.put("waitingForIndex", s.get("waiting"));
            m.put("problemFiles", s.get("problem"));
            assessed(m);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> links(Frame f) {
        for (LinkStatsSource s : LINK_SOURCES) {
            try {
                Map<String, Object> m = s.typeLinks(f);
                if (m != null) return m;
            } catch (Exception e) {
                Map<String, Object> m = failed(e);
                m.put("from", null);
                return m;
            }
        }
        Map<String, Object> m = notAssessed("the type is a link end of no saved view, index mapping or default link mapping over a Dataset you can view");
        m.put("from", null);
        return m;
    }

    /** Relationships: for each OTHER Entity Type bound in the same Dataset row, how many of it one Entity of this type carries. */
    private static List<Map<String, Object>> relationships(Frame f) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String ds : f.datasets()) {
            if (f.relationSql.get(ds) == null) continue;
            List<EntityTypeBindings.Binding> mine = new ArrayList<>();
            for (EntityTypeBindings.Binding b : f.usable) if (b.dataset().equals(ds)) mine.add(b);
            if (mine.isEmpty()) continue;
            for (EntityTypes.EntityType other : f.resolved.types()) {
                if (other.id().equals(f.type.id())) continue;
                List<EntityTypeBindings.Binding> theirs = new ArrayList<>();
                for (EntityTypeBindings.Binding b : f.resolved.of(other.id()))
                    if (b.dataset().equals(ds) && f.column(ds, b.column()) != null) theirs.add(b);
                // Only columns of the same party of the row are paired (columns[].party): a served IMSI with the served
                // MSISDN, never with the other party's number - that pairing counts contacts, not numbers on the SIM.
                List<String> parts = new ArrayList<>();
                for (EntityTypeBindings.Binding a : mine)
                    for (EntityTypeBindings.Binding c : theirs)
                        if (EntityTypeBindings.sameParty(a, c))
                            parts.add("SELECT NULLIF(" + EntityKeySql.key(f.type.normaliser(), q(f.column(ds, a.column()))) + ", '') AS e, NULLIF("
                                    + EntityKeySql.key(other.normaliser(), q(f.column(ds, c.column()))) + ", '') AS o FROM ("
                                    + f.relationSql.get(ds) + ") AS __r");
                if (parts.isEmpty()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("otherType", other.id());
                m.put("dataset", ds);
                try {
                    int thr = f.lim.relationshipThreshold();
                    String pairs = String.join(" UNION ALL ", parts);
                    // Exact first. A per-Entity distinct count over a production day can outgrow the memory budget or the
                    // statement timeout (MSISDN to IMSI over 132 M rows ran past the 600 s ceiling); then it is read over a
                    // hash sample of the Entities (1 in SAMPLE_EVERY): each sampled Entity's count stays exact, the share is
                    // an estimate, and the counts are scaled up and marked approximate.
                    long every = 1;
                    Map<String, Object> r;
                    try {
                        r = relationshipRow(f, pairs, thr, 1);
                    } catch (Exception oom) {
                        if (!isOutOfMemory(oom.getMessage()) && !isTimeout(oom.getMessage())) throw oom;
                        every = SAMPLE_EVERY;
                        r = relationshipRow(f, pairs, thr, every);
                    }
                    long total = ColumnProfile.num(r.get("total"));
                    m.put("oneToOneShare", total == 0 ? null : round((double) ColumnProfile.num(r.get("one")) / total));
                    m.put("maxPerEntity", total == 0 ? null : ColumnProfile.num(r.get("mx")));
                    m.put("threshold", thr);
                    m.put("entitiesAtOrAboveThreshold", ColumnProfile.num(r.get("hi")) * every);
                    m.put("entities", total * every);
                    if (every > 1) {
                        m.put("sampled", true);
                        m.put("sampleEvery", every);
                        m.put("approximate", true);
                    }
                    assessed(m);
                } catch (Exception e) {
                    m.putAll(failed(e));
                }
                out.add(m);
            }
        }
        return out;
    }

    /** The Entity sample of a relationship read that outgrew its memory budget: 1 Entity in this many (by key hash). */
    static final long SAMPLE_EVERY = 64;

    /** One relationship read over {@code pairs}; {@code every > 1} keeps only the Entities whose key hash falls in 1 of {@code every}. */
    private static Map<String, Object> relationshipRow(Frame f, String pairs, int thr, long every) throws Exception {
        String sample = every > 1 ? " AND hash(e) % " + every + " = 0" : "";
        return f.run(PAIRS, pairs, "SELECT COUNT(*) FILTER (WHERE n = 1) AS one, COUNT(*) AS total, MAX(n) AS mx, COUNT(*) FILTER (WHERE n >= "
                + thr + ") AS hi FROM (SELECT e, COUNT(DISTINCT o) AS n FROM " + PAIRS + " WHERE e IS NOT NULL AND o IS NOT NULL" + sample
                + " GROUP BY e) AS g", 1, List.of()).rows().get(0);
    }

    /**
     * Headroom of the per-Entity Measure sample: 1 Entity in {@code ceil(distinct x this / measure_sample_entities)}, so the hash
     * spread and the approximate distinct count rarely carry the sample to the cap. The read's LIMIT is the hard bound.
     */
    static final double MEASURE_SAMPLE_HEADROOM = 1.1;

    /**
     * Measures (LA-COLUMN-ROLES-2 D-1 b): one row per binding x Measure of its Dataset ({@link #measureColumns}) over the rows
     * where the bound column names an Entity (a non-empty normalised key), inside {@code window} on the binding's time column -
     * rows holding a number, sum, lowest, highest, unit - in ONE aggregate statement per bound Dataset (each binding is its own
     * row, so no Dataset row is summed twice). Plus each row's {@code distribution}: p50 / p90 / max of the per-Entity totals and
     * the Entities measured, one GROUP BY per binding over at most {@code entity_profile.measure_sample_entities} Entities -
     * above it over a key-hash sample of 1 Entity in {@code every}, as {@code relationships} samples, and said so.
     */
    static List<Map<String, Object>> typeMeasures(Frame f, String[] window) {
        Map<EntityTypeBindings.Binding, List<Map<String, Object>>> rowsOf = new LinkedHashMap<>();
        for (String ds : f.datasets()) {
            List<EntityTypeBindings.Binding> read = new ArrayList<>();
            List<List<String[]>> measuresOf = new ArrayList<>();
            for (EntityTypeBindings.Binding b : f.usable) {
                if (!b.dataset().equals(ds)) continue;
                List<String[]> ms = measureColumns(f, b);
                if (ms.isEmpty()) continue;
                List<Map<String, Object>> rows = new ArrayList<>();
                for (String[] m : ms) rows.add(measureRow(b, m));
                rowsOf.put(b, rows);
                if (window != null && timeColumn(f, b) == null) {   // undated rows are outside every window
                    for (Map<String, Object> r : rows) r.putAll(notAssessed("the binding has no time column, so none of its rows is inside the window"));
                    continue;
                }
                read.add(b);
                measuresOf.add(ms);
            }
            if (read.isEmpty()) continue;
            String rel = f.relationSql.get(ds);
            int cap = f.lim.measureSampleEntities();
            List<String> binds = new ArrayList<>();
            StringBuilder inner = new StringBuilder("SELECT 0 AS z"), agg = new StringBuilder("SELECT 0 AS z");
            for (int i = 0; i < read.size(); i++) {
                String k = entityKey(f, read.get(i));
                inner.append(", ").append(k).append(" AS k").append(i).append(", COALESCE(").append(k).append(" <> ''")
                        .append(within(f, read.get(i), window, binds)).append(", false) AS n").append(i);
                agg.append(", APPROX_COUNT_DISTINCT(k").append(i).append(") FILTER (WHERE n").append(i).append(") AS d").append(i);
                for (int j = 0; j < measuresOf.get(i).size(); j++) {
                    String a = i + "_" + j, w = ") FILTER (WHERE n" + i + ") AS ";
                    inner.append(", TRY_CAST(").append(q(measuresOf.get(i).get(j)[1])).append(" AS DOUBLE) AS v").append(a);
                    agg.append(", COUNT(v").append(a).append(w).append("c").append(a).append(", SUM(v").append(a).append(w).append("s").append(a)
                            .append(", MIN(v").append(a).append(w).append("lo").append(a).append(", MAX(v").append(a).append(w).append("hi").append(a);
                }
            }
            Map<String, Object> r;
            try {
                r = f.run(MEASURE_ROWS, rel, agg + " FROM (" + inner + " FROM " + MEASURE_ROWS + ") AS r", 1, binds).rows().get(0);
            } catch (Exception e) {
                for (EntityTypeBindings.Binding b : read) for (Map<String, Object> row : rowsOf.get(b)) row.putAll(failed(e));
                continue;
            }
            for (int i = 0; i < read.size(); i++) {
                List<Map<String, Object>> rows = rowsOf.get(read.get(i));
                for (int j = 0; j < rows.size(); j++) {
                    String a = i + "_" + j;
                    Map<String, Object> row = rows.get(j);
                    row.put("rows", ColumnProfile.num(r.get("c" + a)));
                    row.put("sum", number(r.get("s" + a)));
                    row.put("min", number(r.get("lo" + a)));
                    row.put("max", number(r.get("hi" + a)));
                }
                long distinct = ColumnProfile.num(r.get("d" + i));
                long every = distinct > cap ? Math.max(2, (long) Math.ceil(distinct * MEASURE_SAMPLE_HEADROOM / cap)) : 1;
                List<Map<String, Object>> dist;
                try {
                    dist = distribution(f, read.get(i), measuresOf.get(i), window, every, cap);
                } catch (Exception e) {
                    dist = new ArrayList<>();
                    for (int j = 0; j < rows.size(); j++) dist.add(failed(e));
                }
                for (int j = 0; j < rows.size(); j++) {
                    rows.get(j).put("distribution", dist.get(j));
                    assessed(rows.get(j));
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (List<Map<String, Object>> rows : rowsOf.values()) out.addAll(rows);
        return out;
    }

    /**
     * The per-Entity totals of one binding's Measures: GROUP BY the Entity key (over the 1-in-{@code every} key-hash sample when
     * {@code every > 1}), at most {@code cap} Entities, then p50 / p90 / max of each Measure's totals and the Entities holding one.
     */
    private static List<Map<String, Object>> distribution(Frame f, EntityTypeBindings.Binding b, List<String[]> measures, String[] window,
                                                          long every, int cap) throws Exception {
        List<String> binds = new ArrayList<>();
        StringBuilder rows = new StringBuilder("SELECT ").append(entityKey(f, b)).append(" AS k");
        StringBuilder per = new StringBuilder("SELECT k");
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS e");
        for (int j = 0; j < measures.size(); j++) {
            rows.append(", TRY_CAST(").append(q(measures.get(j)[1])).append(" AS DOUBLE) AS v").append(j);
            per.append(", SUM(v").append(j).append(") AS t").append(j);
            String t = "t" + j;
            sql.append(", COUNT(").append(t).append(") AS e").append(j)
               .append(", ").append(ColumnProfiler.quantile(t, 0.5, QuantileMode.DISCRETE)).append(" AS p50_").append(j)
               .append(", ").append(ColumnProfiler.quantile(t, 0.9, QuantileMode.DISCRETE)).append(" AS p90_").append(j)
               .append(", MAX(").append(t).append(") AS mx").append(j);
        }
        rows.append(" FROM ").append(MEASURE_ROWS).append(" WHERE TRUE").append(within(f, b, window, binds));
        per.append(" FROM (").append(rows).append(") AS r WHERE k <> ''").append(every > 1 ? " AND hash(k) % " + every + " = 0" : "")
           .append(" GROUP BY k LIMIT ").append(cap);
        Map<String, Object> r = f.run(MEASURE_ROWS, f.relationSql.get(b.dataset()), sql.append(" FROM (").append(per).append(") AS g").toString(),
                1, binds).rows().get(0);
        long entities = ColumnProfile.num(r.get("e"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (int j = 0; j < measures.size(); j++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("entities", ColumnProfile.num(r.get("e" + j)));
            d.put("p50", number(r.get("p50_" + j)));
            d.put("p90", number(r.get("p90_" + j)));
            d.put("max", number(r.get("mx" + j)));
            d.put("sampled", every > 1);
            d.put("every", every);
            d.put("sampleEntities", cap);
            if (every > 1) d.put("approximate", true);
            d.put("truncated", entities >= cap);   // the cap's LIMIT may have cut the Entities read
            assessed(d);
            out.add(d);
        }
        return out;
    }

    private static Map<String, Object> measureRow(EntityTypeBindings.Binding b, String[] measure) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataset", b.dataset());
        m.put("column", b.column());
        m.put("measure", measure[0]);
        m.put("unit", measure[2]);
        return m;
    }

    /** The normalised key of a binding's column, over its Dataset's own relation. */
    private static String entityKey(Frame f, EntityTypeBindings.Binding b) {
        return EntityKeySql.key(f.type.normaliser(), q(f.column(b.dataset(), b.column())));
    }

    private static String timeColumn(Frame f, EntityTypeBindings.Binding b) {
        return b.timeColumn() == null ? null : f.column(b.dataset(), b.timeColumn());
    }

    /** {@code window}'s predicate on the binding's time column ("" for none), its instants appended to {@code binds}. */
    private static String within(Frame f, EntityTypeBindings.Binding b, String[] window, List<String> binds) {
        if (window == null) return "";
        String t = "TRY_CAST(" + q(timeColumn(f, b)) + " AS TIMESTAMP)", out = "";
        if (window[0] != null) {
            out += " AND " + t + " >= CAST(? AS TIMESTAMP)";
            binds.add(LocalDateTime.ofInstant(Instant.parse(window[0]), UTC).toString());
        }
        if (window[1] != null) {
            out += " AND " + t + " < CAST(? AS TIMESTAMP)";
            binds.add(LocalDateTime.ofInstant(Instant.parse(window[1]), UTC).toString());
        }
        return out;
    }

    // ── headline + cache ─────────────────────────────────────────────────────────────────────────────────────────

    /** The list route's headline: the last computed volume (and the oldest data-as-of of the type's Datasets). */
    private static Map<String, Object> headline(Section volume, Object time, Frame f) {
        Map<String, Object> v = castMap(volume.value());
        Map<String, Object> h = new LinkedHashMap<>();
        boolean ok = Boolean.TRUE.equals(v.get("assessed"));
        h.put("distinct", ok ? v.get("distinct") : null);
        h.put("approximate", true);
        h.put("rows", ok ? v.get("rows") : null);
        String asOf = null;
        List<Map<String, Object>> list = time instanceof Map<?, ?> t && t.get("dataAsOf") instanceof List<?> l ? castList(l)
                : dataAsOf(f, f.datasets());
        for (Map<String, Object> d : list)
            if (d.get("dataAsOf") instanceof String s && (asOf == null || s.compareTo(asOf) < 0)) asOf = s;
        h.put("dataAsOf", asOf);
        h.put("computedAt", volume.computedAt().toString());
        h.put("assessed", ok);
        h.put("reason", ok ? null : v.get("reason"));
        return h;
    }

    /** The headline the list route shows for a type, or {@code assessed:false, not computed yet}. */
    static Map<String, Object> headline(Path writeRoot, EntityTypes.EntityType type, EntityTypeBindings.Resolved resolved) {
        Map<String, Object> h = Cache.headline(new Cache.Key(writeRoot, type.id(), bindingSignature(resolved.of(type.id()), resolved)));
        if (h != null) return h;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("distinct", null);
        m.put("approximate", true);
        m.put("rows", null);
        m.put("dataAsOf", null);
        m.put("computedAt", null);
        m.put("assessed", false);
        m.put("reason", "not computed yet");
        return m;
    }

    /**
     * The cache stamp of a profile: the type, the masking mode, the limits, every binding and link mapping over the type's
     * Datasets, and each Dataset's input-file fingerprint ({@link InputFingerprintCache}, the same listing the index uses for
     * currency). Null - not cacheable - unless every bound Dataset has a {@code files:} fingerprint: a view over other views or a
     * relation without enumerable files cannot say whether its data changed.
     */
    static String stamp(Frame f) {
        StringBuilder sb = new StringBuilder();
        sb.append(f.type).append('|').append(f.maskMode).append('|').append(f.lim).append('|').append(bindingSignature(f));
        for (String ds : f.datasets()) {
            Map<String, Object> content = f.resolved.datasets().get(ds);
            InputFingerprint fp = InputFingerprintCache.get(f.writeRoot, ds, "entity-profile",
                    () -> DatasetProviders.require().inputFingerprint(content, f.api.dataRoot(), f.writeRoot));
            if (fp == null || fp.value() == null || !fp.value().startsWith(InputFingerprint.FILES)) return null;
            sb.append('|').append(ds).append('=').append(fp.value());
        }
        return sha256(sb.toString());
    }

    private static String bindingSignature(Frame f) {
        return bindingSignature(f.bindings, f.resolved);
    }

    /** What a caller's view of a type's bindings is: its bindings, and every other binding and mapping over those Datasets. */
    private static String bindingSignature(List<EntityTypeBindings.Binding> bindings, EntityTypeBindings.Resolved resolved) {
        Set<String> ds = new TreeSet<>();
        for (EntityTypeBindings.Binding b : bindings) ds.add(b.dataset());
        StringBuilder sb = new StringBuilder();
        for (List<EntityTypeBindings.Binding> l : resolved.byType().values())
            for (EntityTypeBindings.Binding b : l) if (ds.contains(b.dataset())) sb.append(b).append(';');
        for (EntityTypeBindings.LinkMapping m : resolved.mappings()) if (ds.contains(m.dataset())) sb.append(m).append(';');
        return sha256(sb.toString());
    }

    /** In-memory, bounded (least recently used out): a profile per (Space, type, the caller's bindings view). */
    static final class Cache {
        static final int MAX_ENTRIES = 256;

        record Key(Path root, String type, String bindings) {}

        private record Cached(String stamp, Map<String, Section> sections) {}

        private static final Map<Key, Cached> ENTRIES = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
        private static final Map<Key, Map<String, Object>> HEADLINES = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Map<String, Object>> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        private Cache() {}

        static synchronized Map<String, Section> sections(Key k, String stamp) {
            Cached e = ENTRIES.get(k);
            return e != null && e.stamp().equals(stamp) ? e.sections() : Map.of();
        }

        static synchronized void put(Key k, String stamp, Map<String, Section> sections) {
            Cached e = ENTRIES.get(k);
            Map<String, Section> merged = new LinkedHashMap<>(e != null && e.stamp().equals(stamp) ? e.sections() : Map.of());
            merged.putAll(sections);
            ENTRIES.put(k, new Cached(stamp, merged));
        }

        static synchronized void headline(Key k, Map<String, Object> h) {
            HEADLINES.put(k, h);
        }

        static synchronized Map<String, Object> headline(Key k) {
            return HEADLINES.get(k);
        }

        /** Test seam: forget every profile and headline. */
        static synchronized void clear() {
            ENTRIES.clear();
            HEADLINES.clear();
        }
    }

    // ── the Entity profile ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * One Entity (plan §3.2): {@code key} is its normalised typed key, already resolved from a raw value or an exploration alias by
     * the route; {@code shownKey} is what the response names it by (the alias under masking). One filtered read per section over
     * the union relation.
     */
    static Map<String, Object> entityProfile(Frame f, String key, String shownKey, String[] window) {
        Map<String, Object> out = new LinkedHashMap<>();
        // A deep link's window (half-open [from, to), UTC): rows, span and days are read inside it; links and related stay whole-history.
        String within = "";
        List<String> wb = new ArrayList<>();
        if (window != null) {
            Map<String, Object> echo = new LinkedHashMap<>();
            echo.put("from", window[0]);
            echo.put("to", window[1]);
            out.put("window", echo);
            if (window[0] != null) {
                within += " AND ts >= CAST(? AS TIMESTAMP)";
                wb.add(LocalDateTime.ofInstant(Instant.parse(window[0]), UTC).toString());
            }
            if (window[1] != null) {
                within += " AND ts < CAST(? AS TIMESTAMP)";
                wb.add(LocalDateTime.ofInstant(Instant.parse(window[1]), UTC).toString());
            }
        }
        List<String> keyed = new ArrayList<>(List.of(key));
        keyed.addAll(wb);
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("type", f.type.id());
        entity.put("key", shownKey);
        entity.put("masked", f.masked());
        out.put("entity", entity);
        if (f.keysRelation == null) {
            out.putAll(notAssessed(f.bindings.isEmpty() ? "no Dataset column you can view is bound to this Entity Type"
                    : "none of the type's bound columns could be read"));
            return out;
        }
        boolean truncated = f.datasetsTruncated;
        try {
            List<Map<String, Object>> where = new ArrayList<>();
            for (Map<String, Object> r : f.keys("SELECT b, COUNT(*) AS n FROM " + KEYS + " WHERE k = ?" + within + " GROUP BY b ORDER BY b",
                    f.usable.size(), keyed).rows()) {
                EntityTypeBindings.Binding b = f.usable.get((int) ColumnProfile.num(r.get("b")));
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("dataset", b.dataset());
                w.put("column", b.column());
                w.put("rows", ColumnProfile.num(r.get("n")));
                where.add(w);
            }
            Map<String, Object> span = f.keys("SELECT strftime(MIN(ts), '%Y-%m-%dT%H:%M:%SZ') AS mn, strftime(MAX(ts), '%Y-%m-%dT%H:%M:%SZ') AS mx, "
                    + "COUNT(DISTINCT CAST(ts AS DATE)) AS active FROM " + KEYS + " WHERE k = ? AND ts IS NOT NULL" + within, 1, keyed).rows().get(0);
            long covered = ColumnProfile.num(f.keys("SELECT COUNT(DISTINCT CAST(ts AS DATE)) AS n FROM " + KEYS + " WHERE ts IS NOT NULL" + within,
                    1, wb).rows().get(0).get("n"));
            DatasetProvider.Result days = f.keys("SELECT CAST(CAST(ts AS DATE) AS VARCHAR) AS day, COUNT(*) AS n FROM " + KEYS
                    + " WHERE k = ? AND ts IS NOT NULL" + within + " GROUP BY day ORDER BY day DESC", f.lim.timelineDays(), keyed);
            List<Map<String, Object>> perDay = new ArrayList<>();
            for (Map<String, Object> r : days.rows()) perDay.add(row("day", String.valueOf(r.get("day")), "rows", ColumnProfile.num(r.get("n"))));
            Collections.reverse(perDay);
            truncated |= days.truncated();

            out.put("firstSeen", span.get("mn"));
            out.put("lastSeen", span.get("mx"));
            out.put("activeDays", ColumnProfile.num(span.get("active")));
            out.put("coveredDays", covered);
            out.put("perDay", perDay);
            out.put("where", where);
            out.put("measures", measures(f, key, window));
            out.put("links", entityLinks(f, key));
            List<Map<String, Object>> related = related(f, key);
            truncated |= truncated(related);
            out.put("related", related);
            List<String> seenIn = new ArrayList<>();
            for (Map<String, Object> w : where) if (!seenIn.contains((String) w.get("dataset"))) seenIn.add((String) w.get("dataset"));
            out.put("dataAsOf", dataAsOf(f, seenIn));
            out.put("truncated", truncated);
            assessed(out);
        } catch (Exception e) {
            out.putAll(failed(e));
        }
        return out;
    }

    /**
     * The Start box's type-ahead (LA-ONBOARD-1 OB-9) over the frame's bindings (the caller keeps only link ends): the values
     * whose raw spelling - or normalised key - begins with {@code prefix}, busiest first ({@code links} = the rows naming the
     * value in a link-end column), each with its first and last seen; and {@code distinct}, the Entities matching. A masked
     * type lists nothing and is counted only for an empty prefix: a count per prefix would spell a raw value out - unless
     * {@code reveal} (the route has checked {@code canRevealLinkEntities}): then it lists raw values and says {@code revealed}.
     */
    static Map<String, Object> suggest(Frame f, String prefix, int limit, boolean reveal) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean hidden = f.masked() && !reveal;
        out.put("type", f.type.id());
        out.put("masked", f.masked());
        out.put("revealed", f.masked() && reveal);
        out.put("datasets", f.datasets());
        out.put("items", List.of());
        out.put("distinct", null);
        if (f.keysRelation == null) {
            out.putAll(notAssessed(f.bindings.isEmpty() ? "no link column of a Dataset you can view holds this Entity Type"
                    : "none of the type's link columns could be read"));
            return out;
        }
        if (hidden && !prefix.isEmpty()) {
            out.putAll(notAssessed("the Entity Type is masked: its values are not listed - type the whole value"));
            return out;
        }
        String key = prefix.isEmpty() ? "" : EntityTypes.normalise(f.type.normaliser(), prefix);
        // a value that normalises to nothing (`unknown` under e164) is no Entity, so it never starts a graph
        String where = " WHERE k <> ''" + (prefix.isEmpty() ? ""
                : " AND (starts_with(lower(raw), lower(?))" + (key.isEmpty() ? "" : " OR starts_with(k, ?)") + ")");
        List<String> binds = prefix.isEmpty() ? List.of() : key.isEmpty() ? List.of(prefix) : List.of(prefix, key);
        try {
            out.put("distinct", ColumnProfile.num(f.keys("SELECT COUNT(DISTINCT k) AS n FROM " + KEYS + where, 1, binds).rows().get(0).get("n")));
            if (hidden) {
                out.putAll(notAssessed("the Entity Type is masked: its values are not listed - type the whole value"));
                return out;
            }
            DatasetProvider.Result r = f.keys("SELECT raw AS v, COUNT(*) AS n, strftime(MIN(ts), '%Y-%m-%dT%H:%M:%SZ') AS mn, "
                    + "strftime(MAX(ts), '%Y-%m-%dT%H:%M:%SZ') AS mx FROM " + KEYS + where + " GROUP BY raw ORDER BY n DESC, v LIMIT " + limit,
                    limit, binds);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("value", String.valueOf(row.get("v")));
                item.put("links", ColumnProfile.num(row.get("n")));
                item.put("firstSeen", row.get("mn"));
                item.put("lastSeen", row.get("mx"));
                items.add(item);
            }
            out.put("items", items);
            out.put("truncated", f.datasetsTruncated);
            assessed(out);
        } catch (Exception e) {
            out.putAll(failed(e));
        }
        return out;
    }

    /** The relation name a binding's Dataset is registered under for its measure read. */
    private static final String MEASURE_ROWS = "__entity_measure_rows";

    /**
     * LA-COLUMN-ROLES-1 P3: every Measure of every Dataset the Entity is bound in, over the rows that name it there - how
     * many rows hold a number, their sum, lowest and highest, and the Measure's unit when the Dataset says one. Inside the
     * profile's window when the binding has a time column. A Dataset with no Measure column adds nothing; a value that is
     * no number is not counted (TRY_CAST). One read per binding, so a Dataset's own column names are never mixed up.
     */
    static List<Map<String, Object>> measures(Frame f, String key, String[] window) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityTypeBindings.Binding b : f.usable) {
            List<String[]> present = measureColumns(f, b);
            if (present.isEmpty()) continue;
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS n");
            for (int i = 0; i < present.size(); i++) {
                String v = "TRY_CAST(" + q(present.get(i)[1]) + " AS DOUBLE)";
                sql.append(", COUNT(").append(v).append(") AS c").append(i).append(", SUM(").append(v).append(") AS s").append(i)
                        .append(", MIN(").append(v).append(") AS lo").append(i).append(", MAX(").append(v).append(") AS hi").append(i);
            }
            String col = q(f.column(b.dataset(), b.column()));
            sql.append(" FROM ").append(MEASURE_ROWS).append(" WHERE ").append(EntityKeySql.key(f.type.normaliser(), col)).append(" = ?");
            List<String> binds = new ArrayList<>(List.of(key));
            String time = b.timeColumn() == null ? null : f.column(b.dataset(), b.timeColumn());
            if (window != null && time == null) continue;   // undated rows are outside every window, as `where` counts them
            if (window != null) {
                if (window[0] != null) {
                    sql.append(" AND TRY_CAST(").append(q(time)).append(" AS TIMESTAMP) >= CAST(? AS TIMESTAMP)");
                    binds.add(LocalDateTime.ofInstant(Instant.parse(window[0]), UTC).toString());
                }
                if (window[1] != null) {
                    sql.append(" AND TRY_CAST(").append(q(time)).append(" AS TIMESTAMP) < CAST(? AS TIMESTAMP)");
                    binds.add(LocalDateTime.ofInstant(Instant.parse(window[1]), UTC).toString());
                }
            }
            Map<String, Object> r = f.run(MEASURE_ROWS, f.relationSql.get(b.dataset()), sql.toString(), 1, binds).rows().get(0);
            for (int i = 0; i < present.size(); i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("dataset", b.dataset());
                m.put("column", b.column());
                m.put("measure", present.get(i)[0]);
                m.put("unit", present.get(i)[2]);
                m.put("rows", ColumnProfile.num(r.get("c" + i)));
                m.put("sum", number(r.get("s" + i)));
                m.put("min", number(r.get("lo" + i)));
                m.put("max", number(r.get("hi" + i)));
                out.add(m);
            }
        }
        return out;
    }

    /**
     * The Measures a binding's rows are summed over: its Dataset's {@code columns[].role: measure} columns present in the
     * relation, as {@code {measure, its own spelling, unit}} - never the bound column, never a column holding an Entity Type.
     */
    static List<String[]> measureColumns(Frame f, EntityTypeBindings.Binding b) {
        List<String[]> present = new ArrayList<>();
        Map<String, Object> content = f.resolved.datasets().get(b.dataset());
        if (content == null || !(content.get("columns") instanceof List<?> cols)) return present;
        for (Object o : cols)
            if (o instanceof Map<?, ?> c && "measure".equalsIgnoreCase(String.valueOf(c.get("role")))
                    && c.get("name") instanceof String n && f.column(b.dataset(), n) != null
                    && !n.equalsIgnoreCase(b.column())
                    // a column that holds an Entity Type is an identifier, never summed: under masking its raw values
                    // would come back as a sum, a lowest and a highest (the Dataset rules refuse such a Measure too)
                    && !(c.get("classification") instanceof String k && !k.isBlank()))
                present.add(new String[] {n, f.column(b.dataset(), n), c.get("unit") instanceof String u ? u : null});
        return present;
    }

    /** A DOUBLE aggregate as a JSON number (null when no row held a number), at most 6 decimals. */
    static Double number(Object v) {
        if (!(v instanceof Number n) || !Double.isFinite(n.doubleValue())) return null;
        // BigDecimal, not Math.round(v * 1e6): that saturates at Long.MAX_VALUE, so any total from 9.2e12 up came back wrong
        return java.math.BigDecimal.valueOf(n.doubleValue()).setScale(6, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private static Map<String, Object> entityLinks(Frame f, String key) {
        for (LinkStatsSource s : LINK_SOURCES) {
            try {
                Map<String, Object> m = s.entityLinks(f, key);
                if (m != null) return m;
            } catch (Exception e) {
                Map<String, Object> m = failed(e);
                m.put("from", null);
                return m;
            }
        }
        Map<String, Object> m = notAssessed("the type is a link end of no saved view, index mapping or default link mapping over a Dataset you can view");
        m.put("from", null);
        return m;
    }

    /**
     * Related identifiers: the other Entity Types in the same rows, with how many distinct of each and up to {@code top_n} of their
     * keys - as aliases whenever either type is masked under the Space's mode (the exploration rule: a masked bound column masks
     * every id the read returns).
     */
    private static List<Map<String, Object>> related(Frame f, String key) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EntityTypes.EntityType other : f.resolved.types()) {
            if (other.id().equals(f.type.id())) continue;
            List<String> parts = new ArrayList<>();
            for (String ds : f.datasets()) {
                if (f.relationSql.get(ds) == null) continue;
                for (EntityTypeBindings.Binding a : f.usable) {
                    if (!a.dataset().equals(ds)) continue;
                    for (EntityTypeBindings.Binding c : f.resolved.of(other.id())) {
                        String cc = c.dataset().equals(ds) ? f.column(ds, c.column()) : null;
                        if (cc == null || !EntityTypeBindings.sameParty(a, c)) continue;   // the same party of the row only
                        parts.add("SELECT " + EntityKeySql.key(f.type.normaliser(), q(f.column(ds, a.column()))) + " AS e, NULLIF("
                                + EntityKeySql.key(other.normaliser(), q(cc)) + ", '') AS o, CAST(" + q(cc) + " AS VARCHAR) AS raw FROM ("
                                + f.relationSql.get(ds) + ") AS __r");
                    }
                }
            }
            if (parts.isEmpty()) continue;
            DatasetProvider.Result r = f.run(PAIRS, String.join(" UNION ALL ", parts), "SELECT o, MIN(raw) AS raw, COUNT(*) OVER () AS total FROM "
                    + PAIRS + " WHERE e = ? AND o IS NOT NULL GROUP BY o ORDER BY o", f.lim.topN(), List.of(key));
            if (r.rows().isEmpty()) continue;
            boolean masked = maskedUnder(f.maskMode, f.type) || maskedUnder(f.maskMode, other);
            List<String> keys = new ArrayList<>();
            for (Map<String, Object> row : r.rows()) {
                String raw = String.valueOf(row.get("raw"));
                keys.add(masked ? ExplorationMasking.alias(f.writeRoot, raw) : raw);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", other.id());
            m.put("count", ColumnProfile.num(r.rows().get(0).get("total")));
            m.put("keys", keys);
            m.put("truncated", r.truncated());
            out.add(m);
        }
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────────

    static Map<String, Object> typeMap(EntityTypes.EntityType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("label", t.label());
        m.put("normaliser", t.normaliser());
        m.put("masked", t.masked());
        m.put("classifications", t.classifications());
        EntityTypes.putGuidance(m, t);
        return m;
    }

    /** A two-key row in wire order. */
    private static Map<String, Object> row(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    static double round(double v) {
        return Math.round(v * 10_000d) / 10_000d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(List<?> l) {
        return (List<Map<String, Object>>) l;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
