package com.gamma.la.graph;

import com.gamma.la.graph.GraphAlgorithms.Edge;
import com.gamma.la.graph.GraphAlgorithms.Graph;
import com.gamma.la.graph.GraphAlgorithms.Node;
import com.gamma.la.graph.GraphAlgorithms.Score;
import com.gamma.la.graph.GraphCentrality.Method;
import com.gamma.la.graph.GraphCentrality.PredictedLink;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code la-audit} Space Template's seven planted stories (plan section 2.B), over the SHIPPED corpus
 * ({@code spaces/_templates/la-audit/data}, read in place). The links are folded with the same rules the template's
 * Jobs use (shared-attribute pairs once, placeholder and blank values excluded, pair cap 10), so a regression in the
 * generator {@code tools/gen-la-audit-data.py} cannot silently break a promise a presenter makes.
 */
class LaAuditGoldenTest {

    private static final Path DATA = Path.of("..", "..", "spaces", "_templates", "la-audit", "data");
    private static final double LIMIT = 10000.0;
    private static final Set<String> PLACEHOLDER = Set.of("", "PENDING");

    private static List<String[]> load(String sub) {
        List<String[]> out = new ArrayList<>();
        try (var files = Files.list(DATA.resolve("inbox").resolve(sub))) {
            for (Path p : files.sorted().toList()) {
                List<String> lines = Files.readAllLines(p);
                for (String l : lines.subList(1, lines.size())) out.add(l.split(",", -1));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static final List<String[]> INVOICES = load("ap_invoice");     // id, vendor, no, entity, date, payDate, amount, ccy, account
    private static final List<String[]> APPROVALS = load("ap_approval");   // id, invoice, approver, at, date, level
    private static final List<String[]> VENDORS = load("vendor_master");   // id, name, account, address, phone, category, onboarded
    private static final List<String[]> EMPLOYEES = load("employee_master"); // id, name, manager, dept, account, address, limit, hired
    private static final List<String[]> JOURNAL = load("gl_journal");      // id, line, date, at, from, to, account, amount, desc

    /** One edge per distinct (src, dst, kind); links are {src, dst, kind}. */
    private static Graph graph(Collection<String[]> links) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, Edge> edges = new LinkedHashMap<>();
        for (String[] l : links) {
            nodes.putIfAbsent(l[0], new Node(l[0], l[0]));
            nodes.putIfAbsent(l[1], new Node(l[1], l[1]));
            String id = l[0] + "|" + l[1] + "|" + l[2];
            edges.putIfAbsent(id, new Edge(id, l[0], l[1]));
        }
        return new Graph(new ArrayList<>(nodes.values()), new ArrayList<>(edges.values()));
    }

    private static List<String[]> approved() {
        Map<String, String> vendorOf = INVOICES.stream().collect(Collectors.toMap(i -> i[0], i -> i[1]));
        List<String[]> out = new ArrayList<>();
        for (String[] a : APPROVALS) out.add(new String[]{a[2], vendorOf.get(a[1]), "approved"});
        return out;
    }

    /** The approval graph as the app draws it: ONE edge per invoice (parallel edges kept), so a pair with two invoices is no bridge. */
    private static Graph approvalMultigraph() {
        Map<String, String> vendorOf = INVOICES.stream().collect(Collectors.toMap(i -> i[0], i -> i[1]));
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        for (String[] a : APPROVALS) {
            String v = vendorOf.get(a[1]);
            nodes.putIfAbsent(a[2], new Node(a[2], a[2]));
            nodes.putIfAbsent(v, new Node(v, v));
            edges.add(new Edge(a[2] + "|" + v + "|" + a[1], a[2], v));
        }
        return new Graph(new ArrayList<>(nodes.values()), edges);
    }

    private static List<String[]> paidTo() {
        List<String[]> out = new ArrayList<>();
        for (String[] i : INVOICES) if (!PLACEHOLDER.contains(i[8])) out.add(new String[]{i[1], i[8], "paid_to"});
        return out;
    }

    /** Shared-attribute pairs over vendors AND employees, once per unordered pair, as the ap_links Job folds them. */
    private static List<String[]> shares(int vendorCol, String kind) {
        Map<String, Set<String>> holders = new TreeMap<>();
        for (String[] v : VENDORS) if (!PLACEHOLDER.contains(v[vendorCol])) holders.computeIfAbsent(v[vendorCol], k -> new TreeSet<>()).add(v[0]);
        for (String[] e : EMPLOYEES) if (!PLACEHOLDER.contains(e[vendorCol + 2])) holders.computeIfAbsent(e[vendorCol + 2], k -> new TreeSet<>()).add(e[0]);
        List<String[]> out = new ArrayList<>();
        for (Set<String> h : holders.values()) {
            if (h.size() < 2 || h.size() > 10) continue;
            List<String> l = new ArrayList<>(h);
            for (int a = 0; a < l.size(); a++) for (int b = a + 1; b < l.size(); b++) out.add(new String[]{l.get(a), l.get(b), kind});
        }
        return out;
    }

    private static List<String[]> sharesAccount() { return shares(2, "shares_account"); }

    private static List<String[]> sharesAddress() { return shares(3, "shares_address"); }

    private static boolean touches(List<String[]> links, String v) {
        return links.stream().anyMatch(l -> l[0].equals(v) || l[1].equals(v));
    }

    @Test
    void thePlantedVendorSharesAnAccountAndAnAddressWithAnEmployeeWhoseManagerApprovedAllItsInvoices() {
        assertTrue(sharesAccount().stream().anyMatch(l -> l[0].equals("E-31") && l[1].equals("V-117")));
        assertTrue(sharesAddress().stream().anyMatch(l -> l[0].equals("E-31") && l[1].equals("V-117")));
        List<String> ids = INVOICES.stream().filter(i -> i[1].equals("V-117")).map(i -> i[0]).toList();
        assertEquals(14, ids.size(), "invoices of V-117");
        String manager = EMPLOYEES.stream().filter(e -> e[0].equals("E-31")).findFirst().orElseThrow()[2];
        assertEquals("E-15", manager);
        Set<String> approvers = APPROVALS.stream().filter(a -> ids.contains(a[1])).map(a -> a[2]).collect(Collectors.toSet());
        assertEquals(Set.of(manager), approvers, "every invoice approved by E-31's manager");
    }

    @Test
    void theShellClusterIsOneComponentOfFiveAndTheSixthIsUnlinkedButPredicted() {
        List<String[]> acct = sharesAccount();
        List<List<String>> comps = GraphAlgorithms.connectedComponents(graph(acct)).stream()
                .filter(c -> !c.contains("V-117")).toList();
        assertEquals(1, comps.size(), "one shell component besides the fictitious vendor: " + comps);
        assertEquals(Set.of("V-131", "V-132", "V-133", "V-134", "V-135"), new HashSet<>(comps.get(0)));
        assertFalse(touches(acct, "V-136"), "V-136 shares no account");
        for (String v : List.of("V-090", "V-091", "V-092")) assertFalse(touches(acct, v), v + ": a placeholder account makes no link");
        for (String v : List.of("V-100", "V-101", "V-102", "V-103"))
            assertFalse(touches(sharesAddress(), v), v + ": a blank address makes no link");

        List<String[]> all = new ArrayList<>(acct);
        all.addAll(sharesAddress());
        List<PredictedLink> top = GraphCentrality.linkPrediction(graph(all), Method.COMMON_NEIGHBORS, 3);
        Set<String> got = top.stream().map(p -> p.source().compareTo(p.target()) > 0 ? p.target() + "~" + p.source() : p.source() + "~" + p.target())
                .collect(Collectors.toSet());
        assertEquals(Set.of("V-133~V-136", "V-134~V-136", "V-135~V-136"), got, "link prediction proposes the sixth vendor: " + top);
    }

    @Test
    void jaccardOfTheShellVendorsIsOneAndTheSixthIsAThird() {
        List<String[]> g = approved();
        g.addAll(paidTo());
        List<Score> sim = GraphCentrality.jaccardSimilarity(graph(g), "V-131");
        Map<String, Double> byId = sim.stream().collect(Collectors.toMap(Score::id, Score::score));
        for (String v : List.of("V-132", "V-133", "V-134", "V-135")) assertEquals(1.0, byId.get(v), 1e-9, v);
        assertEquals(1.0 / 3, byId.get("V-136"), 1e-9);
        assertEquals(4, sim.stream().filter(s -> s.score() >= 1.0 - 1e-9).count(), "exactly the four twins at 1.0");
    }

    @Test
    void thirtyInvoicesSitOneToThreePercentUnderTheLimitOverSixWeeks() {
        List<String[]> band = INVOICES.stream()
                .filter(i -> Double.parseDouble(i[6]) >= LIMIT * 0.97 && Double.parseDouble(i[6]) < LIMIT).toList();
        assertEquals(30, band.size(), "the only invoices in the 97-100 % band");
        assertTrue(band.stream().allMatch(i -> i[1].equals("V-099")));
        Set<String> ids = band.stream().map(i -> i[0]).collect(Collectors.toSet());
        assertEquals(Set.of("E-23"), APPROVALS.stream().filter(a -> ids.contains(a[1])).map(a -> a[2]).collect(Collectors.toSet()));
        List<LocalDate> d = band.stream().map(i -> LocalDate.parse(i[4])).sorted().toList();
        assertTrue(ChronoUnit.DAYS.between(d.get(0), d.get(d.size() - 1)) <= 42, "within six weeks");
        assertTrue(INVOICES.stream().noneMatch(i -> Double.parseDouble(i[6]) >= LIMIT), "nothing at or over the limit");
    }

    @Test
    void oneApproverIsTheArticulationPointAndTheOnlyBridgeIntoTheRestOfTheApprovalGraphAndTheOrgChartIsATree() {
        Graph g = approvalMultigraph();   // as the app draws it: one link per invoice
        assertFalse(GraphStructure.isForest(g), "the approval graph has cycles");
        List<String> art = GraphStructure.articulationPoints(g);
        assertTrue(art.contains("E-07") && art.contains("V-041"), art.toString());
        List<String> bridges = GraphStructure.bridges(approvalMultigraph());
        assertEquals(1, bridges.size(), "the one bridge of the approval graph as drawn (one link per invoice): " + bridges);
        assertTrue(bridges.get(0).startsWith("E-07|V-041|"), bridges.toString());
        Map<String, Set<String>> approversOf = new HashMap<>();
        for (String[] l : approved()) approversOf.computeIfAbsent(l[1], k -> new HashSet<>()).add(l[0]);
        Set<String> single = approversOf.entrySet().stream().filter(e -> e.getValue().size() == 1).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertEquals(Set.of("V-117", "V-131", "V-132", "V-133", "V-134", "V-135", "V-136", "V-099"), single,
                "only the planted vendors have a single approver");

        List<Score> btw = GraphCentrality.betweennessCentrality(g).stream()
                .sorted(Comparator.comparingDouble(Score::score).reversed()).toList();
        // betweenness: the utilities vendor leads (busy, benign), then the bridge pair E-07 / V-041 (the cut vertices the app lists);
        // the CFO E-01 is a busy hub by degree (40 vendors) but not a cut vertex. High betweenness alone is not guilt; the cut vertex is the finding (runbook scene).
        List<String> top5 = btw.subList(0, 5).stream().map(Score::id).toList();
        assertEquals("V-001", top5.get(0), "the benign utilities hub leads: " + top5);
        assertTrue(top5.subList(1, 3).containsAll(List.of("E-07", "V-041")), "the bridge pair follows: " + top5);
        Map<String, Double> core = GraphAlgorithms.kCore(g).stream().collect(Collectors.toMap(Score::id, Score::score));
        double max = Collections.max(core.values());
        assertEquals(3.0, max);
        Set<String> densest = core.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).collect(Collectors.toSet());
        assertTrue(densest.containsAll(Set.of("E-07", "E-08", "E-09")), "k-core agrees: " + densest);

        Set<String> pv = new HashSet<>();
        for (int i = 21; i <= 40; i++) pv.add(String.format("V-%03d", i));
        Map<String, String> vendorOf = INVOICES.stream().collect(Collectors.toMap(i -> i[0], i -> i[1]));
        long p = APPROVALS.stream().filter(a -> pv.contains(vendorOf.get(a[1]))).count();
        long e7 = APPROVALS.stream().filter(a -> pv.contains(vendorOf.get(a[1])) && a[2].equals("E-07")).count();
        assertEquals(0.70, (double) e7 / p, 0.02, "E-07 signs 70 % of the 20 vendors' invoices");

        List<String[]> org = new ArrayList<>();
        for (String[] e : EMPLOYEES) if (!e[2].isEmpty()) org.add(new String[]{e[2], e[0], "reports_to"});   // src = manager: parent to child, the orientation isForest reads
        assertEquals(59, org.size());
        assertTrue(GraphStructure.isForest(graph(org)), "the org chart is a tree");
    }

    @Test
    void theJournalHasOneCycleAndMaxFlowQuantifiesIt() {
        Map<String, Double> weight = new LinkedHashMap<>();
        for (String[] r : JOURNAL) weight.merge(r[4] + "|" + r[5] + "|journal_flow", Double.parseDouble(r[7]), Double::sum);
        Graph g = graph(JOURNAL.stream().map(r -> new String[]{r[4], r[5], "journal_flow"}).toList());
        var cycles = GraphStructure.findCycles(g, 50, 8);
        assertEquals(1, cycles.size(), cycles.toString());
        assertEquals(Set.of("LE-05", "LE-06", "LE-07", "LE-08"), new HashSet<>(cycles.get(0).nodeIds()));
        assertEquals(7_200_000.0, GraphPaths.maxFlow(g, weight, "LE-05", "LE-07").value(), 0.01, "three laps of 2 400 000");
        assertEquals(40, JOURNAL.stream().map(r -> r[0]).distinct().count(), "journals");
    }

    @Test
    void thirteenDuplicatePaymentPairsShareVendorAmountAndWeekWithDifferentInvoiceNumbers() {
        Map<String, List<String[]>> groups = new HashMap<>();
        for (String[] i : INVOICES) {
            LocalDate d = LocalDate.parse(i[4]);
            groups.computeIfAbsent(i[1] + "|" + i[6] + "|" + d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
                    k -> new ArrayList<>()).add(i);
        }
        List<List<String[]>> dups = groups.values().stream()
                .filter(l -> l.size() == 2 && !l.get(0)[2].equals(l.get(1)[2])).toList();
        assertEquals(13, dups.size());
        assertTrue(groups.values().stream().noneMatch(l -> l.size() > 2));
    }

    @Test
    void theBenignLookAlikesAreBusyButOrdinary() {
        Map<String, Long> perVendor = INVOICES.stream().collect(Collectors.groupingBy(i -> i[1], Collectors.counting()));
        assertEquals("V-001", Collections.max(perVendor.entrySet(), Map.Entry.comparingByValue()).getKey(), "the utilities vendor is the busiest");
        assertEquals(100, perVendor.get("V-001"));
        assertEquals(40, APPROVALS.stream().filter(a -> a[2].equals("E-01")).count(), "the CFO's approvals");
        assertEquals(150, VENDORS.size());
        assertEquals(60, EMPLOYEES.size());
        assertTrue(INVOICES.size() >= 1600 && INVOICES.size() <= 1900, "invoices: " + INVOICES.size());
    }

    @Test
    void theStagedMonthAddsNoNewApprovalPairAndNoNewVendor() throws Exception {
        Set<String> landed = approved().stream().map(l -> l[0] + "~" + l[1]).collect(Collectors.toSet());
        List<String> invLines = Files.readAllLines(DATA.resolve("staged").resolve("ap_invoice").resolve("AP_INVOICE_202610.csv"));
        Map<String, String> stagedVendor = new HashMap<>();
        for (String l : invLines.subList(1, invLines.size())) {
            String[] c = l.split(",", -1);
            stagedVendor.put(c[0], c[1]);
            assertTrue(c[4].startsWith("2026-10"), "staged invoice date " + c[4]);
        }
        List<String> apr = Files.readAllLines(DATA.resolve("staged").resolve("ap_approval").resolve("AP_APPROVAL_202610.csv"));
        for (String l : apr.subList(1, apr.size())) {
            String[] a = l.split(",", -1);
            assertTrue(landed.contains(a[2] + "~" + stagedVendor.get(a[1])), "new approval pair " + a[2] + "~" + stagedVendor.get(a[1]));
        }
    }
}
