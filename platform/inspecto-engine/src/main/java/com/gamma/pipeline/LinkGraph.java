package com.gamma.pipeline;

import com.gamma.config.io.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A config root resolved once against {@link LinkCensus} (`SPACE-LINK-CENSUS-1` LC-1): one {@link Edge} per link
 * value found, each {@link Status#RESOLVED}, {@link Status#DANGLING} or {@link Status#UNCHECKED}. Read-only; the
 * checks that used to hard-code their keys are queries over it.
 *
 * <p><b>Order.</b> Edges come grouped by file kind; within a kind, file (or component) order, and within one
 * file the census order of its links — so a consumer that filters by kind sees what it used to see.
 *
 * <p><b>Values.</b> An edge's value is the raw text ({@code String.valueOf}, untrimmed); a blank scalar is not a
 * link and yields no edge. A list element yields an edge whenever it is non-null (a blank Reconciliation side is
 * a broken reference, as it always was). An {@link LinkCensus.Type#ID_LIST} value (a Job's comma-list
 * {@code on_pipeline}) yields one edge per member, trimmed, under the same key.
 *
 * <p><b>Status.</b> Only an enforced link to a Dataset, Query, Widget or Pipeline is judged: a component id must
 * name a component of that type exactly; a Pipeline id matches case-insensitively after trimming, and only when
 * the pipeline universe is known. A Pipeline's own links ({@code collector.connection}, {@code collector.dataset},
 * {@code processing.schema_file}) are judged as their loaders resolve them, where the loader reads them
 * ({@link PipelineLinks}; SE-1b). Everything else — stores, paths, columns, signals, and links nothing enforces
 * yet — is {@link Status#UNCHECKED}: stores legitimately exist that no Pipeline owns (job-created ones), so
 * judging them would be a false finding.
 *
 * <p>Module direction: this class sits in {@code inspecto-engine}, below the control plane, so the reserved-path
 * rule ({@code com.gamma.service.ReservedConfigPaths}) is passed in rather than referenced.
 */
public final class LinkGraph {

    private static final Logger log = LoggerFactory.getLogger(LinkGraph.class);

    /** Registry component kinds that hold links, in emission order. */
    private static final List<String> COMPONENT_KINDS = List.of("expectation", "decision-rule", "dataset",
            "alert-rule", "widget", "dashboard", "link-analysis-view", "geo-map-view", "reconciliation", "query");

    /** {@link #NOT_ANALYSED}: a column reference in SQL text the census could not read or attribute (LC-5) - a
     *  gap in the analysis, never a finding. */
    public enum Status { RESOLVED, DANGLING, UNCHECKED, NOT_ANALYSED }

    /**
     * One link found.
     *
     * @param file     the file holding it; {@code null} for an in-memory component
     * @param fromName the holder's id: a component's name, else the file name without {@code .toon}
     * @param key      the concrete key, e.g. {@code references.region_dim.ref} or {@code tiles[2].widgetId}
     * @param toKind   what the value names; for an Expectation / Decision Rule {@code target}, its
     *                 {@code targetType} ({@code pipeline} when absent)
     * @param origins  for a RESOLVED column link, where the column is published ({@code schema:<path>},
     *                 {@code pipeline:<id>}, {@code dataset:<name>} - {@link ColumnSets}); empty otherwise
     */
    public record Edge(LinkCensus.Link link, Path file, String fromName, String key, String value,
                       String toKind, Status status, Set<String> origins) {
        public String fromKind() {
            return link.fromKind();
        }
    }

    private final List<Edge> edges;
    private final Universe universe;

    private LinkGraph(List<Edge> edges, Universe universe) {
        this.edges = List.copyOf(edges);
        this.universe = universe;
    }

    /**
     * The edges one document would hold as a {@code kind} named {@code name}, judged against this graph's
     * universe (`SPACE-LINK-CENSUS-1` LC-3: the save check compares a draft's {@link Status#DANGLING} edges with
     * the stored version's). A Job is read inside its {@code job:} section. {@code file} on each edge is null.
     */
    public List<Edge> edgesOf(String kind, String name, Map<?, ?> doc) {
        List<Edge> out = new ArrayList<>();
        if (doc == null) return out;
        Map<?, ?> d = kind.equals("job") && doc.get("job") instanceof Map<?, ?> inner ? inner : doc;
        emit(kind, null, name, d, universe, out);
        return out;
    }

    public List<Edge> edges() {
        return edges;
    }

    /** The edges of one census link, in graph order. */
    public List<Edge> edges(String fromKind, String keyPath) {
        LinkCensus.Link l = LinkCensus.of(fromKind, keyPath);
        return edges.stream().filter(e -> e.link() == l).toList();
    }

    /** The edges of one census link, in graph order (for a key holding two links: {@link LinkCensus#of(String, String, String)}). */
    public List<Edge> edges(LinkCensus.Link l) {
        return edges.stream().filter(e -> e.link() == l).toList();
    }

    /** The edges held by one kind, in graph order. */
    public List<Edge> from(String fromKind) {
        return edges.stream().filter(e -> e.fromKind().equals(fromKind)).toList();
    }

    /** The lower-cased pipeline ids the config root's pipeline files declare; empty when none (or not known). */
    public Set<String> pipelineIds() {
        return universe.pipelineIds() == null ? Set.of() : Set.copyOf(universe.pipelineIds());
    }

    public List<Edge> dangling() {
        return edges.stream().filter(e -> e.status() == Status.DANGLING).toList();
    }

    /** The column-link edges (LC-5), in graph order. */
    public List<Edge> columnEdges() {
        return edges.stream().filter(e -> e.toKind().equals("column")).toList();
    }

    /**
     * Every resolved column link to {@code column} as the Schema file {@code schemaFile} publishes it - what a
     * rename of that mapped column would break. Matched case-insensitively, as DuckDB binds every consumer.
     */
    public List<Edge> columnConsumers(Path schemaFile, String column) {
        String origin = ColumnSets.schemaOrigin(schemaFile);
        String want = column.trim();
        return edges.stream().filter(e -> e.toKind().equals("column") && e.status() == Status.RESOLVED
                && e.origins().contains(origin) && e.value().trim().equalsIgnoreCase(want)).toList();
    }

    // ── building ────────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a config root: every {@code *_pipeline.toon}, {@code *_enrich.toon} and {@code *_job.toon} in the
     * {@link #configFiles} set, the root's {@code nav-menus.toon}, and the registry under {@code registry/}. The
     * pipeline universe is the ids those pipeline files declare. Best-effort per file: an unreadable one is
     * logged and skipped. An absent root is an empty graph.
     */
    public static LinkGraph of(Path configRoot, Predicate<String> reserved) {
        if (configRoot == null || !Files.isDirectory(configRoot))
            return new LinkGraph(List.of(), Universe.of(Map.of(), null, null, null));
        Map<String, List<ComponentRegistry.Component>> byType = new LinkedHashMap<>();
        ComponentRegistry registry = ComponentRegistry.scan(configRoot.resolve("registry"));
        for (String t : List.of("dataset", "query", "widget")) byType.put(t, registry.ofType(t));
        for (String t : COMPONENT_KINDS) byType.put(t, registry.ofType(t));

        List<Path> pipelineFiles = configFiles(configRoot, "_pipeline.toon", reserved);
        Set<String> pipelineIds = new HashSet<>();
        List<Map<String, Object>> pipelineDocs = new ArrayList<>();
        for (Path p : pipelineFiles) {
            Map<String, Object> raw = decode(p);
            pipelineDocs.add(raw);
            if (raw == null) continue;
            String id = blank(raw.get("id")) ? null : String.valueOf(raw.get("id")).trim();
            if (id == null && !blank(raw.get("name")))
                id = String.valueOf(raw.get("name")).trim().toLowerCase().replace(' ', '_');
            if (id != null) pipelineIds.add(id.toLowerCase());
        }

        Universe u = Universe.of(byType, pipelineIds,
                new ColumnSets(pipelineFiles, pipelineDocs, byType.getOrDefault("dataset", List.of())),
                PipelineLinks.connectionIds(configRoot));
        List<Edge> out = new ArrayList<>();
        for (int i = 0; i < pipelineFiles.size(); i++)
            if (pipelineDocs.get(i) != null) fileEdges("pipeline", pipelineFiles.get(i), pipelineDocs.get(i), u, out);
        for (Path p : configFiles(configRoot, "_enrich.toon", reserved)) {
            Map<String, Object> raw = decode(p);
            if (raw != null) fileEdges("enrichment", p, raw, u, out);
        }
        for (Path p : configFiles(configRoot, "_job.toon", reserved)) {
            Map<String, Object> raw = decode(p);
            if (raw != null) fileEdges("job", p, jobSection(raw), u, out);
        }
        componentEdges(byType, u, out);
        Path nav = configRoot.resolve("nav-menus.toon");
        if (Files.isRegularFile(nav)) {
            Map<String, Object> raw = decode(nav);
            if (raw != null) fileEdges("nav-menu", nav, raw, u, out);
        }
        return new LinkGraph(out, u);
    }

    /**
     * Resolve an in-memory component universe (the live registry, or registry ∪ an incoming bundle); missing
     * type keys are empty. {@code pipelineIds} is the registered-id universe, or {@code null} when unknown
     * (pipeline links are then {@link Status#UNCHECKED}). No Schema is read here, so every column link is
     * {@link Status#UNCHECKED} (its raw value, as before LC-5) - the bundle import pre-check is unchanged.
     */
    public static LinkGraph ofComponents(Map<String, List<ComponentRegistry.Component>> byType, Set<String> pipelineIds) {
        Set<String> ids = null;
        if (pipelineIds != null) {
            ids = new HashSet<>();
            for (String p : pipelineIds) ids.add(p.toLowerCase());
        }
        List<Edge> out = new ArrayList<>();
        Universe u = Universe.of(byType, ids, null, null);
        componentEdges(byType, u, out);
        return new LinkGraph(out, u);
    }

    /** {@code columns} is {@code null} when column links are not judged ({@link #ofComponents}); {@code connectionIds}
     *  (the ids the server registers from {@code *_connection.toon}) is {@code null} when not known. */
    private record Universe(Map<String, Set<String>> names, Set<String> pipelineIds, ColumnSets columns,
                            Set<String> connectionIds) {
        static Universe of(Map<String, List<ComponentRegistry.Component>> byType, Set<String> pipelineIds,
                           ColumnSets columns, Set<String> connectionIds) {
            Map<String, Set<String>> n = new HashMap<>();
            for (Map.Entry<String, List<ComponentRegistry.Component>> e : byType.entrySet())
                for (ComponentRegistry.Component c : e.getValue())
                    n.computeIfAbsent(e.getKey(), k -> new HashSet<>()).add(c.name());
            return new Universe(n, pipelineIds, columns, connectionIds);
        }

        boolean hasComponent(String type, String name) {
            return names.getOrDefault(type, Set.of()).contains(name);
        }
    }

    private static void componentEdges(Map<String, List<ComponentRegistry.Component>> byType, Universe u, List<Edge> out) {
        for (String kind : COMPONENT_KINDS)
            for (ComponentRegistry.Component c : byType.getOrDefault(kind, List.of()))
                emit(kind, c.path(), c.name(), c.content(), u, out);
    }

    private static void fileEdges(String kind, Path file, Map<?, ?> doc, Universe u, List<Edge> out) {
        emit(kind, file, stem(file), doc, u, out);
    }

    private static void emit(String kind, Path file, String name, Map<?, ?> doc, Universe u, List<Edge> out) {
        for (LinkCensus.Link l : LinkCensus.from(kind)) {
            // A v2 Reconciliation lists its sides in `datasets`; left/right/third are then not read (as before).
            if (kind.equals("reconciliation") && (l.keyPath().equals("leftDataset") || l.keyPath().equals("rightDataset")
                    || l.keyPath().equals("thirdDataset")) && doc.get("datasets") instanceof List<?>) continue;
            List<String[]> found = new ArrayList<>();
            walk(doc, l.keyPath().split("\\."), 0, "", found);
            if (l.type() == LinkCensus.Type.ID_LIST) found = members(found);
            if (u.columns() != null && l.toKind().equals("column")) {
                columnEdges(kind, file, name, doc, l, found, u.columns(), out);
                continue;
            }
            for (String[] kv : found) {
                String toKind = l.toKind();
                if (l.keyPath().equals("target") && (kind.equals("expectation") || kind.equals("decision-rule"))) {
                    // getOrDefault, as every reader of targetType does: an explicit null is not "pipeline"
                    String tt = String.valueOf(doc.containsKey("targetType") ? doc.get("targetType") : "pipeline");
                    toKind = "pipeline".equalsIgnoreCase(tt) ? "pipeline" : tt;
                }
                LinkGraph.Status st = kind.equals("pipeline") ? pipelineStatus(l, file, doc, kv[1], u)
                        : status(l, toKind, kv[1], u);
                out.add(new Edge(l, file, name, kv[0], kv[1], toKind, st, Set.of()));
            }
        }
    }

    /** LC-5: a COLUMN value judged against the holder's set(s); a SQL text expanded into one edge per column it
     *  references ({@link ColumnSets#sql}). */
    private static void columnEdges(String kind, Path file, String name, Map<?, ?> doc, LinkCensus.Link l,
                                    List<String[]> found, ColumnSets cols, List<Edge> out) {
        if (l.type() == LinkCensus.Type.SQL_COLUMN) {
            boolean expr = l.keyPath().equals("calculated[].expr");
            for (String[] kv : found)
                for (ColumnSets.Judged j : ColumnSets.sql(kv[0], kv[1],
                        expr ? rel -> cols.dataset(name) : cols.relationsFor(kind, name, doc),
                        expr ? "__dataset" : null))
                    out.add(new Edge(l, file, name, j.key(), j.value(), l.toKind(), j.status(), j.origins()));
            return;
        }
        List<ColumnSets.Cols> sets = cols.setsFor(kind, l.keyPath(), name, doc);
        for (String[] kv : found) {
            Set<String> origins = new java.util.LinkedHashSet<>();
            Status st = ColumnSets.judge(l, kv[1], sets, origins);
            out.add(new Edge(l, file, name, kv[0], kv[1], l.toKind(), st, Set.copyOf(origins)));
        }
    }

    /** A Pipeline's own links, judged as their loaders resolve them ({@link PipelineLinks}); UNCHECKED where they
     *  cannot be judged without guessing. */
    private static Status pipelineStatus(LinkCensus.Link l, Path file, Map<?, ?> doc, String value, Universe u) {
        if (!l.enforced()) return Status.UNCHECKED;
        Status s = PipelineLinks.status(l, file, doc, value, id -> u.hasComponent("dataset", id), u.connectionIds());
        return s == null ? Status.UNCHECKED : s;
    }

    private static Status status(LinkCensus.Link l, String toKind, String value, Universe u) {
        // A rule target of another targetType (job, dataset, ...) is not the link the checks enforce.
        if (!l.enforced() || !toKind.equals(l.toKind())) return Status.UNCHECKED;
        return switch (toKind) {
            case "dataset", "query", "widget" ->
                    u.hasComponent(toKind, value) ? Status.RESOLVED : Status.DANGLING;
            case "pipeline" -> u.pipelineIds() == null ? Status.UNCHECKED
                    : u.pipelineIds().contains(l.named(value).toLowerCase()) ? Status.RESOLVED : Status.DANGLING;
            default -> Status.UNCHECKED;
        };
    }

    /** Collect {@code [concreteKey, value]} for {@code segs[i..]} under {@code node}. */
    private static void walk(Object node, String[] segs, int i, String at, List<String[]> found) {
        if (i == segs.length) {
            // A scalar path takes the value's TEXT whatever its shape — a list or map included, as every check
            // always read it (String.valueOf / toString); only a `[]` segment splits a list.
            if (!blank(node)) found.add(new String[]{at, String.valueOf(node)});
            return;
        }
        String seg = segs[i];
        if (seg.equals("**")) {
            descend(node, segs, i + 1, at, found);
            return;
        }
        if (!(node instanceof Map<?, ?> map)) return;
        if (seg.equals("*")) {
            for (Map.Entry<?, ?> e : map.entrySet())
                walk(e.getValue(), segs, i + 1, join(at, String.valueOf(e.getKey())), found);
            return;
        }
        if (seg.equals("*[]")) {
            // each element of each list value (a Widget's controls.<slot>[].field)
            for (Map.Entry<?, ?> e : map.entrySet())
                if (e.getValue() instanceof List<?> list)
                    for (int k = 0; k < list.size(); k++)
                        walk(list.get(k), segs, i + 1, join(at, String.valueOf(e.getKey())) + "[" + k + "]", found);
            return;
        }
        boolean each = seg.endsWith("[]");
        String key = each ? seg.substring(0, seg.length() - 2) : seg;
        Object v = map.get(key);
        if (!each) {
            walk(v, segs, i + 1, join(at, key), found);
        } else if (v instanceof List<?> list) {
            if (i + 1 == segs.length) {
                // each non-null element is a link, a blank one included (a blank Reconciliation side dangles)
                for (int k = 0; k < list.size(); k++)
                    if (list.get(k) != null)
                        found.add(new String[]{join(at, key) + "[" + k + "]", String.valueOf(list.get(k))});
            } else {
                for (int k = 0; k < list.size(); k++)
                    walk(list.get(k), segs, i + 1, join(at, key) + "[" + k + "]", found);
            }
        }
    }

    /** An {@link LinkCensus.Type#ID_LIST} value is one edge per member: split on commas, trimmed, blanks dropped
     *  ({@code JobConfig.onPipelines}). */
    private static List<String[]> members(List<String[]> found) {
        List<String[]> out = new ArrayList<>();
        for (String[] kv : found)
            for (String m : kv[1].split(","))
                if (!m.isBlank()) out.add(new String[]{kv[0], m.trim()});
        return out;
    }

    /** {@code **}: apply the rest of the path at this node and at every map / list node below it. */
    private static void descend(Object node, String[] segs, int i, String at, List<String[]> found) {
        if (node instanceof Map<?, ?> map) {
            walk(map, segs, i, at, found);
            for (Map.Entry<?, ?> e : map.entrySet())
                if (e.getValue() instanceof Map<?, ?> || e.getValue() instanceof List<?>)
                    descend(e.getValue(), segs, i, join(at, String.valueOf(e.getKey())), found);
        } else if (node instanceof List<?> list) {
            for (int k = 0; k < list.size(); k++) descend(list.get(k), segs, i, at + "[" + k + "]", found);
        }
    }

    private static String join(String at, String key) {
        return at.isEmpty() ? key : at + "." + key;
    }

    private static boolean blank(Object v) {
        return v == null || v.toString().isBlank();
    }

    static Map<String, Object> decodeQuietly(Path p) {
        return decode(p);
    }

    private static Map<String, Object> decode(Path p) {
        try {
            return ConfigLoader.filesystem().decode(p.toString());
        } catch (Exception ex) {
            log.warn("[LINKS] skipping unreadable {}: {}", p, ex.getMessage());
            return null;
        }
    }

    // ── shared helpers (moved down from com.gamma.service.PipelineDependents) ───────────────

    /**
     * Every live config under {@code root} whose name ends with {@code suffix}, at any depth — the loader walks the
     * whole tree, so a file in a sub-folder is as live as one at the root. Skips what {@code reserved} names
     * (given a root-relative path): {@code .history/} copies and the server-written state dirs. Sorted; empty
     * when the root is absent. One unreadable directory, or a file deleted mid-walk, does not empty the answer.
     */
    public static List<Path> configFiles(Path root, String suffix, Predicate<String> reserved) {
        if (root == null || !Files.isDirectory(root)) return List.of();
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    return !dir.equals(root) && reserved.test(root.relativize(dir).toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (a.isRegularFile() && f.getFileName().toString().endsWith(suffix)
                            && !reserved.test(root.relativize(f).toString())) out.add(f);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path f, IOException ex) {
                    log.warn("[LINKS] skipping unreadable {}: {}", f, ex.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException ex) {
            log.warn("[LINKS] could not finish listing {} under {}: {}", suffix, root, ex.getMessage());
        }
        out.sort(Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /** The map holding a Job's keys: the {@code job:} section {@code JobConfig} loads, else the document itself. */
    public static Map<?, ?> jobSection(Map<String, Object> raw) {
        return raw.get("job") instanceof Map<?, ?> inner ? inner : raw;
    }

    /** A config file's id: its file name with {@code .toon} dropped. */
    public static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".toon") ? n.substring(0, n.length() - ".toon".length()) : n;
    }
}
