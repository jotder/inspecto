package com.gamma.la.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.la.graph.GraphAlgorithms.Direction;
import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphAlgorithms.Selection;
import com.gamma.la.graph.GraphSuspicion.Suspicion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-mobile-money} Space Template's seven planted stories (docs/superpower/la-multi-vertical-showcase-plan.md,
 * vertical A), over the SHIPPED corpus ({@code spaces/_templates/la-mobile-money/data}, read in place). The link table is
 * folded the way the {@code mm_links} Job folds it (one link per transaction; shared_device / shared_id pairs from
 * wallet_master, placeholders excluded, a key held by more than 12 wallets excluded, lower id to higher id); the graph is
 * one edge per distinct (src, dst, kind), edge weight = the summed amount. The Job's SQL itself is run by
 * {@code MmLinksJobGoldenTest} in inspecto-engine. Expectations: {@code la-mm-expected.json}.
 */
class LaMobileMoneyGoldenTest {

    private static final Path DATA = Path.of("..", "..", "spaces", "_templates", "la-mobile-money", "data");
    private static final JsonNode E = expected();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> PLACEHOLDER = Set.of("", "UNKNOWN", "N/A", "000000000000000", "PENDING");
    private static final int MAX_KEY = 12;

    record Txn(String id, String src, String dst, String type, double amount, LocalDateTime ts) {}

    record Wallet(String id, String nid, String device, String opened, double prior) {}

    private static List<Txn> landed;
    private static Map<String, Wallet> wallets;
    private static List<String[]> shared;   // {src, dst, kind, via}

    private static JsonNode expected() {
        try (var in = LaMobileMoneyGoldenTest.class.getResourceAsStream("/la-mm-expected.json")) {
            return new ObjectMapper().readTree(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String[]> csv(Path p) throws Exception {
        assertTrue(Files.isRegularFile(p), "the template ships no " + p.toAbsolutePath().normalize());
        List<String> lines = Files.readAllLines(p);
        List<String[]> out = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) out.add(l.split(",", -1));
        return out;
    }

    private static List<Txn> txns(Path dir) throws Exception {
        List<Txn> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted().toList())
                for (String[] c : csv(p)) out.add(new Txn(c[0], c[1], c[2], c[3], Double.parseDouble(c[4]), LocalDateTime.parse(c[7], TS)));
        }
        return out;
    }

    @BeforeAll
    static void load() throws Exception {
        landed = txns(DATA.resolve("inbox").resolve("wallet_txn"));
        wallets = new LinkedHashMap<>();
        for (String[] c : csv(DATA.resolve("inbox/wallet_master/WALLET_MASTER_20261014.csv")))
            wallets.put(c[0], new Wallet(c[0], c[2], c[3], c[5], Double.parseDouble(c[7])));
        shared = new ArrayList<>();
        for (String kind : List.of("shared_device", "shared_id")) {
            Map<String, List<String>> byKey = new TreeMap<>();
            for (Wallet w : wallets.values()) {
                String k = kind.equals("shared_device") ? w.device() : w.nid();
                if (k == null || k.isBlank() || PLACEHOLDER.contains(k.trim().toUpperCase())) continue;
                byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(w.id());
            }
            for (var e : byKey.entrySet()) {
                List<String> ids = e.getValue().stream().sorted().toList();
                if (ids.size() < 2 || ids.size() > MAX_KEY) continue;
                for (int i = 0; i < ids.size(); i++)
                    for (int j = i + 1; j < ids.size(); j++)
                        shared.add(new String[]{ids.get(i), ids.get(j), kind, (kind.equals("shared_device") ? "device:" : "id:") + e.getKey()});
            }
        }
    }

    private static Graph fold(List<Txn> rows) {
        Set<String> nodes = new LinkedHashSet<>();
        Map<String, String[]> ends = new LinkedHashMap<>();
        for (Txn t : rows) put(nodes, ends, t.src(), t.dst(), t.type());
        for (String[] s : shared) put(nodes, ends, s[0], s[1], s[2]);
        List<Edge> edges = new ArrayList<>();
        int n = 0;
        for (String[] e : ends.values()) edges.add(new Edge("e" + n++, e[0], e[1]));
        return new Graph(nodes.stream().map(x -> new Node(x, x)).toList(), edges);
    }

