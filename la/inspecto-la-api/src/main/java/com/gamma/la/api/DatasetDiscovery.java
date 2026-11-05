package com.gamma.la.api;

import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.InputFingerprint;
import com.gamma.spi.http.ApiContext;
import com.gamma.sql.SqlSandboxPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.util.SqlIdent.q;

/**
 * Discover one Dataset (LA-BASIC-DISCOVER-1, plan {@code docs/superpower/la-basic-discovery-plan.md} §3 P1): what is in it
 * before anything is drawn - its rows and period, the distinct entities per Entity Type, the kinds of link, the totals of
 * every Measure and the busiest entities at each end of the links.
 *
 * <p><b>Cheap passes over THIS Dataset, never the Entity Type union.</b> V4 measured the {@link EntityProfiles} union read at
 * 112 s on IMS (39.4 M links): Discover instead reads the Dataset's own columns in four statements - one pass for rows, period
 * and Measures; one approximate distinct count per Entity Type over its columns (normalised as the profiles normalise); one
 * {@code GROUP BY} on the kind of link; and the busiest entities from the link index's {@code nodes} table when a current index
 * exists (the presets' path, {@link PresetRoutes#fromIndex}), else from the Dataset under the Space's statement timeout.
 *
 * <p><b>Never zero for the unmeasured.</b> Every section says {@code assessed} and, when not, {@code reason} (no such role, a
 * read that failed or ran out of time). Counts of distinct entities are approximate and say so.
 *
 * <p><b>Roles</b> (LA-COLUMN-ROLES-1): From, To, Kind and the primary Time come from {@link PresetQueries#roles} (the link
 * mapping's primaries, else the back-compatible read); the Measures are every column whose role is {@code measure} (with its
 * {@code unit}), plus the mapping's primary Measure when no column states a role; the Entity Types are the columns classified
 * as one the Space knows.
 *
 * <p><b>Cached</b> per Space, Dataset, its entry, the roles and the limits, stamped with the Dataset's input-file fingerprint:
 * a new File (an arrival) changes the stamp and the next read recomputes. A Dataset without a {@code files:} fingerprint is
 * never cached. Entity ids are cached RAW and masked on the way out, so a change of the Space's masking applies at once.
 */
final class DatasetDiscovery {

    private DatasetDiscovery() {}

    /** The busiest entities listed per end. */
    static final int TOP = 10;
    /** The most kinds of link listed. */
    static final int MAX_KINDS = 50;
    private static final ZoneId UTC = ZoneId.of("UTC");   // DuckDB refuses "Z"

    /** One Measure column: its own spelling and its unit (null when the Dataset states none). */
    record Measure(String column, String unit) {}

    /** One Entity Type the Dataset holds and the columns that hold it. */
    record Held(EntityTypes.EntityType type, List<String> columns) {}

    /**
     * The Discover answer for {@code t} with entity ids RAW ({@link #mask} turns them into aliases): served from the cache when
     * the Dataset's input files have not changed.
     */
    static Map<String, Object> discover(ApiContext api, Path writeRoot, PresetRoutes.Target t, Map<String, Object> dataset,
                                        List<DatasetProvider.Column> columns) {
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        List<Measure> measures = measures(dataset, columns, t.roles());
        List<Held> held = held(dataset, columns, settings.effectiveEntityTypes());
        ValueMeasures.Fences fences = ValueMeasures.Fences.of(writeRoot);
        // the masking mode is in the key: a cached Entity Type's `masked` must follow a change of the Space's mode
        String key = sha256(writeRoot + "|" + t.datasetId() + "|" + dataset + "|" + t.roles() + "|" + fences + "|" + held + "|"
                + settings.effectiveMaskingMode());
        String files = stamp(api, writeRoot, t.datasetId(), dataset);
        // ... and the link index published for it is in the stamp: an index built (or rebuilt) since, answers the busiest
        String stamp = files == null ? null : files + "|" + indexStamp(writeRoot, t.datasetId());
        Map<String, Object> hit = stamp == null ? null : Cache.get(key, stamp);
        if (hit != null) {
            Map<String, Object> out = new LinkedHashMap<>(hit);
            out.put("cached", true);
            return out;
        }
        SqlSandboxPolicy policy = SqlSandboxPolicy.withCaps(null, 0, fences.timeoutSeconds());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", t.datasetId());
        out.put("computedAt", Instant.now().toString());
        out.put("cached", false);
        Map<String, Object> roles = new LinkedHashMap<>();
        roles.put("from", t.roles().from());
        roles.put("to", t.roles().to());
        roles.put("kind", t.roles().kind());
        roles.put("time", t.roles().time());
        out.put("roles", roles);

        Map<String, Object> pass = null;
        Exception passFailed = null;
        try {
            pass = onePass(t, measures, policy);
        } catch (Exception e) {
            passFailed = e;
        }
        out.put("rows", pass == null ? null : ColumnProfile.num(pass.get("n")));
        if (pass == null) out.put("rowsReason", EntityProfiles.reason(passFailed));   // never a bare null for a count not taken
        out.put("entityTypes", entityTypes(t, held, settings.effectiveMaskingMode(), policy));
        out.put("period", period(t, pass, passFailed));
        out.put("linkKinds", linkKinds(t, policy));
        Map<String, Object> busiest = busiest(api, writeRoot, t, fences);
        busiest.put("fromType", typeHolding(held, t.roles().from()));
        busiest.put("toType", typeHolding(held, t.roles().to()));
        out.put("busiest", busiest);
        out.put("measures", measureTotals(measures, pass, passFailed));
        // a read that failed or ran out of time is answered but never kept: the next read tries again
        if (stamp != null && passFailed == null && !failedAnywhere(out)) Cache.put(key, stamp, out);
        return out;
    }

