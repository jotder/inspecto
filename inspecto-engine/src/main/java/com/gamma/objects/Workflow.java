package com.gamma.objects;

import com.gamma.util.ToonHelper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static com.gamma.util.Values.trimToNull;

/**
 * A config-driven state machine for an {@link ObjectType} — the "Workflow Engine" of the Operational
 * Intelligence Platform (Phase 2). It defines the legal states an {@link com.gamma.ops.OperationalObject}
 * may occupy and the {@link Transition}s between them. {@link com.gamma.ops.ObjectService} consults it
 * to validate every lifecycle change before persisting, and records each transition as a Phase-1 event.
 *
 * <p>Per the requirement's "configuration over custom code" principle a workflow may be authored as a
 * {@code *_workflow.toon} (see {@link #load}); {@link #defaultFor(ObjectType)} supplies a sensible
 * built-in so the engine works with zero configuration (Phase 2 ships the {@link ObjectType#ALERT}
 * lifecycle {@code OPEN → ACKNOWLEDGED → RESOLVED}).
 *
 * <p>States are normalised to upper-case, actions to lower-case, so matching is case-insensitive.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record Workflow(ObjectType objectType, String initialState, Set<Transition> transitions,
                       Set<String> terminalStates) {

    /** One legal move: {@code from} state, via {@code action}, to {@code to} state. */
    public record Transition(String from, String to, String action) {
        public Transition {
            from = norm(from);
            to = norm(to);
            action = action == null ? null : action.trim().toLowerCase(Locale.ROOT);
            if (from == null || to == null || action == null || action.isBlank())
                throw new IllegalArgumentException("transition needs from, to and action");
        }
    }

    public Workflow {
        if (objectType == null) throw new IllegalArgumentException("workflow objectType is required");
        initialState = norm(initialState);
        if (initialState == null) throw new IllegalArgumentException("workflow initialState is required");
        transitions = transitions == null ? Set.of() : Set.copyOf(transitions);
        Set<String> term = new LinkedHashSet<>();
        if (terminalStates != null) for (String s : terminalStates) if (norm(s) != null) term.add(norm(s));
        terminalStates = Set.copyOf(term);
    }

    /** The target state reached from {@code fromState} via {@code action}, if such a transition exists. */
    public Optional<String> apply(String fromState, String action) {
        String from = norm(fromState);
        String act = action == null ? null : action.trim().toLowerCase(Locale.ROOT);
        return transitions.stream()
                .filter(t -> t.from().equals(from) && t.action().equals(act))
                .map(Transition::to)
                .findFirst();
    }

    /** {@code true} when some transition goes from {@code fromState} directly to {@code toState}. */
    public boolean allows(String fromState, String toState) {
        String from = norm(fromState);
        String to = norm(toState);
        return transitions.stream().anyMatch(t -> t.from().equals(from) && t.to().equals(to));
    }

    /** {@code true} when {@code state} is terminal (no further transitions are intended). */
    public boolean isTerminal(String state) {
        return terminalStates.contains(norm(state));
    }

    /** Every state named by the initial state, the terminal set, or any transition endpoint. */
    public Set<String> states() {
        Set<String> all = new LinkedHashSet<>();
        all.add(initialState);
        all.addAll(terminalStates);
        for (Transition t : transitions) { all.add(t.from()); all.add(t.to()); }
        return all;
    }

    /**
     * Every state in a stable, presentation-friendly order: the initial state first, then BFS over
     * the transitions (ties broken alphabetically), unreachable stragglers last — what a UI renders
     * as lifecycle folders/columns without hardcoding the state list ({@code GET /workflows/{type}}).
     */
    public List<String> orderedStates() {
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>();
        seen.add(initialState);
        frontier.add(initialState);
        while (!frontier.isEmpty()) {
            String cur = frontier.poll();
            out.add(cur);
            transitions.stream()
                    .filter(t -> t.from().equals(cur))
                    .sorted(Comparator.comparing(Transition::to))
                    .forEach(t -> { if (seen.add(t.to())) frontier.add(t.to()); });
        }
        for (String s : states()) if (seen.add(s)) out.add(s);
        return out;
    }

    /**
     * JSON-ready view (stable key + state order) — backs {@code GET /workflows/{type}} so the UI can
     * derive its folders and action verbs from the <em>effective</em> (possibly TOON-overridden)
     * workflow instead of hardcoding lifecycles.
     */
    public Map<String, Object> toMap() {
        List<String> ordered = orderedStates();
        List<Map<String, Object>> moves = new ArrayList<>();
        for (String from : ordered) {
            transitions.stream()
                    .filter(t -> t.from().equals(from))
                    .sorted(Comparator.comparing(Transition::action).thenComparing(Transition::to))
                    .forEach(t -> {
                        Map<String, Object> mv = new LinkedHashMap<>();
                        mv.put("from", t.from());
                        mv.put("to", t.to());
                        mv.put("action", t.action());
                        moves.add(mv);
                    });
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", objectType.name());
        m.put("initial", initialState);
        m.put("states", ordered);
        m.put("terminal", terminalStates.stream().sorted().toList());
        m.put("transitions", moves);
        return m;
    }

    // ── built-in defaults ─────────────────────────────────────────────────────────

    /**
     * The built-in workflow for {@code type}.
     * <ul>
     *   <li>{@link ObjectType#ALERT} (Phase 2): {@code OPEN → ACKNOWLEDGED → RESOLVED} (with a direct
     *       {@code OPEN → RESOLVED} for "resolve without acking"); {@code RESOLVED} is terminal.</li>
     *   <li>{@link ObjectType#INCIDENT} (mail lifecycle, GLOSSARY §9):
     *       {@code IDENTIFIED → DIAGNOSING → RESOLVED → ARCHIVED} (actions {@code accept}/{@code resolve}/
     *       {@code archive}, with {@code resolve}/{@code archive} also legal straight from earlier states —
     *       Gmail's "trash anything"), plus {@code reopen}: {@code RESOLVED|ARCHIVED → DIAGNOSING}. Only
     *       {@code ARCHIVED} is terminal; reopening out of it clears {@code closedAt}. The SLA clock still
     *       stops at {@code RESOLVED}, distinct from archival.</li>
     *   <li>{@link ObjectType#CASE} (Phase 4): {@code OPEN → INVESTIGATING → ESCALATED → RESOLVED → CLOSED}
     *       (actions {@code investigate}/{@code escalate}/{@code resolve}/{@code close}, plus a direct
     *       {@code INVESTIGATING → RESOLVED} for "resolve without escalating"); only {@code CLOSED} is
     *       terminal.</li>
     * </ul>
     * The remaining type ({@link ObjectType#TASK}) gets a minimal {@code OPEN → CLOSED} placeholder that a
     * later phase replaces, or that a {@code *_workflow.toon} overrides today.
     */
    public static Workflow defaultFor(ObjectType type) {
        if (type == ObjectType.ALERT) {
            return new Workflow(ObjectType.ALERT, "OPEN",
                    Set.of(new Transition("OPEN", "ACKNOWLEDGED", "ack"),
                            new Transition("ACKNOWLEDGED", "RESOLVED", "resolve"),
                            new Transition("OPEN", "RESOLVED", "resolve")),
                    Set.of("RESOLVED"));
        }
        if (type == ObjectType.INCIDENT) {
            return new Workflow(ObjectType.INCIDENT, "IDENTIFIED",
                    Set.of(new Transition("IDENTIFIED", "DIAGNOSING", "accept"),
                            new Transition("IDENTIFIED", "RESOLVED", "resolve"),
                            new Transition("IDENTIFIED", "ARCHIVED", "archive"),
                            new Transition("DIAGNOSING", "RESOLVED", "resolve"),
                            new Transition("DIAGNOSING", "ARCHIVED", "archive"),
                            new Transition("RESOLVED", "ARCHIVED", "archive"),
                            new Transition("RESOLVED", "DIAGNOSING", "reopen"),
                            new Transition("ARCHIVED", "DIAGNOSING", "reopen")),
                    Set.of("ARCHIVED"));
        }
        if (type == ObjectType.CASE) {
            return new Workflow(ObjectType.CASE, "OPEN",
                    Set.of(new Transition("OPEN", "INVESTIGATING", "investigate"),
                            new Transition("INVESTIGATING", "ESCALATED", "escalate"),
                            new Transition("ESCALATED", "RESOLVED", "resolve"),
                            new Transition("INVESTIGATING", "RESOLVED", "resolve"),
                            new Transition("RESOLVED", "CLOSED", "close")),
                    Set.of("CLOSED"));
        }
        return new Workflow(type, "OPEN",
                Set.of(new Transition("OPEN", "CLOSED", "close")), Set.of("CLOSED"));
    }

    // ── authored-workflow validation (ASSURE-WORKFLOW-SLA-1) ──────────────────────────

    /**
     * Every reason this workflow must not be installed; empty = valid. Run at save (the {@code workflow} component
     * kind, every writer) and again at load, so a hand-edited file that fails is skipped rather than served.
     * <ol>
     *   <li>At least one terminal state is declared.</li>
     *   <li>Every state is reachable from the initial state.</li>
     *   <li>Every non-terminal state has a way out — a dead end nobody declared terminal is a silent finish line.</li>
     *   <li>No two transitions leave one state by the same action ({@link #apply} would pick one arbitrarily).</li>
     *   <li>For an {@link ObjectType#INCIDENT}: {@code RESOLVED} exists; {@code ARCHIVED}, if present, is terminal;
     *       and every move into a terminal state other than {@code ARCHIVED} leaves from {@code RESOLVED} — so a
     *       decided outcome is only ever recorded where the Disposition and postmortem gate runs. The gate itself
     *       ({@code ObjectService.decidesIncident}) also covers every custom terminal state at runtime; this rule
     *       keeps a workflow from even describing a path around it.</li>
     * </ol>
     */
    public List<String> problems() {
        List<String> out = new ArrayList<>();
        if (terminalStates.isEmpty()) out.add("declare at least one terminal state");
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>(List.of(initialState));
        reachable.add(initialState);
        while (!frontier.isEmpty()) {
            String cur = frontier.poll();
            for (Transition t : transitions) if (t.from().equals(cur) && reachable.add(t.to())) frontier.add(t.to());
        }
        for (String s : states()) {
            if (!reachable.contains(s)) out.add("state " + s + " is unreachable from the initial state " + initialState);
            if (!terminalStates.contains(s) && transitions.stream().noneMatch(t -> t.from().equals(s)))
                out.add("state " + s + " has no way out and is not declared terminal");
        }
        Set<String> moves = new LinkedHashSet<>();
        for (Transition t : transitions)
            if (!moves.add(t.from() + " " + t.action()))
                out.add("two transitions leave " + t.from() + " by action '" + t.action() + "'");
        if (objectType == ObjectType.INCIDENT) {
            if (!states().contains("RESOLVED"))
                out.add("an Incident workflow needs a RESOLVED state — it is where the resolution gate records the outcome");
            if (states().contains("ARCHIVED") && !terminalStates.contains("ARCHIVED"))
                out.add("ARCHIVED must be terminal in an Incident workflow");
            for (Transition t : transitions)
                if (terminalStates.contains(t.to()) && !"ARCHIVED".equals(t.to()) && !"RESOLVED".equals(t.to())
                        && !"RESOLVED".equals(t.from()))
                    out.add("transition " + t.from() + " -" + t.action() + "-> " + t.to()
                            + " finishes an Incident around RESOLVED, skipping the Disposition and postmortem gate");
        }
        return out;
    }

    /** {@code this}, or {@link IllegalArgumentException} naming every {@link #problems() problem}. */
    public Workflow validated() {
        List<String> p = problems();
        if (!p.isEmpty()) throw new IllegalArgumentException("workflow for " + objectType + " is invalid: " + String.join("; ", p));
        return this;
    }

    /**
     * Parse + validate a {@code workflow} component ({@code registry/workflows/<type>.toon}): the {@link #fromMap}
     * shape, exactly one initial state (a string, never a list), the component id equal to the object type, then
     * {@link #validated()}.
     */
    public static Workflow fromComponent(String id, Map<String, Object> content) {
        if (content == null) throw new IllegalArgumentException("workflow content is required");
        Object initial = content.getOrDefault("initial", content.get("initial_state"));
        if (!(initial instanceof String))
            throw new IllegalArgumentException("workflow.initial must be exactly one state name");
        Workflow wf = fromMap(content);
        if (id != null && !wf.objectType().name().equalsIgnoreCase(id))
            throw new IllegalArgumentException("workflow objectType '" + wf.objectType()
                    + "' must match the component id '" + id + "' (one workflow per object type)");
        return wf.validated();
    }

    // ── .toon authoring ─────────────────────────────────────────────────────────────

    /** Load a {@code *_workflow.toon} (a {@code workflow { … }} block). */
    @SuppressWarnings("unchecked")
    public static Workflow load(Path path) throws IOException {
        Map<String, Object> root = ToonHelper.load(path.toString());
        Object wf = root.get("workflow");
        if (!(wf instanceof Map)) throw new IllegalArgumentException(path + " has no 'workflow' block");
        return fromMap((Map<String, Object>) wf);
    }

    /** Parse + validate from a decoded {@code workflow { … }} map. */
    @SuppressWarnings("unchecked")
    public static Workflow fromMap(Map<String, Object> wf) {
        if (wf == null) throw new IllegalArgumentException("missing 'workflow' block");
        ObjectType type = ObjectType.of(trimToNull(wf.getOrDefault("object_type", wf.get("objectType"))));
        if (type == null) throw new IllegalArgumentException("workflow.object_type is required");
        String initial = trimToNull(wf.getOrDefault("initial", wf.get("initial_state")));

        Set<String> terminal = new LinkedHashSet<>();
        Object term = wf.getOrDefault("terminal", wf.get("terminal_states"));
        if (term instanceof List<?> list) for (Object s : list) if (trimToNull(s) != null) terminal.add(trimToNull(s));

        Set<Transition> transitions = new LinkedHashSet<>();
        Object trs = wf.get("transitions");
        if (trs instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> tm) {
                    Map<String, Object> t = (Map<String, Object>) tm;
                    transitions.add(new Transition(trimToNull(t.get("from")), trimToNull(t.get("to")), trimToNull(t.get("action"))));
                }
            }
        }
        if (transitions.isEmpty()) throw new IllegalArgumentException("workflow needs at least one transition");
        return new Workflow(type, initial, transitions, terminal);
    }

    private static String norm(String s) {
        return (s == null || s.isBlank()) ? null : s.trim().toUpperCase(Locale.ROOT);
    }
}
