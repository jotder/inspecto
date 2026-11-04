package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.IndustryPack;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.la.core.DatasetProvider;
import com.gamma.la.core.DatasetProviders;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.la.storage.IndexReader;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.sql.SqlSandboxPolicy;
import com.sun.net.httpserver.HttpExchange;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.gamma.util.SqlIdent.q;

/**
 * Preset queries over a Dataset (LA-PRESET-QUERIES-1, {@link PresetQueries}) - Basic's way to ask the questions an
 * industry already asks, without writing a query.
 *
 * <ul>
 *   <li>{@code GET /inv/presets?dataset=} - {@code {dataset, pack, roles, presets}}: the presets the Space's Industry
 *       pack offers whose roles this Dataset maps, with their parameters; {@code roles} says which column plays which
 *       role, so the screen can say why a preset is missing.</li>
 *   <li>{@code POST /inv/presets/{id}/run {dataset, params}} - {@code {preset, columns, rows, count, truncated, masking}}.
 *       Read-shaped: it persists nothing.</li>
 * </ul>
 *
 * <p>Gate order: write root 503 → body 422 → Dataset 404 (R3 via {@link InvRoutes#relationFor}) → preset 404 → a role
 * the Dataset does not map, an unknown or bad parameter 422. Entity ids leave as aliases when the Space masks them
 * (exactly as exploration does), and an {@code entity} parameter may be an alias this server served. At most
 * {@link PresetQueries#MAX_ROWS} rows (and the Space's entity fence), under the Space's statement timeout; every run is
 * audited as {@code LINK_PRESET_RUN}.
 */
public final class PresetRoutes implements RouteModule {

    private static final ZoneId UTC = ZoneId.of("UTC");   // DuckDB refuses "Z"

