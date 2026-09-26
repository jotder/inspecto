package com.gamma.geolink;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.gamma.control.EntityTypes;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pure, deterministic evaluator behind an <b>Investigation</b> (LA-10, plan §2): folds an ordered op log
 * into the <b>Working Set</b> it defines. It never reads a Dataset — every Dataset-reading op ({@code expand})
 * carries its SEALED read (the materialised rows, decision D-E3), so evaluating a log is a function of the log
 * alone and replays identically on any day, against any data.
 *
 * <p>The op semantics (plan §2.3), for the nine ops shipped so far:
 * <ul>
 *   <li>{@code seed {ids, entityType?}} — admits each id at hop 0 as its own seed. An already-admitted id keeps
 *       its original provenance; an EXCLUDED id is re-admitted, because a later explicit analyst op wins.</li>
 *   <li>{@code expand} — adds the sealed one-hop rows. A row touching an excluded entity is dropped (exclude
 *       is gone from traversal); a new endpoint is admitted at {@code hop+1} of the frontier entity it hangs
 *       off, inheriting that entity's seed. The first row that reaches an entity wins, and rows are in a total
 *       order, so provenance is deterministic.</li>
 *   <li>{@code exclude {ids, reason}} — gone from display, traversal and counts: the entity and every link
 *       touching it leave the Working Set, and the id is remembered (with its reason and step) so later
 *       expands never re-admit it. ⚠ A KEPT entity is not removed — {@code keep} protects it — and the step
 *       reports it as {@code protected} rather than silently succeeding.</li>
 *   <li>{@code hide {ids}} — display-only: still traversed and counted (the difference from exclude).</li>
 *   <li>{@code keep {ids}} — pins an entity against later excludes.</li>
 *   <li>{@code annotate {ids, note, confidence?}} (LA-19; {@code confidence} is an Admiralty grade, D-U9) — attaches the note to each named entity in the Working Set. It changes
 *       nothing about traversal, display or counts; a later exclusion keeps the note, because a note is history.</li>
 *   <li>{@code window {window}} (LA-13) — sets the window later {@code expand}s inherit ({@code null} clears it).
 *       It re-filters nothing already admitted: a sealed row is a folded count with no timestamps left in it, and
 *       an earlier step's read is evidence as it was made. Each expand's rows are already in-window (the route
 *       resolves the window into the read), so the evaluator needs nothing more than to carry it.</li>
 *   <li>{@code excludeBy {listId, reason}} (LA-17) — over the Entity List SEALED into the entry at append
 *       ({@code list: {normaliser, members[], …}}; replay never re-reads the list). Members are normalised KEYS and
 *       ids are raw column values, so an entity matches when {@code EntityTypes.normalise(normaliser, id)} is a
 *       member. Every matching admitted entity is excluded exactly as {@code exclude} would (kept ones are
 *       protected), and the member keys are remembered per normaliser, so a later {@code expand} never admits an
 *       entity that matches them. An entity already in the Working Set is not blocked by a remembered key — only
 *       an explicit later {@code seed}/{@code seedBy} or a {@code keep} can have put it there.</li>
 *   <li>{@code seedBy {listId}} (LA-17) — seeds the SEALED {@code read.ids} (the raw values the route found
 *       normalising to a member) exactly as {@code seed} does, with {@code entityType} = the list's type.</li>
 * </ul>
 * An {@code undo} log entry is NOT a vocabulary op — it is a log edit, recorded append-only, naming the step it
 * reverts. Undo always targets the latest effective op, so skipping undone steps is exactly equivalent to
 * popping them: the state after an undo is byte-identical to the state before the undone step.
 *
 * <p>⛔ The evaluator is TOTAL: an op naming an id that is not (or no longer) in the Working Set is a no-op for
 * that id, never an error. Validation is the route's job at append time; totality is what lets a re-ordered log
 * (a fork, D-E4) evaluate at all.
 */
final class InvestigationEvaluator {

    private InvestigationEvaluator() {}

    /** Canonical JSON: map keys sorted, so equal states hash equal regardless of insertion order. */
    static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** An admitted entity and where it came from (gate G-E8: nothing is silently arbitrary). */
    record Entity(String id, String type, int hop, String seed, int admittedBy) {}

