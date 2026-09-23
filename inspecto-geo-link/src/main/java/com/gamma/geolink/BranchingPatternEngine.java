package com.gamma.geolink;

import com.gamma.geolink.PatternQueryCompiler.Stage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The windowed search of a branching motif over the legs {@link PatternQueryCompiler} selected — a line-for-line
 * port of {@code matchBranchingPattern} in {@code inspecto-ui/src/app/inspecto/graph/branching-pattern-engine.ts}.
 * Keep the two in step: {@code ControlApiInvPatternTest} and {@code branching-parity.spec.ts} assert the
 * SAME golden fixture ({@code inspecto-ui/src/app/modules/admin/studio/link-analysis/branching-parity.fixture.json}).
 *
 * <p>Differences from the browser, all deliberate: there is no node cap (the SQL prune bounds the input
 * instead), and the anchors of stage 0 are visited in key order rather than projection order — only which
 * matches a {@code limit} cuts can differ, never whether a match exists.
 */
final class BranchingPatternEngine {

    /** Ceiling on leg examinations per run — the TS {@code BRANCHING_WORK_BUDGET}. */
    static final int WORK_BUDGET = 500_000;

    private BranchingPatternEngine() {}

    /** A leg of a stage: its id, endpoints (normalised keys) and time (epoch ms, null when unknown). */
    record Edge(String id, String source, String target, Long t) {}

    record Match(List<String> nodeIds, List<String> edgeIds, List<List<String>> layers) {}

    record Result(List<Match> matches, boolean truncated) {}

    /** A leg in the frame of the stage node it is grouped under: {@code other} is the counterparty. */
    private record Leg(String edgeId, String other, Long t) {}

    private record Window(List<Leg> legs, Long arrival) {}

    private record State(Map<String, Long> frontier, Set<String> used, List<String> edges, List<List<String>> layers) {}

    /** TS {@code followsInTime} (LA-14a): strictly after, within the gap; an unknown time on either side fails. */
    static boolean followsInTime(Long prev, Long t, Stage st) {
        if (!st.afterPrevious()) return true;
        if (t == null || prev == null) return false;
        if (t <= prev) return false;
        return st.maxGapHours() == null || t - prev <= st.maxGapHours() * 3_600_000;
    }

    private static Long completion(List<Leg> ordered, int min) {
        Set<String> seen = new HashSet<>();
        for (Leg l : ordered) {
            seen.add(l.other());
            if (seen.size() >= min) return l.t();
        }
        return null;
    }

    /** TS {@code selectWindow}: the EARLIEST window holding ≥ {@code min} distinct counterparties. */
    private static Window selectWindow(List<Leg> legs, int min, Double windowHours) {
        Set<String> distinct = new HashSet<>();
        for (Leg l : legs) distinct.add(l.other());
        if (distinct.size() < min) return null;
        List<Leg> timed = new ArrayList<>();
        for (Leg l : legs) if (l.t() != null) timed.add(l);
        timed.sort(Comparator.comparingLong(Leg::t));   // stable, like Array.prototype.sort
        if (windowHours == null || windowHours <= 0) return new Window(legs, completion(timed, min));
        double span = windowHours * 3_600_000;
        for (int i = 0, j = 0; i < timed.size(); i++) {
            while (j < timed.size() && timed.get(j).t() - timed.get(i).t() <= span) j++;
            List<Leg> win = timed.subList(i, j);
            Set<String> d = new HashSet<>();
            for (Leg l : win) d.add(l.other());
            if (d.size() >= min) return new Window(new ArrayList<>(win), completion(win, min));
        }
        return null;
    }

    /** TS {@code earliestPerNode}: each reached node with the EARLIEST time a counted leg reached it. */
    private static Map<String, Long> earliestPerNode(List<Leg> legs) {
        Map<String, Long> next = new LinkedHashMap<>();
        for (Leg l : legs) {
            Long prev = next.get(l.other());
            if (!next.containsKey(l.other()) || (l.t() != null && (prev == null || l.t() < prev))) next.put(l.other(), l.t());
        }
        return next;
    }