    // ── roles ────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The Measures: every column whose role is {@code measure} (and that holds no Entity Type - an identifier is never summed),
     * in the entry's order; when no column states a role at all, the mapping's primary Measure (the back-compatible read).
     */
    static List<Measure> measures(Map<String, Object> dataset, List<DatasetProvider.Column> columns, PresetQueries.Roles roles) {
        List<Measure> out = new ArrayList<>();
        boolean anyRole = false;
        if (dataset.get("columns") instanceof List<?> cols)
            for (Object o : cols) {
                if (!(o instanceof Map<?, ?> c)) continue;
                if (c.get("role") instanceof String r && !r.isBlank()) anyRole = true;
                String real = real(columns, c.get("name"));
                if (real != null && "measure".equalsIgnoreCase(String.valueOf(c.get("role")))
                        && !(c.get("classification") instanceof String k && !k.isBlank()))
                    out.add(new Measure(real, c.get("unit") instanceof String u && !u.isBlank() ? u.trim() : null));
            }
        if (!anyRole && roles.amount() != null) out.add(new Measure(roles.amount(), null));
        return out;
    }

    /** The Entity Types the Dataset holds: its columns classified as a type the Space knows, grouped by type, in column order. */
    static List<Held> held(Map<String, Object> dataset, List<DatasetProvider.Column> columns, List<EntityTypes.EntityType> types) {
        Map<String, Held> out = new LinkedHashMap<>();
        if (dataset.get("columns") instanceof List<?> cols)
            for (Object o : cols) {
                if (!(o instanceof Map<?, ?> c) || !(c.get("classification") instanceof String k) || k.isBlank()) continue;
                String real = real(columns, c.get("name"));
                EntityTypes.EntityType type = typeOf(k, types);
                if (real == null || type == null) continue;
                out.computeIfAbsent(type.id(), id -> new Held(type, new ArrayList<>())).columns().add(real);
            }
        return List.copyOf(out.values());
    }

    private static EntityTypes.EntityType typeOf(String classification, List<EntityTypes.EntityType> types) {
        for (EntityTypes.EntityType t : types)
            for (String c : t.classifications()) if (c.trim().equalsIgnoreCase(classification.trim())) return t;
        return null;
    }

    /** The id of the Entity Type {@code column} holds (what an entity summary of a busiest row asks for), or null. */
    private static String typeHolding(List<Held> held, String column) {
        if (column != null) for (Held h : held) if (h.columns().contains(column)) return h.type().id();
        return null;
    }

    /** The relation's own spelling of a column named in the entry (only names a quoted identifier may carry), or null. */
    private static String real(List<DatasetProvider.Column> columns, Object name) {
        if (!(name instanceof String s)) return null;
        for (DatasetProvider.Column c : columns)
            if (c.name().equalsIgnoreCase(s.trim()) && PresetQueries.SAFE_IDENT.matcher(c.name()).matches()) return c.name();
        return null;
    }