    /** A folded link, as read by the {@code expand} that admitted it. */
    record Link(String source, String target, String kind, long count, int admittedBy) {}

    /** Why an id is excluded, and by which step. */
    record Exclusion(int step, String reason) {}

    /** One analyst note on one entity (LA-19), the step that made it, and its Admiralty grade (D-U9; null = none). */
    record Annotation(int step, String note, String confidence) {}

    /** The Working Set at one log position. Mutable only inside this class. */
    static final class State {
        final TreeMap<String, Entity> entities = new TreeMap<>();
        final TreeMap<String, Link> links = new TreeMap<>();
        final TreeMap<String, Exclusion> excluded = new TreeMap<>();
        final TreeSet<String> hidden = new TreeSet<>();
        final TreeSet<String> kept = new TreeSet<>();
        /** Per-entity notes (LA-19), in step order per entity. They outlive a later exclusion: a note is history. */
        final TreeMap<String, List<Annotation>> annotations = new TreeMap<>();
        /** The window the latest {@code window} op set (LA-13), inherited by later expands; null = the full range. */
        Map<String, Object> window;
        /** Entity List keys an {@code excludeBy} excluded (LA-17): normaliser → key → the step that excluded it. */
        final TreeMap<String, TreeMap<String, Integer>> excludedKeys = new TreeMap<>();

        /** Whether {@code id} is NOT in the Working Set and normalises to a key an {@code excludeBy} excluded. */
        boolean blockedByKey(String id) {
            if (excludedKeys.isEmpty() || entities.containsKey(id)) return false;
            for (var k : excludedKeys.entrySet())
                if (k.getValue().containsKey(EntityTypes.normalise(k.getKey(), id))) return true;
            return false;
        }

        /** The canonical, response-shaped view of this state. */
        Map<String, Object> toMap() {
            List<Map<String, Object>> es = new ArrayList<>();
            for (Entity e : entities.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.id());
                m.put("type", e.type());
                m.put("hop", e.hop());
                m.put("seed", e.seed());
                m.put("admittedBy", e.admittedBy());
                m.put("hidden", hidden.contains(e.id()));
                m.put("kept", kept.contains(e.id()));
                es.add(m);
            }
            List<Map<String, Object>> ls = new ArrayList<>();
            for (Link l : links.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", l.source());
                m.put("target", l.target());
                m.put("kind", l.kind());
                m.put("count", l.count());
                m.put("admittedBy", l.admittedBy());
                ls.add(m);
            }
            List<Map<String, Object>> xs = new ArrayList<>();
            for (var x : excluded.entrySet()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", x.getKey());
                m.put("step", x.getValue().step());
                m.put("reason", x.getValue().reason());
                xs.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("entities", es);
            out.put("links", ls);
            out.put("excluded", xs);
            // Only when set: a state no window op touched hashes exactly as it did before LA-13.
            if (window != null) out.put("window", window);
            // Only when present, for the same reason: an unannotated state hashes exactly as it did before LA-19.
            if (!annotations.isEmpty()) {
                List<Map<String, Object>> as = new ArrayList<>();
                for (var a : annotations.entrySet())
                    for (Annotation n : a.getValue()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", a.getKey());
                        m.put("step", n.step());
                        m.put("note", n.note());
                        // Only when graded (D-U9): an ungraded note hashes exactly as it did before the grade existed.
                        if (n.confidence() != null) m.put("confidence", n.confidence());
                        as.add(m);
                    }
                out.put("annotations", as);
            }
            // Only when present (LA-17): a state no excludeBy touched hashes exactly as it did before.
            if (!excludedKeys.isEmpty()) {
                List<Map<String, Object>> ks = new ArrayList<>();
                for (var n : excludedKeys.entrySet())
                    for (var k : n.getValue().entrySet()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("normaliser", n.getKey());
                        m.put("key", k.getKey());
                        m.put("step", k.getValue());
                        ks.add(m);
                    }
                out.put("excludedKeys", ks);
            }
            return out;
        }

        String hash() {
            return sha256(canonical(toMap()));
        }
    }

