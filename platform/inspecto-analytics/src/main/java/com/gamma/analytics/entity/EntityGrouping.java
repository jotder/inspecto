package com.gamma.analytics.entity;

import com.gamma.analytics.similarity.Similarity;
import com.gamma.graph.UnionFind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic entity grouping (ANALYTICS-CORE-1 S2): records that share a block and satisfy a {@link MatchCondition}
 * are joined, the joins are closed transitively (union-find), and every record gets a <b>resolution group</b> id.
 *
 * <h2>The definition</h2>
 * <ol>
 *   <li>Every key column is normalised ({@link Normalization}); NULL or empty after normalising is <b>absent</b>.</li>
 *   <li>A record's <b>block</b> is the tuple of its normalised blocking keys; a record with any blocking key absent is in
 *       no block and stays a group of one. With no blocking keys every record is in the one block.</li>
 *   <li>Two records <b>match</b> when they share a block and, for at least one condition, every column of it is present
 *       on both and equal. The records sharing a block and one condition's values form a <b>match cell</b>.</li>
 *   <li>Resolution groups are the connected components of "match". The <b>group id</b> is the key of the member whose
 *       key text is smallest in code-point order (= DuckDB's VARCHAR order), so it does not depend on input order.</li>
 * </ol>
 * The JVM form ({@link #group}) uses the graph library's {@link UnionFind}; the DuckDB form is {@link EntityGroupingSql}.
 *
 * <h2>Caps — fail closed</h2>
 * Grouping refuses, before joining anything, when a match cell or a review block holds more than {@code maxBlockSize}
 * records (typically a placeholder value many records carry) or when the candidate pairs — the pairs of records sharing
 * a match cell, plus the pairs sharing a review block — exceed {@code maxPairs}. A record key that is empty or repeated
 * is refused too: a group id must name exactly one record.
 *
 * <p>⛔ <b>Identity is never asserted by similarity (D-M1).</b> Only {@link MatchCondition}s form groups. The optional
 * {@link SimilarityCandidate} is computed after the groups are final and fills {@link Candidate}s only.
 */
public final class EntityGrouping {

    private EntityGrouping() {
    }

    /** Default of the largest match cell or review block. */
    public static final int DEFAULT_MAX_BLOCK_SIZE = 1_000;
    /** The highest block size cap a spec may set. */
    public static final int MAX_BLOCK_SIZE_CEILING = 100_000;
    /** Default of the candidate pairs. */
    public static final long DEFAULT_MAX_PAIRS = 1_000_000L;
    /** The highest pair cap a spec may set. */
    public static final long MAX_PAIRS_CEILING = 100_000_000L;

    /**
     * A grouping.
     *
     * @param keyColumn    the record key column; every key present and distinct
     * @param blocking     blocking keys, may be empty
     * @param conditions   at least one deterministic match condition; any one joins two records
     * @param candidate    the optional similarity review (needs blocking keys), or {@code null}
     * @param maxBlockSize the largest match cell or review block allowed, 2..{@link #MAX_BLOCK_SIZE_CEILING}
     * @param maxPairs     the most candidate pairs allowed, 1..{@link #MAX_PAIRS_CEILING}
     */
    public record Spec(String keyColumn, List<KeyColumn> blocking, List<MatchCondition> conditions,
                       SimilarityCandidate candidate, int maxBlockSize, long maxPairs) {
        public Spec {
            Objects.requireNonNull(keyColumn, "keyColumn");
            blocking = List.copyOf(blocking);
            conditions = List.copyOf(conditions);
            if (conditions.isEmpty()) throw new IllegalArgumentException("entity grouping needs at least one match condition");
            if (candidate != null && blocking.isEmpty())
                throw new IllegalArgumentException("a similarity review compares records within a block - declare blocking keys");
            if (maxBlockSize < 2 || maxBlockSize > MAX_BLOCK_SIZE_CEILING)
                throw new IllegalArgumentException("the block size cap must be between 2 and " + MAX_BLOCK_SIZE_CEILING);
            if (maxPairs < 1 || maxPairs > MAX_PAIRS_CEILING)
                throw new IllegalArgumentException("the pair cap must be between 1 and " + MAX_PAIRS_CEILING);
        }

        /** A spec with the default caps and no similarity review. */
        public static Spec of(String keyColumn, List<KeyColumn> blocking, List<MatchCondition> conditions) {
            return new Spec(keyColumn, blocking, conditions, null, DEFAULT_MAX_BLOCK_SIZE, DEFAULT_MAX_PAIRS);
        }

        /** The refusal for empty and repeated keys, or {@code null}; the same words in both forms. */
        public String keyRefusal(long emptyKeys, long repeatedKeys) {
            if (emptyKeys > 0)
                return emptyKeys + " record(s) have an empty '" + keyColumn + "' - every record needs a key";
            if (repeatedKeys > 0)
                return repeatedKeys + " '" + keyColumn + "' value(s) appear more than once - the key must name one record";
            return null;
        }

        /** The refusal for the caps, or {@code null}; the same words in both forms. */
        public String capRefusal(long largestBlock, long pairs) {
            if (largestBlock > maxBlockSize)
                return "a block holds " + largestBlock + " records, more than the cap of " + maxBlockSize
                        + " - usually a placeholder value many records share (0, unknown, a switchboard number); "
                        + "filter it out first or add a blocking key";
            if (pairs > maxPairs)
                return pairs + " candidate pairs, more than the cap of " + maxPairs + " - add a blocking key";
            return null;
        }
    }

    /**
     * One record's similarity review: its best score against a record of another group in its block, that record's
     * group, and whether the score reaches the threshold. {@code score} and {@code group} are {@code null} when the
     * block holds no other group to compare with.
     */
    public record Candidate(Double score, String group, boolean review) {
        static final Candidate NONE = new Candidate(null, null, false);
    }

    /**
     * A grouping's result: record key → group id, in input order; and, with a similarity review, record key → its
     * {@link Candidate} (empty otherwise). Candidates never change a group.
     */
    public record Result(Map<String, String> groups, Map<String, Candidate> candidates) {
    }

    /** The JVM form. {@link IllegalStateException} carries a key or cap refusal. */
    public static Result group(Spec spec, List<EntityRecord> records) {
        Objects.requireNonNull(spec, "spec");
        long empty = 0, repeated = 0;
        Set<String> seen = new HashSet<>();
        for (EntityRecord r : records) {
            if (r.key() == null) empty++;
            else if (!seen.add(r.key())) repeated++;
        }
        String refusal = spec.keyRefusal(empty, repeated);
        if (refusal != null) throw new IllegalStateException(refusal);

        List<List<String>> blocks = new ArrayList<>(records.size());
        for (EntityRecord r : records) blocks.add(normalised(spec.blocking(), r));
        List<List<String>> cells = new ArrayList<>();
        for (MatchCondition condition : spec.conditions()) {
            Map<List<String>, List<String>> byValue = new LinkedHashMap<>();
            for (int i = 0; i < records.size(); i++) {
                List<String> block = blocks.get(i);
                List<String> values = normalised(condition.columns(), records.get(i));
                if (block == null || values == null) continue;
                List<String> cell = new ArrayList<>(block);
                cell.addAll(values);
                byValue.computeIfAbsent(cell, k -> new ArrayList<>()).add(records.get(i).key());
            }
            cells.addAll(byValue.values());
        }
        Map<List<String>, List<EntityRecord>> reviewBlocks = new LinkedHashMap<>();
        if (spec.candidate() != null)
            for (int i = 0; i < records.size(); i++)
                if (blocks.get(i) != null)
                    reviewBlocks.computeIfAbsent(blocks.get(i), k -> new ArrayList<>()).add(records.get(i));

        long largest = 0, pairs = 0;
        for (List<String> cell : cells) {
            largest = Math.max(largest, cell.size());
            pairs += (long) cell.size() * (cell.size() - 1) / 2;
        }
        for (List<EntityRecord> block : reviewBlocks.values()) {
            largest = Math.max(largest, block.size());
            pairs += (long) block.size() * (block.size() - 1) / 2;
        }
        refusal = spec.capRefusal(largest, pairs);
        if (refusal != null) throw new IllegalStateException(refusal);

        // the groups: formed by the match cells alone, final before any similarity is looked at (D-M1)
        UnionFind sets = new UnionFind();
        for (List<String> cell : cells)
            for (int i = 1; i < cell.size(); i++) sets.union(cell.get(i), cell.get(0));
        Map<String, String> smallest = new HashMap<>();
        for (EntityRecord r : records)
            smallest.merge(sets.find(r.key()), r.key(), (a, b) -> compareText(a, b) <= 0 ? a : b);
        Map<String, String> groups = new LinkedHashMap<>();
        for (EntityRecord r : records) groups.put(r.key(), smallest.get(sets.find(r.key())));
        Map<String, String> finalGroups = Collections.unmodifiableMap(groups);
        return new Result(finalGroups, candidates(spec.candidate(), records, reviewBlocks, finalGroups));
    }

    /** The similarity review over finished groups; it only reads {@code groups}. */
    private static Map<String, Candidate> candidates(SimilarityCandidate candidate, List<EntityRecord> records,
                                                     Map<List<String>, List<EntityRecord>> reviewBlocks,
                                                     Map<String, String> groups) {
        if (candidate == null) return Map.of();
        Map<String, Candidate> out = new LinkedHashMap<>();
        for (EntityRecord r : records) out.put(r.key(), Candidate.NONE);
        for (List<EntityRecord> block : reviewBlocks.values())
            for (EntityRecord r : block) {
                String a = text(candidate, r);
                if (a == null) continue;
                String own = groups.get(r.key());
                Double best = null;
                String bestGroup = null;
                for (EntityRecord s : block) {
                    String g = groups.get(s.key());
                    if (g.equals(own)) continue;
                    String b = text(candidate, s);
                    if (b == null) continue;
                    double score = candidate.method().score(a, b);
                    if (best == null || score > best || (score == best && compareText(g, bestGroup) < 0)) {
                        best = score;
                        bestGroup = g;
                    }
                }
                if (best != null) out.put(r.key(), new Candidate(best, bestGroup, best >= candidate.threshold()));
            }
        return Collections.unmodifiableMap(out);
    }

    private static String text(SimilarityCandidate candidate, EntityRecord r) {
        String v = r.value(candidate.column());
        return v == null || !candidate.normalize() ? v : Similarity.normalize(v);
    }

    /** The normalised values of {@code columns}, or {@code null} when any is absent. */
    private static List<String> normalised(List<KeyColumn> columns, EntityRecord r) {
        List<String> out = new ArrayList<>(columns.size());
        for (KeyColumn k : columns) {
            String v = k.normalization().apply(r.value(k.column()));
            if (v == null) return null;
            out.add(v);
        }
        return out;
    }

    /** Code-point order — the order of UTF-8 bytes, which is DuckDB's VARCHAR order. */
    public static int compareText(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }
}
