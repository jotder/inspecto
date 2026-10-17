package com.gamma.graph;

import com.gamma.graph.GraphAlgorithms.Edge;
import com.gamma.graph.GraphAlgorithms.Graph;
import com.gamma.graph.GraphAlgorithms.Node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The shipped {@code la-claims} corpus ({@code spaces/_templates/la-claims/data/inbox}, read in place) and an INDEPENDENT Java fold of it
 * into the link graph the {@code claims_links} Job builds in SQL. The two are held equal by the per-kind row counts both tests pin
 * ({@code LaClaimsLinksJobTest} in inspecto-engine, {@code LaClaimsGoldenTest} here).
 */
final class ClaimsCorpus {

    static final Path INBOX = Path.of("..", "..", "spaces", "_templates", "la-claims", "data", "inbox");
    static final int MAX_KEY_PARTIES = 8;
    static final Set<String> PLACEHOLDER_ADDRESS = Set.of("UNKNOWN", "N/A", "NA", "NONE", "TBC");

    record Party(String claim, String id, String role, String name, String dob, String phone, String address) {}
    record Claim(String id, String policy, String accident, String loss, double amount, String garage, String clinic, String lawyer,
                 String referredBy) {}
    record Accident(String id, String city, double lat, double lon, String at, String report) {}
    record Bill(String claim, String provider, double amount) {}
    record Policy(String id, String holder, String broker, String inception) {}
    /** {@code kind} is the link kind; the pair is stored once (src &lt; dst) for the symmetric kinds. */
    record Link(String src, String dst, String kind) {}

    final List<Party> parties = new ArrayList<>();
    final Map<String, Claim> claims = new LinkedHashMap<>();
    final Map<String, Accident> accidents = new LinkedHashMap<>();
    final List<Bill> bills = new ArrayList<>();
    final Map<String, Policy> policies = new LinkedHashMap<>();
    final Set<Link> links = new LinkedHashSet<>();

    static ClaimsCorpus load() throws Exception {
        ClaimsCorpus c = new ClaimsCorpus();
        for (String[] r : rows("claim")) c.claims.put(r[0], new Claim(r[0], r[1], r[2], r[3], Double.parseDouble(r[5]), r[6], r[7], r[8], r[9]));
        for (String[] r : rows("claim_party")) c.parties.add(new Party(r[0], r[1], r[2], r[3], r[4], r[5], r[6]));
        for (String[] r : rows("accident_location")) c.accidents.put(r[0], new Accident(r[0], r[1], Double.parseDouble(r[2]), Double.parseDouble(r[3]), r[4], r[5]));
        for (String[] r : rows("provider_bill")) c.bills.add(new Bill(r[1], r[2], Double.parseDouble(r[4])));
        for (String[] r : rows("policy")) c.policies.put(r[0], new Policy(r[0], r[1], r[2], r[3]));
        c.fold();
        return c;
    }

    private static List<String[]> rows(String feed) throws Exception {
        Path dir = INBOX.resolve(feed);
        try (var files = Files.list(dir)) {
            Path f = files.filter(p -> p.toString().endsWith(".csv")).findFirst().orElseThrow();
            List<String[]> out = new ArrayList<>();
            List<String> lines = Files.readAllLines(f);
            for (String l : lines.subList(1, lines.size())) out.add(split(l));
            return out;
        }
    }

    /** One CSV line, honouring "double quoted, with ""escapes""" fields (addresses hold a comma). */
    static String[] split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else sb.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(ch);
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    static String digits(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }

    static boolean usablePhone(String phone) {
        String d = digits(phone);
        return d.length() >= 7 && !d.replace(d.substring(0, 1), "").isEmpty();
    }

    static boolean usableAddress(String a) {
        return a != null && !a.trim().isEmpty() && !PLACEHOLDER_ADDRESS.contains(a.trim().toUpperCase());
    }

    private void add(String a, String b, String kind, boolean symmetric) {
        if (symmetric && a.compareTo(b) > 0) { String t = a; a = b; b = t; }
        links.add(new Link(a, b, kind));
    }