    /** What one step changed — the incremental response the SPA applies to its canvas. */
    static Map<String, Object> delta(State before, State after) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("admitted", minus(after.entities.keySet(), before.entities.keySet()));
        d.put("removed", minus(before.entities.keySet(), after.entities.keySet()));
        d.put("linksAdded", minus(after.links.keySet(), before.links.keySet()).size());
        d.put("linksRemoved", minus(before.links.keySet(), after.links.keySet()).size());
        d.put("hidden", minus(after.hidden, before.hidden));
        d.put("kept", minus(after.kept, before.kept));
        d.put("excluded", minus(after.excluded.keySet(), before.excluded.keySet()));
        return d;
    }

    private static List<String> minus(Set<String> a, Set<String> b) {
        List<String> out = new ArrayList<>();
        for (String s : a) if (!b.contains(s)) out.add(s);
        return out;
    }

    /** The steps an {@code undo} entry has reverted. */
    static Set<Integer> undone(List<Map<String, Object>> log) {
        Set<Integer> out = new HashSet<>();
        for (Map<String, Object> e : log)
            if ("undo".equals(e.get("kind")) && e.get("undoes") instanceof Number n) out.add(n.intValue());
        return out;
    }

    /** The latest op step not already undone, or -1 — what an undo appended now would revert. */
    static int undoTarget(List<Map<String, Object>> log) {
        Set<Integer> undone = undone(log);
        for (int i = log.size() - 1; i >= 0; i--) {
            Map<String, Object> e = log.get(i);
            int step = ((Number) e.get("step")).intValue();
            if ("op".equals(e.get("kind")) && !undone.contains(step)) return step;
        }
        return -1;
    }

    /**
     * Full evaluation of {@code log} up to and including step {@code at} (every step when {@code at < 0}).
     * {@code hashesOut}, when given, receives each position's Working Set hash in step order — the replay's
     * equivalence check compares them with the hashes recorded at append time.
     *
     * <p>⛔ PREFIX semantics: position k honours only the undo entries at positions ≤ k — exactly what the append
     * path saw when it recorded k's hash. Resolving the undone set over the whole log first made every prefix skip
     * an op that is only undone LATER, so an untampered log with an undo replayed as not equivalent. Ops fold
     * incrementally; an undo re-folds its own prefix (it reverts the latest effective op, which an incremental
     * state cannot pop).
     */
    static State evaluate(List<Map<String, Object>> log, int at, List<String> hashesOut) {
        List<Map<String, Object>> prefix = new ArrayList<>();
        for (Map<String, Object> e : log) {
            if (at >= 0 && ((Number) e.get("step")).intValue() > at) break;
            prefix.add(e);
        }
        if (hashesOut == null) return fold(prefix);
        State s = new State();
        for (int k = 0; k < prefix.size(); k++) {
            Map<String, Object> e = prefix.get(k);
            if ("op".equals(e.get("kind"))) apply(s, e);
            else if ("undo".equals(e.get("kind"))) s = fold(prefix.subList(0, k + 1));
            hashesOut.add(s.hash());
        }
        return s;
    }

    /** One whole log, its undos resolved within it. */
    private static State fold(List<Map<String, Object>> log) {
        State s = new State();
        Set<Integer> undone = undone(log);
        for (Map<String, Object> e : log)
            if ("op".equals(e.get("kind")) && !undone.contains(((Number) e.get("step")).intValue())) apply(s, e);
        return s;
    }

    /** Apply one op entry to {@code s}. Total: unknown ids are no-ops (see the class note). */
    @SuppressWarnings("unchecked")
    static void apply(State s, Map<String, Object> entry) {
        int step = ((Number) entry.get("step")).intValue();
        Map<String, Object> p = entry.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<String> ids = strings(p.get("ids"));
        switch (String.valueOf(entry.get("op"))) {
            case "seed" -> seed(s, ids, p.get("entityType") == null ? null : String.valueOf(p.get("entityType")), step);
            case "seedBy" -> seed(s, strings(((Map<String, Object>) entry.get("read")).get("ids")),
                    String.valueOf(((Map<String, Object>) entry.get("list")).get("entityType")), step);
            case "expand" -> {
                Map<String, Object> read = (Map<String, Object>) entry.get("read");
                Set<String> frontier = new HashSet<>(strings(((Map<String, Object>) read.get("query")).get("frontier")));
                for (Object o : (List<Object>) read.get("rows")) {
                    Map<String, Object> r = (Map<String, Object>) o;
                    String src = String.valueOf(r.get("source")), tgt = String.valueOf(r.get("target"));
                    if (s.excluded.containsKey(src) || s.excluded.containsKey(tgt)) continue;
                    if (s.blockedByKey(src) || s.blockedByKey(tgt)) continue;   // excludeBy remembers keys
                    Entity from = frontier.contains(src) ? s.entities.get(src)
                            : frontier.contains(tgt) ? s.entities.get(tgt) : null;
                    if (from == null) continue;   // the frontier entity left the Working Set (fork re-order)
                    String other = from.id().equals(src) ? tgt : src;
                    s.entities.putIfAbsent(other, new Entity(other, null, from.hop() + 1, from.seed(), step));
                    String kind = r.get("kind") == null ? null : String.valueOf(r.get("kind"));
                    s.links.putIfAbsent(src + '\u0000' + tgt + '\u0000' + kind,
                            new Link(src, tgt, kind, ((Number) r.get("count")).longValue(), step));
                }
            }
            case "exclude" -> {
                String reason = String.valueOf(p.get("reason"));
                for (String id : ids) exclude(s, id, reason, step);
            }
            case "excludeBy" -> {
                Map<String, Object> list = (Map<String, Object>) entry.get("list");
                String normaliser = String.valueOf(list.get("normaliser"));
                Set<String> members = new HashSet<>(strings(list.get("members")));
                String reason = String.valueOf(p.get("reason"));
                for (String id : new ArrayList<>(s.entities.keySet()))
                    if (members.contains(EntityTypes.normalise(normaliser, id))) exclude(s, id, reason, step);
                if (!members.isEmpty()) {   // an empty list remembers nothing — and leaves no empty entry in the hash
                    TreeMap<String, Integer> keys = s.excludedKeys.computeIfAbsent(normaliser, k -> new TreeMap<>());
                    for (String m : members) keys.putIfAbsent(m, step);
                }
            }
            case "hide" -> {
                for (String id : ids) if (s.entities.containsKey(id)) s.hidden.add(id);
            }
            case "keep" -> {
                for (String id : ids) if (s.entities.containsKey(id)) s.kept.add(id);
            }
            case "window" -> s.window = p.get("window") instanceof Map<?, ?> w ? (Map<String, Object>) w : null;
            case "annotate" -> {
                String note = String.valueOf(p.get("note"));
                String confidence = p.get("confidence") == null ? null : String.valueOf(p.get("confidence"));
                for (String id : ids)
                    if (s.entities.containsKey(id))
                        s.annotations.computeIfAbsent(id, k -> new ArrayList<>())
                                .add(new Annotation(step, note, confidence));
            }
            default -> throw new IllegalStateException("op '" + entry.get("op") + "' in a sealed log is not evaluable");
        }
    }

    private static void seed(State s, List<String> ids, String type, int step) {
        for (String id : ids) {
            s.excluded.remove(id);
            s.entities.putIfAbsent(id, new Entity(id, type, 0, id, step));
        }
    }

    private static void exclude(State s, String id, String reason, int step) {
        if (s.kept.contains(id)) return;   // keep protects it; the route reports it as protected
        s.entities.remove(id);
        s.hidden.remove(id);
        s.links.values().removeIf(l -> l.source().equals(id) || l.target().equals(id));
        s.excluded.put(id, new Exclusion(step, reason));
    }

    /**
     * The entity count after each log position, as the append path saw it (PREFIX semantics, as {@link #evaluate}
     * with hashes) — so a plain-language line can say how many entities an {@code excludeBy} removed without that
     * count being stored in the log.
     */
    static List<Integer> entityCounts(List<Map<String, Object>> log) {
        List<Integer> out = new ArrayList<>();
        State s = new State();
        for (int k = 0; k < log.size(); k++) {
            Map<String, Object> e = log.get(k);
            if ("op".equals(e.get("kind"))) apply(s, e);
            else if ("undo".equals(e.get("kind"))) s = fold(log.subList(0, k + 1));
            out.add(s.entities.size());
        }
        return out;
    }

    static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        return out;
    }

    static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise investigation state", e);
        }
    }

    static String sha256(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