    /** Gated by the geoLink feature: a Space whose modules.toon disables it answers 404 MODULE_DISABLED here. */
    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("geoLink");
    }

    @Override
    public void register(ApiContext api) {
        api.get("/inv/presets", (e, m) -> list(api, e));
        api.post("/inv/presets/([^/]+)/run", (e, m) -> run(api, e, m.group(1), api.body(e)));
    }

    /** The presets a link index answers without scanning the Dataset: from its {@code nodes} table or its derived tables. */
    static final java.util.Set<String> INDEX_SERVED = java.util.Set.of("fan_in", "fan_out", "newcomers", "two_way", "short_loops");

    /** Two-way pairs and Short loops read the build's derived tables (LA-INDEX-PAIRS-LOOPS-1), never {@code nodes}. */
    private static final java.util.Set<String> DERIVED = java.util.Set.of("two_way", "short_loops");

    /** An index answer ({@code rows} set), or only the {@code source} saying why the Dataset answers instead. */
    record Answer(List<PresetQueries.Column> columns, List<Map<String, Object>> rows, boolean truncated,
                          Map<String, Object> source) {}

    /**
     * Reached by many, Reaching many and Newcomers read the {@code nodes} table, Two-way pairs and Short loops the derived tables
     * ({@link #fromDerived}), of a link index published for the Dataset's From and To - through {@link IndexedRead#select}, so the
     * Space's index switch and the staleness refusal apply exactly as they do for exploration. A kinds filter, no index, or a
     * refused one: null rows, and the Dataset answers. {@code null} for every other preset.
     */
    static Answer fromIndex(ApiContext api, Path writeRoot, Target t, PresetQueries.Preset p, Map<String, String> given,
                                    int limit) throws SQLException, java.io.IOException {
        if (!INDEX_SERVED.contains(p.id())) return null;
        if (p.param("kinds") != null && !PresetQueries.kinds(PresetQueries.value(p, given, "kinds")).isEmpty())
            return new Answer(null, null, false, IndexedRead.Outcome.flat(IndexedRead.Reason.filter_not_indexed,
                    "the index's per-entity table does not split links by kind").source());
        boolean newcomers = p.id().equals("newcomers");
        IndexedRead.Selection sel = IndexedRead.select(writeRoot, api.dataRoot(), t.relationSql(), t.datasetId(), t.roles().from(),
                t.roles().to(), (m, utc) -> newcomers && m.timeColumn() == null
                        ? IndexedRead.Fitted.no(IndexedRead.Reason.column_not_indexed) : IndexedRead.Fitted.ok(null));
        if (!sel.usable()) return new Answer(null, null, false, sel.flat().source());
        if (DERIVED.contains(p.id())) return fromDerived(writeRoot, sel, p, given, limit);
        List<IndexReader.NodeStat> stats;
        List<PresetQueries.Column> columns;
        boolean exact;
        try (IndexReader rd = IndexReader.borrow(sel.dir(), sel.manifest(), InvRoutes.traversalPolicy(writeRoot))) {
            exact = rd.exactLinks();
            long min = (long) Math.ceil(PresetQueries.number(p, given, "min"));
            if (newcomers) {
                stats = rd.newcomers(PresetQueries.wholeDays(PresetQueries.number(p, given, "days")), min, limit + 1);
                // the index counts each side apart: a counterpart on both sides counts twice, per kind of link, self-links in
                columns = List.of(new PresetQueries.Column("entity", "Entity", true),
                        new PresetQueries.Column("first_seen", "First seen", false),
                        new PresetQueries.Column("links", "Links in and out", false),
                        new PresetQueries.Column("counterparts", "Counterparts in + out (each side counted)", false));
            } else {
                stats = rd.rankNodes(p.id().equals("fan_in") ? IndexReader.NodeRank.IN_LINKS : IndexReader.NodeRank.OUT_LINKS, min,
                        limit + 1);
                columns = List.of(new PresetQueries.Column("entity", "Entity", true),
                        new PresetQueries.Column("counterparts", sel.manifest().mapping().kindColumn() == null
                                ? "Distinct counterparts" : "Distinct counterparts (per kind of link)", false),
                        new PresetQueries.Column("links", "Links", false));
            }
        } catch (SQLException | java.io.IOException | IllegalStateException unreadable) {
            // as every indexed read does: the Dataset answers, and says why
            return new Answer(null, null, false, IndexedRead.Outcome.flat(IndexedRead.Reason.index_read_failed,
                    "the index could not be read: " + unreadable.getMessage()).source());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IndexReader.NodeStat n : stats.subList(0, Math.min(limit, stats.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("entity", n.id());
            if (newcomers) {
                r.put("first_seen", n.firstSeen() == null ? null : n.firstSeen().toString());
                r.put("links", n.outEdges() + n.inEdges());
                r.put("counterparts", n.outLinks() + n.inLinks());
            } else {
                boolean in = p.id().equals("fan_in");
                r.put("counterparts", in ? n.inLinks() : n.outLinks());
                r.put("links", in ? n.inEdges() : n.outEdges());
            }
            rows.add(r);
        }
        Map<String, Object> source = new LinkedHashMap<>(sel.served(rows).source());
        if (newcomers) {
            source.put("timeColumn", sel.manifest().mapping().timeColumn());
            source.put("counting", "as the index counts: links in and out separately, a counterpart on both sides twice, "
                    + "per kind of link, self-links included");
        }
        if (!exact) source.put("approximate", "the index has appended deltas: link counts are summed across them, an upper bound");
        return new Answer(columns, rows, stats.size() > limit, source);
    }

    /**
     * Two-way pairs and Short loops from the version's {@code pairs_back} / {@code loops}: only when the manifest says the build
     * wrote them, and for Short loops only up to the build's cap (Q-5: a larger {@code maxLinks} reads the Dataset, which keeps
     * entities the build left out). A failed read falls back to the Dataset, as every indexed read does.
     */
    private static Answer fromDerived(Path writeRoot, IndexedRead.Selection sel, PresetQueries.Preset p, Map<String, String> given,
                                      int limit) {
        boolean loops = p.id().equals("short_loops");
        String table = loops ? "loops" : "pairs_back";
        Long cap = sel.manifest().loopsMaxDegree();
        if (cap == null || !sel.manifest().tables().containsKey(table))
            return new Answer(null, null, false, IndexedRead.Outcome.flat(IndexedRead.Reason.column_not_indexed,
                    "this index version has no " + table + " table (built before it existed, or with index.loops_max_degree 0)").source());
        long asked = loops ? (long) Math.floor(PresetQueries.number(p, given, "maxLinks")) : 0;
        if (loops && asked > cap)
            return new Answer(null, null, false, IndexedRead.Outcome.flat(IndexedRead.Reason.column_not_indexed,
                    "the index keeps loops only among entities with at most " + cap + " links in and out; ask for at most " + cap
                            + " to read it").source());
        List<PresetQueries.Column> columns = PresetQueries.graphColumns(p.graph());
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated;
        try (IndexReader rd = IndexReader.borrow(sel.dir(), sel.manifest(), InvRoutes.traversalPolicy(writeRoot))) {
            if (loops) {
                List<IndexReader.Loop> got = rd.loops(asked < cap ? asked : null, limit + 1);
                truncated = got.size() > limit;
                for (IndexReader.Loop l : got.subList(0, Math.min(limit, got.size())))
                    rows.add(new LinkedHashMap<>(Map.of("entity", l.x(), "second", l.y(), "third", l.z(), "links", l.links())));
            } else {
                List<IndexReader.TwoWay> got = rd.twoWay((long) Math.ceil(PresetQueries.number(p, given, "min")), limit + 1);
                truncated = got.size() > limit;
                for (IndexReader.TwoWay w : got.subList(0, Math.min(limit, got.size())))
                    rows.add(new LinkedHashMap<>(Map.of("entity", w.a(), "counterpart", w.b(), "links_out", w.linksAb(),
                            "links_back", w.linksBa())));
            }
        } catch (SQLException | java.io.IOException | IllegalStateException unreadable) {
            return new Answer(null, null, false, IndexedRead.Outcome.flat(IndexedRead.Reason.index_read_failed,
                    "the index could not be read: " + unreadable.getMessage()).source());
        }
        Map<String, Object> source = new LinkedHashMap<>(sel.served(rows).source());
        source.put("table", table);
        if (loops) source.put("loopsMaxDegree", cap);
        return new Answer(columns, rows, truncated, source);
    }

    /** What a Dataset is, for presets: its relation, its roles, and the Space's pack. */
    record Target(String datasetId, String relationSql, PresetQueries.Roles roles, String packId) {}

    static Target target(ApiContext api, HttpExchange ex, Path writeRoot, String datasetId) {
        String relationSql = InvRoutes.relationFor(api, ex, writeRoot, datasetId);
        Map<String, Object> dataset = DatasetProviders.require().dataset(writeRoot, datasetId).orElseThrow();
        List<DatasetProvider.Column> columns;
        try {
            columns = DatasetProviders.require().run(new DatasetProvider.Request(datasetId, relationSql,
                    "SELECT * FROM " + q(datasetId), 0, 0, List.of(), List.of())).columns();
        } catch (Exception unusable) {
            throw DatasetReadFault.of("cannot read the columns of dataset '" + datasetId + "'", datasetId, writeRoot, unusable);
        }
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        IndustryPack pack = settings.activePack();
        PresetQueries.Roles roles;
        try {
            roles = PresetQueries.roles(dataset, columns, settings.effectiveEntityTypes(), pack);
        } catch (IllegalArgumentException drifted) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + datasetId + "': " + drifted.getMessage());
        }
        return new Target(datasetId, relationSql, roles, pack == null ? null : pack.id());
    }

    private static String datasetOf(Object raw) {
        if (!(raw instanceof String s) || s.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'dataset' is required");
        return s.trim();
    }

    private Object list(ApiContext api, HttpExchange ex) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "preset queries");
        Target t = target(api, ex, writeRoot, datasetOf(ApiContext.query(ex, "dataset")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", t.datasetId());
        out.put("pack", t.packId());
        out.put("roles", t.roles().toMap());
        out.put("presets", PresetQueries.forPack(t.packId()).stream().filter(p -> p.offeredFor(t.roles()))
                .map(PresetQueries.Preset::toMap).toList());
        return out;
    }

    private Object run(ApiContext api, HttpExchange ex, String presetId, Map<String, Object> body) {
        Path writeRoot = WriteGates.requireWriteRoot(api, "preset query");
        if (body == null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a JSON body is required");
        String datasetId = datasetOf(body.get("dataset"));
        Map<String, String> given = new LinkedHashMap<>();
        if (body.get("params") != null) {
            if (!(body.get("params") instanceof Map<?, ?> ps))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'params' must be an object");
            ps.forEach((k, v) -> given.put(String.valueOf(k), v == null ? null : String.valueOf(v)));
        }
        Target t = target(api, ex, writeRoot, datasetId);
        PresetQueries.Preset p = PresetQueries.find(t.packId(), presetId);
        if (p == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no preset query '" + presetId + "' in this Space");
        List<String> missing = p.needs().stream().filter(n -> t.roles().get(n) == null).toList();
        if (!missing.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + p.label() + "' needs the Dataset to map "
                    + missing + "; '" + datasetId + "' does not");
        for (String k : given.keySet())
            if (p.param(k) == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + p.label() + "' has no parameter '" + k + "'");

        ValueMeasures.Fences fences = ValueMeasures.Fences.of(writeRoot);
        ExplorationMasking mask = ExplorationMasking.of(writeRoot, datasetId,
                Map.of("sourceCol", t.roles().from(), "targetCol", t.roles().to()));
        List<PresetQueries.Column> columns;
        List<Map<String, Object>> rows;
        boolean truncated;
        Map<String, Object> source = Map.of("kind", "dataset");
        try {
            Answer indexed = fromIndex(api, writeRoot, t, p, given, Math.min(PresetQueries.top(p, given), fences.maxEntities()));
            if (indexed != null) source = indexed.source();
            if (indexed != null && indexed.rows() != null) {
                columns = indexed.columns();
                rows = indexed.rows();
                truncated = indexed.truncated();
            } else if (p.measure() != null) {
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("name", p.measure());
                block.put("valueCol", t.roles().amount());
                block.put("timeCol", t.roles().time());
                for (PresetQueries.Param param : p.params()) block.put(param.name(), PresetQueries.value(p, given, param.name()));
                ValueMeasures.Spec spec = ValueMeasures.parse(block, false, fences);
                ValueMeasures.Result r = ValueMeasures.evaluate(datasetId, t.relationSql(), t.roles().from(), t.roles().to(),
                        t.roles().kind(), spec, null, fences);
                columns = List.of(new PresetQueries.Column("entity", "Entity", true),
                        new PresetQueries.Column("hours", "Median hours until it leaves", false),
                        new PresetQueries.Column("legs", "Links out", false),
                        new PresetQueries.Column("inbound", "Received", false));
                int cap = Math.min(PresetQueries.MAX_ROWS, fences.maxEntities());
                rows = r.entities().size() > cap ? r.entities().subList(0, cap) : r.entities();
                truncated = r.truncated() || r.entities().size() > cap;
            } else if (p.graph() != null) {
                String entity = p.param("entity") == null ? null : PresetQueries.value(p, given, "entity");
                if (p.param("entity") != null && entity == null) throw new IllegalArgumentException("'entity' is required");
                if (entity != null) entity = mask.in(entity);
                columns = PresetQueries.graphColumns(p.graph());
                List<String> binds = new ArrayList<>();
                String sql = PresetQueries.graphSql(datasetId, p.graph(), t.roles(), name -> PresetQueries.number(p, given, name),
                        entity, binds);
                int limit = Math.min(PresetQueries.top(p, given), fences.maxEntities());
                DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId,
                        t.relationSql(), sql, limit, 0, List.of(), List.of(), binds),
                        SqlSandboxPolicy.withCaps(null, 0, fences.timeoutSeconds()), UTC);
                rows = r.rows();
                truncated = r.truncated();
            } else {
                PresetQueries.Aggregate a = p.aggregate();
                String entity = a.entityParam() == null ? null : PresetQueries.value(p, given, a.entityParam());
                if (a.entityParam() != null && entity == null)
                    throw new IllegalArgumentException("'" + a.entityParam() + "' is required");
                if (entity != null) entity = mask.in(entity);
                Double min = a.minParam() == null ? null : PresetQueries.number(p, given, a.minParam());
                columns = PresetQueries.columns(a, t.roles());
                List<String> binds = new ArrayList<>();
                String sql = PresetQueries.aggregateSql(datasetId, a, t.roles(), columns, entity,
                        PresetQueries.kinds(PresetQueries.value(p, given, a.kindsParam())), min, binds);
                int limit = Math.min(PresetQueries.top(p, given), fences.maxEntities());
                DatasetProvider.Result r = DatasetProviders.require().run(new DatasetProvider.Request(datasetId,
                        t.relationSql(), sql, limit, 0, List.of(), List.of(), binds),
                        SqlSandboxPolicy.withCaps(null, 0, fences.timeoutSeconds()), UTC);
                rows = r.rows();
                truncated = r.truncated();
            }
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (SQLException | java.io.IOException e) {
            // the statement timeout surfaces as SQLTimeoutException("INTERRUPT Error: Interrupted!") from DuckDB's JDBC driver
            // the advice follows why the index did not answer: an index that exists but cannot (a cap, a filter) says so -
            // never a failed read's details, which carry the driver's text
            if (e instanceof java.sql.SQLTimeoutException || String.valueOf(e.getMessage()).startsWith("INTERRUPT Error")) {
                String why = String.valueOf(source.get("reason"));
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "This question took longer than this Space allows ("
                        + fences.timeoutSeconds() + " s) on '" + datasetId + "'. " + (!INDEX_SERVED.contains(p.id())
                        ? "Ask for fewer rows, or a shorter window."
                        : why.equals(IndexedRead.Reason.index_read_failed.name()) ? "Its link index could not be read; ask for fewer rows."
                        : source.get("details") != null ? "Its link index could not answer it: " + source.get("details") + "."
                        : "A link index of the Dataset answers it quickly - build one - or ask for fewer rows."));
            }
            throw DatasetReadFault.of("preset query failed", datasetId, writeRoot, e);
        }

        List<Map<String, Object>> shown = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> c = new LinkedHashMap<>();
            for (PresetQueries.Column col : columns)
                c.put(col.id(), col.entity() ? mask.out(row.get(col.id())) : row.get(col.id()));
            shown.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("preset", p.toMap());
        out.put("dataset", datasetId);
        out.put("columns", columns.stream().map(PresetQueries.Column::toMap).toList());
        out.put("rows", shown);
        out.put("count", shown.size());
        out.put("truncated", truncated);
        out.put("masking", mask.describe());
        out.put("source", source);
        try {
            EventSink.current().emit(Event.builder(LinkEventTypes.LINK_PRESET_RUN).source("inv")
                    .message("link.preset.run — " + p.id() + " over " + datasetId)
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("link.preset.run").actionCategory("analysis")
                    .attr("dataset", datasetId).attr("preset", p.id())
                    .attr("rows", shown.size()).attr("truncated", truncated));
        } catch (RuntimeException ignored) {
            // best effort (LA-04) — the read already succeeded
        }
        return out;
    }
}