    private static void put(Set<String> nodes, Map<String, String[]> ends, String s, String d, String k) {
        nodes.add(s);
        nodes.add(d);
        ends.putIfAbsent(s + "|" + d + "|" + k, new String[]{s, d});
    }

    /** Edge weights: the summed amount per (src, dst) edge; shared links weigh 1. */
    private static Map<String, Double> weights(Graph g, List<Txn> rows) {
        Map<String, Double> sum = new HashMap<>();
        for (Txn t : rows) sum.merge(t.src() + "|" + t.dst(), t.amount(), Double::sum);
        Map<String, Double> w = new HashMap<>();
        for (Edge e : g.edges()) {
            Double s = sum.get(e.source() + "|" + e.target());
            if (s != null) w.put(e.id(), s);
        }
        return w;
    }

    private static boolean chain(Txn t) {
        return Stream.of("STRUCT-", "HUB-", "RELAY-").anyMatch(p -> t.src().startsWith(p) || t.dst().startsWith(p));
    }

    private static List<Txn> where(Predicate<Txn> p) {
        return landed.stream().filter(p).toList();
    }

    @Test
    void corpusSizesAndTheStructuringBand() {
        assertEquals(E.get("landedRows").asInt(), landed.size(), "landed rows");
        assertEquals(E.get("wallets").asInt(), wallets.size(), "wallets");
        double lo = E.get("tierLimit").asDouble() * 0.95, hi = E.get("tierLimit").asDouble();
        List<Txn> band = where(t -> t.amount() >= lo && t.amount() < hi);
        assertTrue(band.stream().allMatch(LaMobileMoneyGoldenTest::chain), "ordinary traffic never sits in the top-5% band: " + band.size() + " legs, all the planted chain");
        assertEquals(50, band.stream().filter(t -> t.src().startsWith("STRUCT-") || t.dst().startsWith("STRUCT-")).count(), "25 cash-ins + 25 wallet-to-hub legs");
    }

    @Test
    void story1_theRogueAgentCashesInFor25WalletsInsideThirtySixHours() {
        JsonNode s = E.get("structuring");
        List<Txn> in = where(t -> t.src().equals(s.get("agent").asText()) && t.type().equals("cash_in"));
        assertEquals(s.get("wallets").asInt(), in.stream().map(Txn::dst).distinct().count(), "fan-in of the rogue agent");
        LocalDateTime first = in.stream().map(Txn::ts).min(LocalDateTime::compareTo).get();
        LocalDateTime last = in.stream().map(Txn::ts).max(LocalDateTime::compareTo).get();
        assertTrue(Duration.between(first, last).toHours() <= s.get("maxSpanHours").asInt(), "span " + Duration.between(first, last));
        List<Txn> toHub = where(t -> t.dst().equals(s.get("hub").asText()) && t.type().equals("p2p"));
        assertEquals(25, toHub.stream().map(Txn::src).distinct().count(), "the hub is paid by 25 distinct wallets");
        assertTrue(toHub.stream().allMatch(t -> t.amount() >= 4750 && t.amount() < 5000), "every leg in the band");
        assertTrue(where(t -> t.src().equals("AG-18") || t.dst().equals("AG-18")).stream().allMatch(t -> t.dst().startsWith("STRUCT-")),
                "the rogue agent serves nobody else");
    }

