package com.gamma.la.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.gamma.control.EntityTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The pure, deterministic evaluator behind an <b>Investigation</b> (LA-10, plan §2): folds an ordered op log
 * into the <b>Working Set</b> it defines. It never reads a Dataset — every Dataset-reading op ({@code expand})
 * carries its SEALED read (the materialised rows, decision D-E3), so evaluating a log is a function of the log
 * alone and replays identically on any day, against any data.
 *
 * <p>The op semantics (plan §2.3), for the twelve ops shipped (the closed vocabulary is complete):
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
 *   <li>{@code threshold {min?, max?}} (LA-INVESTIGATION-OPS-DEFERRED-1) - the narrowest reading of plan §2.2's
 *       "measure, min, max, evaluation scope": the measure is an entity's DEGREE (its distinct counterparties among the
 *       links of the Working Set as it stands at this step - hidden entities still count, as for every measure), the
 *       scope is the whole Working Set, and the band is {@code min} inclusive, {@code max} exclusive (a threshold is
 *       crossed AT its value). Every entity outside the band is excluded exactly as {@code exclude} would (keep protects
 *       it; the reason names the band; later expands never re-admit it). Degrees are measured ONCE on the state before
 *       the step, so the removal does not cascade.</li>
 *   <li>{@code snapshot {label?}} - a marker: it changes NOTHING in the Working Set. The artifact is the log position
 *       itself, because every entry already records the Working Set hash at that step; the step names the position
 *       an analyst froze.</li>
 *   <li>{@code compare {windowA, windowB}} (LA-INVESTIGATION-OPS-DEFERRED-1) - a marker like {@code snapshot}: it changes
 *       NOTHING in the Working Set. The route SEALS the window diff ({@code entry.comparison}, with a fingerprint) into the
 *       entry at append, so replay never re-reads the Dataset and a Dossier carries it in custody.</li>
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
 *   <li>{@code resolve {atSeq?}} (LA-17 slice 2, design §8.1) — puts the identity resolution SEALED into the entry
 *       ({@code resolution: {atSeq, atHash, groups[], types, columnTypes, …}}; replay never re-reads the fact log) in
 *       force from this step on; a later {@code resolve} replaces it. It changes NOTHING about traversal, exclusion or
 *       counts — an entity stays its raw identifier. What it adds is a VIEW, computed from the state at every later
 *       position: each admitted entity's typed keys ({@link State#keysOf}), and every sealed group one of them is in,
 *       shown as a merged node with ALL its member keys, the assertions that joined it, and the raw entity ids it
 *       covers. So an entity admitted AFTER the resolve is resolved too, under the rule as sealed.</li>
 *   <li>{@code expand {..., merged: true}} / {@code exclude {..., merged: true}} (LA-17 merged traversal, operator
 *       2026-09-30) - OPT-IN per op, only while a resolve is in force. A merged expand's frontier was widened by the
 *       route to every member of each group it touches and SEALED into {@code read.query.merged.anchorOf} (member
 *       value -> the admitted entity it stands for): a row reaching a member value that is not yet admitted admits it
 *       at its anchor's hop and seed, then the far end at {@code hop+1}. A merged exclude carries its SEALED
 *       {@code groups[{id, members[], normalisers{}}]}: every admitted entity whose typed key is a member is
 *       excluded (keep still protects), and the members are remembered, so a later expand never admits a raw id
 *       whose key under the member type's sealed normaliser is one of them.</li>
 * </ul>
 * An {@code undo} log entry is NOT a vocabulary op — it is a log edit, recorded append-only, naming the step it
 * reverts. Undo always targets the latest effective op, so skipping undone steps is exactly equivalent to
 * popping them: the state after an undo is byte-identical to the state before the undone step.
 *
 * <p>⛔ The evaluator is TOTAL: an op naming an id that is not (or no longer) in the Working Set is a no-op for
 * that id, never an error. Validation is the route's job at append time; totality is what lets a re-ordered log
 * (a fork, D-E4) evaluate at all.
 */
