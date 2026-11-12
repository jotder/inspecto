package com.gamma.service;

import com.gamma.pipeline.LinkGraph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only reverse-dependency scan for a pipeline (Catalog-lifecycle: "no dependent check on origin
 * delete"). Answers <em>what breaks if this origin goes away</em> over the by-name binding set the
 * rename path rewrites, plus the two transitive Studio hops a rename never needed.
 *
 * <p>The keys are not kept here: they are the {@link com.gamma.pipeline.LinkCensus} links, read from one
 * {@link LinkGraph} of the write root (`SPACE-LINK-CENSUS-1` LC-1). The rename path
 * ({@code PipelineRenameRoutes.rewriteDependents}) still keeps its own copy until LC-2 —
 * {@code LinkCensusParityTest} fails when it reads a key the census does not declare.
 *
 * <p>Matching mirrors each consumer exactly: enrichment triggers and job triggers compare the whole
 * value case-insensitively, rule targets honour {@code targetType} defaulting to {@code pipeline}
 * (as {@code DataSourceBundleResolver.ruleTargets} does), and a dataset matches on {@code sourceName}
 * or on the <em>first path segment</em> of {@code physicalRef} (as {@code datasetReadsStore} does).
 *
 * <p>The transitive hops are the chain that actually dangles in the UI: origin → dataset
 * {@code physicalRef} → widget {@code datasetId} → dashboard tile {@code widgetId}. They are reported
 * as their own kinds so a caller can tell a direct break from a downstream one.
 *
 * <p>Best-effort per file: one malformed sibling must not make an impact read fail closed on a
 * question it could still mostly answer — an unreadable file is logged and skipped by the graph.
 */
public final class PipelineDependents {

    /** Hard cap on reported dependents, so a diagnostic read can never become an unbounded export. */
    public static final int MAX_DEPENDENTS = 200;

    private PipelineDependents() {
    }

    /**
     * One thing that references the pipeline.
     *
     * @param kind what holds the reference ({@code enrichment} / {@code job} / {@code expectation} /
     *             {@code decision-rule} / {@code dataset} / {@code alert-rule} / {@code widget} / {@code dashboard})
     * @param name the dependent's own id
     * @param via  the key that carries the reference, e.g. {@code triggers.on_pipeline}
     */
    public record Dependent(String kind, String name, String via) {
    }

    /**
     * @param pipeline  the id that was scanned for
     * @param dependents up to {@code limit} dependents
     * @param total     the TRUE count, even when {@code truncated}
     * @param truncated whether {@code dependents} is short of {@code total}
     */
    public record Report(String pipeline, List<Dependent> dependents, int total, boolean truncated) {
        public boolean isEmpty() {
            return total == 0;
        }

        /** Compact {@code kind/name} list for a 409 message — bounded by what the report already holds. */
        public String summary() {
            String joined = dependents.stream()
                    .map(d -> d.kind() + "/" + d.name()).collect(Collectors.joining(", "));
            return truncated ? joined + ", … (" + total + " total)" : joined;
        }
    }

    /** Scan with the default cap. */
    public static Report scan(Path writeRoot, String pipelineId) {
        return scan(writeRoot, pipelineId, MAX_DEPENDENTS);
    }

    /**
     * Every config under {@code writeRoot} that references {@code pipelineId}, direct hops first.
     * A blank id matches nothing — never treat "no id" as "matches everything".
     */
    public static Report scan(Path writeRoot, String pipelineId, int limit) {
        String id = pipelineId == null ? "" : pipelineId.trim();
        if (id.isEmpty() || writeRoot == null || !Files.isDirectory(writeRoot)) {
            return new Report(id, List.of(), 0, false);
        }

        List<Dependent> all = new ArrayList<>();
        LinkGraph graph = LinkGraph.of(writeRoot, ReservedConfigPaths::reserved);
        enrichments(graph, id, all);
        jobs(graph, id, all);
        componentTargets(graph, "expectation", id, all);
        componentTargets(graph, "decision-rule", id, all);
        Set<String> datasets = datasets(graph, id, all);
        alertRules(graph, id, datasets, all);
        Set<String> widgets = widgets(graph, datasets, all);
        dashboards(graph, widgets, all);

        int cap = Math.max(0, Math.min(limit, MAX_DEPENDENTS));
        int total = all.size();
        return new Report(id, List.copyOf(all.subList(0, Math.min(cap, total))), total, total > cap);
    }

    private static boolean names(LinkGraph.Edge e, String id) {
        return id.equalsIgnoreCase(e.value().trim());
    }

    /** {@code triggers.on_pipeline} and by-name {@code references.<n>.ref} in every {@code *_enrich.toon}. */
    private static void enrichments(LinkGraph graph, String id, List<Dependent> out) {
        // A by-name reference resolves against the pipeline registry at RUN time (ReferenceReader), so a
        // deleted origin surfaces as a job failure, not a load error — which is exactly why it is caught here.
        for (LinkGraph.Edge e : graph.from("enrichment")) {
            boolean trigger = e.link().keyPath().equals("triggers.on_pipeline");
            if ((trigger || e.link().keyPath().equals("references.*.ref")) && names(e, id))
                out.add(new Dependent("enrichment", e.fromName(), e.key()));
        }
    }

    /** {@code on_pipeline} in every {@code *_job.toon} — under {@code job:} (what the loader reads) or at the top level. */
    private static void jobs(LinkGraph graph, String id, List<Dependent> out) {
        for (LinkGraph.Edge e : graph.edges("job", "on_pipeline"))
            if (names(e, id)) out.add(new Dependent("job", e.fromName(), "on_pipeline"));
    }

    /** {@code target} on every expectation / decision-rule whose {@code targetType} is {@code pipeline}. */
    private static void componentTargets(LinkGraph graph, String type, String id, List<Dependent> out) {
        for (LinkGraph.Edge e : graph.edges(type, "target"))
            if (e.toKind().equals("pipeline") && names(e, id)) out.add(new Dependent(type, e.fromName(), "target"));
    }

    /** Datasets reading this origin's store — by {@code sourceName} or {@code physicalRef}'s head segment. */
    private static Set<String> datasets(LinkGraph graph, String id, List<Dependent> out) {
        Set<String> hit = new LinkedHashSet<>();
        for (LinkGraph.Edge e : graph.from("dataset")) {
            if (hit.contains(e.fromName())) continue;   // sourceName matched first: one dataset is one dependent
            String via = e.link().keyPath();
            boolean match;
            if (via.equals("sourceName")) {
                match = names(e, id);
            } else if (via.equals("physicalRef")) {
                String ref = e.value().trim();
                if ("null".equals(ref)) continue;
                int slash = ref.indexOf('/');
                match = (slash < 0 ? ref : ref.substring(0, slash)).equalsIgnoreCase(id);
            } else {
                continue;
            }
            if (match) {
                out.add(new Dependent("dataset", e.fromName(), via));
                hit.add(e.fromName());
            }
        }
        return hit;
    }

    /**
     * Alert Rules watching this pipeline by {@code onPipeline} (case-insensitively, a space read as
     * {@code _}, as {@code AlertService} matches it) or one of the affected datasets by {@code dataset}.
     * A rule naming neither watches every pipeline and belongs to the space, not to this one.
     */
    private static void alertRules(LinkGraph graph, String id, Set<String> datasets, List<Dependent> out) {
        // Sorted by name (a stable sort, so a rule's onPipeline stays before its dataset): the store lists in
        // directory order, which is not stable across platforms.
        List<LinkGraph.Edge> edges = new ArrayList<>(graph.from("alert-rule"));
        edges.sort(Comparator.comparing(LinkGraph.Edge::fromName));
        Set<String> hit = new LinkedHashSet<>();
        for (LinkGraph.Edge e : edges) {
            if (hit.contains(e.fromName())) continue;
            String v = e.value().trim();
            boolean match = switch (e.link().keyPath()) {
                case "onPipeline" -> v.equalsIgnoreCase(id) || v.replace(' ', '_').equalsIgnoreCase(id);
                case "dataset" -> datasets.stream().anyMatch(v::equalsIgnoreCase);
                default -> false;
            };
            if (match) {
                out.add(new Dependent("alert-rule", e.fromName(), e.link().keyPath()));
                hit.add(e.fromName());
            }
        }
    }

    /** Widgets bound to any of the affected datasets ({@code datasetId}) — the first transitive hop. */
    private static Set<String> widgets(LinkGraph graph, Set<String> datasets, List<Dependent> out) {
        Set<String> hit = new LinkedHashSet<>();
        if (datasets.isEmpty()) return hit;
        for (LinkGraph.Edge e : graph.edges("widget", "datasetId")) {
            if (datasets.contains(e.value().trim())) {
                out.add(new Dependent("widget", e.fromName(), "datasetId"));
                hit.add(e.fromName());
            }
        }
        return hit;
    }

    /** Dashboards with a tile on any affected widget ({@code tiles[].widgetId}) — the second hop. */
    private static void dashboards(LinkGraph graph, Set<String> widgets, List<Dependent> out) {
        if (widgets.isEmpty()) return;
        Set<String> hit = new LinkedHashSet<>();
        for (LinkGraph.Edge e : graph.edges("dashboard", "tiles[].widgetId")) {
            // one dashboard is one dependent, however many of its tiles break
            if (!hit.contains(e.fromName()) && widgets.contains(e.value().trim())) {
                out.add(new Dependent("dashboard", e.fromName(), "tiles[].widgetId"));
                hit.add(e.fromName());
            }
        }
    }

    /**
     * Every live config under {@code writeRoot} whose name ends with {@code suffix}, at any depth — the loader
     * ({@code ServiceBootstrap.resolveBySuffix}) walks the whole tree, so a dependent in a sub-folder
     * ({@code orders/orders_daily_enrich.toon}) is as live as one at the root. Skips {@code .history/} copies and
     * the server-written state dirs ({@link ReservedConfigPaths#reserved}). The file set {@link LinkGraph} reads;
     * shared with the rename path ({@code PipelineRenameRoutes}). Empty when the root is absent.
     */
    public static List<Path> configFiles(Path writeRoot, String suffix) {
        return LinkGraph.configFiles(writeRoot, suffix, ReservedConfigPaths::reserved);
    }

    /** The map holding a Job's keys: the {@code job:} section {@code JobConfig} loads, else the document itself. */
    public static Map<?, ?> jobSection(Map<String, Object> raw) {
        return LinkGraph.jobSection(raw);
    }

    /** The report as the route's JSON body — grouped by kind, so a UI can render sections directly. */
    public static Map<String, Object> toJson(Report report) {
        Map<String, List<Map<String, String>>> byKind = new LinkedHashMap<>();
        for (Dependent d : report.dependents()) {
            byKind.computeIfAbsent(d.kind(), k -> new ArrayList<>())
                    .add(Map.of("name", d.name(), "via", d.via()));
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("pipeline", report.pipeline());
        r.put("total", report.total());
        r.put("truncated", report.truncated());
        r.put("dependents", byKind);
        return r;
    }
}
