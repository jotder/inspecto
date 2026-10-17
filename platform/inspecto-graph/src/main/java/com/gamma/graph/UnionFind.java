package com.gamma.graph;

import java.util.HashMap;
import java.util.Map;

/**
 * Disjoint sets over string keys (union-find with path compression), the one implementation in this library: Kruskal in
 * {@link GraphPaths#maximumSpanningForest} uses it, and it is the shared home the analytics core plan (M8) names for
 * deterministic entity grouping. A key never seen is a set of its own. Deterministic: {@link #union(String, String)}
 * always makes {@code b}'s root the root of the joined set, so a caller that wants a canonical root (say the smallest
 * key) passes the arguments in that order. Not thread-safe.
 */
public final class UnionFind {

    /** Non-root key to its parent; a root has no entry. */
    private final Map<String, String> parent = new HashMap<>();

    /** The root of {@code key}'s set ({@code key} itself when it was never joined). */
    public String find(String key) {
        String root = key;
        for (String p = parent.get(root); p != null; p = parent.get(root)) root = p;
        for (String x = key; !x.equals(root); ) {
            String next = parent.get(x);
            parent.put(x, root);
            x = next;
        }
        return root;
    }

    /** Joins the sets of {@code a} and {@code b} under {@code b}'s root; {@code false} when they were already one set. */
    public boolean union(String a, String b) {
        String ra = find(a);
        String rb = find(b);
        if (ra.equals(rb)) return false;
        parent.put(ra, rb);
        return true;
    }
}