public final class InvestigationEvaluator {

    private InvestigationEvaluator() {}

    /** Canonical JSON: map keys sorted, so equal states hash equal regardless of insertion order. */
    public static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /** An admitted entity and where it came from (gate G-E8: nothing is silently arbitrary). */
    public record Entity(String id, String type, int hop, String seed, int admittedBy) {}

    /** A folded link, as read by the {@code expand} that admitted it. */
    public record Link(String source, String target, String kind, long count, int admittedBy) {}

    /** Why an id is excluded, and by which step. */
    public record Exclusion(int step, String reason) {}

    /** One analyst note on one entity (LA-19), the step that made it, and its Admiralty grade (D-U9; null = none). */
    public record Annotation(int step, String note, String confidence) {}

    /** The Working Set at one log position. Mutable only inside this class. */
    public static final class State {
        public final TreeMap<String, Entity> entities = new TreeMap<>();
        public final TreeMap<String, Link> links = new TreeMap<>();
        public final TreeMap<String, Exclusion> excluded = new TreeMap<>();
        public final TreeSet<String> hidden = new TreeSet<>();
        public final TreeSet<String> kept = new TreeSet<>();
        /** Per-entity notes (LA-19), in step order per entity. They outlive a later exclusion: a note is history. */
        public final TreeMap<String, List<Annotation>> annotations = new TreeMap<>();
        /** Per-link notes (D-U9 remainder), keyed by the link key; like entity notes they outlive a later exclusion. */
        public final TreeMap<String, List<Annotation>> linkAnnotations = new TreeMap<>();
        /** The window the latest {@code window} op set (LA-13), inherited by later expands; null = the full range. */
        public Map<String, Object> window;
        /** Entity List keys an {@code excludeBy} excluded (LA-17): normaliser → key → the step that excluded it. */
        public final TreeMap<String, TreeMap<String, Integer>> excludedKeys = new TreeMap<>();
        /** The identity resolution the latest {@code resolve} sealed (LA-17 slice 2), and its step; null = none. */
        public Map<String, Object> resolution;
        public int resolvedBy;
        /** Groups a merged {@code exclude} excluded (LA-17 merged traversal): group id -> {id, members, normalisers, step, reason}. */
        public final TreeMap<String, Map<String, Object>> excludedGroups = new TreeMap<>();
        /**
         * What the LAST merged {@code exclude} applied actually did, for its log line (plan §5.10): {@code left} - the
         * entities that left the Working Set; {@code kept} - members {@code keep} protected; {@code unmatched} - member
         * keys no admitted entity carried (blocked from later admission only). Derived, never hashed.
         */
        public Map<String, List<String>> lastMerged;

        /**
         * The sealed groups (id -> {@code {id, members[], normalisers{type -> normaliser}}}) that any typed key of the
         * admitted entities {@code ids} is a member of, under the resolution in force. Empty when none is in force.
         */
        @SuppressWarnings("unchecked")
        public TreeMap<String, Map<String, Object>> groupsHit(java.util.Collection<String> ids) {
            TreeMap<String, Map<String, Object>> out = new TreeMap<>();
            if (resolution == null) return out;
            Map<String, SortedSet<String>> sides = sides();
            for (Map<String, Object> sealed : sealedGroups().values()) {
                for (String id : ids) {
                    Entity e = entities.get(id);
                    if (e == null || memberKeysOf(e, sides, sealed).isEmpty()) continue;
                    out.put(String.valueOf(sealed.get("id")), sealed);
                    break;
                }
            }
            return out;
        }

