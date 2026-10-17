package com.gamma.graph;

import com.gamma.graph.ClaimsCorpus.Accident;
import com.gamma.graph.ClaimsCorpus.Claim;
import com.gamma.graph.ClaimsCorpus.Party;
import com.gamma.graph.GraphAlgorithms.Direction;
import com.gamma.graph.GraphAlgorithms.Graph;
import com.gamma.graph.GraphAlgorithms.Score;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code la-claims} Space Template's seven planted stories (docs/superpower/la-multi-vertical-showcase-plan.md, 2.C), over the SHIPPED
 * corpus ({@code spaces/_templates/la-claims/data/inbox}, read in place) folded to the same link graph the {@code claims_links} Job
 * builds (the per-kind row counts below equal those {@code LaClaimsLinksJobTest} pins over the real Pipelines and SQL). Every figure is
 * a promise a presenter may make; a regeneration that breaks one fails here.
 */
class LaClaimsGoldenTest {

    static final String GAR = "GAR-03", CLI = "CLI-04", LAW = "LAW-02", GP = "CLI-01", BROKER = "BRK-05";
    static final double JUNCTION_LAT = 52.9225 + 0.031, JUNCTION_LON = -1.4746 - 0.047;

    static ClaimsCorpus c;
    static Graph g;
    static Set<String> ringClaims, ring, ringProviders = Set.of(GAR, CLI, LAW);