    /**
     * Find every match. {@code legsByStage.get(i)} are stage i's ELIGIBLE legs (kind + threshold already
     * applied). Every projected node is an {@code entity} — see {@link #kindOk}.
     */
    static Result match(List<Stage> stages, List<List<Edge>> legsByStage, int limit) {
        List<Map<String, List<Leg>>> outIdx = new ArrayList<>(), inIdx = new ArrayList<>();
        Set<String> nodes = new java.util.TreeSet<>();
        for (List<Edge> legs : legsByStage) {
            Map<String, List<Leg>> out = new LinkedHashMap<>(), in = new LinkedHashMap<>();
            for (Edge e : legs) {
                if (e.source().equals(e.target())) continue;
                out.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(new Leg(e.id(), e.target(), e.t()));
                in.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(new Leg(e.id(), e.source(), e.t()));
                nodes.add(e.source());
                nodes.add(e.target());
            }
            outIdx.add(out);
            inIdx.add(in);
        }
        Search s = new Search(stages, outIdx, limit);
        Stage first = stages.get(0);
        for (String n : nodes) {
            if (s.truncated || s.matches.size() >= limit) break;
            List<Leg> legs = (first.fanIn() ? inIdx.get(0) : outIdx.get(0)).getOrDefault(n, List.of());
            if (!s.spend(legs.size() + 1)) break;
            if (first.fanIn() && !kindOk(first.nodeKind())) continue;
            List<Leg> usable = !first.fanIn() && !kindOk(first.nodeKind()) ? List.of() : legs;
            Window win = selectWindow(usable, first.minBranches(), first.windowHours());
            if (win == null) continue;
            List<String> others = new ArrayList<>(new LinkedHashSet<>(win.legs().stream().map(Leg::other).toList()));
            List<String> edges = win.legs().stream().map(Leg::edgeId).toList();
            if (first.fanIn()) {
                Set<String> used = new LinkedHashSet<>(others);
                used.add(n);
                s.extend(1, new State(single(n, win.arrival()), used, edges, List.of(others, List.of(n))));
            } else {
                Set<String> used = new LinkedHashSet<>();
                used.add(n);
                used.addAll(others);
                s.extend(1, new State(earliestPerNode(win.legs()), used, edges, List.of(List.of(n), others)));
            }
        }
        if (s.matches.size() >= limit) s.truncated = true;
        return new Result(s.matches, s.truncated);
    }

    /** Every projected node is an {@code entity}; a stage naming another node kind matches nothing, as in the browser. */
    private static boolean kindOk(String k) {
        return k == null || PatternQueryCompiler.ENTITY_KIND.equals(k);
    }

    private static Map<String, Long> single(String node, Long at) {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put(node, at);
        return m;
    }

    private static final class Search {
        final List<Stage> stages;
        final List<Map<String, List<Leg>>> out;
        final int limit;
        final List<Match> matches = new ArrayList<>();
        long work;
        boolean truncated;

        Search(List<Stage> stages, List<Map<String, List<Leg>>> out, int limit) {
            this.stages = stages;
            this.out = out;
            this.limit = limit;
        }

        boolean spend(int n) {
            work += n;
            if (work > WORK_BUDGET) truncated = true;
            return !truncated;
        }

        void extend(int stageIdx, State s) {
            if (truncated || matches.size() >= limit) return;
            if (stageIdx >= stages.size()) {
                matches.add(new Match(new ArrayList<>(s.used()), new ArrayList<>(new LinkedHashSet<>(s.edges())), s.layers()));
                return;
            }
            Stage st = stages.get(stageIdx);
            Map<String, List<Leg>> idx = out.get(stageIdx);
            if (!st.fanIn()) {
                List<Leg> legs = new ArrayList<>();
                for (Map.Entry<String, Long> f : s.frontier().entrySet()) {
                    List<Leg> cand = idx.getOrDefault(f.getKey(), List.of());
                    if (!spend(cand.size())) return;
                    for (Leg l : cand) {
                        if (s.used().contains(l.other()) || !kindOk(st.nodeKind())) continue;
                        if (followsInTime(f.getValue(), l.t(), st)) legs.add(l);
                    }
                }
                Window win = selectWindow(legs, st.minBranches(), st.windowHours());
                if (win == null) return;
                Map<String, Long> next = earliestPerNode(win.legs());
                Set<String> used = new LinkedHashSet<>(s.used());
                used.addAll(next.keySet());
                List<String> edges = new ArrayList<>(s.edges());
                win.legs().forEach(l -> edges.add(l.edgeId()));
                List<List<String>> layers = new ArrayList<>(s.layers());
                layers.add(new ArrayList<>(next.keySet()));
                extend(stageIdx + 1, new State(next, used, edges, layers));
                return;
            }
            Map<String, List<Leg>> byCollector = new LinkedHashMap<>();
            for (Map.Entry<String, Long> f : s.frontier().entrySet()) {
                List<Leg> cand = idx.getOrDefault(f.getKey(), List.of());
                if (!spend(cand.size())) return;
                for (Leg l : cand) {
                    if (s.used().contains(l.other()) || !kindOk(st.nodeKind()) || !followsInTime(f.getValue(), l.t(), st)) continue;
                    // Re-framed so `other` is the SENDER — breadth is counted over distinct frontier members.
                    byCollector.computeIfAbsent(l.other(), k -> new ArrayList<>()).add(new Leg(l.edgeId(), f.getKey(), l.t()));
                }
            }
            for (Map.Entry<String, List<Leg>> c : byCollector.entrySet()) {
                if (truncated || matches.size() >= limit) return;
                if (!spend(c.getValue().size())) return;
                Window win = selectWindow(c.getValue(), st.minBranches(), st.windowHours());
                if (win == null) continue;
                Set<String> used = new LinkedHashSet<>(s.used());
                used.add(c.getKey());
                List<String> edges = new ArrayList<>(s.edges());
                win.legs().forEach(l -> edges.add(l.edgeId()));
                List<List<String>> layers = new ArrayList<>(s.layers());
                layers.add(List.of(c.getKey()));
                extend(stageIdx + 1, new State(single(c.getKey(), win.arrival()), used, edges, layers));
            }
        }
    }
}