    @Test
    void story2_theMuleChainPassesThrough98PercentAndMaxFlowEqualsTheForwardedTotal() {
        JsonNode c = E.get("chain");
        String hub = c.get("from").asText(), end = c.get("to").asText();
        List<Txn> ins = where(t -> t.dst().equals(hub));
        double in = ins.stream().mapToDouble(Txn::amount).sum();
        List<Txn> outs = where(t -> t.src().equals(hub));
        double out = outs.stream().mapToDouble(Txn::amount).sum();
        assertEquals(c.get("passThrough").asDouble(), out / in, 0.001, "pass-through");
        for (Txn o : outs) {
            LocalDateTime lastIn = ins.stream().map(Txn::ts).filter(x -> !x.isAfter(o.ts())).max(LocalDateTime::compareTo).orElseThrow();
            assertTrue(Duration.between(lastIn, o.ts()).toMinutes() <= c.get("maxForwardMinutes").asInt(), "forwarded late: " + o);
        }
        Graph g = fold(landed);
        Selection p = GraphAlgorithms.shortestPath(g, hub, end, Direction.OUT);
        assertNotNull(p, "a directed route exists");
        assertEquals(c.get("hops").asInt(), p.edgeIds().size(), "hub to cash-out agent in hops: " + p.nodeIds());
        GraphPaths.MaxFlowResult f = GraphPaths.maxFlow(g, weights(g, landed), hub, end);
        assertEquals(out, f.value(), 0.01, "max flow hub to the cash-out agent equals the forwarded total");
        List<Selection> all = GraphPaths.allPaths(g, hub, end, 10, 8, Direction.OUT);
        assertEquals(2, all.size(), "two routes (via RELAY-1 and RELAY-2): " + all);
    }

    @Test
    void story3_theDeviceFarmIsOneHandsetAndNineDifferentIds() {
        JsonNode f = E.get("farm");
        List<Wallet> farm = wallets.values().stream().filter(w -> w.id().startsWith("FARM-")).toList();
        assertEquals(f.get("wallets").asInt(), farm.size());
        assertTrue(farm.stream().allMatch(w -> w.device().equals(f.get("device").asText())));
        assertEquals(farm.size(), farm.stream().map(Wallet::nid).distinct().count(), "nine DIFFERENT national ids");
        assertEquals(f.get("sharedDevicePairs").asInt(), shared.stream().filter(s -> s[2].equals("shared_device") && s[0].startsWith("FARM-")).count());
        assertTrue(shared.stream().filter(s -> s[2].equals("shared_id")).noneMatch(s -> s[0].startsWith("FARM-") || s[1].startsWith("FARM-")),
                "the ids do NOT link the farm: only the handset does");
        Graph g = fold(landed);
        List<Score> j = GraphCentrality.jaccardSimilarity(g, "FARM-1");
        Set<String> top = new TreeSet<>();
        for (int i = 0; i < 8; i++) {
            top.add(j.get(i).id());
            assertTrue(j.get(i).score() > f.get("jaccardMin").asDouble(), j.get(i) + " counterparty Jaccard");
        }
        Set<String> others = new TreeSet<>();
        for (int i = 2; i <= 9; i++) others.add("FARM-" + i);
        assertEquals(others, top, "the eight most similar wallets are the rest of the farm");
    }

    @Test
    void story4_theRoundTripLoopIsTheOnlyDirectedCycle() {
        Graph g = fold(landed);
        List<Selection> cycles = GraphStructure.findCycles(g, 50, 8);
        assertEquals(1, cycles.size(), "the loop is the ONLY directed cycle: " + cycles);
        Set<String> want = new TreeSet<>();
        E.get("loop").forEach(n -> want.add(n.asText()));
        assertEquals(want, new TreeSet<>(cycles.get(0).nodeIds()));
        assertEquals(4, new HashSet<>(cycles.get(0).nodeIds()).size());
        List<Txn> laps = where(t -> t.src().startsWith("LOOP-") && t.dst().startsWith("LOOP-"));
        assertEquals(20, laps.size());
        assertTrue(laps.stream().allMatch(t -> t.amount() == 1500.00), "the same amount all the way round");
    }

    @Test
    void story5_theDormantWalletMoves40TimesItsPriorVolumeInTwoDays() {
        JsonNode d = E.get("dormant");
        String w = d.get("wallet").asText();
        assertEquals(d.get("priorLifetimeVolume").asDouble(), wallets.get(w).prior(), 0.001);
        assertTrue(wallets.get(w).opened().compareTo("2025-12-01") < 0, "opened 11 months before the window");
        List<Txn> mine = where(t -> t.src().equals(w) || t.dst().equals(w));
        assertEquals(d.get("firstDay").asText(), mine.stream().map(t -> t.ts().toLocalDate().toString()).min(String::compareTo).get());
        assertEquals(d.get("lastDay").asText(), mine.stream().map(t -> t.ts().toLocalDate().toString()).max(String::compareTo).get());
        double out = where(t -> t.src().equals(w)).stream().mapToDouble(Txn::amount).sum();
        assertEquals(d.get("multiple").asInt() * d.get("priorLifetimeVolume").asDouble(), out, 0.001, "outflow = 40 x prior lifetime volume");
    }