    @BeforeAll
    static void load() throws Exception {
        c = ClaimsCorpus.load();
        g = c.graph();
        ringClaims = c.claims.values().stream().filter(x -> x.garage().equals(GAR) && x.clinic().equals(CLI) && x.lawyer().equals(LAW))
                .map(Claim::id).collect(Collectors.toCollection(TreeSet::new));
        ring = c.parties.stream().filter(p -> ringClaims.contains(p.claim())).map(Party::id).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> community(Map<String, String> communities, String member) {
        String id = communities.get(member);
        return communities.entrySet().stream().filter(e -> e.getValue().equals(id)).map(Map.Entry::getKey).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }

    private static List<String> top(List<Score> scores, int n) {
        return scores.stream().limit(n).map(Score::id).toList();
    }

    private static double metres(double lat, double lon) {
        double r = 6_371_000, p1 = Math.toRadians(lat), p2 = Math.toRadians(JUNCTION_LAT), dp = p2 - p1, dl = Math.toRadians(JUNCTION_LON - lon);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * r * Math.asin(Math.sqrt(a));
    }

    private static String e164(String phone) {
        String d = ClaimsCorpus.digits(phone);
        return d.startsWith("0") ? "44" + d.substring(1) : d;
    }

    private static int edit(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
        return d[a.length()][b.length()];
    }

    @Test
    void theCorpusIsTheDocumentedSizeAndTheLinkKindsFoldToTheirPinnedRows() {
        assertEquals(520, c.claims.size(), "claims");
        assertEquals(380, c.policies.size(), "policies, one per claimant");
        assertEquals(25, c.claims.values().stream().flatMap(x -> java.util.stream.Stream.of(x.garage(), x.clinic(), x.lawyer()))
                .filter(s -> !s.isEmpty()).distinct().count(), "the 25 providers are all used");
        assertEquals(Map.of("claimed_with", 678, "same_accident", 291, "serviced_by", 1298, "represented_by", 241, "referred_to", 22,
                "shares_phone", 20, "shares_address", 24), c.perKind(), "link rows per kind (= LaClaimsLinksJobTest)");
    }

    @Test
    void story1TheRingIsTwelveClaimantsOnNineClaimsSharingFourPhonesAndThreeAddressesBehindOneGarageClinicAndLawyer() {
        assertEquals(9, ringClaims.size());
        assertEquals(12, ring.size());
        List<Party> rp = c.parties.stream().filter(p -> ring.contains(p.id())).toList();
        assertEquals(4, rp.stream().map(Party::phone).distinct().count(), "four phones");
        assertEquals(3, rp.stream().map(Party::address).distinct().count(), "three addresses");
        assertTrue(rp.stream().allMatch(p -> ringClaims.contains(p.claim())), "a ring member is on no claim outside the ring");

        Set<String> comm = community(GraphIterative.louvainCommunities(g), ring.iterator().next());
        assertEquals(union(ring, ringProviders), comm, "Louvain: one community of exactly the 12 claimants + garage, clinic, lawyer");
        assertEquals(comm, community(GraphIterative.detectCommunities(g), ring.iterator().next()), "label propagation finds the same community");

        List<List<String>> cliques = GraphStructure.cliques(g, 4);
        int max = cliques.stream().mapToInt(List::size).max().orElse(0);
        assertEquals(7, max, "the largest clique: 5 phone-sharing claimants + the clinic + the lawyer");
        Set<String> inside = union(ring, Set.of(CLI, LAW));
        assertTrue(cliques.stream().filter(x -> x.size() == max).allMatch(inside::containsAll), "every largest clique lies inside the ring");
        assertTrue(cliques.stream().anyMatch(x -> x.stream().filter(ring::contains).count() >= 4), "cliques of four or more claimants (the owners of one phone)");
    }

    @Test
    void story2OneRingMemberIsAPassengerOnSixClaimsWithSixDifferentDriversInUnderSevenMonths() {
        Map<String, List<Party>> asPassenger = c.parties.stream().filter(p -> p.role().equals("passenger"))
                .collect(Collectors.groupingBy(Party::id));
        var repeat = asPassenger.entrySet().stream().filter(e -> e.getValue().size() >= 6).toList();
        assertEquals(1, repeat.size(), "exactly one person is a passenger six or more times");
        String pax = repeat.get(0).getKey();
        assertTrue(ring.contains(pax), "the repeat passenger is a ring member");
        List<String> claims = repeat.get(0).getValue().stream().map(Party::claim).toList();
        assertEquals(6, claims.size(), "six claims");
        Set<String> drivers = c.parties.stream().filter(p -> claims.contains(p.claim()) && p.role().equals("driver")).map(Party::id).collect(Collectors.toSet());
        assertEquals(6, drivers.size(), "six different drivers");
        List<LocalDate> days = claims.stream().map(id -> LocalDate.parse(c.claims.get(id).loss())).sorted().toList();
        assertTrue(ChronoUnit.DAYS.between(days.get(0), days.get(days.size() - 1)) <= 213, "inside seven months");

        Set<String> coClaimants = c.neighbours(pax, "claimed_with");
        assertEquals(11, coClaimants.size(), "hop 1 by claimed_with reaches the other 11 ring members");
        assertTrue(ring.containsAll(coClaimants) && coClaimants.containsAll(drivers));
        Map<String, Double> tri = GraphAlgorithms.triangleCount(g).stream().collect(Collectors.toMap(Score::id, Score::score));
        assertTrue(tri.get(pax) >= 30, "a dense hop-1 neighbourhood: " + tri.get(pax) + " triangles");
    }

    @Test
    void story3ElevenAccidentsWithinFourHundredMetresOfOneJunctionAtNightNoneWithAPoliceReport() {
        List<Accident> near = c.accidents.values().stream().filter(a -> metres(a.lat(), a.lon()) <= 400).toList();
        assertEquals(11, near.size());
        assertTrue(near.stream().allMatch(a -> a.report().isEmpty()), "no police report");
        assertTrue(near.stream().allMatch(a -> {
            int h = Integer.parseInt(a.at().substring(11, 13));
            return h >= 2 && h < 4;
        }), "all between 02:00 and 03:59");
        Set<String> ringAccidents = ringClaims.stream().map(id -> c.claims.get(id).accident()).collect(Collectors.toSet());
        assertTrue(near.stream().map(Accident::id).collect(Collectors.toSet()).containsAll(ringAccidents), "nine of the eleven are the ring's");
        assertTrue(c.accidents.values().stream().filter(a -> metres(a.lat(), a.lon()) > 400).allMatch(a -> metres(a.lat(), a.lon()) > 1000),
                "everything else is over a kilometre away");
    }

    @Test
    void story4TheRingsClinicBillsThreeTimesItsPeersIsReferredToOnlyByTheRingsLawyerAndHitsAndPageRankAgree() {
        Map<String, double[]> perClinic = new HashMap<>();
        for (var b : c.bills)
            if (b.provider().startsWith("CLI-")) {
                double[] s = perClinic.computeIfAbsent(b.provider(), k -> new double[2]);
                s[0] += b.amount();
                s[1]++;
            }
        double peers = perClinic.entrySet().stream().filter(e -> !e.getKey().equals(CLI)).mapToDouble(e -> e.getValue()[0] / e.getValue()[1]).average().orElseThrow();
        double mine = perClinic.get(CLI)[0] / perClinic.get(CLI)[1];
        assertTrue(mine >= 3.0 * peers, "CLI-04 bills " + mine + " per claim against peers " + peers);
        Set<String> referrers = c.links.stream().filter(l -> l.kind().equals("referred_to") && l.dst().equals(CLI)).map(ClaimsCorpus.Link::src).collect(Collectors.toSet());
        assertEquals(Set.of(LAW), referrers, "referred to ONLY by the ring's lawyer");

        Graph nb = GraphAlgorithms.neighborhood(g, LAW, 2, Direction.BOTH);
        var hits = GraphIterative.hits(nb);
        assertEquals(Set.of(LAW, CLI, GAR), Set.copyOf(top(hits.authorities(), 3)), "HITS authorities in the ring's neighbourhood");
        String topProviderHub = hits.hubs().stream().map(Score::id).filter(id -> !id.startsWith("CL-")).findFirst().orElseThrow();
        assertEquals(LAW, topProviderHub, "among providers the lawyer is the hub (it refers the clinic)");
        assertTrue(top(GraphIterative.pageRank(nb), 4).containsAll(List.of(CLI, LAW, GAR)), "PageRank agrees: " + top(GraphIterative.pageRank(nb), 4));
        assertTrue(ring.containsAll(top(GraphIterative.eigenvectorCentrality(nb), 5)), "eigenvector centrality peaks inside the ring");
        assertTrue(ring.containsAll(top(GraphIterative.katzCentrality(nb), 5)), "Katz centrality peaks inside the ring: " + top(GraphIterative.katzCentrality(nb), 5));
    }

    @Test
    void story5SevenPoliciesWereClaimedOnWithinTwentyDaysAllThroughOneBroker() {
        Map<String, Long> earlyByBroker = new TreeMap<>();
        for (Claim cl : c.claims.values()) {
            var pol = c.policies.get(cl.policy());
            long days = ChronoUnit.DAYS.between(LocalDate.parse(pol.inception()), LocalDate.parse(cl.loss()));
            if (days <= 20) earlyByBroker.merge(pol.broker(), 1L, Long::sum);
            else assertTrue(days > 60, cl.id() + " is claimed " + days + " days after inception");
        }
        assertEquals(Map.of(BROKER, 7L), earlyByBroker);
    }

    @Test
    void story6TwoClaimantRecordsAreOnePersonAndResolvingThemGrowsTheRingByOne() {
        Map<String, Party> person = new TreeMap<>();
        for (Party p : c.parties) person.putIfAbsent(p.id(), p);
        List<String[]> pairs = new ArrayList<>();
        List<String> ids = List.copyOf(person.keySet());
        for (int i = 0; i < ids.size(); i++)
            for (int j = i + 1; j < ids.size(); j++) {
                Party a = person.get(ids.get(i)), b = person.get(ids.get(j));
                if (a.dob().equals(b.dob()) && !a.phone().isEmpty() && e164(a.phone()).equals(e164(b.phone())) && edit(a.name(), b.name()) == 1)
                    pairs.add(new String[]{a.id(), b.id()});
            }
        assertEquals(1, pairs.size(), "exactly one candidate pair (name one letter apart, same date of birth, same normalised phone)");
        String x = pairs.get(0)[0], y = pairs.get(0)[1];
        String survivor = ring.contains(x) ? x : y, duplicate = survivor.equals(x) ? y : x;
        assertTrue(ring.contains(survivor) && !ring.contains(duplicate), "one record is a ring member, the other a separate claimant");
        assertTrue(c.links.stream().noneMatch(l -> l.kind().equals("shares_phone") && Set.of(l.src(), l.dst()).containsAll(Set.of(x, y))),
                "the fold alone does not link them (national vs E.164 spelling)");

        assertFalse(community(GraphIterative.louvainCommunities(g), survivor).contains(duplicate), "before resolving, the duplicate is outside the ring's community");
        Set<String> dupClaims = c.parties.stream().filter(p -> p.id().equals(duplicate)).map(Party::claim).collect(Collectors.toSet());
        Set<String> co = c.parties.stream().filter(p -> dupClaims.contains(p.claim()) && !p.id().equals(duplicate)).map(Party::id)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(1, co.size(), "the duplicate's only co-party");
        Set<String> after = community(GraphIterative.louvainCommunities(c.resolved(Map.of(duplicate, survivor))), survivor);
        Set<String> claimantsAfter = after.stream().filter(id -> id.startsWith("CL-")).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(union(ring, co), claimantsAfter, "after resolve the ring is 13 claimants: the duplicate folds into its twin and brings its co-party");
        assertEquals(ring.size() + 1, claimantsAfter.size());
    }

    @Test
    void story7TheBenignFamilyAndTheBusyClinicAreNotTheRing() {
        Map<String, Set<String>> byPhone = new TreeMap<>();
        for (Party p : c.parties)
            if (ClaimsCorpus.usablePhone(p.phone())) byPhone.computeIfAbsent(ClaimsCorpus.digits(p.phone()), k -> new TreeSet<>()).add(p.id());
        List<Set<String>> families = byPhone.values().stream().filter(s -> s.size() == 4 && s.stream().noneMatch(ring::contains)).toList();
        assertEquals(1, families.size(), "one family of four sharing a phone");
        Set<String> family = families.get(0);
        assertEquals(1, c.parties.stream().filter(p -> family.contains(p.id())).map(Party::address).distinct().count(), "sharing one address");
        assertEquals(2, c.parties.stream().filter(p -> family.contains(p.id())).map(Party::claim).distinct().count(), "two claims in 18 months");

        Set<String> ringComm = community(GraphIterative.louvainCommunities(g), ring.iterator().next());
        assertTrue(family.stream().noneMatch(ringComm::contains), "the family is outside the ring's community");
        assertFalse(ringComm.contains(GP), "the busy GP clinic is outside the ring's community");
        assertTrue(GraphStructure.cliques(g, 5).stream().anyMatch(q -> q.containsAll(family)), "the family is itself a clique (4 members + their garage): sharing is not guilt");

        // high connectivity is not guilt: the busy clinic tops every whole-graph centrality, the ring's providers do not
        assertEquals(GP, top(GraphAlgorithms.degreeCentrality(g), 1).get(0));
        assertEquals(GP, top(GraphIterative.pageRank(g), 1).get(0));
        assertEquals(GP, top(GraphIterative.eigenvectorCentrality(g), 1).get(0));
        assertEquals(GP, top(GraphIterative.katzCentrality(g), 1).get(0));
        assertEquals(GP, top(GraphCentrality.closenessCentrality(g), 1).get(0));
        assertEquals(GP, top(GraphIterative.hits(g).authorities(), 1).get(0));
        assertEquals(GP, top(GraphAlgorithms.triangleCount(g), 1).get(0));
        long placeholders = c.parties.stream().filter(p -> !ClaimsCorpus.usablePhone(p.phone())).map(Party::id).distinct().count();
        assertTrue(placeholders >= 14, "blank and placeholder phones exist in the data (" + placeholders + ") yet make no link (20 shares_phone rows in all)");
    }
}