    // ── the reads ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private static DatasetProvider.Result run(PresetRoutes.Target t, String sql, int limit, SqlSandboxPolicy policy)
            throws Exception {
        return DatasetProviders.require().run(new DatasetProvider.Request(t.datasetId(), t.relationSql(), sql, limit, 0, List.of(),
                List.of(), List.of()), policy, UTC);
    }

    /** Rows, the period on the primary Time, and each Measure's count, sum and approximate median - one scan. */
    private static Map<String, Object> onePass(PresetRoutes.Target t, List<Measure> measures, SqlSandboxPolicy policy)
            throws Exception {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS n");
        if (t.roles().time() != null) {
            String ts = "TRY_CAST(" + q(t.roles().time()) + " AS TIMESTAMP)";
            sql.append(", strftime(MIN(").append(ts).append("), '%Y-%m-%dT%H:%M:%SZ') AS mn, strftime(MAX(").append(ts)
                    .append("), '%Y-%m-%dT%H:%M:%SZ') AS mx, date_diff('day', CAST(MIN(").append(ts).append(") AS DATE), CAST(MAX(")
                    .append(ts).append(") AS DATE)) + 1 AS days");
        }
        for (int i = 0; i < measures.size(); i++) {
            String v = "TRY_CAST(" + q(measures.get(i).column()) + " AS DOUBLE)";
            sql.append(", COUNT(").append(v).append(") AS c").append(i).append(", SUM(").append(v).append(") AS s").append(i)
                    .append(", approx_quantile(").append(v).append(", 0.5) AS m").append(i);
        }
        return run(t, sql.append(" FROM ").append(q(t.datasetId())).toString(), 1, policy).rows().get(0);
    }

    private static Map<String, Object> period(PresetRoutes.Target t, Map<String, Object> pass, Exception failed) {
        Map<String, Object> m = section();
        m.put("column", t.roles().time());
        m.put("from", null);
        m.put("to", null);
        m.put("days", null);
        if (t.roles().time() == null) return notAssessed(m, "no column of this Dataset is a Time");
        if (pass == null) return failed(m, failed);
        if (pass.get("mn") == null) return notAssessed(m, "no row of '" + t.roles().time() + "' reads as a date or time");
        m.put("from", pass.get("mn"));
        m.put("to", pass.get("mx"));
        m.put("days", ColumnProfile.num(pass.get("days")));
        return assessed(m);
    }

