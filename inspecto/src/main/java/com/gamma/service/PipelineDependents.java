package com.gamma.service;

import com.gamma.config.io.ConfigLoader;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only reverse-dependency scan for a pipeline (Catalog-lifecycle: "no dependent check on origin
 * delete"). Answers <em>what breaks if this origin goes away</em> over the same by-name binding set the
 * rename path rewrites, plus the two transitive Studio hops a rename never needed.
 *
 * <p><b>Why this is not an extraction of {@code PipelineRoutes.rewriteDependents}.</b> Those five
 * scanners read <em>and</em> write in one loop body; a read-only variant cannot share it without
 * reshaping a shipped rename path. The key set is deliberately kept identical — <b>if a binding is
 * added there, add it here</b> — but the code stays separate on purpose.
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
 * question it could still mostly answer — an unreadable file is logged and skipped, exactly as the
 * rename path treats one.
 */
public final class PipelineDependents {

    private static final Logger log = LoggerFactory.getLogger(PipelineDependents.class);

    /** Hard cap on reported dependents, so a diagnostic read can never become an unbounded export. */
    public static final int MAX_DEPENDENTS = 200;

    private PipelineDependents() {
    }

    /**
     * One thing that references the pipeline.
     *
     * @param kind what holds the reference ({@code enrichment} / {@code job} / {@code expectation} /
     *             {@code decision-rule} / {@code dataset} / {@code widget} / {@code dashboard})
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
        enrichments(writeRoot, id, all);
        jobs(writeRoot, id, all);

        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        componentTargets(store, "expectation", id, all);
        componentTargets(store, "decision-rule", id, all);
        Set<String> datasets = datasets(store, id, all);
        Set<String> widgets = widgets(store, datasets, all);
        dashboards(store, widgets, all);

        int cap = Math.max(0, Math.min(limit, MAX_DEPENDENTS));
        int total = all.size();
        return new Report(id, List.copyOf(all.subList(0, Math.min(cap, total))), total, total > cap);
    }

    /** {@code triggers.on_pipeline} and by-name {@code references.<n>.ref} in every {@code *_enrich.toon}. */
    private static void enrichments(Path writeRoot, String id, List<Dependent> out) {
        for (Path p : listing(writeRoot, "_enrich.toon")) {
            String name = stem(p);
            try {
                Map<String, Object> raw = ConfigLoader.filesystem().decode(p.toString());
                if (raw.get("triggers") instanceof Map<?, ?> t
                        && id.equalsIgnoreCase(String.valueOf(t.get("on_pipeline")).trim())) {
                    out.add(new Dependent("enrichment", name, "triggers.on_pipeline"));
                }
                // A by-name reference resolves against the pipeline registry at RUN time
                // (ReferenceReader), so a deleted origin surfaces as a job failure, not a load error —
                // which is exactly why it has to be caught here instead.
                if (raw.get("references") instanceof Map<?, ?> refs) {
                    for (Map.Entry<?, ?> e : refs.entrySet()) {
                        if (!(e.getValue() instanceof Map<?, ?> rv)) continue;
                        Object ref = rv.get("ref");
                        if (ref != null && id.equalsIgnoreCase(String.valueOf(ref).trim())) {
                            out.add(new Dependent("enrichment", name, "references." + e.getKey() + ".ref"));
                        }
                    }
                }
            } catch (Exception ex) {
                log.warn("[IMPACT] skipping unreadable enrichment {}: {}", p, ex.getMessage());
            }
        }
    }

    /** Top-level {@code on_pipeline} in every {@code jobs/*_job.toon}. */
    private static void jobs(Path writeRoot, String id, List<Dependent> out) {
        for (Path p : listing(writeRoot.resolve("jobs"), "_job.toon")) {
            try {
                Map<String, Object> raw = ConfigLoader.filesystem().decode(p.toString());
                if (id.equalsIgnoreCase(String.valueOf(raw.get("on_pipeline")).trim())) {
                    out.add(new Dependent("job", stem(p), "on_pipeline"));
                }
            } catch (Exception ex) {
                log.warn("[IMPACT] skipping unreadable job {}: {}", p, ex.getMessage());
            }
        }
    }

    /** {@code target} on every expectation / decision-rule whose {@code targetType} is {@code pipeline}. */
    private static void componentTargets(ComponentStore store, String type, String id, List<Dependent> out) {
        for (ComponentRegistry.Component c : store.list(type)) {
            Map<String, Object> content = c.content();
            String targetType = String.valueOf(content.getOrDefault("targetType", "pipeline"));
            if (!"pipeline".equalsIgnoreCase(targetType)) continue;
            Object target = content.get("target");
            if (target != null && id.equalsIgnoreCase(String.valueOf(target).trim())) {
                out.add(new Dependent(type, c.name(), "target"));
            }
        }
    }

    /** Datasets reading this origin's store — by {@code sourceName} or {@code physicalRef}'s head segment. */
    private static Set<String> datasets(ComponentStore store, String id, List<Dependent> out) {
        Set<String> hit = new LinkedHashSet<>();
        for (ComponentRegistry.Component c : store.list("dataset")) {
            Map<String, Object> content = c.content();

            Object sn = content.get("sourceName");
            if (sn != null && id.equalsIgnoreCase(String.valueOf(sn).trim())) {
                out.add(new Dependent("dataset", c.name(), "sourceName"));
                hit.add(c.name());
                continue;
            }

            Object ref = content.get("physicalRef");
            String refStr = ref == null ? "" : String.valueOf(ref).trim();
            if (refStr.isEmpty() || "null".equals(refStr)) continue;
            int slash = refStr.indexOf('/');
            String head = slash < 0 ? refStr : refStr.substring(0, slash);
            if (head.equalsIgnoreCase(id)) {
                out.add(new Dependent("dataset", c.name(), "physicalRef"));
                hit.add(c.name());
            }
        }
        return hit;
    }

    /** Widgets bound to any of the affected datasets ({@code datasetId}) — the first transitive hop. */
    private static Set<String> widgets(ComponentStore store, Set<String> datasets, List<Dependent> out) {
        Set<String> hit = new LinkedHashSet<>();
        if (datasets.isEmpty()) return hit;
        for (ComponentRegistry.Component c : store.list("widget")) {
            Object dsId = c.content().get("datasetId");
            if (dsId == null) continue;
            if (datasets.contains(String.valueOf(dsId).trim())) {
                out.add(new Dependent("widget", c.name(), "datasetId"));
                hit.add(c.name());
            }
        }
        return hit;
    }

    /** Dashboards with a tile on any affected widget ({@code tiles[].widgetId}) — the second hop. */
    private static void dashboards(ComponentStore store, Set<String> widgets, List<Dependent> out) {
        if (widgets.isEmpty()) return;
        for (ComponentRegistry.Component c : store.list("dashboard")) {
            if (!(c.content().get("tiles") instanceof List<?> tiles)) continue;
            for (Object tile : tiles) {
                if (!(tile instanceof Map<?, ?> t)) continue;
                Object wid = t.get("widgetId");
                if (wid != null && widgets.contains(String.valueOf(wid).trim())) {
                    out.add(new Dependent("dashboard", c.name(), "tiles[].widgetId"));
                    break;   // one dashboard is one dependent, however many of its tiles break
                }
            }
        }
    }

    /** Files directly under {@code dir} whose name ends with {@code suffix}; empty when the dir is absent. */
    private static List<Path> listing(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(suffix)).sorted().toList();
        } catch (IOException ex) {
            log.warn("[IMPACT] could not list {}: {}", dir, ex.getMessage());
            return List.of();
        }
    }

    /** A config file's id: its filename with the {@code .toon} extension dropped. */
    private static String stem(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".toon") ? n.substring(0, n.length() - ".toon".length()) : n;
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
