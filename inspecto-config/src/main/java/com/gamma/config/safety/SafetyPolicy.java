package com.gamma.config.safety;

import com.gamma.api.PublicApi;
import com.gamma.util.CurrentSpace;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The policy a {@link ConfigSafetyValidator} enforces against a config draft: which filesystem roots
 * a config may write under, the numeric caps it may not exceed, and the output formats/codecs it may
 * target. Introduced at M5 (v3.5.0) for the hard-fail config safety gate (security guardrail R6).
 *
 * <p>A config can <em>parse</em> yet be harmful — write outside the workspace, oversubscribe the box,
 * or target an unknown sink. The policy makes "safe" a checkable property rather than a hope. The
 * defaults are deliberately permissive on caps (a safety backstop, not a tuning knob) and strict on
 * paths (the real security boundary): everything must resolve under an allowed root.
 *
 * <p>All fields are normalised + never-null via the compact constructor, so callers can read them
 * without guards. {@code allowedRoots} are made absolute + normalised so {@code startsWith} containment
 * is meaningful.
 *
 * @param allowedRoots       filesystem roots a config's paths must resolve under (absolute, normalised)
 * @param denyRoots          filesystem roots no path may resolve under, even inside an allowed root (deny beats allow;
 *                           from {@code deny.roots} of the server + Space Safety Policy files)
 * @param maxThreads         upper bound for {@code processing.threads} / {@code duckdb_threads}
 * @param maxBatchFiles      upper bound for {@code collector.consignment.max_files} (and the legacy {@code processing.batch.max_files})
 * @param maxBatchBytes      upper bound for {@code collector.consignment.max_bytes} (and the legacy {@code processing.batch.max_bytes})
 * @param allowedFormats     permitted {@code output.format} values (upper-case)
 * @param allowedCompression permitted {@code output.compression} codecs (lower-case)
 * @param advanceState       whether this Space's runs may move progress state ({@code permit.advance_state}); the
 *                           plan-time half of S6 (D9) refuses a config that implies an advance when false. A
 *                           {@code mode: audit} policy reads as true here: the act-time {@link StateGate} logs instead of refusing
 * @since 3.5.0
 */