    private static Map<String, Object> measureTotals(List<Measure> measures, Map<String, Object> pass, Exception failed) {
        Map<String, Object> m = section();
        m.put("items", List.of());
        if (measures.isEmpty()) return notAssessed(m, "no column of this Dataset is a Measure");
        if (pass == null) return failed(m, failed);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < measures.size(); i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("column", measures.get(i).column());
            r.put("unit", measures.get(i).unit());
            r.put("rows", ColumnProfile.num(pass.get("c" + i)));
            r.put("sum", EntityProfiles.number(pass.get("s" + i)));
            r.put("median", EntityProfiles.number(pass.get("m" + i)));
            items.add(r);
        }
        m.put("items", items);
        return assessed(m);
    }

    /** About how many distinct entities of each type, over all the columns that hold it (keys normalised as the profiles do). */
    private static Map<String, Object> entityTypes(PresetRoutes.Target t, List<Held> held, String maskMode, SqlSandboxPolicy policy) {
        Map<String, Object> m = section();
        m.put("items", List.of());
        if (held.isEmpty()) return notAssessed(m, "no column of this Dataset holds an Entity Type");
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            for (Held h : held) {
                List<String> parts = new ArrayList<>();
                for (String c : h.columns())
                    parts.add("SELECT NULLIF(" + EntityKeySql.key(h.type().normaliser(), q(c)) + ", '') AS k FROM " + q(t.datasetId()));
                Object n = run(t, "SELECT approx_count_distinct(k) AS n FROM (" + String.join(" UNION ALL ", parts) + ") AS __k", 1,
                        policy).rows().get(0).get("n");
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("type", h.type().id());
                r.put("label", h.type().label());
                r.put("columns", h.columns());
                r.put("distinct", ColumnProfile.num(n));
                r.put("approximate", true);
                r.put("masked", EntityProfiles.maskedUnder(maskMode, h.type()));
                items.add(r);
            }
        } catch (Exception e) {
            return failed(m, e);
        }
        m.put("items", items);
        return assessed(m);
    }

    private static Map<String, Object> linkKinds(PresetRoutes.Target t, SqlSandboxPolicy policy) {
        Map<String, Object> m = section();
        m.put("column", t.roles().kind());
        m.put("items", List.of());
        m.put("truncated", false);
        if (t.roles().kind() == null) return notAssessed(m, "no column of this Dataset names the kind of link");
        try {
            DatasetProvider.Result r = run(t, "SELECT CAST(" + q(t.roles().kind()) + " AS VARCHAR) AS kind, COUNT(*) AS n FROM "
                    + q(t.datasetId()) + " GROUP BY 1 ORDER BY n DESC, kind NULLS LAST", MAX_KINDS, policy);
            List<Map<String, Object>> items = new ArrayList<>();
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> k = new LinkedHashMap<>();
                k.put("kind", row.get("kind"));
                k.put("links", ColumnProfile.num(row.get("n")));
                items.add(k);
            }
            m.put("items", items);
            m.put("truncated", r.truncated());
        } catch (Exception e) {
            return failed(m, e);
        }
        return assessed(m);
    }

    /**
     * The busiest entities at each end: Reaching many (by From) and Reached by many (by To), ranked by distinct counterparts -
     * from the link index when one answers, else grouped over the Dataset under the Space's statement timeout.
     */
    private static Map<String, Object> busiest(ApiContext api, Path writeRoot, PresetRoutes.Target t, ValueMeasures.Fences fences) {
        Map<String, Object> m = section();
        m.put("from", List.of());
        m.put("to", List.of());
        if (t.roles().from() == null || t.roles().to() == null)
            return notAssessed(m, "the Dataset does not say which columns link one entity to another (a Mapping's From and To)");
        int limit = Math.min(TOP, fences.maxEntities());
        Map<String, String> given = Map.of("min", "1");
        Map<String, Object> source = null;
        try {
            for (String end : List.of("from", "to")) {
                PresetQueries.Preset p = PresetQueries.find(null, "from".equals(end) ? "fan_out" : "fan_in");
                PresetRoutes.Answer indexed = PresetRoutes.fromIndex(api, writeRoot, t, p, given, limit);
                List<Map<String, Object>> rows;
                if (indexed != null && indexed.rows() != null) {
                    rows = indexed.rows();
                    source = indexed.source();
                } else {
                    if (indexed != null) source = indexed.source();
                    List<PresetQueries.Column> cols = List.of(new PresetQueries.Column("entity", "Entity", true),
                            new PresetQueries.Column("counterparts", "Distinct counterparts", false),
                            new PresetQueries.Column("links", "Links", false));
                    List<String> binds = new ArrayList<>();
                    String sql = PresetQueries.aggregateSql(t.datasetId(), p.aggregate(), t.roles(), cols, null, List.of(), 1.0, binds);
                    rows = DatasetProviders.require().run(new DatasetProvider.Request(t.datasetId(), t.relationSql(), sql, limit, 0,
                            List.of(), List.of(), binds), SqlSandboxPolicy.withCaps(null, 0, fences.timeoutSeconds()), UTC).rows();
                }
                List<Map<String, Object>> busy = new ArrayList<>();
                for (Map<String, Object> r : rows) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("entity", r.get("entity") == null ? null : String.valueOf(r.get("entity")));
                    b.put("links", ColumnProfile.num(r.get("links")));
                    b.put("counterparts", ColumnProfile.num(r.get("counterparts")));
                    busy.add(b);
                }
                m.put(end, busy);
            }
        } catch (Exception e) {
            m.put("from", List.of());
            m.put("to", List.of());
            if (EntityProfiles.isTimeout(String.valueOf(e.getMessage())) || e instanceof java.sql.SQLTimeoutException) {
                notAssessed(m, "ranking every entity took longer than this Space allows (" + fences.timeoutSeconds()
                        + " s); a link index of this Dataset answers it quickly");
                m.put("retryable", true);   // a timeout is never cached: an index built next answers the following read
                return m;
            }
            return failed(m, e);
        }
        m.put("source", source == null ? Map.of("kind", "dataset") : source);
        return assessed(m);
    }

    // ── masking, sections, cache ───────────────────────────────────────────────────────────────────────────────────────

    /** The answer as the caller sees it: the busiest entity ids masked like exploration, plus what masking applied. */
    static Map<String, Object> mask(Map<String, Object> raw, ExplorationMasking mask) {
        Map<String, Object> out = new LinkedHashMap<>(raw);
        if (raw.get("busiest") instanceof Map<?, ?> b) {
            Map<String, Object> busy = new LinkedHashMap<>();
            b.forEach((k, v) -> busy.put(String.valueOf(k), v));
            for (String end : List.of("from", "to"))
                if (b.get(end) instanceof List<?> l) {
                    List<Map<String, Object>> shown = new ArrayList<>();
                    for (Object o : l)
                        if (o instanceof Map<?, ?> r) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            r.forEach((k, v) -> c.put(String.valueOf(k), "entity".equals(k) ? mask.out(v) : v));
                            shown.add(c);
                        }
                    busy.put(end, shown);
                }
            out.put("busiest", busy);
        }
        out.put("masking", mask.describe());
        return out;
    }

    private static Map<String, Object> section() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assessed", false);
        m.put("reason", null);
        return m;
    }

    private static Map<String, Object> assessed(Map<String, Object> m) {
        m.put("assessed", true);
        m.put("reason", null);
        return m;
    }

    private static Map<String, Object> notAssessed(Map<String, Object> m, String reason) {
        m.put("assessed", false);
        m.put("reason", reason);
        return m;
    }

    /** A read that failed: not assessed, with the scrubbed reason, and {@code retryable} so the answer is never cached. */
    private static Map<String, Object> failed(Map<String, Object> m, Exception e) {
        notAssessed(m, EntityProfiles.reason(e));
        m.put("retryable", true);
        return m;
    }

    private static boolean failedAnywhere(Map<String, Object> out) {
        for (Object v : out.values()) if (v instanceof Map<?, ?> s && Boolean.TRUE.equals(s.get("retryable"))) return true;
        return false;
    }

    /** The Dataset's input-file fingerprint, or null when it has none that can say whether its data changed. */
    private static String stamp(ApiContext api, Path writeRoot, String datasetId, Map<String, Object> dataset) {
        InputFingerprint fp = InputFingerprintCache.get(writeRoot, datasetId, "discover",
                () -> DatasetProviders.require().inputFingerprint(dataset, api.dataRoot(), writeRoot));
        return fp == null || fp.value() == null || !fp.value().startsWith(InputFingerprint.FILES) ? null : fp.value();
    }

    /** The versions the link index currently publishes for the Dataset (one per mapping), and whether indexes are on. */
    private static String indexStamp(Path writeRoot, String datasetId) {
        StringBuilder sb = new StringBuilder(String.valueOf(LinkAnalysisSettings.forRoot(writeRoot).effectiveIndex().enabledInForce()));
        Path root = writeRoot.resolve(IndexRoutes.INDEX_DIR);
        try {
            for (String hash : com.gamma.la.storage.IndexStore.mappingHashes(root, datasetId))
                sb.append('|').append(hash).append('=').append(new com.gamma.la.storage.IndexStore(root, datasetId, hash).current()
                        .map(p -> p.getFileName().toString()).orElse("-"));
        } catch (IllegalArgumentException cannotNameADirectory) {
            sb.append("|none");
        }
        return sb.toString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** In-memory, bounded (least recently used out): one answer per (Space, Dataset, entry, roles, limits), stamped. */
    static final class Cache {
        static final int MAX_ENTRIES = 128;

        private record Cached(String stamp, Map<String, Object> answer) {}

        private static final Map<String, Cached> ENTRIES = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                return size() > MAX_ENTRIES;
            }
        };

        private Cache() {}

        static synchronized Map<String, Object> get(String key, String stamp) {
            Cached c = ENTRIES.get(key);
            return c != null && c.stamp().equals(stamp) ? c.answer() : null;
        }

        static synchronized void put(String key, String stamp, Map<String, Object> answer) {
            ENTRIES.put(key, new Cached(stamp, java.util.Collections.unmodifiableMap(new LinkedHashMap<>(answer))));
        }

        /** Test seam. */
        static synchronized void clear() {
            ENTRIES.clear();
        }
    }
}