        /** Every group of the resolution in force as {@code {id, members[], normalisers{type -> normaliser}}}, by id. */
        @SuppressWarnings("unchecked")
        public TreeMap<String, Map<String, Object>> sealedGroups() {
            TreeMap<String, Map<String, Object>> out = new TreeMap<>();
            if (resolution == null) return out;
            Map<String, Object> types = (Map<String, Object>) resolution.get("types");
            for (Object o : (List<Object>) resolution.get("groups")) {
                Map<String, Object> g = (Map<String, Object>) o;
                Set<String> members = new TreeSet<>(strings(g.get("members")));
                Map<String, Object> norm = new TreeMap<>();
                for (String m : members) {
                    String t = m.substring(0, m.indexOf(':'));
                    if (types.get(t) instanceof Map<?, ?> def) norm.put(t, String.valueOf(def.get("normaliser")));
                }
                Map<String, Object> sealed = new LinkedHashMap<>();
                sealed.put("id", String.valueOf(g.get("id")));
                sealed.put("members", new ArrayList<>(members));
                sealed.put("normalisers", norm);
                out.put(String.valueOf(g.get("id")), sealed);
            }
            return out;
        }

        /**
         * The member keys of sealed group {@code g} ({@code {members[], normalisers{}}}) that admitted entity {@code e}
         * carries: its typed keys ({@link #keysOf}) that are members; or - when no type can be told for it at all (an
         * UNTYPED id: no own type, no typed bound column; live check 2026-09-30, plan §5.10) - the member keys its raw
         * id makes under each member type's SEALED normaliser, the same rule {@link #blockedByGroup} and a merged
         * expand's member-value scan already apply to a raw id - and only when UNAMBIGUOUS ({@link #rawMemberKeys}:
         * exactly one member type maps it to a member; D-U11). An entity typed as something else is not a member.
         */
        public SortedSet<String> memberKeysOf(Entity e, Map<String, SortedSet<String>> sides, Map<String, Object> g) {
            Set<String> members = new HashSet<>(strings(g.get("members")));
            SortedSet<String> own = keysOf(e, sides);
            if (own.isEmpty()) return rawMemberKeys(g, e.id());
            own.retainAll(members);
            return own;
        }

        /** Whether {@code id} is NOT in the Working Set and its key under a member type's sealed rule is a member of an excluded group. */
        public boolean blockedByGroup(String id) {
            if (excludedGroups.isEmpty() || entities.containsKey(id)) return false;
            for (Map<String, Object> g : excludedGroups.values()) if (!rawMemberKeys(g, id).isEmpty()) return true;
            return false;
        }

        /**
         * Every typed key {@code <type>:<normalised value>} entity {@code e} carries under the sealed resolution,
         * sorted. Its type is, in order: its own {@code type} (a {@code seed}'s {@code entityType} or a {@code seedBy}
         * list's) when that names a SEALED Entity Type (case-insensitive); else the sealed type of each bound column it
         * stands in within a link of the Working Set ({@code sides}); else — when both bound columns are the SAME type —
         * that type (an id does not record its column, but then it does not matter). A value the type's normaliser
         * empties carries no key. Empty when no resolution is in force.
         */
        @SuppressWarnings("unchecked")
        public SortedSet<String> keysOf(Entity e, Map<String, SortedSet<String>> sides) {
            SortedSet<String> out = new TreeSet<>();
            if (resolution == null) return out;
            Map<String, Object> types = (Map<String, Object>) resolution.get("types");
            Map<String, Object> cols = (Map<String, Object>) resolution.get("columnTypes");
            List<String> typeIds = new ArrayList<>();
            String own = e.type() == null ? null : typeId(types, e.type());
            if (own != null) typeIds.add(own);
            else {
                for (String side : sides.getOrDefault(e.id(), new TreeSet<>()))
                    if (cols.get(side) != null) typeIds.add(String.valueOf(cols.get(side)));
                if (typeIds.isEmpty() && cols.get("source") != null && cols.get("source").equals(cols.get("target")))
                    typeIds.add(String.valueOf(cols.get("source")));
            }
            for (String t : typeIds) {
                if (!(types.get(t) instanceof Map<?, ?> def)) continue;
                String v = EntityTypes.normalise(String.valueOf(def.get("normaliser")), e.id());
                if (!v.isEmpty()) out.add(t + ":" + v);
            }
            return out;
        }

        private static String typeId(Map<String, Object> types, String named) {
            for (String t : types.keySet()) if (t.equalsIgnoreCase(named.trim())) return t;
            return null;
        }

