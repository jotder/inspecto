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
 * The {@code la-claims} Space Template's planted stories: seven from docs/archived-documents/plans-archive/la-multi-vertical-showcase-plan.md, 2.C,
 * plus the parts-supplier ring and the ghost broker (docs/superpower/2026-11-07-la-showcase-police-insurance-utility-plan.md, spec 2), over the SHIPPED
 * corpus ({@code spaces/_templates/la-claims/data/inbox}, read in place) folded to the same link graph the {@code claims_links} Job
 * builds (the per-kind row counts below equal those {@code LaClaimsLinksJobTest} pins over the real Pipelines and SQL). Every figure is
 * a promise a presenter may make; a regeneration that breaks one fails here.
 */
class LaClaimsGoldenTest {

    static final String GAR = "GAR-03", CLI = "CLI-04", LAW = "LAW-02", GP = "CLI-01", BROKER = "BRK-05";
    static final String RING_SUPPLIER = "SUP-07", BIG_SUPPLIER = "SUP-01", GHOST_BROKER = "BRK-07";
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
        assertEquals(405, c.policies.size(), "policies: one per claimant, and the ghost broker's 25");
        assertEquals(1056, c.invoices.size(), "repair invoice lines");
        assertEquals(25, c.claims.values().stream().flatMap(x -> java.util.stream.Stream.of(x.garage(), x.clinic(), x.lawyer()))
                .filter(s -> !s.isEmpty()).distinct().count(), "the 25 providers are all used");
        assertEquals(Map.of("claimed_with", 678, "same_accident", 291, "serviced_by", 1298, "represented_by", 241, "referred_to", 22,
                "shares_phone", 20, "shares_address", 24, "supplied_parts", 34, "paid_by", 29), c.perKind(), "link rows per kind (= LaClaimsLinksJobTest)");
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

