package com.gamma.pipeline;

import java.util.List;
import java.util.Set;

/**
 * Every link between the config files of a Space, declared once (`SPACE-LINK-CENSUS-1` phase LC-1). A link is a
 * key in one file whose value names something defined elsewhere — an id, a file path, a store or a column. The
 * census is a pure declaration; {@link LinkGraph} resolves a config root against it, and the read-only checks
 * (delete impact, {@link ComponentIntegrity}, the bundle import pre-check) query that graph.
 *
 * <p>{@link Link#checks()} records which existing check reads the link today; an empty set means nothing checks
 * it yet ({@link Link#enforced()} is false) — it is declared so the census is the complete list
 * (Space anatomy §12.2). {@code onDelete} / {@code onRename} state the intended policy; the Pipeline rename
 * enforces {@code onRename} for the links {@link #renamedWithPipeline()} names (LC-2); nothing enforces
 * {@code onDelete} yet.
 *
 * <p><b>Key paths</b> are read against the document root of the {@code fromKind} (for a Job, its {@code job:}
 * section when present — {@link LinkGraph#jobSection}): {@code a.b} nested maps, {@code a[]} each list element,
 * {@code *} each value of a map, {@code **} the node and every node below it.
 */
public final class LinkCensus {

    private LinkCensus() {}

    /** What the value of a link is. */
    public enum Type { ID, ID_LIST, PATH, STORE, COLUMN, SQL_RELATION, TEXT }

    /** Intended behaviour when the target is deleted. */
    public enum OnDelete { REFUSE, WARN }

    /** Intended behaviour when the target is renamed. */
    public enum OnRename { REWRITE, REPORT }

    /** The existing checks that read a link (as of LC-1). */
    public enum Check { LOAD, IMPACT, VALIDATE, IMPORT, RENAME, DELETE }

    /**
     * One link.
     *
     * @param fromKind the kind of file holding the key ({@code enrichment}, {@code job}, {@code pipeline},
     *                 {@code nav-menu}, or a component type)
     * @param keyPath  where the value sits in that file (see the class comment)
     * @param toKind   what the value names
     * @param checks   which existing checks read it; empty = not yet enforced
     */
    public record Link(String fromKind, String keyPath, String toKind, Type type,
                       OnDelete onDelete, OnRename onRename, Set<Check> checks) {
        public boolean enforced() {
            return !checks.isEmpty();
        }
    }

    private static Link link(String from, String key, String to, Type type, OnDelete d, OnRename r, Check... checks) {
        return new Link(from, key, to, type, d, r, Set.of(checks));
    }

    private static final OnDelete REFUSE = OnDelete.REFUSE, WARN = OnDelete.WARN;
    private static final OnRename REWRITE = OnRename.REWRITE, REPORT = OnRename.REPORT;
    private static final Check LOAD = Check.LOAD, IMPACT = Check.IMPACT, VALIDATE = Check.VALIDATE,
            IMPORT = Check.IMPORT, RENAME = Check.RENAME, DELETE = Check.DELETE;