        /** Which bound column(s) each entity stands in within the Working Set's links: id → {source, target}. */
        public Map<String, SortedSet<String>> sides() {
            Map<String, SortedSet<String>> out = new TreeMap<>();
            for (Link l : links.values()) {
                out.computeIfAbsent(l.source(), k -> new TreeSet<>()).add("source");
                out.computeIfAbsent(l.target(), k -> new TreeSet<>()).add("target");
            }
            return out;
        }

        /**
         * The resolution view (see the class note): {@code groups} — every sealed group holding a key of an admitted
         * entity, as {@code {id, members[], assertions[], entities[]}} — and, into {@code resolvedToOut}, entity id →
         * the group it resolves to (the smallest group id when its keys hit more than one). Null when none is in force.
         */
        @SuppressWarnings("unchecked")
        public Map<String, Object> resolutionView(Map<String, String> resolvedToOut) {
            if (resolution == null) return null;
            Map<String, SortedSet<String>> sides = sides();
            Map<String, String> groupOfKey = new TreeMap<>();
            Map<String, Map<String, Object>> sealed = new TreeMap<>();
            for (Object o : (List<Object>) resolution.get("groups")) {
                Map<String, Object> g = (Map<String, Object>) o;
                String gid = String.valueOf(g.get("id"));
                sealed.put(gid, g);
                for (String m : strings(g.get("members"))) groupOfKey.put(m, gid);
            }
            TreeMap<String, Map<String, Object>> withNormalisers = sealedGroups();
            TreeMap<String, TreeSet<String>> entitiesOf = new TreeMap<>();
            for (Entity e : entities.values()) {
                TreeSet<String> hit = new TreeSet<>();
                SortedSet<String> own = keysOf(e, sides);
                for (String k : own) if (groupOfKey.containsKey(k)) hit.add(groupOfKey.get(k));
                // D-U11: an UNTYPED entity joins its group by the same unambiguous rule exclude/expand apply
                if (own.isEmpty())
                    for (var g : withNormalisers.entrySet()) if (!rawMemberKeys(g.getValue(), e.id()).isEmpty()) hit.add(g.getKey());
                if (hit.isEmpty()) continue;
                resolvedToOut.put(e.id(), hit.first());
                for (String gid : hit) entitiesOf.computeIfAbsent(gid, k -> new TreeSet<>()).add(e.id());
            }
            List<Map<String, Object>> groups = new ArrayList<>();
            for (var g : entitiesOf.entrySet()) {
                Map<String, Object> s = sealed.get(g.getKey());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", g.getKey());
                m.put("members", s.get("members"));
                m.put("assertions", s.get("assertions"));
                m.put("entities", new ArrayList<>(g.getValue()));
                groups.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", resolvedBy);
            out.put("atSeq", resolution.get("atSeq"));
            out.put("atHash", resolution.get("atHash"));
            out.put("groups", groups);
            return out;
        }

        /** Whether {@code id} is NOT in the Working Set and normalises to a key an {@code excludeBy} excluded. */
        public boolean blockedByKey(String id) {
            if (excludedKeys.isEmpty() || entities.containsKey(id)) return false;
            for (var k : excludedKeys.entrySet())
                if (k.getValue().containsKey(EntityTypes.normalise(k.getKey(), id))) return true;
            return false;
        }

        /** The canonical, response-shaped view of this state. */
        public Map<String, Object> toMap() {
            SERIALISED.incrementAndGet();
            Map<String, String> resolvedTo = new TreeMap<>();
            Map<String, Object> view = resolutionView(resolvedTo);
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
                // Only when resolved (LA-17 slice 2): an unresolved state hashes exactly as it did before.
                if (resolvedTo.containsKey(e.id())) m.put("resolvedTo", resolvedTo.get(e.id()));
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
            // Only when present (D-U9 remainder), for the same reason. The wire id is NOT stored here: it is minted at
            // render time from the values the caller sees (LinkIds), so masking never leaks a raw key through it.
            if (!linkAnnotations.isEmpty()) {
                List<Map<String, Object>> as = new ArrayList<>();
                for (var a : linkAnnotations.entrySet()) {
                    String[] k = a.getKey().split("\u0000", -1);
                    for (Annotation n : a.getValue()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("source", k[0]);
                        m.put("target", k[1]);
                        m.put("kind", k[2]);
                        m.put("step", n.step());
                        m.put("note", n.note());
                        if (n.confidence() != null) m.put("confidence", n.confidence());
                        as.add(m);
                    }
                }
                out.put("linkAnnotations", as);
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
            // Only when a resolve is in force (LA-17 slice 2), for the same reason.
            if (view != null) out.put("resolution", view);
            // Only when a merged exclude ran (LA-17 merged traversal), for the same reason.
            if (!excludedGroups.isEmpty()) out.put("excludedGroups", new ArrayList<>(excludedGroups.values()));
            return out;
        }

        public String hash() {
            return sha256(canonical(toMap()));
        }

        /**
         * An independent copy (D7-4): {@link #apply} on the copy never shows in this state. The collections are copied; the
         * map VALUES ({@code window}, {@code resolution}, group maps, {@code lastMerged}) are shared because {@code apply}
         * only ever REPLACES them, never edits one in place.
         */
        public State copy() {
            State c = new State();
            c.entities.putAll(entities);
            c.links.putAll(links);
            c.excluded.putAll(excluded);
            c.hidden.addAll(hidden);
            c.kept.addAll(kept);
            annotations.forEach((k, v) -> c.annotations.put(k, new ArrayList<>(v)));
            linkAnnotations.forEach((k, v) -> c.linkAnnotations.put(k, new ArrayList<>(v)));
            c.window = window;
            excludedKeys.forEach((k, v) -> c.excludedKeys.put(k, new TreeMap<>(v)));
            c.resolution = resolution;
            c.resolvedBy = resolvedBy;
            c.excludedGroups.putAll(excludedGroups);
            c.lastMerged = lastMerged;
            return c;
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong FOLDS = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong SERIALISED = new java.util.concurrent.atomic.AtomicLong();

    /** How many full-state serialisations ({@link State#toMap}, which {@link State#hash} runs) this JVM did - the Draft rebase cost test seam. */
    public static long serialisationCount() {
        return SERIALISED.get();
    }

    /** How many whole-log folds this JVM ran (D7-4 test seam: an incremental append must not add to it). */
    public static long foldCount() {
        return FOLDS.get();
    }

    /** What one step changed — the incremental response the SPA applies to its canvas. */
    public static Map<String, Object> delta(State before, State after) {
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
    public static Set<Integer> undone(List<Map<String, Object>> log) {
        Set<Integer> out = new HashSet<>();
        for (Map<String, Object> e : log)
            if ("undo".equals(e.get("kind")) && e.get("undoes") instanceof Number n) out.add(n.intValue());
        return out;
    }

    /** The latest op step not already undone, or -1 — what an undo appended now would revert. */
    public static int undoTarget(List<Map<String, Object>> log) {
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
    public static State evaluate(List<Map<String, Object>> log, int at, List<String> hashesOut) {
        List<Map<String, Object>> prefix = new ArrayList<>();
        for (Map<String, Object> e : log) {
            if (at >= 0 && ((Number) e.get("step")).intValue() > at) break;
            prefix.add(e);
        }
        if (hashesOut == null) return fold(prefix);
        State s = new State();   // replay: ops fold incrementally, so only an undo counts as a fold
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
        FOLDS.incrementAndGet();
        State s = new State();
        Set<Integer> undone = undone(log);
        for (Map<String, Object> e : log)
            if ("op".equals(e.get("kind")) && !undone.contains(((Number) e.get("step")).intValue())) apply(s, e);
        return s;
    }

    /** Apply one op entry to {@code s}. Total: unknown ids are no-ops (see the class note). */
    @SuppressWarnings("unchecked")
    public static void apply(State s, Map<String, Object> entry) {
        int step = ((Number) entry.get("step")).intValue();
        Map<String, Object> p = entry.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<String> ids = strings(p.get("ids"));
        switch (String.valueOf(entry.get("op"))) {
            case "seed" -> seed(s, ids, p.get("entityType") == null ? null : String.valueOf(p.get("entityType")), step);
            case "seedBy" -> seed(s, strings(((Map<String, Object>) entry.get("read")).get("ids")),
                    String.valueOf(((Map<String, Object>) entry.get("list")).get("entityType")), step);
            case "expand" -> {
                Map<String, Object> read = (Map<String, Object>) entry.get("read");
                Map<String, Object> query = (Map<String, Object>) read.get("query");
                Set<String> frontier = new HashSet<>(strings(query.get("frontier")));
                // LA-17 merged traversal: a member value not admitted yet -> the admitted entity it stands for.
                Map<String, Object> anchorOf = query.get("merged") instanceof Map<?, ?> mg
                        && mg.get("anchorOf") instanceof Map<?, ?> a ? (Map<String, Object>) a : Map.of();
                for (Object o : (List<Object>) read.get("rows")) {
                    Map<String, Object> r = (Map<String, Object>) o;
                    String src = String.valueOf(r.get("source")), tgt = String.valueOf(r.get("target"));
                    if (s.excluded.containsKey(src) || s.excluded.containsKey(tgt)) continue;
                    if (s.blockedByKey(src) || s.blockedByKey(tgt)) continue;   // excludeBy remembers keys
                    if (s.blockedByGroup(src) || s.blockedByGroup(tgt)) continue;   // a merged exclude remembers members
                    String end = reachable(s, frontier, anchorOf, src) ? src : reachable(s, frontier, anchorOf, tgt) ? tgt : null;
                    Entity from = end == null ? null : s.entities.get(end);
                    if (from == null && end != null) {   // a member value: the same identity as its anchor
                        Entity an = s.entities.get(String.valueOf(anchorOf.get(end)));
                        from = new Entity(end, null, an.hop(), an.seed(), step);
                        s.entities.put(end, from);
                    }
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
                if (Boolean.TRUE.equals(p.get("merged"))) mergedExclude(s, entry, ids, reason, step);
                else for (String id : ids) exclude(s, id, reason, step);
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
            case "threshold" -> threshold(s, p, step);
            case "snapshot", "compare" -> { }   // markers: the position (and, for compare, the sealed diff on the entry) is the artifact, the state does not move
            case "window" -> s.window = p.get("window") instanceof Map<?, ?> w ? (Map<String, Object>) w : null;
            case "resolve" -> {   // LA-17 slice 2: the sealed resolution, in force from here (see the class note)
                s.resolution = (Map<String, Object>) entry.get("resolution");
                s.resolvedBy = step;
            }
            case "annotate" -> {
                String note = String.valueOf(p.get("note"));
                String confidence = p.get("confidence") == null ? null : String.valueOf(p.get("confidence"));
                for (String id : ids)
                    if (s.entities.containsKey(id))
                        s.annotations.computeIfAbsent(id, k -> new ArrayList<>())
                                .add(new Annotation(step, note, confidence));
                if (p.get("links") instanceof List<?> ls)   // D-U9 remainder: a note on a link, by its key
                    for (Object o : ls) {
                        Map<?, ?> l = (Map<?, ?>) o;
                        String key = LinkIds.key(String.valueOf(l.get("source")), String.valueOf(l.get("target")),
                                String.valueOf(l.get("kind")));
                        if (s.links.containsKey(key))
                            s.linkAnnotations.computeIfAbsent(key, k -> new ArrayList<>())
                                    .add(new Annotation(step, note, confidence));
                    }
            }
            default -> throw new IllegalStateException("op '" + entry.get("op") + "' in a sealed log is not evaluable");
        }
    }

    /** An entity's degree: its distinct counterparties among the links of {@code s} (a self-loop is not a counterparty). */
    public static Map<String, Integer> degrees(State s) {
        Map<String, Set<String>> nbrs = new HashMap<>();
        for (Link l : s.links.values()) {
            if (l.source().equals(l.target())) continue;
            nbrs.computeIfAbsent(l.source(), k -> new HashSet<>()).add(l.target());
            nbrs.computeIfAbsent(l.target(), k -> new HashSet<>()).add(l.source());
        }
        Map<String, Integer> out = new TreeMap<>();
        for (String id : s.entities.keySet()) out.put(id, nbrs.getOrDefault(id, Set.of()).size());
        return out;
    }

    /** The {@code threshold} measures: the default {@code degree}, then {@code weightedDegree} and {@code eventCount}. */
    public static final List<String> MEASURES = List.of("degree", "weightedDegree", "eventCount");

    /** The measure a threshold names ({@code degree} when absent - the pre-measure shape, so old logs replay unchanged). */
    public static String measureOf(Map<String, Object> p) {
        return p.get("measure") == null ? "degree" : String.valueOf(p.get("measure"));
    }

    /**
     * Each admitted entity's value of {@code measure} over the links of {@code s} (self-loops never count, for every measure):
     * {@code degree} = distinct counterparties; {@code weightedDegree} = links touching it, each (kind, direction) once, so a
     * counterparty reached by two kinds weighs 2; {@code eventCount} = the sum of those links' folded event counts.
     */
    public static Map<String, Long> measures(State s, String measure) {
        if ("degree".equals(measure)) {
            Map<String, Long> out = new TreeMap<>();
            degrees(s).forEach((k, v) -> out.put(k, v.longValue()));
            return out;
        }
        Map<String, Long> out = new TreeMap<>();
        for (String id : s.entities.keySet()) out.put(id, 0L);
        for (Link l : s.links.values()) {
            if (l.source().equals(l.target())) continue;
            long w = "eventCount".equals(measure) ? l.count() : 1L;
            out.computeIfPresent(l.source(), (k, v) -> v + w);
            out.computeIfPresent(l.target(), (k, v) -> v + w);
        }
        return out;
    }

    /** The reason a {@code threshold} records for what it removed: names the measure and band, e.g. {@code degree outside [2, 5)}. */
    public static String thresholdReason(Map<String, Object> p) {
        return "threshold: " + measureOf(p) + " outside [" + (p.get("min") == null ? "0" : p.get("min")) + ", "
                + (p.get("max") == null ? "unbounded" : p.get("max")) + ")";
    }

    private static void threshold(State s, Map<String, Object> p, int step) {
        long min = p.get("min") instanceof Number n ? n.longValue() : 0;
        long max = p.get("max") instanceof Number n ? n.longValue() : Long.MAX_VALUE;
        String reason = thresholdReason(p);
        for (var d : measures(s, measureOf(p)).entrySet())
            if (d.getValue() < min || d.getValue() >= max) exclude(s, d.getKey(), reason, step);
    }

    /** A frontier end an expand can hang a row off: admitted, or a sealed member value whose anchor is admitted. */
    private static boolean reachable(State s, Set<String> frontier, Map<String, Object> anchorOf, String id) {
        if (!frontier.contains(id)) return false;
        if (s.entities.containsKey(id)) return true;
        return anchorOf.containsKey(id) && s.entities.containsKey(String.valueOf(anchorOf.get(id)));
    }

    /**
     * LA-17 merged traversal: exclude the named ids and the SEALED groups as a whole - every admitted entity carrying
     * a member key ({@link State#memberKeysOf}, typed or, when untyped, under the sealed normalisers), matched on the
     * state BEFORE anything leaves - remember the groups, and record in {@link State#lastMerged} exactly what happened.
     */
    @SuppressWarnings("unchecked")
    private static void mergedExclude(State s, Map<String, Object> entry, List<String> ids, String reason, int step) {
        Map<String, SortedSet<String>> sides = s.sides();
        List<Entity> had = new ArrayList<>(s.entities.values());
        TreeSet<String> unmatched = new TreeSet<>(), hit = new TreeSet<>(), ambiguous = new TreeSet<>();
        for (Object o : entry.get("groups") instanceof List<?> l ? l : List.of()) {
            Map<String, Object> g = new LinkedHashMap<>((Map<String, Object>) o);
            TreeSet<String> notCarried = new TreeSet<>(strings(g.get("members")));
            for (Entity e : had) {
                SortedSet<String> k = s.memberKeysOf(e, sides, g);
                if (k.isEmpty() && s.keysOf(e, sides).isEmpty() && rawMatches(g, e.id()).size() > 1) ambiguous.add(e.id());
                if (k.isEmpty()) continue;
                notCarried.removeAll(k);
                hit.add(e.id());
            }
            unmatched.addAll(notCarried);
            g.put("step", step);
            g.put("reason", reason);
            s.excludedGroups.putIfAbsent(String.valueOf(g.get("id")), g);
        }
        for (String id : ids) exclude(s, id, reason, step);
        for (String id : hit) exclude(s, id, reason, step);
        TreeSet<String> left = new TreeSet<>(), kept = new TreeSet<>();
        for (Entity e : had)
            if (!s.entities.containsKey(e.id())) left.add(e.id());
            else if (hit.contains(e.id())) kept.add(e.id());   // still here: keep protected it
        s.lastMerged = Map.of("left", new ArrayList<>(left), "kept", new ArrayList<>(kept),
                "unmatched", new ArrayList<>(unmatched), "ambiguous", new ArrayList<>(ambiguous));
    }

    /**
     * The member key of sealed group {@code g} that UNTYPED raw id {@code raw} makes - only when it is UNAMBIGUOUS:
     * exactly one member type's sealed normaliser maps it to a member (operator 2026-09-30, D-U11). Two or more -> empty:
     * the value stays unmatched (a merged exclude's line says so). Applied identically by a merged exclude, a merged
     * expand's member scan, {@link State#blockedByGroup} and the resolution view.
     */
    public static SortedSet<String> rawMemberKeys(Map<String, Object> g, String raw) {
        SortedSet<String> all = rawMatches(g, raw);
        return all.size() == 1 ? all : new TreeSet<>();
    }

    /** Every member key of sealed group {@code g} that raw id {@code raw} makes under each member type's sealed normaliser. */
    @SuppressWarnings("unchecked")
    public static SortedSet<String> rawMatches(Map<String, Object> g, String raw) {
        Set<String> members = new HashSet<>(strings(g.get("members")));
        SortedSet<String> out = new TreeSet<>();
        for (var n : ((Map<String, Object>) g.get("normalisers")).entrySet()) {
            String v = EntityTypes.normalise(String.valueOf(n.getValue()), raw);
            if (!v.isEmpty() && members.contains(n.getKey() + ":" + v)) out.add(n.getKey() + ":" + v);
        }
        return out;
    }

    /**
     * What each merged {@code exclude} did, at the state it ran on (PREFIX semantics, as {@link #entityCounts}):
     * step -> {@link State#lastMerged}. So its line says what left and what was not matched, never more (plan §5.10).
     */
    public static Map<Integer, Map<String, List<String>>> mergedExcludeOutcomes(List<Map<String, Object>> log) {
        Map<Integer, Map<String, List<String>>> out = new TreeMap<>();
        State s = new State();
        for (int k = 0; k < log.size(); k++) {
            Map<String, Object> e = log.get(k);
            if ("undo".equals(e.get("kind"))) s = fold(log.subList(0, k + 1));
            else if ("op".equals(e.get("kind"))) {
                apply(s, e);
                if ("exclude".equals(e.get("op")) && e.get("params") instanceof Map<?, ?> p && Boolean.TRUE.equals(p.get("merged")))
                    out.put(((Number) e.get("step")).intValue(), s.lastMerged);
            }
        }
        return out;
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
    public static List<Integer> entityCounts(List<Map<String, Object>> log) {
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

    public static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
        return out;
    }

    public static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise investigation state", e);
        }
    }

    public static String sha256(String text) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