    private static Set<String> garages() {
        return c.claims.values().stream().map(Claim::garage).filter(x -> !x.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> kindsOf(String node) {
        return c.links.stream().filter(l -> l.src().equals(node) || l.dst().equals(node)).map(ClaimsCorpus.Link::kind).collect(Collectors.toSet());
    }

    /** "12 Quarry Road, Derby" to "quarry road, derby": the street and city, without the house number. */
    private static String street(String address) {
        return address.trim().toLowerCase().replaceFirst("^[0-9]+ +", "");
    }

    @Test
    void story8OnePartsSupplierInvoicesThirtyClaimsAcrossAllNineGaragesAndNoneOfItsPartsIsFitted() {
        Map<String, Set<String>> unfittedClaims = new TreeMap<>();
        for (var i : c.invoices) if (!i.fitted()) unfittedClaims.computeIfAbsent(i.supplier(), k -> new TreeSet<>()).add(i.claim());
        var heavy = unfittedClaims.entrySet().stream().filter(e -> e.getValue().size() >= 10).toList();
        assertEquals(1, heavy.size(), "exactly one supplier has parts not fitted on ten or more claims: " + unfittedClaims);
        assertEquals(RING_SUPPLIER, heavy.get(0).getKey());
        assertEquals(30, heavy.get(0).getValue().size(), "thirty claims");
        assertTrue(c.invoices.stream().filter(i -> i.supplier().equals(RING_SUPPLIER)).noneMatch(ClaimsCorpus.Invoice::fitted), "not one of its parts is fitted");
        assertTrue(unfittedClaims.entrySet().stream().filter(e -> !e.getKey().equals(RING_SUPPLIER)).mapToInt(e -> e.getValue().size()).sum() <= 3,
                "elsewhere a part goes unfitted only now and then (a return)");
        assertEquals(9, garages().size());
        assertEquals(garages(), c.neighbours(RING_SUPPLIER, "supplied_parts"), "hop 1 by supplied_parts reaches all nine garages");
        assertEquals(Set.of("supplied_parts"), kindsOf(RING_SUPPLIER), "the supplier holds no link of another kind");
        assertTrue(heavy.get(0).getValue().stream().noneMatch(ringClaims::contains), "a separate story: none of its claims is a staged-accident ring claim");
    }

    @Test
    void story8LookAlikeTheLargeSupplierReachesEveryGarageTooButEveryPartItSellsIsFitted() {
        assertEquals(garages(), c.neighbours(BIG_SUPPLIER, "supplied_parts"), "the look-alike also reaches all nine garages");
        assertTrue(c.invoices.stream().filter(i -> i.supplier().equals(BIG_SUPPLIER)).allMatch(ClaimsCorpus.Invoice::fitted), "every part fitted");
        long big = c.invoices.stream().filter(i -> i.supplier().equals(BIG_SUPPLIER)).map(ClaimsCorpus.Invoice::claim).distinct().count();
        long ringSup = c.invoices.stream().filter(i -> i.supplier().equals(RING_SUPPLIER)).map(ClaimsCorpus.Invoice::claim).distinct().count();
        assertTrue(big > 5 * ringSup, "the look-alike is far busier (" + big + " claims against " + ringSup + "): reach alone is not guilt");
        assertEquals(Set.of(BIG_SUPPLIER, RING_SUPPLIER), c.links.stream().filter(l -> l.kind().equals("supplied_parts")).map(ClaimsCorpus.Link::src)
                .filter(s -> c.neighbours(s, "supplied_parts").size() == garages().size()).collect(Collectors.toSet()), "exactly two suppliers reach every garage");
        assertEquals(Set.of("supplied_parts"), kindsOf(BIG_SUPPLIER), "the look-alike holds no link of another kind, so none to the ring supplier");
    }

    @Test
    void story9TwentyFivePoliciesThroughOneBrokerAtAddressesFoundNowhereElseArePaidFromTwoBankAccounts() {
        List<ClaimsCorpus.Policy> ghost = c.policies.values().stream().filter(p -> p.broker().equals(GHOST_BROKER)).toList();
        assertEquals(25, ghost.size(), "twenty-five policies");
        Set<String> holders = ghost.stream().map(ClaimsCorpus.Policy::holder).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(25, holders.size());
        assertTrue(c.parties.stream().noneMatch(p -> holders.contains(p.id())), "no ghost policyholder is on any claim");
        List<String> accounts = ghost.stream().map(ClaimsCorpus.Policy::payer).distinct().sorted().toList();
        assertEquals(2, accounts.size(), "all premiums from two bank accounts: " + accounts);
        Set<String> realStreets = new TreeSet<>();
        for (Party p : c.parties) if (ClaimsCorpus.usableAddress(p.address())) realStreets.add(street(p.address()));
        for (var p : c.policies.values())
            if (!p.broker().equals(GHOST_BROKER) && ClaimsCorpus.usableAddress(p.address())) realStreets.add(street(p.address()));
        assertTrue(ghost.stream().noneMatch(p -> realStreets.contains(street(p.address()))), "every ghost address is on a street found on no other record");
        assertTrue(ghost.stream().allMatch(p -> p.premium() < 380), "the false details buy premiums below every genuine policy's");
        assertTrue(c.policies.values().stream().filter(p -> !p.broker().equals(GHOST_BROKER)).allMatch(p -> p.premium() >= 380));

        for (String a : accounts) {
            Set<String> payers = c.neighbours(a, "paid_by");
            assertTrue(payers.size() >= 12 && holders.containsAll(payers), a + " pays a dozen ghost policyholders: " + payers);
        }
        assertEquals(holders, union(c.neighbours(accounts.get(0), "paid_by"), c.neighbours(accounts.get(1), "paid_by")), "the two accounts pay all 25");
        for (String h : holders) assertEquals(Set.of("paid_by"), kindsOf(h), h + ": a ghost policyholder's only link is its premium payment");
    }

    @Test
    void story9LookAlikeTheFamilyPaysItsFourPoliciesFromOneAccountAtItsRealAddress() {
        Map<String, Long> shared = c.policies.values().stream().filter(p -> !p.payer().isBlank())
                .collect(Collectors.groupingBy(ClaimsCorpus.Policy::payer, TreeMap::new, Collectors.counting()));
        shared.values().removeIf(n -> n < 2);
        assertEquals(3, shared.size(), "three accounts pay more than one policy, the two ghost accounts and the family's: " + shared);
        Map<String, Set<String>> byPhone = new TreeMap<>();
        for (Party p : c.parties)
            if (ClaimsCorpus.usablePhone(p.phone())) byPhone.computeIfAbsent(ClaimsCorpus.digits(p.phone()), k -> new TreeSet<>()).add(p.id());
        Set<String> family = byPhone.values().stream().filter(s -> s.size() == 4 && s.stream().noneMatch(ring::contains)).findFirst().orElseThrow();
        String familyAccount = shared.entrySet().stream().filter(e -> e.getValue() == 4).map(Map.Entry::getKey).findFirst().orElseThrow();
        assertEquals(family, c.neighbours(familyAccount, "paid_by"), "the four-policy account pays exactly the family (story 7's)");
        Set<String> homes = c.policies.values().stream().filter(p -> family.contains(p.holder())).map(ClaimsCorpus.Policy::address).collect(Collectors.toSet());
        assertEquals(1, homes.size(), "one address");
        String home = homes.iterator().next();
        assertTrue(c.parties.stream().anyMatch(p -> p.address().equals(home)), "a real address: the one the family gave on its claims");
        assertTrue(c.policies.values().stream().filter(p -> family.contains(p.holder())).noneMatch(p -> p.broker().equals(GHOST_BROKER)),
                "not sold through the ghost broker");
        Set<String> ghostAccounts = c.policies.values().stream().filter(p -> p.broker().equals(GHOST_BROKER)).map(ClaimsCorpus.Policy::payer)
                .collect(Collectors.toSet());
        assertFalse(ghostAccounts.contains(familyAccount));
        assertTrue(family.stream().noneMatch(h -> ghostAccounts.stream().anyMatch(a -> c.neighbours(a, "paid_by").contains(h))),
                "the family is not linked to a ghost account");
    }
}
