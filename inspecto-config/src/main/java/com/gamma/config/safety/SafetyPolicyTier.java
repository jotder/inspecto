package com.gamma.config.safety;

import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * One tier of a <b>Safety Policy</b> — the server file or a Space file — and, after {@link #fold}, the
 * effective policy a run is checked against ({@code docs/archived-documents/plans-archive/policy-narrowing-design.md} §2–§3).
 *
 * <p><b>A lower tier can only narrow.</b> There is no name, no overlay, no replace: {@code permit.*} fold by
 * AND, {@code allow.*} by intersection (prefix sets through a boundary matcher, §3.3), {@code deny.*} by
 * union, {@code caps.*} by MIN (D14), and {@code mode} is server-only. So for every Space tier {@code W},
 * {@code permitted(fold(S, W), act) ⇒ permitted(S, act)} — the monotonicity property
 * {@code SafetyPolicyTierTest} generates cases for.
 *
 * <p>⚠ <b>Absent ≠ empty.</b> Every field is {@code null} when the tier does not state it. A {@code null}
 * permit does not constrain ({@code true}); a {@code null} allow-set is ⊤ ("anything"), while an empty one
 * means <em>nothing</em>; a {@code null} cap is +∞.
 *
 * <p>Pure: no I/O, no DNS. Loading, the unreadable states and the per-run snapshot are slice S2.
 */
public record SafetyPolicyTier(
        Mode mode,
        Boolean network,
        Boolean installExtensions,
        Boolean advanceState,
        Boolean rewindState,
        List<Path> allowRoots,
        List<HostPattern> allowHosts,
        Set<String> allowConnectors,
        Set<String> allowExtensions,
        Set<String> allowFormats,
        List<Path> denyRoots,
        List<HostPattern> denyHosts,
        Integer maxThreads,
        Integer maxBatchFiles,
        Long maxBatchBytes) {

    /** D5: {@code enforce} refuses; {@code audit} records a would-refuse and lets the act proceed. */
    public enum Mode { ENFORCE, AUDIT }

    /** A tier that states nothing — narrows nothing. */
    public static final SafetyPolicyTier NONE = new SafetyPolicyTier(null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null);

    public SafetyPolicyTier {
        allowRoots = allowRoots == null ? null : normRoots(allowRoots);
        denyRoots = denyRoots == null ? null : normRoots(denyRoots);
        allowHosts = allowHosts == null ? null : List.copyOf(allowHosts);
        denyHosts = denyHosts == null ? null : List.copyOf(denyHosts);
        allowConnectors = lower(allowConnectors);
        allowExtensions = lower(allowExtensions);
        allowFormats = allowFormats == null ? null : upperSet(allowFormats);
    }

    /**
     * The effective policy of a server tier narrowed by a Space tier. A Space tier stating {@code mode}
     * is refused — {@code mode} is server-only (D5; §5.1 makes such a file unreadable).
     */
    public static SafetyPolicyTier fold(SafetyPolicyTier server, SafetyPolicyTier space) {
        if (space.mode != null) throw new IllegalArgumentException("mode is legal in the server Safety Policy only");
        return new SafetyPolicyTier(
                server.mode == null ? Mode.ENFORCE : server.mode,
                and(server.network, space.network),
                and(server.installExtensions, space.installExtensions),
                and(server.advanceState, space.advanceState),
                and(server.rewindState, space.rewindState),
                meet(server.allowRoots, space.allowRoots, PathJail::contains),
                meet(server.allowHosts, space.allowHosts, HostPattern::covers),
                meetSet(server.allowConnectors, space.allowConnectors),
                meetSet(server.allowExtensions, space.allowExtensions),
                meetSet(server.allowFormats, space.allowFormats),
                union(server.denyRoots, space.denyRoots),
                union(server.denyHosts, space.denyHosts),
                min(server.maxThreads, space.maxThreads),
                min(server.maxBatchFiles, space.maxBatchFiles),
                min(server.maxBatchBytes, space.maxBatchBytes));
    }

    // ---- verdicts -------------------------------------------------------------------------------------

    public Mode effectiveMode() { return mode == null ? Mode.ENFORCE : mode; }
    public boolean permitsNetwork() { return !Boolean.FALSE.equals(network); }
    public boolean permitsInstallExtensions() { return !Boolean.FALSE.equals(installExtensions); }
    public boolean permitsAdvanceState() { return !Boolean.FALSE.equals(advanceState); }
    public boolean permitsRewindState() { return !Boolean.FALSE.equals(rewindState); }

    /** A dial to {@code host}: the network permit, the allow-set, and no deny entry (deny beats allow). */
    public boolean permitsHost(String host) {
        if (!permitsNetwork()) return false;
        if (allowHosts != null && allowHosts.stream().noneMatch(p -> p.matches(host))) return false;
        return denyHosts == null || denyHosts.stream().noneMatch(p -> p.matches(host));
    }

    /** D13: the resolved address of a permitted name still lands in a denied CIDR (e.g. metadata endpoints). */
    public boolean deniesAddress(InetAddress resolved) {
        return denyHosts != null && denyHosts.stream().anyMatch(p -> p.matches(resolved));
    }

    /** A path is permitted when some allow root contains it (component-wise) and no deny root does. */
    public boolean permitsPath(Path p) {
        if (allowRoots != null && allowRoots.stream().noneMatch(r -> PathJail.contains(r, p))) return false;
        return denyRoots == null || denyRoots.stream().noneMatch(r -> PathJail.contains(r, p));
    }

    public boolean permitsConnector(String scheme) { return inSet(allowConnectors, scheme.toLowerCase(Locale.ROOT)); }
    public boolean permitsExtension(String name) { return inSet(allowExtensions, name.toLowerCase(Locale.ROOT)); }
    public boolean permitsFormat(String format) { return inSet(allowFormats, format.toUpperCase(Locale.ROOT)); }

    /** A cap with the base value used when no tier states it (D14: the validator's caps are the server defaults). */
    public int capThreads(int base) { return maxThreads == null ? base : Math.min(base, maxThreads); }
    public int capBatchFiles(int base) { return maxBatchFiles == null ? base : Math.min(base, maxBatchFiles); }
    public long capBatchBytes(long base) { return maxBatchBytes == null ? base : Math.min(base, maxBatchBytes); }

    /**
     * The object-store prefix boundary matcher (§3.3): {@code a} covers {@code b} iff same bucket and the key
     * prefix of {@code b} equals {@code a}'s or starts with {@code a}'s <em>normalised to end in {@code /}</em>.
     * Values are {@code bucket/key-prefix}; so {@code bucket/in} does not cover {@code bucket/inbox}.
     */
    public static boolean objectPrefixCovers(String a, String b) {
        String na = trimSlash(a), nb = trimSlash(b);
        int ia = na.indexOf('/'), ib = nb.indexOf('/');
        String bucketA = ia < 0 ? na : na.substring(0, ia), bucketB = ib < 0 ? nb : nb.substring(0, ib);
        if (!bucketA.equals(bucketB)) return false;
        return ia < 0 || nb.equals(na) || nb.startsWith(na + "/");
    }

    // ---- fold helpers ----------------------------------------------------------------------------------

    private static Boolean and(Boolean a, Boolean b) {
        if (a == null) return b;
        if (b == null) return a;
        return a && b;
    }

    private static <T extends Comparable<T>> T min(T a, T b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** Prefix-set intersection: {@code narrower(a, b)} for every pair where one covers the other; ⊤ is null. */
    private static <T> List<T> meet(List<T> a, List<T> b, BiPredicate<T, T> covers) {
        if (a == null) return b;
        if (b == null) return a;
        Set<T> out = new LinkedHashSet<>();
        for (T x : a) {
            for (T y : b) {
                if (covers.test(x, y)) out.add(y);
                else if (covers.test(y, x)) out.add(x);
            }
        }
        return List.copyOf(out);
    }

    private static Set<String> meetSet(Set<String> a, Set<String> b) {
        if (a == null) return b;
        if (b == null) return a;
        Set<String> out = new LinkedHashSet<>(a);
        out.retainAll(b);
        return Set.copyOf(out);
    }

    private static <T> List<T> union(List<T> a, List<T> b) {
        if (a == null) return b;
        if (b == null) return a;
        Set<T> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return List.copyOf(out);
    }

    private static boolean inSet(Set<String> allow, String v) { return allow == null || allow.contains(v); }

    private static List<Path> normRoots(List<Path> roots) {
        List<Path> out = new ArrayList<>(roots.size());
        for (Path p : roots) out.add(p.toAbsolutePath().normalize());
        return List.copyOf(out);
    }

    private static Set<String> lower(Set<String> s) {
        if (s == null) return null;
        Set<String> out = new LinkedHashSet<>();
        for (String v : s) out.add(v.trim().toLowerCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    private static Set<String> upperSet(Set<String> s) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : s) out.add(v.trim().toUpperCase(Locale.ROOT));
        return Set.copyOf(out);
    }

    private static String trimSlash(String s) {
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }
}
