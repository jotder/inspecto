package com.gamma.pipeline;

import com.gamma.api.PublicApi;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structural validation of a {@link PipelineGraph} before it is executed or accepted from the UI
 * (doc §14 T14, §13 R5, §12 B7). Unlike {@link com.gamma.etl.ConfigValidator} — which only emits
 * non-fatal warnings about suspicious-but-legal {@code *_pipeline.toon} settings — this validator
 * distinguishes hard {@link Severity#ERROR}s that make a flow <b>unexecutable</b> (a cycle, a
 * dangling edge) from {@link Severity#WARNING}s, so the executor and the future authoring API can
 * <em>reject</em> a broken graph rather than fail mid-run.
 *
 * <p>Checks (all over the IR alone — zero engine coupling):
 * <ul>
 *   <li><b>DAG over {@code data} edges</b> — a cycle in the record-set subgraph is rejected; flows
 *       are DAGs so the topological walk terminates (B7 / D10). Control + split + {@code route:*}
 *       edges are excluded from the cycle check, matching the executor's walk.</li>
 *   <li><b>No same-graph {@code on_commit}</b> (R5) — {@code on_commit} is <em>cross-flow only</em>
 *       (it triggers a downstream flow); an {@code on_commit} edge whose target is a node in this
 *       same graph would be a cycle the data-edge check can't see, so it is rejected here.</li>
 *   <li><b>No dangling endpoints</b> — every edge's {@code from} must be a node in the graph, and so
 *       must its {@code to} <em>unless</em> the edge is {@code on_commit} (whose {@code to} names
 *       another flow, not a local node).</li>
 *   <li><b>No duplicate node ids</b> and <b>at least one entry (trigger) node</b> for a non-empty
 *       graph (nothing can start a flow in which every node has an inbound edge).</li>
 *   <li><b>Relationship wiring against the node-output contract</b> (T9): an edge's relationship must
 *       be one its source node type {@link PipelineNodeType#emits() emits} (or a {@code route:*} branch
 *       when it {@link PipelineNodeType#emitsNamedRoutes() emits named routes}); a {@code data} edge's
 *       target must {@link PipelineNodeType#accepts() accept} {@code data}; and an outcome/route edge's
 *       target must accept the relationship <em>or</em> accept {@code data} (A6 — the handler exemption:
 *       a control/split outcome ({@code failure}/{@code unmatched}/{@code gap}) routed to a row-consumer
 *       (sink/alert) is rows, so handlers need not list every inbound outcome; only a target that can
 *       take neither refuses, with {@link #ILLEGAL_PAIRING}). An unregistered node type is flagged
 *       (warning) and its wiring left unchecked.</li>
 *   <li><b>Unknown {@code use:} kind</b> — a node's {@code use:} reference must have a recognized
 *       {@code <kind>/<name>} prefix ({@link ComponentRegistry#isComponentType}); an unrecognized kind
 *       (typo or removed component type) is a hard error, since it silently falls back to the node's
 *       local config today with no signal to the author. This checks only the kind, not whether
 *       {@code <name>} resolves to an actual on-disk component — that requires a live registry scan,
 *       unlike every other check here which is pure IR.</li>
 * </ul>
 */
@PublicApi(since = "4.0.0")
public final class PipelineValidator {

    private PipelineValidator() {}

    /** Whether an issue blocks execution ({@link #ERROR}) or is merely advisory ({@link #WARNING}). */
    public enum Severity { ERROR, WARNING }

    // ── issue codes (stable identifiers for the UI / tests / audit) ───────────────
    public static final String EMPTY_GRAPH = "EMPTY_GRAPH";
    public static final String DUPLICATE_NODE = "DUPLICATE_NODE";
    public static final String DANGLING_FROM = "DANGLING_FROM";
    public static final String DANGLING_TO = "DANGLING_TO";
    public static final String ON_COMMIT_SAME_GRAPH = "ON_COMMIT_SAME_GRAPH";
    public static final String CYCLE = "CYCLE";
    public static final String NO_ENTRY = "NO_ENTRY";
    public static final String ILLEGAL_EMIT = "ILLEGAL_EMIT";
    public static final String ILLEGAL_ACCEPT = "ILLEGAL_ACCEPT";
    /**
     * An outcome/route edge whose target can neither accept the edge's relationship nor consume it
     * as rows (A6, pipeline-multiplicity plan). The handler exemption is preserved: a target that
     * accepts {@code data} may take any outcome/route stream as rows (a sink taking a reject
     * stream), so this fires only for targets that accept neither — an entry node (accepts
     * nothing), or a specialised consumer like {@code gap} fed the wrong relationship.
     */
    public static final String ILLEGAL_PAIRING = "ILLEGAL_PAIRING";
    public static final String UNKNOWN_TYPE = "UNKNOWN_TYPE";
    public static final String UNKNOWN_USE_KIND = "UNKNOWN_USE_KIND";
    /**
     * A {@code use:} binding whose kind is recognized but whose NAMED component does not exist in the
     * registry — e.g. {@code grammar/pipe-delimted}. Only reported when a {@link ComponentRegistry} is
     * supplied ({@link #validate(PipelineGraph, ComponentRegistry)}); a registry-less validate cannot
     * tell a typo from a component it simply cannot see, so it stays silent rather than guessing.
     */
    public static final String UNKNOWN_USE_REF = "UNKNOWN_USE_REF";

    /** One validation finding: a {@code severity}, a stable {@code code}, and a human message. */
    public record Issue(Severity severity, String code, String message) {
        public boolean isError() {
            return severity == Severity.ERROR;
        }
    }

    /** The outcome of validating a graph: every issue found, in detection order. */
    public record Result(List<Issue> issues) {
        public Result {
            issues = (issues == null) ? List.of() : List.copyOf(issues);
        }

        /** Only the blocking {@link Severity#ERROR} issues. */
        public List<Issue> errors() {
            return issues.stream().filter(Issue::isError).toList();
        }

        /** Only the advisory {@link Severity#WARNING} issues. */
        public List<Issue> warnings() {
            return issues.stream().filter(i -> i.severity() == Severity.WARNING).toList();
        }

        /** {@code true} when the graph is executable (no errors; warnings are allowed). */
        public boolean ok() {
            return errors().isEmpty();
        }
    }

    /** Validate {@code g}, collecting every issue (does not throw). Does not check {@code use:} targets. */
    public static Result validate(PipelineGraph g) {
        return validate(g, null);
    }

    /**
     * Validate {@code g}, additionally checking that every {@code use:} binding names a component that
     * actually EXISTS in {@code registry}. Pass the registry on any save/authoring path so a typo'd or
     * unregistered binding is refused at save time; without it a dangling ref stays silent and
     * {@link ComponentRegistry#effectiveConfig} later degrades to the node's local config, which is how
     * a mistyped binding used to reach the engine unnoticed.
     *
     * @param registry the populated component registry, or {@code null} to skip the existence check
     */
    public static Result validate(PipelineGraph g, ComponentRegistry registry) {
        List<Issue> issues = new ArrayList<>();
        Set<String> ids = checkNodeIdentity(g, issues);

        if (g.nodes().isEmpty()) {
            issues.add(new Issue(Severity.WARNING, EMPTY_GRAPH, "Pipeline '" + g.name() + "' has no nodes."));
            return new Result(issues);
        }

        checkEdgeEndpoints(g, ids, issues);
        detectDataCycle(g, ids, issues);
        if (g.entryNodes().isEmpty()) {
            issues.add(new Issue(Severity.ERROR, NO_ENTRY,
                    "Pipeline '" + g.name() + "' has no entry node — every node has an inbound edge, so nothing triggers it."));
        }
        checkWiring(g, issues, registry);
        return new Result(issues);
    }

    /**
     * Validate {@code g} and throw if it is not executable. The exception message lists every error.
     * Use this on the execution path (authoring/CRUD code prefers {@link #validate} to surface all
     * issues, including warnings, to the user).
     */
    public static void validateOrThrow(PipelineGraph g) {
        Result r = validate(g);
        if (!r.ok()) {
            StringBuilder sb = new StringBuilder("Invalid pipeline '").append(g.name()).append("':");
            for (Issue e : r.errors()) sb.append("\n  - [").append(e.code()).append("] ").append(e.message());
            throw new IllegalArgumentException(sb.toString());
        }
    }

    /** Collect node ids, flagging duplicates; returns the set of (distinct) ids present. */
    private static Set<String> checkNodeIdentity(PipelineGraph g, List<Issue> issues) {
        Set<String> ids = new LinkedHashSet<>();
        for (PipelineNode n : g.nodes()) {
            if (!ids.add(n.id())) {
                issues.add(new Issue(Severity.ERROR, DUPLICATE_NODE, "Duplicate node id '" + n.id() + "'."));
            }
        }
        return ids;
    }

    /** Every edge endpoint must resolve to a local node — except an {@code on_commit} target (cross-flow). */
    private static void checkEdgeEndpoints(PipelineGraph g, Set<String> ids, List<Issue> issues) {
        for (PipelineEdge e : g.edges()) {
            if (!ids.contains(e.from())) {
                issues.add(new Issue(Severity.ERROR, DANGLING_FROM,
                        "Edge '" + e.rel() + "' from unknown node '" + e.from() + "'."));
            }
            boolean onCommit = PipelineRel.ON_COMMIT.equals(e.rel());
            if (onCommit) {
                // on_commit is cross-flow only: a local target is a hidden cycle (R5).
                if (ids.contains(e.to())) {
                    issues.add(new Issue(Severity.ERROR, ON_COMMIT_SAME_GRAPH,
                            "on_commit edge from '" + e.from() + "' targets node '" + e.to()
                                    + "' in the same pipeline — on_commit is cross-pipeline only; link a downstream pipeline instead."));
                }
            } else if (!ids.contains(e.to())) {
                issues.add(new Issue(Severity.ERROR, DANGLING_TO,
                        "Edge '" + e.rel() + "' from '" + e.from() + "' to unknown node '" + e.to() + "'."));
            }
        }
    }

    /** DFS over the {@code data}-edge subgraph; report the first back-edge as a cycle (§B7). */
    private static void detectDataCycle(PipelineGraph g, Set<String> ids, List<Issue> issues) {
        Map<String, List<String>> adj = new LinkedHashMap<>();
        for (String id : ids) adj.put(id, new ArrayList<>());
        for (PipelineEdge e : g.edges()) {
            if (e.isData() && adj.containsKey(e.from()) && adj.containsKey(e.to())) {
                adj.get(e.from()).add(e.to());
            }
        }
        Set<String> visiting = new HashSet<>();   // on the current DFS stack (gray)
        Set<String> done = new HashSet<>();        // fully explored (black)
        Deque<String> stack = new ArrayDeque<>();
        for (String start : adj.keySet()) {
            if (!done.contains(start) && dfsCycle(start, adj, visiting, done, stack, issues)) {
                return; // first cycle is enough to reject; the user fixes and re-validates
            }
        }
    }

    private static boolean dfsCycle(String u, Map<String, List<String>> adj, Set<String> visiting,
                                    Set<String> done, Deque<String> stack, List<Issue> issues) {
        visiting.add(u);
        stack.addLast(u);
        for (String v : adj.get(u)) {
            if (visiting.contains(v)) {
                List<String> path = new ArrayList<>(stack);
                path.subList(0, path.indexOf(v)).clear();   // trim to the cycle start
                path.add(v);                                  // close the loop
                issues.add(new Issue(Severity.ERROR, CYCLE,
                        "Data-edge cycle (pipelines must be a DAG — §B7): " + String.join(" -> ", path)));
                return true;
            }
            if (!done.contains(v) && dfsCycle(v, adj, visiting, done, stack, issues)) return true;
        }
        visiting.remove(u);
        stack.removeLast();
        done.add(u);
        return false;
    }

    /**
     * Check each edge against the node-output contract (T9): the source must {@code emit} the edge's
     * relationship; a {@code data} edge's target must {@code accept} {@code data}. Endpoints that don't
     * resolve to a node are skipped here (already flagged as dangling); unregistered types are warned and
     * left unchecked (a plugin may define them out of core).
     *
     * <p>When {@code registry} is non-null a {@code use:} binding is checked in two steps — the KIND
     * prefix against the static component-type set, then the NAMED component against the registry's
     * contents. A bad kind alone is reported: the name check would be meaningless for a kind that has no
     * registry dir, and two errors for one typo reads as two faults.
     */
    private static void checkWiring(PipelineGraph g, List<Issue> issues, ComponentRegistry registry) {
        Map<String, PipelineNode> byId = g.byId();
        for (PipelineNode n : g.nodes()) {
            if (!PipelineNodeTypes.isKnown(n.type())) {
                issues.add(new Issue(Severity.WARNING, UNKNOWN_TYPE,
                        "Node '" + n.id() + "' has unregistered type '" + n.type() + "' — wiring not validated."));
            }
            if (n.hasUse()) {
                String use = n.use();
                int slash = use.indexOf('/');
                String kind = slash < 0 ? use : use.substring(0, slash);
                // A DERIVED binding is not a ComponentRegistry ref and has no dir to look in: an
                // enrichment node's `enrichment/<name>` points at a companion registered through
                // POST /enrichment, and the read side synthesizes it on every graph/raw. Checking it
                // here reported UNKNOWN_USE_KIND and 422'd an untouched open→save round trip.
                if (PipelineEditable.isDerivedBinding(n.type(), use)) {
                    continue;
                }
                if (!ComponentRegistry.isComponentType(kind)) {
                    issues.add(new Issue(Severity.ERROR, UNKNOWN_USE_KIND,
                            "Node '" + n.id() + "' has use: '" + use + "' with unrecognized component kind '"
                                    + kind + "'."));
                } else if (registry != null && !registry.isKnown(use)) {
                    issues.add(new Issue(Severity.ERROR, UNKNOWN_USE_REF,
                            "Node '" + n.id() + "' has use: '" + use + "' but no " + kind
                                    + " named '" + (slash < 0 ? "" : use.substring(slash + 1))
                                    + "' is registered."));
                }
            }
        }
        for (PipelineEdge e : g.edges()) {
            PipelineNode from = byId.get(e.from());
            if (from != null) {
                PipelineNodeTypes.get(from.type()).ifPresent(src -> {
                    if (!emitsRel(src, e.rel())) {
                        issues.add(new Issue(Severity.ERROR, ILLEGAL_EMIT,
                                "Node '" + e.from() + "' (" + from.type() + ") does not emit relationship '"
                                        + e.rel() + "' — emits " + src.emits()
                                        + (src.emitsNamedRoutes() ? " + route:*" : "") + "."));
                    }
                });
            }
            if (e.isData()) {
                PipelineNode to = byId.get(e.to());
                if (to != null) {
                    PipelineNodeTypes.get(to.type()).ifPresent(dst -> {
                        if (!dst.accepts().contains(PipelineRel.DATA)) {
                            issues.add(new Issue(Severity.ERROR, ILLEGAL_ACCEPT,
                                    "Node '" + e.to() + "' (" + to.type() + ") does not accept data — accepts "
                                            + dst.accepts() + "."));
                        }
                    });
                }
            } else if (!PipelineRel.ON_COMMIT.equals(e.rel())) {
                // Neighbour pairing for an outcome/route edge (A6): the target must accept the
                // relationship, or accept data (the handler exemption — a reject/route stream is
                // rows to a row-consumer). on_commit is cross-flow, its target is not a local node.
                PipelineNode to = byId.get(e.to());
                if (to != null) {
                    PipelineNodeTypes.get(to.type()).ifPresent(dst -> {
                        if (!dst.accepts().contains(e.rel()) && !dst.accepts().contains(PipelineRel.DATA)) {
                            issues.add(new Issue(Severity.ERROR, ILLEGAL_PAIRING,
                                    "Node '" + e.to() + "' (" + to.type() + ") cannot be wired after '"
                                            + e.from() + "' via '" + e.rel() + "' — it accepts " + dst.accepts()
                                            + " and does not consume rows."));
                        }
                    });
                }
            }
        }
    }

    /** Whether {@code src} may emit {@code rel}: an explicit emit, or a {@code route:*} when it emits named routes. */
    private static boolean emitsRel(PipelineNodeType src, String rel) {
        return src.emits().contains(rel) || (PipelineRel.isRoute(rel) && src.emitsNamedRoutes());
    }
}
