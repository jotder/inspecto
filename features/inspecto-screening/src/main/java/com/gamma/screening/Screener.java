package com.gamma.screening;

import com.gamma.entitystore.EntityFactLog;
import com.gamma.entitystore.EntityListFacts;
import com.gamma.entitystore.EntityRegistry;
import com.gamma.entitystore.MaskTokens;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Screens subjects against Entity Lists (SCR-D3 … SCR-D9, {@code docs/superpower/screening-addon-plan.md}).
 *
 * <ul>
 *   <li><b>identifier</b> — the list's own matching ({@link EntityRegistry.EntityList#match}: exact under the sealed
 *       normaliser, then prefix / range / CIDR), score 1.0;</li>
 *   <li><b>name</b> — {@link NameMatcher} against the list's LIVE members, compared only with members that share
 *       the first folded letter of at least one token (blocking, SCR-D9).</li>
 * </ul>
 * A retired list matches nothing; an expired entry never matches. An entry is shown as the list renders it: a
 * masked list's entry is its mask token (SCR-D14).
 */
public final class Screener {

    /** Default and bounds of the Match Score threshold (SCR-D6). */
    public static final double DEFAULT_THRESHOLD = 0.85, MIN_THRESHOLD = 0.5;
    /** Default and ceiling of matches reported per subject. */
    public static final int DEFAULT_MAX_MATCHES = 5, MAX_MATCHES = 20;

    /** One thing to screen: a caller key (opaque) plus a name and/or an identifier. */
    public record Subject(String key, String name, String identifier) {}

    /** One match: the list, the entry as the list renders it, {@code name | identifier}, the Match Score. */
    public record Match(String listId, String purpose, String entry, String method, double score) {}

    /** A list made ready for screening: its folded live members, blocked by initial, and its renderer. */
    public static final class Prepared {
        final EntityRegistry.EntityList list;
        final Map<Character, List<Member>> byInitial = new HashMap<>();
        final UnaryOperator<String> shown;

        Prepared(EntityRegistry.EntityList list, Instant now, UnaryOperator<String> shown) {
            this.list = list;
            this.shown = shown;
            if (list.retired()) return;
            for (String key : list.liveMembers(now)) {
                List<String> tokens = NameMatcher.tokens(key);
                if (tokens.isEmpty()) continue;
                Member m = new Member(key, tokens);
                for (Character c : initials(tokens)) byInitial.computeIfAbsent(c, x -> new ArrayList<>()).add(m);
            }
        }

        public String id() { return list.id(); }
    }

    private record Member(String key, List<String> tokens) {}

    private Screener() {}

    /**
     * The named lists of the Space at {@code writeRoot}, ready to screen. Unknown ids are refused
     * ({@link IllegalArgumentException} naming them); a broken fact chain throws as {@link EntityListFacts#read} does.
     */
    public static List<Prepared> load(Path writeRoot, List<String> listIds, Instant now) throws IOException {
        EntityFactLog log = new EntityFactLog(writeRoot);
        EntityFactLog.Log head = EntityListFacts.read(log);
        Map<String, EntityRegistry.EntityList> all = EntityRegistry.fold(head.facts(), head.headSeq());
        List<String> unknown = listIds.stream().filter(id -> !all.containsKey(id)).toList();
        if (!unknown.isEmpty()) throw new IllegalArgumentException("unknown entity list(s) " + unknown);
        List<Prepared> out = new ArrayList<>();
        byte[] key = null;
        for (String id : new LinkedHashSet<>(listIds)) {
            EntityRegistry.EntityList l = all.get(id);
            UnaryOperator<String> shown = UnaryOperator.identity();
            if (EntityListFacts.masked(writeRoot, l)) {
                if (key == null) key = MaskTokens.key(log.directory());
                byte[] k = key;
                shown = e -> MaskTokens.token(k, e);
            }
            out.add(new Prepared(l, now, shown));
        }
        return out;
    }

    /** The matches of {@code s} at or above {@code threshold}, best first, at most {@code maxMatches}. */
    public static List<Match> screen(Subject s, List<Prepared> lists, double threshold, int maxMatches, Instant now) {
        List<Match> out = new ArrayList<>();
        List<String> nameTokens = NameMatcher.tokens(s.name());
        for (Prepared p : lists) {
            if (p.list.retired()) continue;
            Set<String> seen = new java.util.HashSet<>();
            if (s.identifier() != null && !s.identifier().isBlank()) {
                String entry = p.list.match(s.identifier(), now);
                if (entry != null && seen.add(entry))
                    out.add(new Match(p.list.id(), p.list.purpose(), p.shown.apply(entry), "identifier", 1.0));
            }
            if (nameTokens.isEmpty()) continue;
            Set<Member> candidates = new LinkedHashSet<>();
            for (Character c : initials(nameTokens)) candidates.addAll(p.byInitial.getOrDefault(c, List.of()));
            for (Member m : candidates) {
                if (seen.contains(m.key())) continue;
                double score = NameMatcher.score(nameTokens, m.tokens());
                if (score >= threshold) {
                    seen.add(m.key());
                    out.add(new Match(p.list.id(), p.list.purpose(), p.shown.apply(m.key()), "name", score));
                }
            }
        }
        out.sort(Comparator.comparingDouble(Match::score).reversed()
                .thenComparing(Match::listId).thenComparing(Match::entry));
        return out.size() > maxMatches ? List.copyOf(out.subList(0, maxMatches)) : out;
    }

    private static Set<Character> initials(List<String> tokens) {
        Set<Character> out = new LinkedHashSet<>();
        for (String t : tokens) out.add(t.charAt(0));
        return out;
    }

    /** The wire shape of one match. */
    static Map<String, Object> toMap(Match m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listId", m.listId());
        out.put("purpose", m.purpose());
        out.put("entry", m.entry());
        out.put("method", m.method());
        out.put("score", m.score());
        return out;
    }
}