    private void fold() {
        Map<String, List<String>> byClaim = new LinkedHashMap<>();
        for (Party p : parties) {
            if (p.id().isEmpty()) continue;
            List<String> m = byClaim.computeIfAbsent(p.claim(), k -> new ArrayList<>());
            if (!m.contains(p.id())) m.add(p.id());
        }
        for (var e : byClaim.entrySet()) {
            Claim c = claims.get(e.getKey());
            for (String a : e.getValue()) {
                for (String b : e.getValue()) if (a.compareTo(b) < 0) add(a, b, "claimed_with", true);
                if (!c.garage().isEmpty()) add(a, c.garage(), "serviced_by", false);
                if (!c.clinic().isEmpty()) add(a, c.clinic(), "serviced_by", false);
                if (!c.lawyer().isEmpty()) add(a, c.lawyer(), "represented_by", false);
            }
            if (!c.referredBy().isEmpty() && !c.clinic().isEmpty()) add(c.referredBy(), c.clinic(), "referred_to", false);
        }
        Map<String, List<String>> byAccident = new LinkedHashMap<>();
        for (Claim c : claims.values()) byAccident.computeIfAbsent(c.accident(), k -> new ArrayList<>()).add(c.id());
        for (List<String> cl : byAccident.values())
            for (String x : cl) for (String y : cl)
                if (!x.equals(y) && byClaim.containsKey(x) && byClaim.containsKey(y))
                    for (String a : byClaim.get(x)) for (String b : byClaim.get(y)) if (a.compareTo(b) < 0) add(a, b, "same_accident", true);
        Map<String, Set<String>> phone = new TreeMap<>(), address = new TreeMap<>();
        for (Party p : parties) {
            if (usablePhone(p.phone())) phone.computeIfAbsent(digits(p.phone()), k -> new TreeSet<>()).add(p.id());
            if (usableAddress(p.address())) address.computeIfAbsent(p.address().trim().toLowerCase(), k -> new TreeSet<>()).add(p.id());
        }
        shared(phone, "shares_phone");
        shared(address, "shares_address");
    }

    private void shared(Map<String, Set<String>> holders, String kind) {
        for (Set<String> h : holders.values()) {
            if (h.size() < 2 || h.size() > MAX_KEY_PARTIES) continue;
            for (String a : h) for (String b : h) if (a.compareTo(b) < 0) add(a, b, kind, true);
        }
    }

    Map<String, Integer> perKind() {
        Map<String, Integer> out = new TreeMap<>();
        for (Link l : links) out.merge(l.kind(), 1, Integer::sum);
        return out;
    }

    /** The link graph the Entity projection draws: one edge per distinct (src, dst, kind). */
    Graph graph() { return graph(links); }

    static Graph graph(Iterable<Link> ls) {
        Set<String> nodes = new LinkedHashSet<>();
        List<Edge> edges = new ArrayList<>();
        for (Link l : ls) {
            nodes.add(l.src());
            nodes.add(l.dst());
            edges.add(new Edge("e" + edges.size(), l.src(), l.dst()));
        }
        return new Graph(nodes.stream().map(n -> new Node(n, n)).toList(), edges);
    }

    /** The graph with every endpoint named in {@code merge} renamed (identity resolution: the duplicate folds into the survivor). */
    Graph resolved(Map<String, String> merge) {
        Set<Link> out = new LinkedHashSet<>();
        for (Link l : links) {
            String a = merge.getOrDefault(l.src(), l.src()), b = merge.getOrDefault(l.dst(), l.dst());
            if (!a.equals(b)) out.add(new Link(a, b, l.kind()));
        }
        return graph(out);
    }

    Set<String> neighbours(String id, String kind) {
        Set<String> out = new TreeSet<>();
        for (Link l : links)
            if (l.kind().equals(kind)) {
                if (l.src().equals(id)) out.add(l.dst());
                else if (l.dst().equals(id)) out.add(l.src());
            }
        return out;
    }

    Map<String, Integer> claimCount() {
        Map<String, Integer> out = new HashMap<>();
        for (Party p : parties) out.merge(p.id(), 1, Integer::sum);
        return out;
    }
}
