package com.gamma.la.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * LA-SEED-CONNECT-1 - the pair table behind the {@code connect} op: for every unordered pair of seeds, the shortest
 * connection within {@code maxHops}, or the statement that there is none.
 *
 * <p>One bounded breadth-first search PER SEED (N searches, never N(N-1)/2), each level one {@link LevelReader} call - the
 * Investigation's own one-hop read, so the edge index answers it when it can and the Dataset otherwise. A pair is read off
 * the search of its first seed: its distance, its route count (shortest paths, counted per level) and one example route
 * (the first parent found, which is the strongest link because a read is ordered strongest first). A pair with no route
 * within {@code maxHops} is DISCONNECTED and is never retried deeper (operator 2026-10-13).
 *
 * <p><b>Hub rule.</b> An entity the read flags as a hub (above the rung's hub threshold) is reached and recorded but never
 * walked THROUGH - supernode suppression, as an expand's frontier - and a pair that came out disconnected names the hubs
 * that could have joined it, reached from both ends within the hop limit ({@code notWalked}), so "not connected" is never silently a hub the search refused to cross.
 * Pure: no HTTP, no store, no masking - the caller seals the result and the response masks it.
 */
final class ConnectSeeds {

    /** The seed cap: 20 seeds are 190 pairs from 20 searches. */
    static final int MAX_SEEDS = 20;
    /** The hop ceiling (operator 2026-10-13: the hop limit is 3). */
    static final int MAX_HOPS = 3;
    private static final int MAX_NOT_WALKED = 10;

    private ConnectSeeds() {}

    /** One level of one search: the one-hop read for {@code frontier} - {@code {rows[{source,target,kind,count}], truncated, hubs[{id,degree}]}}. */
    interface LevelReader {
        Map<String, Object> read(List<String> frontier);
    }

    /** {@code pairs} one per unordered pair (seed order); {@code rows} the links of the example routes; {@code hubs} the hubs reached; {@code noLinks} seeds whose first read found nothing. */
    record Result(List<Map<String, Object>> pairs, List<Map<String, Object>> rows, boolean truncated,
                  List<Map<String, Object>> hubs, List<String> noLinks, int reads) {}

    private static final class Search {
        final Map<String, Integer> dist = new HashMap<>();
        final Map<String, Long> sigma = new HashMap<>();
        final Map<String, String> parent = new HashMap<>();
        final Map<String, Map<String, Object>> edge = new HashMap<>();   // undirected pair key -> its strongest row
        final Map<String, Long> hubs = new TreeMap<>();                  // hub id -> degree, reached in THIS search
    }

    static Result compute(List<String> seeds, int maxHops, Set<String> excluded, LevelReader reader) {
        List<Search> searches = new ArrayList<>();
        boolean truncated = false;
        int reads = 0;
        List<String> noLinks = new ArrayList<>();
        for (String seed : seeds) {
            Search s = new Search();
            s.dist.put(seed, 0);
            s.sigma.put(seed, 1L);
            List<String> level = List.of(seed);
            for (int d = 0; d < maxHops && !level.isEmpty(); d++) {
                Map<String, Object> read = reader.read(level);
                reads++;
                truncated |= Boolean.TRUE.equals(read.get("truncated"));
                if (read.get("hubs") instanceof List<?> hs)
                    for (Object o : hs)
                        if (o instanceof Map<?, ?> h && h.get("id") != null && !seeds.contains(String.valueOf(h.get("id"))))   // a seed is always walkable
                            s.hubs.put(String.valueOf(h.get("id")), h.get("degree") instanceof Number n ? n.longValue() : 0L);
                Set<String> inLevel = new HashSet<>(level);
                Set<String> pairsSeen = new HashSet<>();
                boolean any = false;
                for (Object o : (List<?>) read.get("rows")) {
                    @SuppressWarnings("unchecked") Map<String, Object> row = (Map<String, Object>) o;
                    String u = String.valueOf(row.get("source")), v = String.valueOf(row.get("target"));
                    if (u.equals(v) || excluded.contains(u) || excluded.contains(v)) continue;
                    any = true;
                    String key = u.compareTo(v) <= 0 ? u + '\n' + v : v + '\n' + u;
                    if (!pairsSeen.add(key)) continue;   // one pair, however many kinds / directions it was read in
                    s.edge.putIfAbsent(key, row);
                    if (inLevel.contains(u) == inLevel.contains(v)) continue;   // inside the level, or not touching it: no new distance
                    String x = inLevel.contains(u) ? u : v, y = x.equals(u) ? v : u;
                    Integer dy = s.dist.get(y);
                    if (dy == null) {
                        s.dist.put(y, d + 1);
                        s.sigma.put(y, s.sigma.get(x));
                        s.parent.put(y, x);
                    } else if (dy == d + 1) {
                        s.sigma.merge(y, s.sigma.get(x), Long::sum);
                    }
                }
                if (d == 0 && !any) noLinks.add(seed);
                TreeSet<String> next = new TreeSet<>();
                for (var e : s.dist.entrySet()) if (e.getValue() == d + 1 && !s.hubs.containsKey(e.getKey())) next.add(e.getKey());
                level = new ArrayList<>(next);
            }
            searches.add(s);
        }
        List<Map<String, Object>> pairs = new ArrayList<>();
        Map<String, Map<String, Object>> routeRows = new TreeMap<>();
        for (int i = 0; i < seeds.size(); i++)
            for (int j = i + 1; j < seeds.size(); j++) {
                Search s = searches.get(i);
                String a = seeds.get(i), b = seeds.get(j);
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("a", a);
                pair.put("b", b);
                Integer hops = s.dist.get(b);
                if (hops == null) {
                    pair.put("connected", false);
                    Search t = searches.get(j);
                    TreeSet<String> held = new TreeSet<>();   // only a hub BOTH searches reached close enough that walking it could have joined the pair
                    for (String h : s.hubs.keySet())
                        if (t.dist.containsKey(h) && s.dist.get(h) + t.dist.get(h) <= maxHops) held.add(h);
                    held.remove(a);
                    held.remove(b);
                    if (!held.isEmpty()) pair.put("notWalked", new ArrayList<>(held).subList(0, Math.min(held.size(), MAX_NOT_WALKED)));
                } else {
                    List<String> route = new ArrayList<>();
                    for (String at = b; at != null; at = s.parent.get(at)) route.add(0, at);
                    pair.put("connected", true);
                    pair.put("hops", hops);
                    pair.put("routes", s.sigma.get(b));
                    pair.put("route", route);
                    for (int k = 0; k + 1 < route.size(); k++) {
                        String u = route.get(k), v = route.get(k + 1);
                        Map<String, Object> row = s.edge.get(u.compareTo(v) <= 0 ? u + '\n' + v : v + '\n' + u);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("source", row.get("source"));
                        m.put("target", row.get("target"));
                        m.put("kind", row.get("kind"));
                        m.put("count", row.get("count"));
                        routeRows.putIfAbsent(m.get("source") + "\n" + m.get("target") + "\n" + m.get("kind"), m);
                    }
                }
                pairs.add(pair);
            }
        TreeMap<String, Long> hubDegree = new TreeMap<>();
        for (Search s : searches) hubDegree.putAll(s.hubs);
        List<Map<String, Object>> hubs = new ArrayList<>();
        for (var h : hubDegree.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getKey());
            m.put("degree", h.getValue());
            hubs.add(m);
        }
        List<Map<String, Object>> rows = new ArrayList<>(routeRows.values());
        rows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("source")))
                .thenComparing(r -> String.valueOf(r.get("target"))).thenComparing(r -> String.valueOf(r.get("kind"))));
        return new Result(pairs, rows, truncated, hubs, noLinks, reads);
    }
}
