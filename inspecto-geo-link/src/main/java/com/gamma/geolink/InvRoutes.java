package com.gamma.geolink;

import com.gamma.control.ApiContext;
import com.gamma.control.ApiException;
import com.gamma.control.ComponentAccess;
import com.gamma.control.RouteModule;
import com.gamma.control.WriteGates;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.ViewStore;
import com.gamma.query.ConditionSql;
import com.gamma.query.DatasetRelation;
import com.gamma.query.QueryExecutor;
import com.gamma.query.ResultSetDescriptor;
import com.gamma.sql.SqlGuard;
import com.gamma.util.JsonAttributes;
import com.gamma.util.SqlIdent;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Investigation-studio backend (INV-1): the real <b>Entity Projection</b> over a Dataset — the DuckDB-side
 * fold the Link Analysis studio's mock-first {@code entity-projection} GraphSource was designed against
 * ({@code docs/superpower/link-analysis-and-graphsource.md} §7).
 *
 * <p>{@code POST /inv/projection} — body {@code {dataset, sourceCol, targetCol, linkKindCol?, attrCols?, limit?, filter?}}
 * → {@code {rows:[{source,target,kind,count,attrs?}], truncated}}: distinct {@code (source, target[, kind]
 * [, ...attrCols])} tuples with folded row counts, heaviest first. When {@code attrCols} is given, each
 * column joins the fold key — a folded edge with differing attribute values across rows becomes separate
 * output rows, one per distinct attribute combination, consistent with the "identical tuples fold" contract
 * above (no attribute value is silently dropped). The G6 node/edge <em>presentation</em> fold (entity nodes,
 * edge kind·count labels, the 500-node cap) deliberately stays client-side where it already lives — this
 * endpoint is the aggregation, so the projection scales to Datasets far beyond what the browser could fold
 * row-by-row.
 *
 * <p>{@code POST /inv/projection/neighbors} (Phase E, incremental expand) — same body plus a required
 * {@code value}: the one-hop neighborhood of that entity (rows where it's either endpoint), so the
 * Studio's "expand node" action can grow the canvas without re-fetching the whole relation.
 *
 * <p>{@code POST /inv/projection/multi} (LA-08) — node mappings and edge projections across several Datasets
 * in one call, every row tagged with its {@code __provenance_dataset}. See {@link #projectMulti}.
 *
 * <p>Fail-closed like {@code BiRoutes}: write root unset → 503; unknown dataset → 404; a non-identifier
 * column or unusable dataset → 422. Column names are validated identifiers — no caller SQL text enters
 * the statement — and NULL endpoints are excluded (a link needs both ends).
 *
 * <p>{@code GET /inv/schema/relationships} — the schema-relationship model (INV-1 V1's last open item):
 * naming-convention FK suggestions across every Dataset, so the Studio can pre-fill multi-mapping
 * projections instead of requiring every column pair to be hand-picked. See {@link #schemaRelationships}.
 *
 * <p>{@code POST /inv/schema/overlap-profile} (LA-15) — the <em>empirical</em> half of the same question:
 * cardinality and Jaccard overlap across candidate key columns, so an implicit foreign key naming never
 * reveals still surfaces, and a name match whose values never meet can be discounted. Body
 * {@code {datasets?, columns?, limit?}}; see {@link #overlapProfile} for why that is the whole body.
 */
/*
 * ⚠ Relocated from com.gamma.control (inspecto) on 2026-09-07, EDG-01 cell 3b — EDITIONS CP-09 is "not for
 * Personal", and this class shipped in every bundle because it sat in the core. It now reaches ControlApi only
 * through the public RouteModule SPI (META-INF/services), from a module the Personal build does not include.
 * The package moved with it so com.gamma.control is not split across two jars. Nothing in the handlers changed.
 */
public final class InvRoutes implements RouteModule {

    private static final Pattern SAFE_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final int DEFAULT_LIMIT = 2_000;
    private static final int MAX_LIMIT = 20_000;
    /** LA-15 pair budget: one DuckDB query per measured pair, so the cap is a query-count cap. */
    private static final int DEFAULT_PAIRS = 100;
    private static final int MAX_PAIRS = 500;
    /** The synthetic relation name a pair's {@code A UNION ALL B} is registered under (LA-15). */
    private static final String PAIR_RELATION = "__overlap_pair";

    @Override
    public void register(ApiContext api) {
        api.post("/inv/projection", (e, m) -> project(api, e, api.body(e), null));
        api.post("/inv/projection/neighbors", (e, m) -> neighbors(api, e, api.body(e)));
        api.post("/inv/projection/multi", (e, m) -> projectMulti(api, e, api.body(e)));
        api.get("/inv/schema/relationships", (e, m) -> schemaRelationships(api, e));
        api.post("/inv/schema/overlap-profile", (e, m) -> overlapProfile(api, e, api.body(e)));
        // ⛔ The two POSTs genuinely PERSIST, so unlike the projection routes above they cannot take the
        // "read-shaped" exemption — that exemption says "persists nothing", and claiming it here would be a
        // false declaration in the file whose whole job is to say what each route is gated on. They are gated
        // on canManageIncidents, the same capability as POST /objects: sealing evidence and attaching it to a
        // Case is Case work. ⚠ The GET stays ungated like this module's other reads, and returns ids only.
        // ⚠ The capability is written as a string LITERAL on purpose, not as the constant, and that is
        // not a style slip: CapabilityManifestTest scans these registration sites with a regex matching
        // only a literal argument, so a constant reference reads to it as "declared but not registered"
        // and takes the build red. Every other withCapability site in the repo uses the literal too.
        api.post("/inv/snapshots", ApiContext.withCapability("canManageIncidents",
                (e, m) -> createSnapshot(api, e, api.body(e))));
        api.get("/inv/snapshots", (e, m) -> listSnapshots(api, e));
        api.post("/inv/snapshots/attach", ApiContext.withCapability("canManageIncidents",
                (e, m) -> attachSnapshot(api, e, api.body(e))));
    }

    private static final int SNAPSHOT_LIST_DEFAULT = 100;
    private static final int SNAPSHOT_LIST_MAX = 1000;

    /**
     * {@code POST /inv/snapshots} (LA-03) — seal one Link Analysis evidence snapshot.
     *
     * <p>The body is the SPA's {@code GraphSnapshot} verbatim: full {@code nodes} and {@code edges}
     * ("frozen content"), {@code metrics}, {@code predicate}, {@code origin}, {@code annotations} and the
     * {@code manifestHash} fingerprint. ⚠ It is stored as given — the route neither re-shapes nor narrows it,
     * because storing id references instead of content would hand the sealed record the very defect that makes
     * a saved view not-evidence (plan §5.4, corrected 2026-09-22). Key ORDER is not preserved and need not be:
     * the SPA canonicalises (sorts keys) before hashing, so {@code manifestHash} survives a re-serialisation.
     *
     * <p>Gates, fail-closed in order: write root unset → 503; a missing or unsafe {@code id}, or an absent
     * {@code manifestHash} → 422; a resolved path escaping the snapshot directory → 403; an id that already
     * exists → <b>409</b>. 🔴 The 409 is the object's whole point, not a technicality — evidence that can be
     * silently replaced is not evidence.
     *
     * <p>⛔ One more fail-closed step that is easy to miss: {@link JsonAttributes#toPayloadJson} is deliberately
     * TOTAL and answers {@code "{}"} on any serialisation failure. Sealing that would store an EMPTY snapshot
     * under a real id and report success — a false negative wearing the costume of evidence. A non-empty body
     * that serialises to {@code "{}"} is therefore refused (500) rather than written.
     */
    private Object createSnapshot(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis snapshot write");
        String id = str(body.get("id"));
        if (id == null || !SnapshotStore.SAFE_ID.matcher(id).matches())
            throw new ApiException(422, "id must match " + SnapshotStore.SAFE_ID.pattern() + ", got '" + id + "'");
        if (str(body.get("manifestHash")) == null)
            throw new ApiException(422, "manifestHash is required — an unfingerprinted snapshot cannot be verified");

        String json = JsonAttributes.toPayloadJson(body);
        if (!body.isEmpty() && "{}".equals(json))
            throw new ApiException(500, "snapshot could not be serialised — refusing to seal an empty record");

        SnapshotStore store = new SnapshotStore(writeRoot);
        Path target = store.directory().resolve(id + ".json").normalize();
        if (!target.startsWith(store.directory().normalize()))
            throw new ApiException(403, "snapshot id escapes the snapshot directory");

        if (!store.create(id, json))
            throw new ApiException(409, "snapshot '" + id + "' already exists — a sealed snapshot is never replaced");

        emitSnapshotEvent(ex, EventType.LINK_SNAPSHOT_SEALED, "link.snapshot.sealed",
                "link.snapshot.sealed — " + sizeOf(body.get("nodes")) + " nodes, "
                        + sizeOf(body.get("edges")) + " edges",
                b -> b.attr("snapshotId", id).attr("nodes", sizeOf(body.get("nodes")))
                        .attr("edges", sizeOf(body.get("edges"))));
        return Map.of("id", id, "sealed", true);
    }

    /**
     * {@code GET /inv/snapshots?limit=n} — sealed snapshot ids, newest first. Bounded like every other
     * diagnostic read here: {@code limit} defaults to {@value #SNAPSHOT_LIST_DEFAULT}, clamps to
     * {@value #SNAPSHOT_LIST_MAX}, and the TRUE total ships alongside so a bounded read never reads as a
     * complete one.
     */
    private Object listSnapshots(ApiContext api, HttpExchange ex) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis snapshot list");
        int limit = SNAPSHOT_LIST_DEFAULT;
        String raw = ApiContext.query(ex, "limit");
        if (raw != null && !raw.isBlank()) {
            try {
                limit = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, "limit must be an integer, got '" + raw + "'");
            }
        }
        limit = Math.min(Math.max(limit, 1), SNAPSHOT_LIST_MAX);
        SnapshotStore store = new SnapshotStore(writeRoot);
        int[] total = new int[1];
        List<String> ids = store.list(limit, total);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ids", ids);
        out.put("total", total[0]);
        out.put("truncated", total[0] > ids.size());
        return out;
    }

    /**
     * {@code POST /inv/snapshots/attach} — body {@code {snapshotId, caseId}} — record that a sealed snapshot
     * was attached to a Case.
     *
     * <p>🔴 <b>This never reopens the snapshot.</b> Attachment is a relationship, not part of the sealed
     * content; writing it into the record would mutate a sealed object and invalidate the fingerprint that
     * makes it evidence. It appends to a separate log instead. ⚠ The Case id is deliberately NOT verified to
     * exist: the ops module owns Cases and is absent in some editions, so a hard dependency would make
     * evidence capture fail wherever Case management is not installed.
     */
    private Object attachSnapshot(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis snapshot attach");
        String snapshotId = str(body.get("snapshotId"));
        String caseId = str(body.get("caseId"));
        if (snapshotId == null || !SnapshotStore.SAFE_ID.matcher(snapshotId).matches())
            throw new ApiException(422, "snapshotId must match " + SnapshotStore.SAFE_ID.pattern());
        if (caseId == null || caseId.isBlank()) throw new ApiException(422, "caseId is required");

        SnapshotStore store = new SnapshotStore(writeRoot);
        if (store.read(snapshotId) == null) throw new ApiException(404, "no sealed snapshot '" + snapshotId + "'");
        store.attach(snapshotId, caseId, java.time.Instant.now().toString());
        emitSnapshotEvent(ex, EventType.LINK_SNAPSHOT_ATTACHED, "link.snapshot.attached",
                "link.snapshot.attached — " + snapshotId + " → " + caseId,
                b -> b.attr("snapshotId", snapshotId).attr("caseId", caseId));
        return Map.of("snapshotId", snapshotId, "attachedTo", store.attachmentsOf(snapshotId));
    }

    /**
     * Audit one snapshot act. Best effort, exactly like the projection events (LA-04): an audit failure must
     * never fail the analyst's call — but note the ordering, which is deliberate. The event is emitted AFTER
     * the write succeeds, so the trail never claims a seal that did not happen.
     */
    private static void emitSnapshotEvent(HttpExchange ex, String type, String action, String message,
                                          java.util.function.UnaryOperator<Event.Builder> attrs) {
        try {
            Event.Builder b = Event.builder(type).source("inv").message(message)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis");
            EventLog.current().emit(attrs.apply(b));
        } catch (RuntimeException ignored) {
            // audit is best effort; the snapshot is already sealed and that is what matters
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int sizeOf(Object v) {
        return v instanceof List<?> l ? l.size() : 0;
    }

    /**
     * {@code GET /inv/schema/relationships} — the schema-relationship model (INV-1 V1's last open
     * item, {@code docs/superpower/link-analysis-and-graphsource.md} §7): suggests entity-projection
     * mappings across Datasets by naming convention instead of requiring the user to hand-pick every
     * column pair. For every Dataset column named {@code <base>_id}, looks for a Dataset whose id
     * matches {@code <base>} (singular or plural) and links to its {@code id} column ({@code high}
     * confidence) or, failing that, to a same-named column on that Dataset ({@code medium}
     * confidence). Self-references (e.g. {@code manager_id} on the same Dataset) are included —
     * hierarchies are a legitimate entity-projection use case. Best-effort: a Dataset whose relation
     * can't be probed (unbound, bad view) is silently skipped, never fails the whole call.
     */
    private Object schemaRelationships(ApiContext api, HttpExchange ex) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "schema relationship inference");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        ViewStore views = new ViewStore(writeRoot.resolve("views"));

        Map<String, List<String>> columnsByDataset = new LinkedHashMap<>();
        int skipped = 0;
        for (ComponentRegistry.Component c : store.list("dataset")) {
            try {
                String relationSql = DatasetRelation.relationSql(c.content(), api.dataRoot(), views);
                QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                        c.name(), relationSql, "SELECT * FROM " + q(c.name()), 0, 0, List.of(), List.of()));
                columnsByDataset.put(c.name(),
                        r.columns().stream().map(ResultSetDescriptor.Column::name).toList());
            } catch (Exception unusable) {
                skipped++;   // unbound dataset, bad view, etc. — degrade, don't fail the call
            }
        }

        List<Map<String, Object>> relationships = new ArrayList<>();
        for (var from : columnsByDataset.entrySet()) {
            for (String col : from.getValue()) {
                String base = fkBase(col);
                if (base == null) continue;
                for (var to : columnsByDataset.entrySet()) {
                    if (!matchesDatasetName(base, to.getKey())) continue;
                    String toCol = containsIgnoreCase(to.getValue(), "id") ? "id"
                            : containsIgnoreCase(to.getValue(), col) ? col : null;
                    if (toCol == null) continue;
                    String confidence = "id".equals(toCol) ? "high" : "medium";
                    Map<String, Object> rel = new LinkedHashMap<>();
                    rel.put("fromDataset", from.getKey());
                    rel.put("fromColumn", col);
                    rel.put("toDataset", to.getKey());
                    rel.put("toColumn", toCol);
                    rel.put("confidence", confidence);
                    relationships.add(rel);
                }
            }
        }
        relationships.sort((a, b) -> {
            int c = ((String) a.get("confidence")).equals("high") == ((String) b.get("confidence")).equals("high")
                    ? 0 : ((String) a.get("confidence")).equals("high") ? -1 : 1;
            if (c != 0) return c;
            c = ((String) a.get("fromDataset")).compareTo((String) b.get("fromDataset"));
            if (c != 0) return c;
            return ((String) a.get("fromColumn")).compareTo((String) b.get("fromColumn"));
        });

        try {
            EventLog.current().emit(Event.builder(EventType.LINK_SCHEMA_INSPECTED).source("inv")
                    .message("link.schema.inspected — " + relationships.size() + " relationships over "
                            + columnsByDataset.size() + " datasets")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.schema.inspected").actionCategory("analysis")
                    .attr("datasetsScanned", columnsByDataset.size())
                    .attr("datasetsSkipped", skipped)
                    .attr("relationships", relationships.size()));
        } catch (RuntimeException ignore) {
            // best effort — the audit must never fail the analyst's query
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("relationships", relationships);
        out.put("datasetsScanned", columnsByDataset.size());
        out.put("datasetsSkipped", skipped);
        return out;
    }

    /** {@code <base>_id} → {@code base} (case-insensitive); {@code null} if {@code col} isn't that shape or is bare {@code id}. */
    private static String fkBase(String col) {
        if (col.length() <= 3 || !col.toLowerCase().endsWith("_id")) return null;
        String base = col.substring(0, col.length() - 3);
        return base.isEmpty() ? null : base;
    }

    /** {@code base} matches {@code datasetId} directly, pluralized, or {@code datasetId} singularized (strip trailing 's'). */
    private static boolean matchesDatasetName(String base, String datasetId) {
        String b = base.toLowerCase(), d = datasetId.toLowerCase();
        if (b.equals(d) || b.equals(d + "s")) return true;
        return d.endsWith("s") && b.equals(d.substring(0, d.length() - 1));
    }

    private static boolean containsIgnoreCase(List<String> cols, String target) {
        for (String c : cols) if (c.equalsIgnoreCase(target)) return true;
        return false;
    }

    /** One column's cardinality profile — the left/right operand of a Jaccard pair. */
    private record ColumnStats(String dataset, String column, String relationSql,
                               long rows, long distinct, long nulls) {}

    /**
     * {@code POST /inv/schema/overlap-profile} (LA-15) — the <b>empirical</b> counterpart to
     * {@link #schemaRelationships}, which infers foreign keys from naming alone. This one measures how much
     * two columns' value sets actually overlap, so a real implicit join that naming never reveals surfaces,
     * and a name match whose values never meet scores ~0 and can be discounted.
     *
     * <p><b>Body</b> {@code {datasets?: string[], columns?: string[], limit?: number}} — deliberately the
     * smallest body that does the job. The only input the computation needs is <em>which columns are in
     * scope</em>: absent {@code datasets} profiles every registered Dataset, absent {@code columns} profiles
     * every column of each, and the two lists together are how an analyst narrows a large registry to the
     * candidate keys they care about. {@code limit} caps the measured column PAIRS — the one axis that grows
     * quadratically. Nothing is per-pair-configurable on purpose: a profile the caller has to parameterise
     * per pair is just {@code /inv/projection} with extra steps.
     *
     * <p><b>Response</b>, in {@link #schemaRelationships}' envelope style:
     * {@code {columns:[{dataset,column,rows,distinct,nulls}],
     * pairs:[{fromDataset,fromColumn,toDataset,toColumn,distinctFrom,distinctTo,intersection,jaccard}],
     * datasetsScanned, datasetsSkipped, pairsConsidered, truncated}} — strongest overlap first.
     *
     * <p><b>How the intersection is estimated.</b> {@code APPROX_COUNT_DISTINCT} is a cardinality function,
     * not a set, so the intersection comes from inclusion–exclusion over one extra aggregate:
     * {@code |A ∩ B| = |A| + |B| − |A ∪ B|}, the union measured by counting distinct values over
     * {@code A UNION ALL B}. That is <b>one query per pair</b> plus one per Dataset for the per-column
     * stats — never a cross join, and no values are transferred to the JVM. Both sides are cast to VARCHAR
     * so an INTEGER key still meets its VARCHAR twin, and so the per-column counts and the union count are
     * measured over the same domain. The estimate is clamped to {@code [0, min(|A|,|B|)]}: approximation
     * error on either side can otherwise push it outside the range a set size can occupy.
     *
     * <p><b>Bounded.</b> Only <em>cross-Dataset</em> pairs are measured (a column against another column of
     * its own Dataset is not an implicit foreign key), in a deterministic order, capped at {@code limit}
     * (default {@value #DEFAULT_PAIRS}, max {@value #MAX_PAIRS}) with {@code pairsConsidered} reporting the
     * TRUE total and {@code truncated} saying the cap bit.
     *
     * <p><b>Fail closed.</b> Every caller-supplied column name is a validated identifier AND must be a real
     * column of a profiled relation — an unknown one is a 422 naming it, so it never reaches SQL. Dataset
     * ids are not caller-supplied identifiers: a {@code datasets} entry selects a registered Dataset by
     * name (unknown → 404) and only the registry's own name is quoted into SQL, as
     * {@link #schemaRelationships} already does. A Dataset that cannot be probed is skipped and counted,
     * exactly as {@link #schemaRelationships} does — never a 500.
     */
    private Object overlapProfile(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "column overlap profiling");
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        ViewStore views = new ViewStore(writeRoot.resolve("views"));
        List<String> wantDatasets = nameList(body, "datasets", false);
        List<String> wantColumns = nameList(body, "columns", true);
        int maxPairs = body.get("limit") instanceof Number n
                ? Math.max(1, Math.min(MAX_PAIRS, n.intValue())) : DEFAULT_PAIRS;

        List<ColumnStats> columns = new ArrayList<>();
        List<String> seenColumns = new ArrayList<>();
        List<String> seenDatasets = new ArrayList<>();
        int scanned = 0, skipped = 0;
        for (ComponentRegistry.Component c : store.list("dataset")) {
            if (!wantDatasets.isEmpty() && !containsIgnoreCase(wantDatasets, c.name())) continue;
            seenDatasets.add(c.name());
            try {
                String relationSql = DatasetRelation.relationSql(c.content(), api.dataRoot(), views);
                List<String> inScope = new ArrayList<>();
                for (String col : relationColumns(c.name(), relationSql)) {
                    seenColumns.add(col);
                    if (wantColumns.isEmpty() || containsIgnoreCase(wantColumns, col)) inScope.add(col);
                }
                if (!inScope.isEmpty()) columns.addAll(columnStats(c.name(), relationSql, inScope));
                scanned++;
            } catch (Exception unusable) {
                skipped++;   // unbound dataset, bad view, etc. — degrade, don't fail the call
            }
        }
        for (String want : wantDatasets)
            if (!containsIgnoreCase(seenDatasets, want)) throw new ApiException(404, "no dataset '" + want + "'");
        for (String want : wantColumns)
            if (!containsIgnoreCase(seenColumns, want))
                throw new ApiException(422, "unknown column '" + want + "' — not a column of any profiled dataset");

        List<Map<String, Object>> pairs = new ArrayList<>();
        int considered = 0;
        for (int i = 0; i < columns.size(); i++) {
            for (int j = i + 1; j < columns.size(); j++) {
                ColumnStats a = columns.get(i), b = columns.get(j);
                if (a.dataset().equals(b.dataset())) continue;
                considered++;
                if (pairs.size() >= maxPairs) continue;   // keep counting to report the TRUE total
                try {
                    pairs.add(pair(a, b));
                } catch (Exception unusable) {
                    // a pair that will not measure is dropped, not fatal — same posture as a skipped Dataset
                }
            }
        }
        pairs.sort((x, y) -> {
            int c = Double.compare((Double) y.get("jaccard"), (Double) x.get("jaccard"));
            if (c != 0) return c;
            c = ((String) x.get("fromDataset")).compareTo((String) y.get("fromDataset"));
            return c != 0 ? c : ((String) x.get("fromColumn")).compareTo((String) y.get("fromColumn"));
        });

        List<Map<String, Object>> profiles = new ArrayList<>(columns.size());
        for (ColumnStats s : columns) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("dataset", s.dataset());
            p.put("column", s.column());
            p.put("rows", s.rows());
            p.put("distinct", s.distinct());
            p.put("nulls", s.nulls());
            profiles.add(p);
        }

        try {
            EventLog.current().emit(Event.builder(EventType.LINK_OVERLAP_PROFILED).source("inv")
                    .message("link.overlap.profiled — " + pairs.size() + " pairs over " + profiles.size()
                            + " columns in " + scanned + " datasets")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.overlap.profiled").actionCategory("analysis")
                    .attr("datasetsScanned", scanned)
                    .attr("datasetsSkipped", skipped)
                    .attr("columnsProfiled", profiles.size())
                    .attr("pairsProfiled", pairs.size())
                    .attr("pairsConsidered", considered)
                    .attr("truncated", considered > pairs.size()));
        } catch (RuntimeException ignore) {
            // best effort — the audit must never fail the analyst's query
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", profiles);
        out.put("pairs", pairs);
        out.put("datasetsScanned", scanned);
        out.put("datasetsSkipped", skipped);
        out.put("pairsConsidered", considered);
        out.put("truncated", considered > pairs.size());
        return out;
    }

    /**
     * One aggregate query per Dataset covering every in-scope column: row count, approximate distinct
     * cardinality and NULL count. The distinct count is taken over {@code CAST(col AS VARCHAR)} so it is
     * measured in the same domain as the union count in {@link #pair} — comparing a raw-typed cardinality
     * against a VARCHAR union would make inclusion–exclusion meaningless across mismatched column types.
     */
    private static List<ColumnStats> columnStats(String dataset, String relationSql, List<String> cols)
            throws SQLException, IOException {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) AS row_count");
        for (int i = 0; i < cols.size(); i++) {
            String c = q(cols.get(i));
            sql.append(", APPROX_COUNT_DISTINCT(CAST(").append(c).append(" AS VARCHAR)) AS d_").append(i)
               .append(", COUNT(").append(c).append(") AS n_").append(i);
        }
        sql.append(" FROM ").append(q(dataset));
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                dataset, relationSql, sql.toString(), 1, 0, List.of(), List.of()));
        Map<String, Object> row = r.rows().get(0);
        long rows = num(row.get("row_count"));
        List<ColumnStats> out = new ArrayList<>(cols.size());
        for (int i = 0; i < cols.size(); i++) {
            long nonNull = num(row.get("n_" + i));
            out.add(new ColumnStats(dataset, cols.get(i), relationSql,
                    rows, num(row.get("d_" + i)), rows - nonNull));
        }
        return out;
    }

    /**
     * The Jaccard estimate for one cross-Dataset column pair: one query for {@code |A ∪ B|} over
     * {@code A UNION ALL B}, then {@code |A ∩ B| = |A| + |B| − |A ∪ B|} clamped into the range a set size
     * can actually occupy. NULLs are excluded on both sides — a NULL is not a value two columns can share.
     *
     * <p>⛔ The two-relation union is passed as the <b>relation</b>, not folded into the query text.
     * {@code QueryExecutor} registers the relation before it seals the sandbox, and that registration is the
     * only place file-reading SQL may run — a Dataset backed by Parquet would be refused outright if its
     * relation reached the sealed statement instead.
     */
    private static Map<String, Object> pair(ColumnStats a, ColumnStats b) throws SQLException, IOException {
        String relation = valueSelect(a) + " UNION ALL " + valueSelect(b);
        QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                PAIR_RELATION, relation,
                "SELECT APPROX_COUNT_DISTINCT(v) AS union_distinct FROM " + q(PAIR_RELATION),
                1, 0, List.of(), List.of()));
        long union = num(r.rows().get(0).get("union_distinct"));
        long intersection = Math.max(0, Math.min(Math.min(a.distinct(), b.distinct()),
                a.distinct() + b.distinct() - union));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fromDataset", a.dataset());
        out.put("fromColumn", a.column());
        out.put("toDataset", b.dataset());
        out.put("toColumn", b.column());
        out.put("distinctFrom", a.distinct());
        out.put("distinctTo", b.distinct());
        out.put("intersection", intersection);
        out.put("jaccard", union == 0 ? 0.0 : (double) intersection / union);
        return out;
    }

    /** One side of the union: the column's non-NULL values as VARCHAR, over that Dataset's own relation. */
    private static String valueSelect(ColumnStats s) {
        String c = q(s.column());
        return "SELECT CAST(" + c + " AS VARCHAR) AS v FROM (" + s.relationSql() + ") AS __r"
                + " WHERE " + c + " IS NOT NULL";
    }

    private static long num(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * An optional {@code string[]} body field. {@code identifiers} marks a list whose entries become SQL
     * identifiers on the caller's say-so ({@code columns}) and are therefore SAFE_IDENT-validated; a
     * {@code datasets} entry only selects from the registry, so it is taken verbatim and matched there.
     */
    private static List<String> nameList(Map<String, Object> body, String key, boolean identifiers) {
        Object raw = body.get(key);
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            String v = String.valueOf(o);
            if (identifiers && !SAFE_IDENT.matcher(v).matches())
                throw new ApiException(422, "unsafe column identifier '" + v + "' for " + key);
            out.add(v);
        }
        return out;
    }

    /**
     * {@code POST /inv/projection/neighbors} — body adds a required {@code value}: the one-hop
     * neighborhood of that entity value (rows where it appears as either endpoint), for Link Analysis
     * Studio's incremental "expand node" action (Phase E). Same shape/gates as {@link #project}, just
     * pre-filtered server-side instead of returning the whole relation.
     */
    private Object neighbors(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        String value = ApiContext.str(body, "value");
        if (value == null) throw new ApiException(422, "body must include 'value'");
        return project(api, ex, body, value);
    }

    /** LA-08 bound on the query count: one DuckDB query per mapping. */
    private static final int MAX_MAPPINGS = 16;
    /** LA-08: the tag on every node and edge naming the Dataset that produced it (contract §5.2). */
    private static final String PROVENANCE = "__provenance_dataset";

    /** One validated LA-08 mapping, resolved and rendered before any query runs. */
    private record Mapping(boolean node, String dataset, String relationSql, String sql, String kind,
                           String category, List<String> attrs) {}

    /**
     * {@code POST /inv/projection/multi} (LA-08, contract §5.2) — node mappings and edge projections over
     * several Datasets in one call, returned as one union: {@code {nodes:[{id,label,category,attrs?,
     * __provenance_dataset}], edges:[{source,target,kind,count,attrs?,__provenance_dataset}],
     * mappings:[{dataset,role,rows,truncated}], truncated}}.
     *
     * <p><b>Values stay raw</b> (operator decision D-S4, 2026-09-23: value-projected, the SPA normalises and
     * warns). An entity appearing in two Datasets is two entries here, one per provenance; joining them is
     * id equality on the client. Ids are cast to VARCHAR, so an INTEGER key still meets its VARCHAR twin.
     *
     * <p><b>Fail closed, whole call.</b> Every mapping is resolved through {@link #relationFor} — unknown or
     * not viewable → 404, the same answer as absence — and every identifier and {@code filter} validated
     * BEFORE the first query runs. A caller who cannot view ONE Dataset gets no rows from any of them: a
     * partial union would silently read as the whole graph. The top-level {@code filter} applies to every
     * edge mapping and must name columns each of them has; an edge mapping's own {@code filter} narrows only
     * it. Each built statement also passes {@link SqlGuard} (defence in depth, as {@code BiRoutes} does).
     * {@code limit} applies per mapping; {@code truncated} is true if any mapping hit it.
     */
    private Object projectMulti(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "multi-dataset entity projection");
        List<Map<String, Object>> nodeSpecs = mappingList(body, "nodes");
        List<Map<String, Object>> edgeSpecs = mappingList(body, "edges");
        if (nodeSpecs.isEmpty() && edgeSpecs.isEmpty())
            throw new ApiException(422, "body must include at least one of 'nodes' or 'edges'");
        if (nodeSpecs.size() + edgeSpecs.size() > MAX_MAPPINGS)
            throw new ApiException(422, "at most " + MAX_MAPPINGS + " mappings per call");
        int limit = body.get("limit") instanceof Number n
                ? Math.max(1, Math.min(MAX_LIMIT, n.intValue())) : DEFAULT_LIMIT;

        List<Mapping> plan = new ArrayList<>();
        for (Map<String, Object> m : nodeSpecs) {
            String ds = datasetOf(m, "nodes");
            String relationSql = relationFor(api, ex, writeRoot, ds);
            String idCol = ident(m, "idColumn", true), labelCol = ident(m, "labelColumn", false);
            List<String> attrs = nameList(m, "attributes", true);
            StringBuilder sql = new StringBuilder("SELECT DISTINCT CAST(" + q(idCol) + " AS VARCHAR) AS id, ")
                    .append(labelCol != null ? "CAST(" + q(labelCol) + " AS VARCHAR)" : "NULL").append(" AS label");
            for (int i = 0; i < attrs.size(); i++)
                sql.append(", CAST(").append(q(attrs.get(i))).append(" AS VARCHAR) AS attr_").append(i);
            sql.append(" FROM ").append(q(ds)).append(" WHERE ").append(q(idCol)).append(" IS NOT NULL ORDER BY 1, 2");
            plan.add(new Mapping(true, ds, relationSql, guarded(sql.toString(), ds), null,
                    ApiContext.str(m, "category"), attrs));
        }
        for (Map<String, Object> m : edgeSpecs) {
            String ds = datasetOf(m, "edges");
            String relationSql = relationFor(api, ex, writeRoot, ds);
            String srcCol = ident(m, "sourceColumn", true), tgtCol = ident(m, "targetColumn", true);
            List<String> attrs = nameList(m, "attributes", true);
            String filterSql = "(" + filterSql(body.get("filter"), ds, relationSql) + ") AND ("
                    + filterSql(m.get("filter"), ds, relationSql) + ")";
            String sql = edgeSql(ds, srcCol, tgtCol, null, attrs, "", filterSql);
            plan.add(new Mapping(false, ds, relationSql, guarded(sql, ds), ApiContext.str(m, "type"), null, attrs));
        }

        List<Map<String, Object>> nodes = new ArrayList<>(), edges = new ArrayList<>(), mappings = new ArrayList<>();
        boolean truncated = false;
        for (Mapping mp : plan) {
            QueryExecutor.Result r;
            try {
                r = QueryExecutor.run(new QueryExecutor.Request(mp.dataset(), mp.relationSql(), mp.sql(),
                        limit, 0, List.of(), List.of()));
            } catch (SQLException e) {
                throw new ApiException(422, "projection of dataset '" + mp.dataset() + "' failed: " + e.getMessage());
            }
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> out;
                if (mp.node()) {
                    out = new LinkedHashMap<>();
                    out.put("id", row.get("id"));
                    out.put("label", row.get("label"));
                    out.put("category", mp.category());
                    if (!mp.attrs().isEmpty()) {
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        for (int i = 0; i < mp.attrs().size(); i++) attrs.put(mp.attrs().get(i), row.get("attr_" + i));
                        out.put("attrs", attrs);
                    }
                } else {
                    out = edgeRow(row, mp.kind(), mp.attrs());
                }
                out.put(PROVENANCE, mp.dataset());
                (mp.node() ? nodes : edges).add(out);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("dataset", mp.dataset());
            summary.put("role", mp.node() ? "node" : "edge");
            summary.put("rows", r.rows().size());
            summary.put("truncated", r.truncated());
            mappings.add(summary);
            truncated |= r.truncated();
            audit(ex, mp.dataset(), null, r.rows().size(), r.truncated());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("edges", edges);
        out.put("mappings", mappings);
        out.put("truncated", truncated);
        return out;
    }

    /** An optional list of mapping objects; any non-object entry is a 422, never silently skipped. */
    private static List<Map<String, Object>> mappingList(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw new ApiException(422, "'" + key + "' must be a list");
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new ApiException(422, "every '" + key + "' entry must be an object");
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            out.add(copy);
        }
        return out;
    }

    private static String datasetOf(Map<String, Object> mapping, String key) {
        String ds = ApiContext.str(mapping, "dataset");
        if (ds == null) throw new ApiException(422, "every '" + key + "' entry must include 'dataset'");
        return ds;
    }

    /** Defence in depth: a server-built statement still passes the caller-SQL guard, trusting only its own relation. */
    private static String guarded(String sql, String datasetId) {
        if (!SqlGuard.check(sql, datasetId).isEmpty())
            throw new ApiException(422, "projection of dataset '" + datasetId + "' failed the SQL safety check");
        return sql;
    }

    private Object project(ApiContext api, HttpExchange ex, Map<String, Object> body, String neighborsOf) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity projection");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        List<String> attrCols = attrCols(body);
        int limit = body.get("limit") instanceof Number n
                ? Math.max(1, Math.min(MAX_LIMIT, n.intValue())) : DEFAULT_LIMIT;

        String relationSql = relationFor(api, ex, writeRoot, datasetId);

        // LA-01: the optional condition tree is validated against the relation's REAL columns and
        // rendered BEFORE a single character of the statement is assembled below — an identifier the
        // relation does not have cannot reach SQL, because the render never happens.
        String filterSql = filterSql(body.get("filter"), datasetId, relationSql);

        // The value is bound, not interpolated: `Request` gained `binds` and `QueryExecutor` a
        // PreparedStatement branch, which retired this route's hand-rolled quote-doubling. Two `?` in
        // source order — binds are positional, and `wrap()` adds none of its own. Column identifiers are
        // still built from validated identifiers; JDBC cannot bind those.
        String src = q(sourceCol), tgt = q(targetCol);
        String neighborFilter = neighborsOf != null
                ? " AND (CAST(" + src + " AS VARCHAR) = ? OR CAST(" + tgt + " AS VARCHAR) = ?)" : "";
        List<String> binds = neighborsOf != null ? List.of(neighborsOf, neighborsOf) : List.of();
        // Server-built from validated identifiers only; one extra row detects truncation.
        String sql = edgeSql(datasetId, sourceCol, targetCol, kindCol, attrCols, neighborFilter, filterSql);

        try {
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    datasetId, relationSql, sql, limit, 0, List.of(), List.of(), binds));
            List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
            for (Map<String, Object> row : r.rows())
                rows.add(edgeRow(row, kindCol != null ? row.get("kind") : null, attrCols));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("rows", rows);
            out.put("truncated", r.truncated());
            audit(ex, datasetId, neighborsOf, rows.size(), r.truncated());
            return out;
        } catch (SQLException e) {
            throw new ApiException(422, "projection failed: " + e.getMessage());
        }
    }

    /**
     * Resolve a Dataset id to its trusted relation SQL — the gate order every projection shares: unknown →
     * 404; not viewable by this request's subject → the SAME 404 (R3: shared-away is indistinguishable from
     * absence, exactly as {@code BiRoutes} answers); an unusable Dataset → 422.
     */
    private static String relationFor(ApiContext api, HttpExchange ex, Path writeRoot, String datasetId) {
        Map<String, Object> dataset = new ComponentStore(writeRoot.resolve("registry")).get("dataset", datasetId)
                .map(ComponentRegistry.Component::content)
                .orElseThrow(() -> new ApiException(404, "no dataset '" + datasetId + "'"));
        if (!ComponentAccess.canView(ex, dataset))
            throw new ApiException(404, "no dataset '" + datasetId + "'");
        try {
            return DatasetRelation.relationSql(dataset, api.dataRoot(), new ViewStore(writeRoot.resolve("views")));
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, bad.getMessage());
        }
    }

    /**
     * The folded-edge statement: distinct {@code (source, target[, kind][, attr_i...])} with a row count,
     * heaviest first, NULL endpoints excluded. Built from validated identifiers only; {@code extraWhere} is
     * server-authored (a bound-parameter clause or empty) and {@code filterSql} is the LA-01 render.
     */
    private static String edgeSql(String datasetId, String sourceCol, String targetCol, String kindCol,
                                  List<String> attrCols, String extraWhere, String filterSql) {
        String src = q(sourceCol), tgt = q(targetCol);
        String kindSel = kindCol != null ? ", CAST(" + q(kindCol) + " AS VARCHAR) AS kind" : "";
        StringBuilder attrSel = new StringBuilder();
        for (int i = 0; i < attrCols.size(); i++) {
            attrSel.append(", CAST(").append(q(attrCols.get(i))).append(" AS VARCHAR) AS attr_").append(i);
        }
        StringBuilder groupBy = new StringBuilder("GROUP BY 1, 2");
        int nextGroupIdx = 3;
        if (kindCol != null) groupBy.append(", ").append(nextGroupIdx++);
        for (int i = 0; i < attrCols.size(); i++) groupBy.append(", ").append(nextGroupIdx++);
        return "SELECT CAST(" + src + " AS VARCHAR) AS source, CAST(" + tgt + " AS VARCHAR) AS target"
                + kindSel + attrSel + ", COUNT(*) AS cnt FROM " + q(datasetId)
                + " WHERE " + src + " IS NOT NULL AND " + tgt + " IS NOT NULL" + extraWhere
                + " AND (" + filterSql + ")"
                + " " + groupBy
                + " ORDER BY cnt DESC, source, target";
    }

    /** One {@link #edgeSql} result row in the response shape {@code {source,target,kind,count,attrs?}}. */
    private static Map<String, Object> edgeRow(Map<String, Object> row, Object kind, List<String> attrCols) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", row.get("source"));
        out.put("target", row.get("target"));
        out.put("kind", kind);
        out.put("count", row.get("cnt"));
        if (!attrCols.isEmpty()) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            for (int i = 0; i < attrCols.size(); i++) attrs.put(attrCols.get(i), row.get("attr_" + i));
            out.put("attrs", attrs);
        }
        return out;
    }

    /**
     * Best-effort audit of one analytic act (LA-04): a projection, or — when {@code neighborsOf} is set —
     * an expansion, which is a different act and gets its own type. Emitted only after the rows are built,
     * so {@code rows}/{@code truncated} describe what the analyst actually saw; a partial result the trail
     * cannot show as partial is worth nothing to an investigator.
     */
    private static void audit(HttpExchange ex, String datasetId, String neighborsOf, int rows, boolean truncated) {
        try {
            boolean expand = neighborsOf != null;
            String action = expand ? "link.expanded" : "link.projected";
            Event.Builder b = Event.builder(expand ? EventType.LINK_EXPANDED : EventType.LINK_PROJECTED)
                    .source("inv")
                    .message(action + " " + datasetId + " — " + rows + " rows" + (truncated ? " (truncated)" : ""))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis")
                    .target("dataset", datasetId)
                    .attr("dataset", datasetId).attr("rows", rows).attr("truncated", truncated);
            if (expand) b.attr("value", neighborsOf);
            EventLog.current().emit(b);
        } catch (RuntimeException ignore) {
            // best effort — the audit must never fail the analyst's query
        }
    }

    /**
     * Optional {@code filter} (LA-01, contract §5.1): the {@code query-types.ts} condition tree the SPA
     * already builds, sent verbatim, pushed into the {@code WHERE} <b>ahead of the {@code GROUP BY}</b> so
     * {@code count} folds over the surviving rows rather than being filtered after the fold.
     *
     * <p><b>D-S5(a) — field validation is the safeguard.</b> The tree is rendered by the shared
     * {@link ConditionSql} (which quote-escapes every literal, the tested contract for authored config),
     * and every leaf {@code field} is first checked against the relation's <em>actual</em> columns: an
     * unknown identifier is a 422 naming the field, and the renderer is never reached. A bind-emitting
     * renderer is a deliberate follow-on, not this item.
     *
     * <p>Absent tree, or one that constrains nothing (empty group, only incomplete leaves) → {@code TRUE},
     * a no-op — parity with {@code ConditionSql}/{@code ConditionTree}'s "an empty group matches every row".
     */
    private static String filterSql(Object filter, String datasetId, String relationSql) {
        if (filter == null) return "TRUE";
        List<String> columns = relationColumns(datasetId, relationSql);
        checkFilterFields(filter, columns, datasetId);
        return ConditionSql.predicate(filter);
    }

    /** The relation's column names, probed with a zero-row SELECT — the same technique {@link #schemaRelationships} uses. */
    private static List<String> relationColumns(String datasetId, String relationSql) {
        try {
            QueryExecutor.Result r = QueryExecutor.run(new QueryExecutor.Request(
                    datasetId, relationSql, "SELECT * FROM " + q(datasetId), 0, 0, List.of(), List.of()));
            return r.columns().stream().map(ResultSetDescriptor.Column::name).toList();
        } catch (Exception unusable) {
            throw new ApiException(422, "cannot read the columns of dataset '" + datasetId
                    + "' to validate 'filter': " + unusable.getMessage());
        }
    }

    /**
     * Walk the tree exactly as {@link ConditionSql} does (a node is a group when {@code kind=group} or it
     * carries {@code items}/{@code conditions}) and reject any leaf naming a column the relation has not
     * got. A blank {@code field} is left alone: the renderer treats such a leaf as incomplete and emits
     * nothing for it, so there is no identifier to protect.
     */
    private static void checkFilterFields(Object node, List<String> columns, String datasetId) {
        if (!(node instanceof Map<?, ?> m)) return;
        Object rawItems = m.get("items") != null ? m.get("items") : m.get("conditions");
        if ("group".equals(m.get("kind")) || (!"condition".equals(m.get("kind")) && rawItems != null)) {
            if (rawItems instanceof List<?> items)
                for (Object it : items) checkFilterFields(it, columns, datasetId);
            return;
        }
        Object raw = m.get("field");
        String field = raw == null ? "" : String.valueOf(raw);
        if (field.isEmpty()) return;
        if (!containsIgnoreCase(columns, field))
            throw new ApiException(422, "unknown filter field '" + field + "' — not a column of dataset '"
                    + datasetId + "'");
    }

    /** Optional {@code attrCols: string[]} — each validated as a safe identifier. */
    private static List<String> attrCols(Map<String, Object> body) {
        Object raw = body.get("attrCols");
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            String v = String.valueOf(o);
            if (!SAFE_IDENT.matcher(v).matches())
                throw new ApiException(422, "unsafe column identifier '" + v + "' for attrCols");
            out.add(v);
        }
        return out;
    }

    private static String ident(Map<String, Object> body, String key, boolean required) {
        String v = ApiContext.str(body, key);
        if (v == null) {
            if (required) throw new ApiException(422, "body must include '" + key + "'");
            return null;
        }
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }

    private static String q(String ident) {
        return SqlIdent.q(ident);
    }
}