@PublicApi(since = "4.0.0")
public record SafetyPolicy(
        List<Path> allowedRoots,
        List<Path> denyRoots,
        int maxThreads,
        int maxBatchFiles,
        long maxBatchBytes,
        Set<String> allowedFormats,
        Set<String> allowedCompression,
        boolean advanceState) {

    private static final Set<String> DEFAULT_FORMATS = Set.of("CSV", "PARQUET");
    private static final Set<String> DEFAULT_COMPRESSION =
            Set.of("none", "uncompressed", "snappy", "gzip", "zstd", "lz4");

    public SafetyPolicy {
        // ⛔ No working-directory substitution here. Until 2026-08-14 an empty list silently became
        // [CWD] — which quietly granted the server's working directory to every containment check on
        // an unconfigured deployment, the exact posture defaultPolicy()'s Javadoc forbids. Empty stays
        // empty; PathJail.requireUnderAny throws on it, so the failure is loud and names the property.
        allowedRoots = allowedRoots == null ? List.of() : normalizeRoots(allowedRoots);
        denyRoots = denyRoots == null ? List.of() : normalizeRoots(denyRoots);
        if (maxThreads <= 0) maxThreads = Runtime.getRuntime().availableProcessors();
        if (maxBatchFiles <= 0) maxBatchFiles = 1_000_000;
        if (maxBatchBytes <= 0) maxBatchBytes = Long.MAX_VALUE;
        allowedFormats = (allowedFormats == null || allowedFormats.isEmpty())
                ? DEFAULT_FORMATS : Set.copyOf(allowedFormats);
        allowedCompression = (allowedCompression == null || allowedCompression.isEmpty())
                ? DEFAULT_COMPRESSION : Set.copyOf(allowedCompression);
    }

    private static List<Path> normalizeRoots(List<Path> roots) {
        List<Path> out = new ArrayList<>(roots.size());
        for (Path p : roots) {
            if (p != null) out.add(p.toAbsolutePath().normalize());
        }
        return List.copyOf(out);
    }

    /**
     * The production default: {@link #forSpace(String)} for the Space the calling thread is bound to
     * ({@link CurrentSpace#id()} — the request's {@code /spaces/{id}} binding, a job's run, a collector
     * cycle). Recomputed per call, so a space created at runtime is an allowed root on the very next
     * check — no restart.
     *
     * <p>🔴 <b>Not a union of every hosted Space</b> ({@code CROSS-SPACE-JAIL-1}, 2026-09-24): until then
     * this unioned every registered base, so a config in Space A could name a path inside Space B's base
     * and pass. A thread with no Space binding is the {@code default} Space, and gets only that Space's
     * base (if one is registered) — never another Space's.
     *
     * <p>⚠ There is <b>no working-directory fallback</b> — with the property unset and no spaces
     * hosted the root list is <b>empty</b>, and {@link PathJail#requireUnderAny} throws on that, so
     * every jailed reference fails rather than silently jailing to the CWD. Fail-closed: a
     * misconfigured deployment must not quietly grant the server's working directory. (This Javadoc
     * claimed that posture from 2026-08-14, but the record constructor still substituted the CWD
     * until later the same day — the claim is now true.)
     */
    public static SafetyPolicy defaultPolicy() {
        String space = CurrentSpace.id();
        if (PINNED.isBound() && PINNED.get().space().equals(space)) return PINNED.get().policy();
        return forSpace(space);
    }

    /** The policy and folded tier this thread's run pinned, if any - capture it before fanning out to a worker pool. */
    public static java.util.Optional<Pin> pinned() {
        return PINNED.isBound() && PINNED.get().space().equals(CurrentSpace.id())
                ? java.util.Optional.of(PINNED.get()) : java.util.Optional.empty();
    }

    /**
     * Re-binds an already-resolved {@code pin} (policy <b>and</b> tier) for {@code run} on the calling (worker)
     * thread. The pin is a {@link ScopedValue}: it does not follow a task onto an executor, so a worker that makes
     * act-time decisions (path jail, {@link EgressGate}) is handed the planning thread's {@link #pinned()} snapshot
     * explicitly - it must not re-read the files (a different answer mid-run) nor fall back to the {@code default}
     * Space when its MDC is empty.
     */
    public static <T> T runWithPinned(Pin pin, java.util.function.Supplier<T> run) {
        return ScopedValue.where(PINNED, new Pin(CurrentSpace.id(), pin.policy(), pin.tier())).call(run::get);
    }

    /** One run's pinned snapshot: the Space it was resolved for, its policy, and the folded tier it came from. */
    public record Pin(String space, SafetyPolicy policy, SafetyPolicyTier tier) {}

    /** The policy a run planned with, visible to {@link #defaultPolicy()} for the rest of that run. */
    private static final ScopedValue<Pin> PINNED = ScopedValue.newInstance();

    /**
     * Plan-time pin of one run ({@code policy-narrowing-design.md} §5.3, D8): resolves the calling Space's
     * effective policy <b>once</b> and runs {@code run} with that snapshot, so a file tightened mid-run applies to
     * the next run. Throws {@link SafetyPolicyUnreadableException} <b>before</b> {@code run} starts when a policy
     * file in scope is unreadable - the run is refused, never started under "no policy".
     *
     * <p>⚠ The pin rides a {@link ScopedValue}, so it is visible on the planning thread and not on a worker pool
     * the run fans out to; an act-time gate that runs on such a thread must be handed the policy explicitly.
     */
    public static <T> T pinnedForRun(java.util.function.Supplier<T> run) {
        String space = CurrentSpace.id();
        SafetyPolicyTier t = tierForSpace(space);
        return ScopedValue.where(PINNED, new Pin(space, toPolicy(t), t)).call(run::get);
    }

    /**
     * Every Safety Policy file problem in scope right now: the server file and each hosted Space's file, one
     * message each (distinct). Empty when every file in scope is readable. Backs {@code /health} (D6).
     */
    public static List<String> unreadable() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        java.util.Set<String> ids = new java.util.TreeSet<>(DiscoveredRoots.ids());
        if (ids.isEmpty()) ids.add(CurrentSpace.DEFAULT_SPACE_ID);
        for (String id : ids) {
            try {
                forSpace(id);
            } catch (SafetyPolicyUnreadableException e) {
                out.add(e.getMessage());
            }
        }
        return List.copyOf(out);
    }

    /**
     * One Space's policy: the operator-declared {@code -Dassist.safety.roots} (a {@code ;}-separated list —
     * destinations outside the space layout, e.g. {@code /mnt/backups}) plus <b>that Space's own base</b>
     * as registered in {@link DiscoveredRoots}; caps sized to this box. Another Space's base is never an
     * allowed root here. For a caller that already holds the Space id; everyone else takes
     * {@link #defaultPolicy()}.
     */
    public static SafetyPolicy forSpace(String spaceId) {
        return toPolicy(tierForSpace(spaceId));
    }

    /**
     * The folded Safety Policy tier the calling thread's Space runs under: the run's pinned snapshot when one is
     * bound on this thread, else resolved now. Act-time gates ({@link EgressGate}) read it.
     */
    public static SafetyPolicyTier effectiveTier() {
        String space = CurrentSpace.id();
        if (PINNED.isBound() && PINNED.get().space().equals(space)) return PINNED.get().tier();
        return tierForSpace(space);
    }

    private static SafetyPolicyTier tierForSpace(String spaceId) {
        return tiersForSpace(spaceId).effective();
    }

    /**
     * The server and Space tiers a Space's effective policy folds, with the files consulted - the input of the
     * explain route (S7). Same loading and refusals as {@link #forSpace}: throws when a file is unreadable.
     */
    public static SafetyPolicyFiles.Tiers tiersForSpace(String spaceId) {
        List<Path> roots = baseRoots(spaceId);
        Path base = DiscoveredRoots.baseOf(spaceId).orElse(null);
        // Safety Policy tiers (policy-narrowing-design S2): the server + Space files can only NARROW these
        // defaults; an unreadable file throws SafetyPolicyUnreadableException instead of reading as "none".
        String serverDir = System.getProperty("system.config.dir");
        return SafetyPolicyFiles.tiers(
                serverDir == null || serverDir.isBlank() ? null : Paths.get(serverDir.trim()), base, spaceId, roots);
    }

    private static SafetyPolicy toPolicy(SafetyPolicyTier t) {
        Set<String> formats = new java.util.LinkedHashSet<>(DEFAULT_FORMATS);
        if (t.allowFormats() != null) formats.retainAll(t.allowFormats());
        return new SafetyPolicy(t.allowRoots(), t.denyRoots(), t.capThreads(Runtime.getRuntime().availableProcessors()),
                t.capBatchFiles(1_000_000), t.capBatchBytes(Long.MAX_VALUE), formats.isEmpty() ? Set.of("-") : formats,
                DEFAULT_COMPRESSION, t.permitsAdvanceState() || t.effectiveMode() == SafetyPolicyTier.Mode.AUDIT);
    }

    /**
     * The roots a Space has <b>before any Safety Policy file narrows them</b>: the operator-declared
     * {@code -Dassist.safety.roots} plus that Space's own base. Never reads a policy file, so it cannot throw
     * {@link SafetyPolicyUnreadableException}: it is the fail-closed fallback for a <em>load</em>-time check when a
     * policy file is unreadable (the run itself is still refused - see {@link #pinnedForRun}), never wider than
     * the pre-narrowing posture.
     */
    public static List<Path> baseRoots(String spaceId) {
        List<Path> roots = new ArrayList<>();
        String prop = System.getProperty("assist.safety.roots", "");
        for (String s : prop.split(";")) {
            if (!s.isBlank()) roots.add(Paths.get(s.trim()).toAbsolutePath().normalize());
        }
        DiscoveredRoots.baseOf(spaceId).ifPresent(roots::add);
        return roots;
    }

    /** A policy rooted at the given dirs (the skill's workspace, or a test temp dir). */
    public static SafetyPolicy withRoots(Path... roots) {
        return new SafetyPolicy(List.of(roots), List.of(), Runtime.getRuntime().availableProcessors(),
                1_000_000, Long.MAX_VALUE, DEFAULT_FORMATS, DEFAULT_COMPRESSION, true);
    }
}