    /**
     * The census. Within one {@code fromKind}, {@link LinkGraph} emits a component's edges in this order — the
     * order the checks have always reported them in (a widget's {@code datasetId} before its {@code queryId}, a
     * Dataset's {@code sourceName} before its {@code physicalRef}, an Alert Rule's {@code onPipeline} before its
     * {@code dataset}).
     */
    public static final List<Link> LINKS = List.of(
            // ── Pipeline → elsewhere ─────────────────────────────────────────────────────────────
            link("pipeline", "processing.schema_file", "schema-file", Type.PATH, REFUSE, REWRITE, LOAD),
            link("pipeline", "**.use", "component", Type.ID, REFUSE, REWRITE, DELETE),
            link("pipeline", "collector.connection", "connection", Type.ID, REFUSE, REWRITE, DELETE),
            link("pipeline", "collector.dataset", "dataset", Type.ID, REFUSE, REWRITE),
            // ── Enrichment ───────────────────────────────────────────────────────────────────────
            link("enrichment", "triggers.on_pipeline", "pipeline", Type.ID, REFUSE, REWRITE, RENAME, IMPACT, VALIDATE),
            link("enrichment", "references.*.ref", "pipeline", Type.ID, REFUSE, REWRITE, RENAME, IMPACT, VALIDATE),
            link("enrichment", "input.database", "store", Type.PATH, WARN, REPORT),
            link("enrichment", "references.*.path", "file", Type.PATH, WARN, REPORT),
            link("enrichment", "transform", "column", Type.SQL_RELATION, WARN, REPORT),
            // ── Job (keys read inside the job: section) ──────────────────────────────────────────
            link("job", "on_pipeline", "pipeline", Type.ID_LIST, REFUSE, REWRITE, RENAME, IMPACT),
            link("job", "template", "job-template", Type.ID, REFUSE, REPORT, LOAD),
            link("job", "pipeline_config", "pipeline-file", Type.PATH, REFUSE, REWRITE),
            link("job", "dataset", "dataset", Type.ID, REFUSE, REWRITE),
            link("job", "target", "dataset", Type.ID, REFUSE, REWRITE),
            link("job", "dir", "directory", Type.PATH, WARN, REPORT),
            link("job", "on_signal", "signal", Type.TEXT, WARN, REPORT),
            link("job", "when", "signal", Type.TEXT, WARN, REPORT),
            // ── Registry components ──────────────────────────────────────────────────────────────
            link("expectation", "target", "pipeline", Type.ID, REFUSE, REWRITE, RENAME, IMPACT, VALIDATE),
            link("expectation", "column", "column", Type.COLUMN, WARN, REPORT),
            link("decision-rule", "target", "pipeline", Type.ID, REFUSE, REWRITE, RENAME, IMPACT, VALIDATE),
            link("decision-rule", "field", "column", Type.COLUMN, WARN, REPORT),
            link("dataset", "sourceName", "store", Type.STORE, REFUSE, REWRITE, RENAME, IMPACT),
            link("dataset", "physicalRef", "store", Type.STORE, REFUSE, REWRITE, RENAME, IMPACT, VALIDATE),
            link("dataset", "sql", "store", Type.SQL_RELATION, REFUSE, REWRITE, RENAME),
            link("dataset", "dateField", "column", Type.COLUMN, WARN, REPORT),
            link("alert-rule", "onPipeline", "pipeline", Type.ID, REFUSE, REWRITE, RENAME, IMPACT),
            link("alert-rule", "dataset", "dataset", Type.ID, REFUSE, REWRITE, IMPACT),
            link("widget", "datasetId", "dataset", Type.ID, REFUSE, REWRITE, IMPACT, VALIDATE, IMPORT),
            link("widget", "queryId", "query", Type.ID, REFUSE, REWRITE, VALIDATE, IMPORT),
            link("widget", "controls", "column", Type.COLUMN, WARN, REPORT),
            link("dashboard", "tiles[].widgetId", "widget", Type.ID, REFUSE, REWRITE, IMPACT, VALIDATE, IMPORT),
            link("link-analysis-view", "query.projection.datasetId", "dataset", Type.ID, REFUSE, REWRITE, VALIDATE, IMPORT),
            link("geo-map-view", "query.projection.datasetId", "dataset", Type.ID, REFUSE, REWRITE, VALIDATE, IMPORT),
            link("reconciliation", "datasets[]", "dataset", Type.ID, REFUSE, REWRITE, VALIDATE, IMPORT),
            link("reconciliation", "leftDataset", "dataset", Type.ID, REFUSE, REWRITE, VALIDATE, IMPORT),
            link("reconciliation", "rightDataset", "dataset", Type.ID, REFUSE, REWRITE, VALIDATE, IMPORT),
            link("reconciliation", "thirdDataset", "dataset", Type.ID, REFUSE, REWRITE, VALIDATE, IMPORT),
            link("reconciliation", "keyColumns", "column", Type.COLUMN, WARN, REPORT),
            link("reconciliation", "compareColumns", "column", Type.COLUMN, WARN, REPORT),
            // ── Space settings ───────────────────────────────────────────────────────────────────
            link("nav-menu", "**.binding.componentId", "component", Type.ID, WARN, REWRITE));

    /** The one link declared for {@code fromKind} at {@code keyPath}; fails loudly on a typo. */
    public static Link of(String fromKind, String keyPath) {
        for (Link l : LINKS) if (l.fromKind().equals(fromKind) && l.keyPath().equals(keyPath)) return l;
        throw new IllegalArgumentException("no census link " + fromKind + ":" + keyPath);
    }

    /**
     * The links a Pipeline rename rewrites (`SPACE-LINK-CENSUS-1` LC-2): {@code onRename = REWRITE} and naming the
     * Pipeline itself or the store its id names. A Job's {@code pipeline_config} (a file path) is not one: the
     * rename moves the file, and rewriting paths is not part of it yet.
     */
    public static List<Link> renamedWithPipeline() {
        return LINKS.stream().filter(l -> l.onRename() == OnRename.REWRITE
                && (l.toKind().equals("pipeline") || l.toKind().equals("store"))).toList();
    }

    /** The links held by {@code fromKind}, in census order. */
    public static List<Link> from(String fromKind) {
        return LINKS.stream().filter(l -> l.fromKind().equals(fromKind)).toList();
    }
}