    @Test
    void story6_theBillerIsABenignOneWayHub() {
        JsonNode b = E.get("biller");
        String id = b.get("id").asText();
        assertEquals(b.get("payers").asInt(), where(t -> t.dst().equals(id)).stream().map(Txn::src).distinct().count());
        assertTrue(where(t -> t.src().equals(id)).isEmpty(), "one-way: the biller pays nobody");
        Graph g = fold(landed);
        List<Suspicion> ranked = GraphSuspicion.suspicionScore(g);
        assertEquals(id, ranked.get(0).id(), "high connectivity is not guilt: the biller tops the default Suspicion score");
        assertEquals(0, where(t -> t.src().equals(id)).size(), "...but its shape is one-way, many payers, nothing out");
        long farmInTop = ranked.stream().limit(15).filter(x -> x.id().startsWith("FARM-")).count();
        assertEquals(9, farmInTop, "the nine device-farm wallets sit in the default top 15 (k-core and triangles)");
        // The flow-weighted run an analyst chooses for a mule hunt: betweenness 3, PageRank 3, no k-core, no triangles.
        List<Suspicion> flow = GraphSuspicion.suspicionScore(g, new GraphSuspicion.Weights(1, 3, 3, 0, 0));
        int hubRank = 1 + (int) flow.stream().takeWhile(x -> !x.id().equals("HUB-01")).count();
        int billerRank = 1 + (int) flow.stream().takeWhile(x -> !x.id().equals(id)).count();
        assertEquals(1, billerRank, "the biller still leads: explained by its factor breakdown, not hidden");
        assertTrue(hubRank <= 3, "the mule hub ranks " + hubRank + " in the flow-weighted Suspicion score");
        assertTrue(flow.stream().limit(10).noneMatch(x -> x.id().startsWith("FARM-")), "the farm drops out of the flow-weighted top 10");
    }

    @Test
    void sharedLinksSkipPlaceholdersAndKeepTheBenignLookAlikesApart() {
        JsonNode p = E.get("placeholders");
        Map<String, Long> held = new HashMap<>();
        for (Wallet w : wallets.values()) if (PLACEHOLDER.contains(w.device().toUpperCase())) held.merge(w.device(), 1L, Long::sum);
        p.fields().forEachRemaining(e -> assertEquals(e.getValue().asLong(), held.getOrDefault(e.getKey(), 0L),
                "placeholder '" + e.getKey() + "' is held by this many wallets - under the pair cap, so ONLY the explicit exclusion keeps it out"));
        assertTrue(held.values().stream().allMatch(n -> n <= MAX_KEY));
        assertTrue(shared.stream().noneMatch(s -> PLACEHOLDER.stream().anyMatch(ph -> s[3].equalsIgnoreCase("device:" + ph) || s[3].equalsIgnoreCase("id:" + ph))));
        JsonNode h = E.get("household");
        assertEquals(h.get("sharedIdPairs").asInt(), shared.stream().filter(s -> s[2].equals("shared_id")).count());
        assertEquals(h.get("familyPhonePairs").asInt(), shared.stream().filter(s -> s[3].equals("device:DEV-FAMILY-01")).count());
        assertTrue(shared.stream().allMatch(s -> s[0].compareTo(s[1]) < 0), "one direction, lower id to higher id");
    }

    @Test
    void theStagedDaysAddNoCycleAndNoStructuring() throws Exception {
        List<Txn> all = new ArrayList<>(landed);
        List<Txn> staged = txns(DATA.resolve("staged").resolve("wallet_txn"));
        assertEquals(2, staged.stream().map(t -> t.ts().toLocalDate()).distinct().count(), "the next day and the gap day");
        all.addAll(staged);
        Graph g = fold(all);
        assertEquals(1, GraphStructure.findCycles(g, 50, 8).size(), "still only the loop");
        assertTrue(staged.stream().noneMatch(t -> t.amount() >= 4750 && t.amount() < 5000), "no new structuring leg");
        assertFalse(staged.isEmpty());
    }
}
