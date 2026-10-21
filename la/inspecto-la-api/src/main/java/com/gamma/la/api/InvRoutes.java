package com.gamma.la.api;

import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.SnapshotStore;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.access.ComponentAccess;
import com.gamma.entitystore.EntityTypes;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.spi.http.RouteModule;
import com.gamma.access.WriteGates;

import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.sql.SqlGuard;
import com.gamma.sql.SqlSandboxPolicy;
import com.gamma.util.JsonAttributes;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.gamma.util.SqlIdent.q;

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
 * <p>{@code POST /inv/projection/neighbors/preview} (LA-GROW-EXCLUDE-1) — the explorer's Grow preview: the counts of one
 * neighbours step from a set of entities, read-only. See {@link #neighboursPreview}.
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
    // LA-CONFIG-LIMITS-1 P3: the read caps below are the Space's limits in force (SpaceLimits): traversal.rows_* (projection and
    // neighbour rows), traversal.pairs_* (LA-15: one DuckDB query per measured pair, so a query-count cap), traversal.depth_*,
    // traversal.edge_yield_*, traversal.paths_*, traversal.timeout_s (LA-11 fences, plan §3.3: the edge yield bounds each
    // recursion LEVEL; the timeout is per statement), snapshots.page_*, neighbours.page_* and input.projection_mappings.
    /** The synthetic relation name a pair's {@code A UNION ALL B} is registered under (LA-15). */
    private static final String PAIR_RELATION = "__overlap_pair";

    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.post("/inv/projection", (e, m) -> project(api, e, api.body(e), null));
        api.post("/inv/projection/neighbors", (e, m) -> neighbors(api, e, api.body(e)));
        api.post("/inv/projection/neighbors/preview", (e, m) -> neighboursPreview(api, e, api.body(e)));   // LA-GROW-EXCLUDE-1: read-only
        api.post("/inv/projection/multi", (e, m) -> projectMulti(api, e, api.body(e)));
        api.get("/inv/schema/relationships", (e, m) -> schemaRelationships(api, e));
        api.post("/inv/schema/overlap-profile", (e, m) -> overlapProfile(api, e, api.body(e)));
        api.post("/inv/traversal/recursive-paths", (e, m) -> recursivePaths(api, e, api.body(e)));
        api.post("/inv/reference-rows", (e, m) -> ReferenceRows.handle(api, e, api.body(e)));
        // POST /inv/case-history is CaseHistoryRoutes' now: an edition gate is per RouteModule, and Case history is Standard and up (D-LAE1).
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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "id must match " + SnapshotStore.SAFE_ID.pattern() + ", got '" + id + "'");
        if (str(body.get("manifestHash")) == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "manifestHash is required — an unfingerprinted snapshot cannot be verified");

        String json = JsonAttributes.toPayloadJson(body);
        if (!body.isEmpty() && "{}".equals(json))
            throw new ApiException(500, ErrorCodes.INTERNAL, "snapshot could not be serialised — refusing to seal an empty record");

        SnapshotStore store = new SnapshotStore(writeRoot);
        Path target = store.directory().resolve(id + ".json").normalize();
        if (!target.startsWith(store.directory().normalize()))
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "snapshot id escapes the snapshot directory");

        if (!store.create(id, json))
            throw new ApiException(409, ErrorCodes.CONFLICT, "snapshot '" + id + "' already exists — a sealed snapshot is never replaced");

        emitSnapshotEvent(ex, LinkEventTypes.LINK_SNAPSHOT_SEALED, "link.snapshot.sealed",
                "link.snapshot.sealed — " + sizeOf(body.get("nodes")) + " nodes, "
                        + sizeOf(body.get("edges")) + " edges",
                b -> b.attr("snapshotId", id).attr("nodes", sizeOf(body.get("nodes")))
                        .attr("edges", sizeOf(body.get("edges"))));
        return Map.of("id", id, "sealed", true);
    }

    /**
     * {@code GET /inv/snapshots?limit=n} — sealed snapshot ids, newest first. Bounded like every other
     * diagnostic read here: {@code limit} defaults to the Space's {@code snapshots.page_default}, clamps to
     * {@code snapshots.page_max}, and the TRUE total ships alongside so a bounded read never reads as a
     * complete one.
     */
    private Object listSnapshots(ApiContext api, HttpExchange ex) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "link analysis snapshot list");
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int limit = SpaceLimits.get(lim, LaLimit.SNAPSHOTS_PAGE_DEFAULT);
        String raw = ApiContext.query(ex, "limit");
        if (raw != null && !raw.isBlank()) {
            try {
                limit = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "limit must be an integer, got '" + raw + "'");
            }
        }
        limit = Math.min(Math.max(limit, 1), SpaceLimits.get(lim, LaLimit.SNAPSHOTS_PAGE_MAX));
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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "snapshotId must match " + SnapshotStore.SAFE_ID.pattern());
        if (caseId == null || caseId.isBlank()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "caseId is required");

        SnapshotStore store = new SnapshotStore(writeRoot);
        if (store.read(snapshotId) == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no sealed snapshot '" + snapshotId + "'");
        store.attach(snapshotId, caseId, java.time.Instant.now().toString());
        emitSnapshotEvent(ex, LinkEventTypes.LINK_SNAPSHOT_ATTACHED, "link.snapshot.attached",
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
            EventSink.current().emit(attrs.apply(b));
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

        Map<String, List<String>> columnsByDataset = new LinkedHashMap<>();
        int skipped = 0;
        for (DatasetProvider.Entry c : DatasetProviders.require().datasets(writeRoot)) {
            if (!ComponentAccess.canView(ex, c.content())) continue;   // R3: not counted as skipped — that would leak it
            try {
                String relationSql = DatasetProviders.require().relationSql(c.content(), api.dataRoot(), writeRoot);
                DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                        c.name(), relationSql, "SELECT * FROM " + q(c.name()), 0, 0, List.of(), List.of()));
                columnsByDataset.put(c.name(),
                        r.columns().stream().map(DatasetProvider.Column::name).toList());
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
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_SCHEMA_INSPECTED).source("inv")
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

    static boolean containsIgnoreCase(List<String> cols, String target) {
        for (String c : cols) if (c.equalsIgnoreCase(target)) return true;
        return false;
    }

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
     * (default the Space's {@code traversal.pairs_default}, max {@code traversal.pairs_max}) with {@code pairsConsidered} reporting the
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
        List<String> wantDatasets = nameList(body, "datasets", false);
        List<String> wantColumns = nameList(body, "columns", true);
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int maxPairs = clamp(body.get("limit"), SpaceLimits.get(lim, LaLimit.TRAVERSAL_PAIRS_DEFAULT), SpaceLimits.get(lim, LaLimit.TRAVERSAL_PAIRS_MAX));

        List<ColumnProfile.Stats> columns = new ArrayList<>();
        List<String> seenColumns = new ArrayList<>();
        List<String> seenDatasets = new ArrayList<>();
        int scanned = 0, skipped = 0;
        for (DatasetProvider.Entry c : DatasetProviders.require().datasets(writeRoot)) {
            if (!wantDatasets.isEmpty() && !containsIgnoreCase(wantDatasets, c.name())) continue;
            if (!ComponentAccess.canView(ex, c.content())) continue;   // R3: shared-away reads as absent (404 below)
            seenDatasets.add(c.name());
            try {
                String relationSql = DatasetProviders.require().relationSql(c.content(), api.dataRoot(), writeRoot);
                List<String> inScope = new ArrayList<>();
                for (String col : relationColumns(c.name(), relationSql)) {
                    seenColumns.add(col);
                    if (wantColumns.isEmpty() || containsIgnoreCase(wantColumns, col)) inScope.add(col);
                }
                if (!inScope.isEmpty()) columns.addAll(ColumnProfile.stats(c.name(), relationSql, inScope, ColumnProfile.AS_VARCHAR));
                scanned++;
            } catch (Exception unusable) {
                skipped++;   // unbound dataset, bad view, etc. — degrade, don't fail the call
            }
        }
        for (String want : wantDatasets)
            if (!containsIgnoreCase(seenDatasets, want)) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + want + "'");
        for (String want : wantColumns)
            if (!containsIgnoreCase(seenColumns, want))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown column '" + want + "' — not a column of any profiled dataset");

        List<Map<String, Object>> pairs = new ArrayList<>();
        int considered = 0;
        for (int i = 0; i < columns.size(); i++) {
            for (int j = i + 1; j < columns.size(); j++) {
                ColumnProfile.Stats a = columns.get(i), b = columns.get(j);
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
        for (ColumnProfile.Stats s : columns) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("dataset", s.dataset());
            p.put("column", s.column());
            p.put("rows", s.rows());
            p.put("distinct", s.distinct());
            p.put("nulls", s.nulls());
            profiles.add(p);
        }

        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_OVERLAP_PROFILED).source("inv")
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
     * The Jaccard estimate for one cross-Dataset column pair: one query for {@code |A ∪ B|} over
     * {@code A UNION ALL B}, then {@code |A ∩ B| = |A| + |B| − |A ∪ B|} clamped into the range a set size
     * can actually occupy. NULLs are excluded on both sides — a NULL is not a value two columns can share.
     *
     * <p>⛔ The two-relation union is passed as the <b>relation</b>, not folded into the query text.
     * {@code QueryExecutor} registers the relation before it seals the sandbox, and that registration is the
     * only place file-reading SQL may run — a Dataset backed by Parquet would be refused outright if its
     * relation reached the sealed statement instead.
     */
    private static Map<String, Object> pair(ColumnProfile.Stats a, ColumnProfile.Stats b) throws SQLException, IOException {
        String relation = valueSelect(a) + " UNION ALL " + valueSelect(b);
        DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
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
    private static String valueSelect(ColumnProfile.Stats s) {
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
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unsafe column identifier '" + v + "' for " + key);
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
        if (value == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'value'");
        if (Boolean.TRUE.equals(body.get("list"))) return neighbourList(api, ex, body, value);
        return project(api, ex, body, value);
    }

    /**
     * The {@code list} form of {@code POST /inv/projection/neighbors}: ONE entity's neighbours folded per neighbour (events,
     * link kinds), strongest first, with the REAL total of distinct neighbours and a window ({@code offset}, {@code limit}).
     * It is a read-only hint for choosing what an expand reads, so it is never held by the four-eyes FAN-OUT threshold (the
     * row-budget bound still applies to the page size): a hub's list must be readable, only the expand is held. Ids leave as
     * aliases when the Space masks them; the flat read is used (the index serves no listing).
     */
    private Object neighbourList(ApiContext api, HttpExchange ex, Map<String, Object> body, String value) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity projection");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        LaLimitValues lim = SpaceLimits.of(writeRoot);   // LA-EXPAND-PICK-NEIGHBOURS-1: neighbours per page (default / ceiling)
        int limit = clamp(body.get("limit"), SpaceLimits.get(lim, LaLimit.NEIGHBOURS_PAGE_DEFAULT), SpaceLimits.get(lim, LaLimit.NEIGHBOURS_PAGE_MAX));
        int offset = body.get("offset") instanceof Number n ? Math.max(0, n.intValue()) : 0;
        String relationSql = relationFor(api, ex, writeRoot, datasetId);
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId, Map.of("sourceCol", sourceCol, "targetCol", targetCol));
        String of = mask.in(value);
        DatasetProvider.BoundFilter filter = filterSql(body.get("filter"), datasetId, relationSql);
        refuseIfSensitive(writeRoot, "a neighbour list (limit " + limit + ")", limit, 0);
        String edges = edgeSql(datasetId, sourceCol, targetCol, kindCol, List.of(),
                " AND (CAST(" + q(sourceCol) + " AS VARCHAR) = ? OR CAST(" + q(targetCol) + " AS VARCHAR) = ?)", filter);
        String kinds = kindCol != null ? ", STRING_AGG(DISTINCT kind, ',' ORDER BY kind) AS kinds" : "";
        String sql = "SELECT other, SUM(cnt) AS cnt" + kinds + ", COUNT(*) OVER () AS total FROM ("
                + "SELECT CASE WHEN source = ? THEN target ELSE source END AS other" + (kindCol != null ? ", kind" : "")
                + ", cnt FROM (" + edges.replaceFirst(" ORDER BY cnt DESC, source, target$", "") + ") e WHERE NOT (source = ? AND target = ?)"
                + ") GROUP BY other ORDER BY cnt DESC, other";
        // Bind order is text order: the CASE, the edge read's neighbour pair, its filter, then the self-link exclusion.
        List<String> binds = new ArrayList<>(List.of(of, of, of));
        binds.addAll(filter.binds());
        binds.addAll(List.of(of, of));
        try {
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                    datasetId, relationSql, sql, limit, offset, List.of(), List.of(), binds));
            List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
            long total = 0;
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("id", mask.active() ? mask.out(row.get("other")) : row.get("other"));
                o.put("count", row.get("cnt"));
                Object k = row.get("kinds");
                o.put("kinds", k == null ? List.of() : List.of(String.valueOf(k).split(",")));
                rows.add(o);
                if (row.get("total") instanceof Number t) total = t.longValue();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("masking", mask.describe());
            out.put("neighbours", rows);
            // A page past the end has no row to carry the total: say 0 rather than guess (the caller keeps its first page's).
            out.put("total", total);
            out.put("offset", offset);
            out.put("limit", limit);
            out.put("hasMore", r.truncated());
            audit(ex, datasetId, of, rows.size(), r.truncated(), null);
            return out;
        } catch (SQLException e) {
            throw DatasetReadFault.of("neighbour list failed", datasetId, writeRoot, e);
        }
    }

    /** A neighbours step's traversal directions - the Investigation {@code expand}'s. */
    private static final List<String> DIRECTIONS = List.of("either", "out", "in", "reciprocal");

    /**
     * {@code POST /inv/projection/neighbors/preview} (LA-GROW-EXCLUDE-1 GE-3 / GE-10) — the Basic explorer's Grow preview:
     * what one neighbours step from a SET of entities would add, with no Investigation. Body {@code {dataset, sourceCol,
     * targetCol, linkKindCol?, ids[] (or the neighbours read's one-entity {@code value}), direction? (either · out · in · reciprocal, default either), linkKinds?,
     * hubThreshold? (default the Space's hub_threshold), excludeBy? [listIds], filter?, limit?}}. Answers
     * {@link GrowPreview}'s shape: {@code addsNodes} the distinct neighbours outside {@code ids} not removed by a list,
     * {@code addsLinks} their distinct {@code (source, target, kind)} links touching {@code ids} in the direction,
     * {@code held} those neighbours with more distinct contacts than the threshold (counted over the whole Dataset, as an
     * expand counts a hub; they are still added), {@code excludedByLists} per named list the neighbours it removes
     * (rounded unless the list is open). Read-only and unrecorded — no event, no state; the flat Dataset answers
     * ({@code readPath: flat}). {@code limit} caps the neighbours read (default / max the Space's traversal rows) and a
     * breach sets {@code truncated}, the counts then being lower bounds. Gates as the neighbours read: write root 503 →
     * a bad body 422 → unknown / not viewable Dataset or list 404 → retired list 409 → four-eyes 403 (D-U7). Ids in and
     * out are the Space's exploration aliases when it masks them (DR-D2).
     */
    private Object neighboursPreview(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity projection");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int maxIds = SpaceLimits.get(lim, LaLimit.INPUT_MAX_IDS), maxIdLength = SpaceLimits.get(lim, LaLimit.INPUT_ID_LENGTH);
        List<String> ids = boundedStrings(body, "ids", maxIds, maxIdLength);
        String value = ApiContext.str(body, "value");   // the neighbours read's one-entity body, accepted as ids: [value]
        if (ids.isEmpty() && value != null) {
            if (value.isBlank() || value.length() > maxIdLength)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'value' must be a non-blank string of at most " + maxIdLength + " chars");
            ids = List.of(value);
        }
        if (ids.isEmpty()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'ids' - the entities to grow from");
        String direction = body.get("direction") == null ? "either" : String.valueOf(body.get("direction"));
        if (!DIRECTIONS.contains(direction))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'direction' must be one of " + DIRECTIONS + ", got '" + direction + "'");
        List<String> kinds = body.get("linkKinds") == null ? null
                : boundedStrings(body, "linkKinds", SpaceLimits.get(lim, LaLimit.INPUT_LINK_KINDS), maxIdLength);
        if (kinds != null && (kinds.isEmpty() || kindCol == null))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'linkKinds' must be a non-empty list and needs 'linkKindCol'");
        Object t = body.get("hubThreshold");
        if (t != null && (!(t instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < 1 || n.longValue() > Integer.MAX_VALUE))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'hubThreshold' must be an integer >= 1, got " + t);
        int hubThreshold = t != null ? ((Number) t).intValue() : LinkAnalysisSettings.forRoot(writeRoot).effectiveHubThreshold();
        List<String> listIds = body.get("excludeBy") == null ? List.of() : boundedStrings(body, "excludeBy", maxIds, maxIdLength);
        int limit = clamp(body.get("limit"), SpaceLimits.get(lim, LaLimit.TRAVERSAL_ROWS_DEFAULT), SpaceLimits.get(lim, LaLimit.TRAVERSAL_ROWS_MAX));

        String relationSql = relationFor(api, ex, writeRoot, datasetId);
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId, Map.of("sourceCol", sourceCol, "targetCol", targetCol));
        java.util.TreeSet<String> frontier = new java.util.TreeSet<>();
        for (String id : ids) frontier.add(mask.in(id));
        DatasetProvider.BoundFilter filter = filterSql(body.get("filter"), datasetId, relationSql);
        Map<String, com.gamma.entitystore.EntityRegistry.EntityList> lists = new LinkedHashMap<>();
        if (!listIds.isEmpty()) {
            var all = GrowPreview.entityLists(writeRoot);
            for (String id : listIds) {
                var l = all.get(id);
                if (l == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "entity list '" + id + "' not found");
                if (l.retired()) throw new ApiException(409, ErrorCodes.CONFLICT, "entity list '" + id + "' is retired");
                // LA-GROW-EXCLUDE-1 GE-8: two previews, with and without the list (or a one-id frontier), would name its
                // members by difference, so a restricted list is refused (403) to a caller who may not see them.
                com.gamma.entitystore.EntityListFacts.requireSeesMembers(ex, l, "preview an exclusion by");
                lists.put(id, l);
            }
        }
        refuseIfSensitive(writeRoot, "a neighbours preview (limit " + limit + ")", limit, 0);

        // Flat and bound: the frontier and kinds are parameters, identifiers validated. pairs = the distinct (source, target, kind)
        // links; nb = each neighbour outside the frontier with its links touching the frontier in the direction; deg = each
        // neighbour's distinct contacts over the whole Dataset (both directions), the number an expand flags a hub on.
        List<String> binds = new ArrayList<>();
        String src = q(sourceCol), tgt = q(targetCol);
        StringBuilder sql = new StringBuilder("WITH fr AS ").append(com.gamma.la.storage.IndexReader.idRelation(frontier, binds))
                .append(", pairs AS (SELECT CAST(").append(src).append(" AS VARCHAR) AS s, CAST(").append(tgt).append(" AS VARCHAR) AS t, ")
                .append(kindCol != null ? "CAST(" + q(kindCol) + " AS VARCHAR)" : "NULL").append(" AS k FROM ").append(q(datasetId))
                .append(" WHERE ").append(src).append(" IS NOT NULL AND ").append(tgt).append(" IS NOT NULL");
        if (kinds != null)
            sql.append(" AND CAST(").append(q(kindCol)).append(" AS VARCHAR) IN ").append(com.gamma.la.storage.IndexReader.idRelation(kinds, binds));
        sql.append(" AND (").append(filter.sql()).append(") GROUP BY 1, 2, 3)");
        binds.addAll(filter.binds());
        String inF = " IN (SELECT id FROM fr)";
        sql.append(", cand AS (SELECT CASE WHEN p.s").append(inF).append(" THEN p.t ELSE p.s END AS other FROM pairs p WHERE ")
           .append(switch (direction) {
               case "out" -> "p.s" + inF;
               case "in" -> "p.t" + inF;
               case "reciprocal" -> "(p.s" + inF + " OR p.t" + inF + ") AND EXISTS (SELECT 1 FROM pairs r WHERE r.s = p.t AND r.t = p.s)";
               default -> "(p.s" + inF + " OR p.t" + inF + ")";
           }).append(")")
           .append(", nb AS (SELECT other, COUNT(*) AS links FROM cand WHERE other NOT").append(inF).append(" GROUP BY other)")
           .append(", deg AS (SELECT id, COUNT(DISTINCT o) AS d FROM (SELECT s AS id, t AS o FROM pairs WHERE s IN (SELECT other FROM nb) "
                   + "UNION ALL SELECT t AS id, s AS o FROM pairs WHERE t IN (SELECT other FROM nb)) u GROUP BY id)")
           .append(" SELECT nb.other AS id, nb.links AS links, deg.d AS degree FROM nb JOIN deg ON deg.id = nb.other ORDER BY nb.other");
        DatasetProvider.Result r;
        try {
            r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql, sql.toString(),
                    limit, 0, List.of(), List.of(), binds));
        } catch (SQLException e) {
            throw DatasetReadFault.of("neighbours preview failed", datasetId, writeRoot, e);
        }
        long addsNodes = 0, addsLinks = 0;
        List<Map<String, Object>> held = new ArrayList<>();
        Map<String, Long> removed = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> members = new LinkedHashMap<>();   // matched as an excludeBy matches: the id's key under the list's normaliser
        java.time.Instant now = java.time.Instant.now();
        for (var l : lists.entrySet()) {
            removed.put(l.getKey(), 0L);
            members.put(l.getKey(), l.getValue().liveMembers(now));
        }
        for (Map<String, Object> row : r.rows()) {
            String id = String.valueOf(row.get("id"));
            boolean dropped = false;
            for (var l : lists.entrySet())   // counted under the FIRST list naming it, as an Investigation's earlier excludeBy wins
                if (members.get(l.getKey()).contains(EntityTypes.normalise(l.getValue().normaliser(), id))) {
                    removed.merge(l.getKey(), 1L, Long::sum);
                    dropped = true;
                    break;
                }
            if (dropped) continue;
            addsNodes++;
            addsLinks += ((Number) row.get("links")).longValue();
            long degree = ((Number) row.get("degree")).longValue();
            if (degree > hubThreshold) held.add(GrowPreview.held(String.valueOf(mask.out(id)), degree));
        }
        GrowPreview.sortHeld(held);
        List<Map<String, Object>> lines = new ArrayList<>();
        removed.forEach((id, n) -> lines.add(GrowPreview.listLine(id, lists.get(id), n, false)));   // a restricted list was refused above
        Map<String, Object> out = GrowPreview.answer(addsNodes, addsLinks, held, lines, r.truncated(), "flat", null);
        out.put("hubThreshold", hubThreshold);
        out.put("masking", mask.describe());
        return out;
    }

    /** An optional list of non-blank strings under {@code key}: at most {@code max} entries of at most {@code maxLength} chars, deduplicated in order. */
    private static List<String> boundedStrings(Map<String, Object> body, String key, int max, int maxLength) {
        Object raw = body.get(key);
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> l) || l.size() > max)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list of at most " + max + " entries");
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Object o : l) {
            String v = o == null ? "" : String.valueOf(o);
            if (v.isBlank() || v.length() > maxLength)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every '" + key + "' entry must be a non-blank string of at most "
                        + maxLength + " chars");
            out.add(v);
        }
        return new ArrayList<>(out);
    }

    /** LA-08: the tag on every node and edge naming the Dataset that produced it (contract §5.2). */
    private static final String PROVENANCE = "__provenance_dataset";

    /** One validated LA-08 mapping, resolved and rendered before any query runs. */
    private record Mapping(boolean node, String dataset, String relationSql, String sql, String kind,
                           String category, List<String> attrs, Map<String, Object> idType,
                           Map<String, Object> sourceType, Map<String, Object> targetType, List<String> binds,
                           ExplorationMasking mask) {}

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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include at least one of 'nodes' or 'edges'");
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int maxMappings = SpaceLimits.get(lim, LaLimit.INPUT_PROJECTION_MAPPINGS);   // LA-08 bound on the query count: one query per mapping
        if (nodeSpecs.size() + edgeSpecs.size() > maxMappings)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + maxMappings + " mappings per call");
        int limit = clamp(body.get("limit"), SpaceLimits.get(lim, LaLimit.TRAVERSAL_ROWS_DEFAULT), SpaceLimits.get(lim, LaLimit.TRAVERSAL_ROWS_MAX));

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
            Map<String, Map<String, Object>> types = columnTypes(writeRoot, ds, List.of(idCol));
            plan.add(new Mapping(true, ds, relationSql, guarded(sql.toString(), ds), null,
                    ApiContext.str(m, "category"), attrs, types.get(idCol), null, null, List.of(),
                    ExplorationMasking.of(writeRoot, ds, Map.of("sourceCol", idCol, "targetCol", idCol))));
        }
        for (Map<String, Object> m : edgeSpecs) {
            String ds = datasetOf(m, "edges");
            String relationSql = relationFor(api, ex, writeRoot, ds);
            String srcCol = ident(m, "sourceColumn", true), tgtCol = ident(m, "targetColumn", true);
            List<String> attrs = nameList(m, "attributes", true);
            DatasetProvider.BoundFilter filter = filterSql(body.get("filter"), ds, relationSql)
                    .and(filterSql(m.get("filter"), ds, relationSql));
            String sql = edgeSql(ds, srcCol, tgtCol, null, attrs, "", filter);
            Map<String, Map<String, Object>> types = columnTypes(writeRoot, ds, List.of(srcCol, tgtCol));
            plan.add(new Mapping(false, ds, relationSql, guarded(sql, ds), ApiContext.str(m, "type"), null, attrs,
                    null, types.get(srcCol), types.get(tgtCol), filter.binds(),
                    ExplorationMasking.of(writeRoot, ds, Map.of("sourceCol", srcCol, "targetCol", tgtCol))));
        }
        // D-U7, judged on the WHOLE plan before any mapping runs (fail closed, whole call): `limit` applies per
        // mapping, so the call reads up to limit × mappings rows (the budget gate); `limit` is a render cap, never a fan-out.
        refuseIfSensitive(writeRoot, "a multi-Dataset projection (limit " + limit + " × " + plan.size()
                + " mappings)", (long) limit * plan.size(), 0);   // limit is a render cap, not a fan-out

        List<Map<String, Object>> nodes = new ArrayList<>(), edges = new ArrayList<>(), mappings = new ArrayList<>();
        boolean truncated = false;
        for (Mapping mp : plan) {
            DatasetProvider.Result r;
            try {
                r = DatasetProviders.require().run(new DatasetProvider.Request(mp.dataset(), mp.relationSql(), mp.sql(),
                        limit, 0, List.of(), List.of(), mp.binds()));
            } catch (SQLException e) {
                throw DatasetReadFault.of("projection of dataset '" + mp.dataset() + "' failed", mp.dataset(), writeRoot, e);
            }
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> out;
                if (mp.node()) {
                    out = new LinkedHashMap<>();
                    out.put("id", mp.mask().out(row.get("id")));
                    // a label column can restate the id (or name its owner), so a masked mapping shows the alias as the label
                    out.put("label", mp.mask().active() && row.get("label") != null ? mp.mask().out(row.get("id")) : row.get("label"));
                    out.put("category", mp.category());
                    if (!mp.attrs().isEmpty()) {
                        Map<String, Object> attrs = new LinkedHashMap<>();
                        for (int i = 0; i < mp.attrs().size(); i++) attrs.put(mp.attrs().get(i), row.get("attr_" + i));
                        out.put("attrs", attrs);
                    }
                    if (mp.idType() != null) out.put("entityType", mp.idType());
                } else {
                    out = maskEnds(mp.mask(), edgeRow(row, mp.kind(), mp.attrs()));
                    if (mp.sourceType() != null) out.put("sourceType", mp.sourceType());
                    if (mp.targetType() != null) out.put("targetType", mp.targetType());
                }
                out.put(PROVENANCE, mp.dataset());
                (mp.node() ? nodes : edges).add(out);
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("dataset", mp.dataset());
            summary.put("role", mp.node() ? "node" : "edge");
            summary.put("rows", r.rows().size());
            summary.put("truncated", r.truncated());
            summary.put("masking", mp.mask().describe());
            mappings.add(summary);
            truncated |= r.truncated();
            audit(ex, mp.dataset(), null, r.rows().size(), r.truncated(), null);
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
        if (!(raw instanceof List<?> list)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list");
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every '" + key + "' entry must be an object");
            Map<String, Object> copy = new LinkedHashMap<>();
            m.forEach((k, v) -> copy.put(String.valueOf(k), v));
            out.add(copy);
        }
        return out;
    }

    private static String datasetOf(Map<String, Object> mapping, String key) {
        String ds = ApiContext.str(mapping, "dataset");
        if (ds == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "every '" + key + "' entry must include 'dataset'");
        return ds;
    }

    /** Defence in depth: a server-built statement still passes the caller-SQL guard, trusting only its own relation. */
    private static String guarded(String sql, String datasetId) {
        if (!SqlGuard.check(sql, datasetId).isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "projection of dataset '" + datasetId + "' failed the SQL safety check");
        return sql;
    }

    /**
     * LA-17 D-M6: the Entity Type each named column is typed by -- its Dataset's registry {@code columns[].classification}
     * claimed (trimmed, case-insensitive) by an in-force Entity Type -- as column -> {@code {id, normaliser}}. A column
     * with no classification, or one no type claims, is absent (untyped). The SPA mints {@code <type>:<key>} from this,
     * so every mint site agrees on which columns are typed; {@code EntityMasking} reads the same classification.
     */
    static Map<String, Map<String, Object>> columnTypes(Path writeRoot, String datasetId, List<String> columns) {
        Map<String, Object> ds = DatasetProviders.require().dataset(writeRoot, datasetId).orElse(Map.of());
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (!(ds.get("columns") instanceof List<?> cols)) return out;
        List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
        for (String name : columns)
            for (Object o : cols)
                if (o instanceof Map<?, ?> c && name.equalsIgnoreCase(String.valueOf(c.get("name")))
                        && c.get("classification") != null) {
                    String cls = String.valueOf(c.get("classification")).trim();
                    types.stream().filter(t -> t.classifications().stream().anyMatch(x -> x.trim().equalsIgnoreCase(cls)))
                            .findFirst().ifPresent(t -> out.put(name, Map.of("id", t.id(), "normaliser", t.normaliser())));
                }
        return out;
    }

    private Object project(ApiContext api, HttpExchange ex, Map<String, Object> body, String neighborsOf) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "entity projection");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String kindCol = ident(body, "linkKindCol", false);
        List<String> attrCols = attrCols(body);
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int limit = clamp(body.get("limit"), SpaceLimits.get(lim, LaLimit.TRAVERSAL_ROWS_DEFAULT), SpaceLimits.get(lim, LaLimit.TRAVERSAL_ROWS_MAX));

        String relationSql = relationFor(api, ex, writeRoot, datasetId);
        // DR-D2: the read's ids leave as aliases when the Space masks them; an alias handed back (expand's value) is resolved here.
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId, Map.of("sourceCol", sourceCol, "targetCol", targetCol));
        if (neighborsOf != null) neighborsOf = mask.in(neighborsOf);

        // LA-01: the optional condition tree is validated against the relation's REAL columns and
        // rendered BEFORE a single character of the statement is assembled below — an identifier the
        // relation does not have cannot reach SQL, because the render never happens.
        DatasetProvider.BoundFilter filter = filterSql(body.get("filter"), datasetId, relationSql);
        // D-U7: `limit` is a RENDER CAP, not a fan-out - it is judged only as rows against the budget gate. The fan-out
        // gate applies where one entity's neighbour expansion is the thing gated: a neighbours read of ONE node is
        // judged on that node's real counted degree (distinct links), exactly what an Investigation expand holds.
        refuseIfSensitive(writeRoot, (neighborsOf != null ? "a neighbours read" : "a projection")
                + " (limit " + limit + ")", limit, 0);
        if (neighborsOf != null && LinkAnalysisSettings.forRoot(writeRoot).fourEyesFanOutAbove() != null) {
            String s0 = q(sourceCol), t0 = q(targetCol);
            List<String> cb = new ArrayList<>(List.of(neighborsOf, neighborsOf));
            cb.addAll(filter.binds());
            String countSql = "SELECT COUNT(*) AS n FROM (" + edgeSql(datasetId, sourceCol, targetCol, kindCol, attrCols,
                    " AND (CAST(" + s0 + " AS VARCHAR) = ? OR CAST(" + t0 + " AS VARCHAR) = ?)", filter) + ")";
            try {
                List<Map<String, Object>> cr = DatasetProviders.require().run(new DatasetProvider.Request(
                        datasetId, relationSql, countSql, 1, 0, List.of(), List.of(), cb)).rows();
                long degree = cr.isEmpty() || !(cr.get(0).get("n") instanceof Number n) ? 0 : n.longValue();
                refuseIfSensitive(writeRoot, "a neighbours read of one entity (degree " + degree + ")", 0,
                        (int) Math.min(Integer.MAX_VALUE, degree));
            } catch (SQLException e) {
                throw DatasetReadFault.of("projection failed", datasetId, writeRoot, e);
            }
        }

        // D-3 step 6: a one-hop neighbours read is answered from the edge index when - and only when - it can answer exactly
        // (IndexedNeighbors). The view gate and the four-eyes refusal above have run; a fallback below is the unchanged flat
        // read. Disabled by default (index.enabled), so the default response gains nothing but `source`.
        IndexedRead.Outcome<IndexedNeighbors.Result> indexed = null;
        if (neighborsOf != null) {
            indexed = IndexedNeighbors.attempt(writeRoot, api.dataRoot(), relationSql, new IndexedNeighbors.Request(datasetId, sourceCol,
                    targetCol, kindCol, attrCols, body.get("filter"), neighborsOf, limit), traversalPolicy(writeRoot));
            if (indexed.served()) {
                List<Map<String, Object>> rows = new ArrayList<>(indexed.result().rows().size());
                for (Map<String, Object> row : indexed.result().rows())
                    rows.add(maskEnds(mask, edgeRow(row, kindCol != null ? row.get("kind") : null, attrCols)));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("masking", mask.describe());
                out.put("rows", rows);
                out.put("truncated", indexed.result().truncated());
                out.put("columnTypes", columnTypes(writeRoot, datasetId, List.of(sourceCol, targetCol)));
                out.put("source", indexed.source());
                audit(ex, datasetId, neighborsOf, rows.size(), indexed.result().truncated(), indexed);
                return out;
            }
        }

        // The value is bound, not interpolated: `Request` gained `binds` and `QueryExecutor` a
        // PreparedStatement branch, which retired this route's hand-rolled quote-doubling. Two `?` in
        // source order — binds are positional, and `wrap()` adds none of its own. Column identifiers are
        // still built from validated identifiers; JDBC cannot bind those.
        String src = q(sourceCol), tgt = q(targetCol);
        String neighborFilter = neighborsOf != null
                ? " AND (CAST(" + src + " AS VARCHAR) = ? OR CAST(" + tgt + " AS VARCHAR) = ?)" : "";
        // The filter's binds follow the neighbour pair: edgeSql puts `extraWhere` ahead of the filter predicate.
        List<String> binds = new ArrayList<>();
        if (neighborsOf != null) {
            binds.add(neighborsOf);
            binds.add(neighborsOf);
        }
        binds.addAll(filter.binds());
        // Server-built from validated identifiers only; one extra row detects truncation.
        String sql = edgeSql(datasetId, sourceCol, targetCol, kindCol, attrCols, neighborFilter, filter);

        try {
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                    datasetId, relationSql, sql, limit, 0, List.of(), List.of(), binds));
            List<Map<String, Object>> rows = new ArrayList<>(r.rows().size());
            for (Map<String, Object> row : r.rows())
                rows.add(maskEnds(mask, edgeRow(row, kindCol != null ? row.get("kind") : null, attrCols)));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("masking", mask.describe());
            out.put("rows", rows);
            out.put("truncated", r.truncated());
            out.put("columnTypes", columnTypes(writeRoot, datasetId, List.of(sourceCol, targetCol)));
            if (indexed != null) out.put("source", indexed.source());
            audit(ex, datasetId, neighborsOf, rows.size(), r.truncated(), indexed);
            return out;
        } catch (SQLException e) {
            throw DatasetReadFault.of("projection failed", datasetId, writeRoot, e);
        }
    }

    /**
     * Four-eyes on the stateless reads (D-U7, operator 2026-09-24). An Investigation's sensitive {@code expand} waits
     * as a pending request for a second person; {@code /inv/projection}, {@code /inv/projection/multi},
     * {@code /inv/projection/neighbors} and {@code /inv/traversal/recursive-paths} have no Investigation to hold
     * one, so — exactly as a template's sensitive step already is — a read above the Space's thresholds is REFUSED
     * rather than run unapproved, and the refusal names where it can be approved. Without this, these routes read
     * what a pending expand is being held back from reading. {@code rows} (the most rows the read can touch) is compared with
     * {@code four_eyes_budget_above}; {@code fanOut} (the most links taken from any one entity) with
     * {@code four_eyes_fan_out_above}. No threshold set — the shipped default — means no gate.
     */
    static void refuseIfSensitive(Path writeRoot, String what, long rows, int fanOut) {
        LinkAnalysisSettings s = LinkAnalysisSettings.forRoot(writeRoot);
        List<String> exceeded = new ArrayList<>();
        if (s.fourEyesBudgetAbove() != null && rows > s.fourEyesBudgetAbove())
            exceeded.add("rows " + rows + " > " + s.fourEyesBudgetAbove());
        if (s.fourEyesFanOutAbove() != null && fanOut > s.fourEyesFanOutAbove())
            exceeded.add("fan-out " + fanOut + " > " + s.fourEyesFanOutAbove());
        if (!exceeded.isEmpty())
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, what + " is a sensitive read " + exceeded + " — four-eyes applies (D-U7) and "
                    + "this route has no Investigation to hold a request for approval; lower the bound, or expand "
                    + "from an Investigation, where a second person can approve the step");
    }

    /**
     * Resolve a Dataset id to its trusted relation SQL — the gate order every projection shares: unknown →
     * 404; not viewable by this request's subject → the SAME 404 (R3: shared-away is indistinguishable from
     * absence, exactly as {@code BiRoutes} answers); an unusable Dataset → 422.
     */
    public static String relationFor(ApiContext api, HttpExchange ex, Path writeRoot, String datasetId) {
        Map<String, Object> dataset = DatasetProviders.require().dataset(writeRoot, datasetId)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + datasetId + "'"));
        if (!ComponentAccess.canView(ex, dataset))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no dataset '" + datasetId + "'");
        try {
            return DatasetProviders.require().relationSql(dataset, api.dataRoot(), writeRoot);
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
    }

    /**
     * The folded-edge statement: distinct {@code (source, target[, kind][, attr_i...])} with a row count,
     * heaviest first, NULL endpoints excluded. Built from validated identifiers only; {@code extraWhere} is
     * server-authored (a bound-parameter clause or empty) and {@code filter} is the LA-01 render, its values bound
     * (its binds follow {@code extraWhere}'s in the caller's list).
     */
    private static String edgeSql(String datasetId, String sourceCol, String targetCol, String kindCol,
                                  List<String> attrCols, String extraWhere, DatasetProvider.BoundFilter filter) {
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
                + " AND (" + filter.sql() + ")"
                + " " + groupBy
                + " ORDER BY cnt DESC, source, target";
    }

    /** DR-D2: an edge row's two endpoints as the caller may see them (the row is this read's own copy). */
    private static Map<String, Object> maskEnds(ExplorationMasking mask, Map<String, Object> row) {
        if (mask.active()) {
            row.put("source", mask.out(row.get("source")));
            row.put("target", mask.out(row.get("target")));
        }
        return row;
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
     * {@code POST /inv/traversal/recursive-paths} (LA-11, D-S2) — the simple paths from {@code startNode}
     * over an edge Dataset, walked server-side by ONE DuckDB recursive CTE. Body
     * {@code {dataset, sourceCol, targetCol, startNode, targetNode?, maxDepth?, direction?, weightCol?,
     * temporalConstraint?: {timestampCol, monotonic?, maxTotalDurationHours?}, filter?, maxEdgeYield?, limit?}}
     * → {@code {paths:[{nodes, hops, weight}], truncated, edgeYieldCapped, fences:{maxDepth, maxEdgeYield, timeoutMs}}}.
     *
     * <p>⛔ <b>Every fence lives INSIDE the recursion</b>, because the executor's {@code LIMIT n+1} sits outside
     * the derived table and bounds the RESULT, never the WORK ({@code QueryExecutorRecursiveCteTest}):
     * <ul>
     *   <li><b>depth</b> — a BOUND {@code ?} in the recursive member (clamped to the Space's {@code traversal.depth_max}); the
     *       caller's number never becomes statement text;</li>
     *   <li><b>cycles</b> — a step onto a node already on the path is refused ({@code list_contains}), so
     *       only simple paths are walked and a cycle cannot re-feed the frontier;</li>
     *   <li><b>edge yield</b> — a bound {@code LIMIT} on each recursion level, so total work is at most
     *       depth × yield rows; a level that reached it sets {@code edgeYieldCapped} (and {@code truncated});</li>
     *   <li><b>timeout</b> — the Space's {@code traversal.timeout_s} statement timeout via a route-local
     *       sandbox policy, tighter than the JVM-wide default.</li>
     * </ul>
     * {@code __walk} is {@code MATERIALIZED}: it is read twice (the paths and the per-level widths), and inlined
     * DuckDB ran the whole recursion twice — 2.5× the G-R4 cost, and the capped width could come from a
     * different walk than the paths. {@code __e0} stays inlined on purpose: materialising it copies the whole
     * edge relation per request, faster at 10⁶ edges but 2× slower at 10⁸ (§7.10 D-S3).
     * With {@code targetNode}, a path stops extending once it reaches the target and only paths ending
     * there are returned. Every named column is checked against the relation's actual columns first.
     */
    private Object recursivePaths(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "recursive traversal");
        String datasetId = ApiContext.str(body, "dataset");
        if (datasetId == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'dataset'");
        String sourceCol = ident(body, "sourceCol", true);
        String targetCol = ident(body, "targetCol", true);
        String weightCol = ident(body, "weightCol", false);
        String startNodeIn = ApiContext.str(body, "startNode");
        if (startNodeIn == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'startNode'");
        // DR-D2: the walk runs on raw ids; the nodes leave as aliases when the Space masks them, and an alias comes back here.
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId, Map.of("sourceCol", sourceCol, "targetCol", targetCol));
        String startNode = mask.in(startNodeIn);
        String targetNode = mask.in(ApiContext.str(body, "targetNode"));
        if (startNode.equals(targetNode)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'targetNode' must differ from 'startNode'");
        String direction = ApiContext.str(body, "direction") != null ? ApiContext.str(body, "direction") : "DIRECTED";
        if (!direction.equals("DIRECTED") && !direction.equals("UNDIRECTED"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'direction' must be DIRECTED or UNDIRECTED");
        String tsColIn = null;
        boolean monotonicIn = false;
        Double maxHoursIn = null;
        Double maxGapHoursIn = null;
        if (body.get("temporalConstraint") instanceof Map<?, ?> tc) {
            @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) tc;
            tsColIn = ident(t, "timestampCol", true);
            monotonicIn = Boolean.TRUE.equals(t.get("monotonic"));
            if (t.get("maxTotalDurationHours") instanceof Number h) {
                if (h.doubleValue() <= 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'maxTotalDurationHours' must be positive");
                maxHoursIn = h.doubleValue();
            }
            if (t.get("maxGapHours") instanceof Number g) {          // time-respecting: each hop within this gap of the previous one
                if (g.doubleValue() <= 0) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'maxGapHours' must be positive");
                if (!monotonicIn) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'maxGapHours' bounds the gap between consecutive edges of a time-ordered path - it needs 'monotonic': true");
                maxGapHoursIn = g.doubleValue();
            }
        }
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        int maxDepth = clamp(body.get("maxDepth"), SpaceLimits.get(lim, LaLimit.TRAVERSAL_DEPTH_DEFAULT), SpaceLimits.get(lim, LaLimit.TRAVERSAL_DEPTH_MAX));
        int maxEdgesIn = clamp(body.get("maxEdgeYield"), SpaceLimits.get(lim, LaLimit.TRAVERSAL_EDGE_YIELD_DEFAULT),
                SpaceLimits.get(lim, LaLimit.TRAVERSAL_EDGE_YIELD_MAX));
        // A DEFAULT is not a declaration (operator 2026-10-11): a caller that sent no maxEdgeYield gets the default clamped to
        // four_eyes_fan_out_above instead of a refusal; an explicitly declared bound above it is still refused below.
        if (body.get("maxEdgeYield") == null) {
            Integer fanOutAbove = LinkAnalysisSettings.forRoot(writeRoot).fourEyesFanOutAbove();
            if (fanOutAbove != null && maxEdgesIn > fanOutAbove) maxEdgesIn = Math.max(1, fanOutAbove);
        }
        int maxEdges = maxEdgesIn;
        int limit = clamp(body.get("limit"), SpaceLimits.get(lim, LaLimit.TRAVERSAL_PATHS_DEFAULT), SpaceLimits.get(lim, LaLimit.TRAVERSAL_PATHS_MAX));
        int timeoutSeconds = SpaceLimits.get(lim, LaLimit.TRAVERSAL_TIMEOUT_S);

        String relationSql = relationFor(api, ex, writeRoot, datasetId);
        String tsCol = tsColIn;
        boolean monotonic = monotonicIn;
        Double maxHours = maxHoursIn;
        Double maxGapHours = maxGapHoursIn;

        // D-3 step 5: answer from the edge index when - and only when - it can answer exactly (IndexedRecursivePaths). The view
        // gate above has run; a fallback below is the unchanged flat read, which then says why on `source`.
        IndexedRead.Outcome<com.gamma.la.storage.IndexedTraversal.Result> indexed = IndexedRecursivePaths.attempt(writeRoot, api.dataRoot(), relationSql,
                new IndexedRecursivePaths.Request(datasetId, sourceCol, targetCol, weightCol, tsCol, body.get("filter"), startNode,
                        targetNode, direction.equals("UNDIRECTED"), maxDepth, maxEdges, limit, monotonic, maxHours, maxGapHours),
                traversalPolicy(writeRoot), () -> refuseIfSensitive(writeRoot, "a traversal (maxDepth " + maxDepth + " × maxEdgeYield " + maxEdges + ")",
                        (long) maxDepth * maxEdges, maxEdges));
        if (indexed.served()) {
            List<Map<String, Object>> paths = new ArrayList<>(indexed.result().paths().size());
            for (com.gamma.la.storage.IndexedTraversal.PathRow row : indexed.result().paths()) {
                Map<String, Object> pathOut = new LinkedHashMap<>();
                pathOut.put("nodes", mask.active() ? row.nodes().stream().map(n -> mask.out(String.valueOf(n))).toList() : row.nodes());
                pathOut.put("hops", row.hops());
                pathOut.put("weight", weightCol != null ? row.weight() : null);
                paths.add(pathOut);
            }
            boolean truncated = indexed.result().limitTruncated() || indexed.result().yieldCapped();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paths", paths);
            out.put("truncated", truncated);
            out.put("edgeYieldCapped", indexed.result().yieldCapped());
            out.put("masking", mask.describe());
            out.put("fences", traversalFences(maxDepth, maxEdges, timeoutSeconds));
            out.put("source", indexed.source());
            auditTraversal(ex, datasetId, startNode, targetNode, maxDepth, paths.size(), truncated, indexed);
            return out;
        }

        // Every identifier is checked against the REAL columns before any statement text is assembled. The
        // columns are probed in the SAME sandbox session the walk then runs in (G-R4, 2026-09-30): a separate
        // probe cost a second temp-DB open/register/close per request.
        DatasetProvider.Planner planner = columns -> {
        for (String col : java.util.Arrays.asList(sourceCol, targetCol, weightCol, tsCol)) {
            if (col != null && !containsIgnoreCase(columns, col))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown column '" + col + "' — not a column of dataset '" + datasetId + "'");
        }
        DatasetProvider.BoundFilter filter = DatasetProvider.BoundFilter.TRUE;
        if (body.get("filter") != null) filter = checkedFilterSql(body.get("filter"), columns, datasetId);
        // D-U7: the walk reads at most maxDepth × maxEdgeYield rows, and takes up to maxEdgeYield links per level.
        refuseIfSensitive(writeRoot, "a traversal (maxDepth " + maxDepth + " × maxEdgeYield " + maxEdges + ")",
                (long) maxDepth * maxEdges, maxEdges);

        String src = q(sourceCol), tgt = q(targetCol);
        String wSel = weightCol != null ? "TRY_CAST(" + q(weightCol) + " AS DOUBLE)" : "CAST(NULL AS DOUBLE)";
        String tsSel = tsCol != null ? "CAST(" + q(tsCol) + " AS TIMESTAMP)" : "CAST(NULL AS TIMESTAMP)";
        List<String> binds = new ArrayList<>(filter.binds());          // the filter is the first `?` in the statement
        StringBuilder sql = new StringBuilder("WITH RECURSIVE __e0 AS (SELECT CAST(").append(src)
                .append(" AS VARCHAR) AS s, CAST(").append(tgt).append(" AS VARCHAR) AS t, ").append(wSel)
                .append(" AS w, ").append(tsSel).append(" AS ts FROM ").append(q(datasetId))
                .append(" WHERE ").append(src).append(" IS NOT NULL AND ").append(tgt).append(" IS NOT NULL AND (")
                .append(filter.sql()).append(")), __e AS (SELECT s, t, w, ts FROM __e0");
        if (direction.equals("UNDIRECTED")) sql.append(" UNION ALL SELECT t, s, w, ts FROM __e0");
        sql.append("), __walk(node, path, depth, weight, first_ts, last_ts) AS MATERIALIZED (")
           .append("SELECT CAST(? AS VARCHAR), list_value(CAST(? AS VARCHAR)), 0, CAST(0 AS DOUBLE),")
           .append(" CAST(NULL AS TIMESTAMP), CAST(NULL AS TIMESTAMP)")
           .append(" UNION ALL SELECT * FROM (SELECT e.t, list_append(w.path, e.t), w.depth + 1,")
           .append(" w.weight + COALESCE(e.w, 0), COALESCE(w.first_ts, e.ts), e.ts")
           .append(" FROM __walk w JOIN __e e ON e.s = w.node")
           .append(" WHERE w.depth < CAST(? AS INTEGER) AND NOT list_contains(w.path, e.t)");
        binds.add(startNode);
        binds.add(startNode);
        binds.add(String.valueOf(maxDepth));
        if (targetNode != null) {
            sql.append(" AND w.node <> ?");
            binds.add(targetNode);
        }
        if (tsCol != null) sql.append(" AND e.ts IS NOT NULL");
        if (monotonic) sql.append(" AND (w.last_ts IS NULL OR e.ts >= w.last_ts)");
        if (maxGapHours != null) {
            sql.append(" AND (w.last_ts IS NULL OR epoch(e.ts) - epoch(w.last_ts) <= CAST(? AS DOUBLE) * 3600)");
            binds.add(String.valueOf(maxGapHours));
        }
        if (maxHours != null) {
            sql.append(" AND (w.first_ts IS NULL OR epoch(e.ts) - epoch(w.first_ts) <= CAST(? AS DOUBLE) * 3600)");
            binds.add(String.valueOf(maxHours));
        }
        sql.append(" LIMIT CAST(? AS BIGINT))), __lv AS (SELECT count(*) AS n FROM __walk GROUP BY depth)")
           .append(" SELECT CAST(to_json(path) AS VARCHAR) AS path_json, depth AS hops, weight,")
           .append(" (SELECT max(n) FROM __lv) AS widest FROM __walk WHERE depth > 0");
        binds.add(String.valueOf(maxEdges));
        if (targetNode != null) {
            sql.append(" AND node = ?");
            binds.add(targetNode);
        }
        sql.append(" ORDER BY hops, path_json");
        return new DatasetProvider.Request(datasetId, relationSql, sql.toString(), limit, 0, List.of(), List.of(), binds);
        };

        try {
            DatasetProvider.Result r = DatasetProviders.require().runPlanned(datasetId, relationSql, traversalPolicy(writeRoot), planner);
            List<Map<String, Object>> paths = new ArrayList<>(r.rows().size());
            boolean yieldCapped = false;
            for (Map<String, Object> row : r.rows()) {
                Map<String, Object> out = new LinkedHashMap<>();
                List<?> walked = ApiContext.JSON.readValue(String.valueOf(row.get("path_json")), List.class);
                out.put("nodes", mask.active() ? walked.stream().map(n -> mask.out(String.valueOf(n))).toList() : walked);
                out.put("hops", row.get("hops"));
                out.put("weight", weightCol != null ? row.get("weight") : null);
                paths.add(out);
                yieldCapped |= num(row.get("widest")) >= maxEdges;
            }
            Map<String, Object> fences = traversalFences(maxDepth, maxEdges, timeoutSeconds);
            boolean truncated = r.truncated() || yieldCapped;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("paths", paths);
            out.put("truncated", truncated);
            out.put("edgeYieldCapped", yieldCapped);
            out.put("masking", mask.describe());
            out.put("fences", fences);
            out.put("source", indexed.source());
            auditTraversal(ex, datasetId, startNode, targetNode, maxDepth, paths.size(), truncated, indexed);
            return out;
        } catch (SQLException e) {
            throw DatasetReadFault.of("traversal failed", datasetId, writeRoot, e);
        }
    }

    private static Map<String, Object> traversalFences(int maxDepth, int maxEdges, int timeoutSeconds) {
        Map<String, Object> fences = new LinkedHashMap<>();
        fences.put("maxDepth", maxDepth);
        fences.put("maxEdgeYield", maxEdges);
        fences.put("timeoutMs", timeoutSeconds * 1000);
        return fences;
    }

    /**
     * The JVM-wide sandbox caps ({@code assist.sql.memory_limit} / {@code assist.sql.threads}) with the route's
     * own statement timeout - the Space's {@code traversal.timeout_s} for {@code writeRoot} ({@code null} = the default). It used to be {@code withCaps(null, 0, timeout)}, which silently replaced the
     * operator's memory and thread caps with the hard-coded 1 GB / 2 threads (G-R4 profiling, 2026-09-30).
     * Threads come from the traversal-only {@code assist.sql.traversal_threads} (default 4, operator 2026-09-30).
     * {@code LA-CONFIG-LIMITS-1}: the install-wide {@code traversal.memory_limit_mb} / {@code traversal.threads} win over both
     * properties when the server-wide document states them; read per call, so a change applies to the next traversal.
     */
    static SqlSandboxPolicy traversalPolicy(Path writeRoot) {
        SqlSandboxPolicy configured = SqlSandboxPolicy.defaultPolicy();
        LaLimitValues install = com.gamma.entitystore.LaLimits.forSpace(null);
        Number mb = install.stated(LaLimit.TRAVERSAL_MEMORY_LIMIT_MB);
        Number threads = install.stated(LaLimit.TRAVERSAL_THREADS);
        return SqlSandboxPolicy.withCaps(mb != null ? mb.longValue() + "MiB" : configured.memoryLimit(),
                threads != null ? threads.intValue() : SqlSandboxPolicy.traversalThreads(), SpaceLimits.get(writeRoot, LaLimit.TRAVERSAL_TIMEOUT_S));
    }

    /** An optional positive integer body field, defaulted when absent and clamped to {@code max} (a default above it, too). */
    private static int clamp(Object raw, int def, int max) {
        return raw instanceof Number n ? Math.max(1, Math.min(max, n.intValue())) : Math.min(def, max);
    }

    /** Best-effort audit of one traversal (LA-04 pattern): a multi-hop walk is its own analytic act. */
    private static void auditTraversal(HttpExchange ex, String datasetId, String startNode, String targetNode,
                                       int maxDepth, int paths, boolean truncated, IndexedRead.Outcome<?> source) {
        try {
            Event.Builder b = Event.builder(LinkEventTypes.LINK_TRAVERSED)
                    .source("inv")
                    .message("link.traversed " + datasetId + " from " + startNode + " — " + paths + " paths"
                            + (truncated ? " (truncated)" : ""))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.traversed").actionCategory("analysis")
                    .target("dataset", datasetId)
                    .attr("dataset", datasetId).attr("startNode", startNode).attr("maxDepth", maxDepth)
                    .attr("paths", paths).attr("truncated", truncated)
                    .attr("source", source.served() ? "index" : "dataset");
            if (source.served()) {
                b.attr("indexVersion", source.version()).attr("indexStale", source.stale())
                        .attr("fingerprint", source.fingerprintKnown() ? "known" : "unknown");
                if (source.stale()) b.attr("indexStaleReasons", String.join(",", source.staleCodes()));
            } else {
                b.attr("sourceReason", source.reason().name());
                if (!source.staleCodes().isEmpty()) b.attr("indexStaleReasons", String.join(",", source.staleCodes()));
            }
            if (targetNode != null) b.attr("targetNode", targetNode);
            EventSink.current().emit(b);
        } catch (RuntimeException ignore) {
            // best effort — the audit must never fail the analyst's query
        }
    }

    /**
     * Best-effort audit of one analytic act (LA-04): a projection, or — when {@code neighborsOf} is set —
     * an expansion, which is a different act and gets its own type. Emitted only after the rows are built,
     * so {@code rows}/{@code truncated} describe what the analyst actually saw; a partial result the trail
     * cannot show as partial is worth nothing to an investigator.
     */
    private static void audit(HttpExchange ex, String datasetId, String neighborsOf, int rows, boolean truncated,
                              IndexedRead.Outcome<?> source) {
        try {
            boolean expand = neighborsOf != null;
            String action = expand ? "link.expanded" : "link.projected";
            Event.Builder b = Event.builder(expand ? LinkEventTypes.LINK_EXPANDED : LinkEventTypes.LINK_PROJECTED)
                    .source("inv")
                    .message(action + " " + datasetId + " — " + rows + " rows" + (truncated ? " (truncated)" : ""))
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action(action).actionCategory("analysis")
                    .target("dataset", datasetId)
                    .attr("dataset", datasetId).attr("rows", rows).attr("truncated", truncated);
            if (expand) b.attr("value", neighborsOf);
            if (source != null) {   // D-3 step 6: which store answered the expansion (a projection has no index path)
                b.attr("source", source.served() ? "index" : "dataset");
                if (source.served()) {
                    b.attr("indexVersion", source.version()).attr("indexStale", source.stale());
                } else {
                    b.attr("sourceReason", source.reason().name());
                }
            }
            EventSink.current().emit(b);
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
     * {@code ConditionSql} (which quote-escapes every literal, the tested contract for authored config),
     * and every leaf {@code field} is first checked against the relation's <em>actual</em> columns: an
     * unknown identifier is a 422 naming the field, and the renderer is never reached. Values are
     * bound ({@code predicateBound}) on the flat reads; the edge-index reads still render literals
     * ({@code LA-FILTER-SQL-BIND-1}).
     *
     * <p>Absent tree, or one that constrains nothing (empty group, only incomplete leaves) → {@code TRUE},
     * a no-op — parity with {@code ConditionSql}/{@code ConditionTree}'s "an empty group matches every row".
     * A root that is not a group (a bare condition, or not an object) is a 422: it used to render as
     * {@code TRUE} and return every row while the analyst believed the data was filtered.
     */
    private static DatasetProvider.BoundFilter filterSql(Object filter, String datasetId, String relationSql) {
        if (filter == null) return DatasetProvider.BoundFilter.TRUE;
        return checkedFilterSql(filter, relationColumns(datasetId, relationSql), datasetId);
    }

    /** Field-check then render; {@code ConditionSql}'s refusal of a non-group root becomes a 422. */
    static DatasetProvider.BoundFilter checkedFilterSql(Object filter, List<String> columns, String datasetId) {
        checkFilterFields(filter, columns, datasetId);
        try {
            return DatasetProviders.require().predicateBound(filter);
        } catch (IllegalArgumentException notAGroup) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'filter': " + notAGroup.getMessage());
        }
    }

    /** The relation's column names, probed with a zero-row SELECT — the same technique {@link #schemaRelationships} uses. */
    static List<String> relationColumns(String datasetId, String relationSql) {
        try {
            DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(
                    datasetId, relationSql, "SELECT * FROM " + q(datasetId), 0, 0, List.of(), List.of()));
            return r.columns().stream().map(DatasetProvider.Column::name).toList();
        } catch (Exception unusable) {
            throw DatasetReadFault.of("cannot read the columns of dataset '" + datasetId + "'", datasetId, null, unusable);
        }
    }

    /**
     * Walk the tree exactly as {@code ConditionSql} does (a node is a group when {@code kind=group} or it
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
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown filter field '" + field + "' — not a column of dataset '"
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
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unsafe column identifier '" + v + "' for attrCols");
            out.add(v);
        }
        return out;
    }

    static String ident(Map<String, Object> body, String key, boolean required) {
        String v = ApiContext.str(body, key);
        if (v == null) {
            if (required) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include '" + key + "'");
            return null;
        }
        if (!SAFE_IDENT.matcher(v).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unsafe column identifier '" + v + "' for " + key);
        return v;
    }
}
