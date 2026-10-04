package com.gamma.la.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * D7-4 - the per-step checkpoint of a Draft (design {@code la-separation-d7-design.md} section 4, decision D7-Q4: a
 * checkpoint at EVERY step). Two in-memory caches, both purely an accelerator: a miss recomputes exactly what the old path
 * did, so a cache can never change an answer, only skip work.
 *
 * <ul>
 *   <li><b>Evaluated state.</b> The Working Set the last step produced, keyed by the Draft directory and valid only while
 *       the Draft's {@code log.jsonl} still has the size and mtime it had when the checkpoint was taken (and the same
 *       number of entries). An op then resumes from it - {@code copy()} + {@code apply} - instead of re-folding the main
 *       prefix and the Draft's own log from step 0; this is the same incremental rule replay uses, so replay == incremental
 *       by construction. An undo cannot pop an incremental state, so it still folds (and re-seeds the checkpoint).</li>
 *   <li><b>Verified base.</b> Whether the main log's first {@code baseStep} entries still hash to the header's
 *       {@code baseLogHash}, keyed by the main log file and valid only while that file keeps its size and mtime. Any append
 *       to (or rewrite of) the main log changes it and forces one re-hash: the fail-closed check is kept, only not repeated
 *       for an unchanged file.</li>
 * </ul>
 * The sets on disk ({@code sets/<step>.json}) stay the audit and replay record; they are a response-shaped view, not a
 * restorable state, so a restart simply takes the first miss and re-folds once.
 */
public final class DraftCheckpoints {

    private DraftCheckpoints() {}

    private record Checkpoint(String logSig, int entries, InvestigationEvaluator.State state) {}

    /** The verified-base verdict for one main log file: what was checked, against what, and the answer. */
    public record Base(String mainSig, int baseStep, String baseLogHash, boolean intact, int mainSize) {}

    private static final Map<String, Checkpoint> STATES = new ConcurrentHashMap<>();
    private static final Map<String, Base> BASES = new ConcurrentHashMap<>();

    /** File identity for cache validity: size + mtime, or {@code "absent"}. */
    static String sig(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) + ":" + Files.getLastModifiedTime(file).toMillis() : "absent";
        } catch (IOException e) {
            return "unreadable:" + System.nanoTime();   // never equal to a later reading: forces a miss
        }
    }

    /** The state of {@code log} (the whole list: main prefix + own entries) - the checkpoint when it is still current, else one fold. */
    public static InvestigationEvaluator.State stateOf(Path draftDir, List<Map<String, Object>> log) {
        return stateOf(keyOf(draftDir), sig(draftDir.resolve("log.jsonl")), log);
    }

    /** The cache key of a Draft directory: what {@link InvestigationStore#cacheKey} answers for the filesystem. */
    static String keyOf(Path draftDir) {
        return draftDir.toAbsolutePath().normalize().toString();
    }

    /**
     * {@link #stateOf(Path, List)} keyed by {@code key} (a store's {@code cacheKey(scope)}) and valid only while {@code token}
     * (the store's {@code logToken(scope)}, which changes whenever the log does) is the one the checkpoint was taken at.
     */
    public static InvestigationEvaluator.State stateOf(String key, String token, List<Map<String, Object>> log) {
        Checkpoint c = STATES.get(key);
        if (c != null && c.entries() == log.size() && c.logSig().equals(token)) return c.state();
        InvestigationEvaluator.State s = InvestigationEvaluator.evaluate(log, -1, null);
        remember(key, token, log.size(), s);
        return s;
    }

    /** The state after appending {@code entry} to {@code log} given the state {@code before} it: one apply for an op, a fold for an undo. */
    public static InvestigationEvaluator.State after(InvestigationEvaluator.State before, List<Map<String, Object>> log, Map<String, Object> entry) {
        if ("op".equals(entry.get("kind"))) {
            InvestigationEvaluator.State s = before.copy();
            InvestigationEvaluator.apply(s, entry);
            return s;
        }
        List<Map<String, Object>> next = new ArrayList<>(log);
        next.add(entry);
        return InvestigationEvaluator.evaluate(next, -1, null);
    }

    /** Record the state at {@code entries} entries; call it AFTER the step was appended so the log signature is the new one. */
    public static void remember(Path draftDir, int entries, InvestigationEvaluator.State state) {
        remember(keyOf(draftDir), sig(draftDir.resolve("log.jsonl")), entries, state);
    }

    public static void remember(String key, String token, int entries, InvestigationEvaluator.State state) {
        STATES.put(key, new Checkpoint(token, entries, state));
    }

    /** Drop every cached base verdict, so the next check re-hashes (the replay audit calls this: it never trusts a cache). */
    public static void forgetBases() {
        BASES.clear();
    }

    /** Forget a Draft's checkpoint (discard). */
    public static void forget(Path draftDir) {
        forget(keyOf(draftDir));
    }

    public static void forget(String key) {
        STATES.remove(key);
    }

    /**
     * Whether the main log's first {@code baseStep} entries still hash to {@code baseLogHash}. {@code mainLines} is read only
     * on a miss (the main log changed, or first look).
     */
    public static Base base(Path mainLog, int baseStep, String baseLogHash, Supplier<List<String>> mainLines) {
        return base(mainLog.toString(), sig(mainLog), baseStep, baseLogHash, mainLines);
    }

    /** {@link #base(Path, int, String, Supplier)} keyed by the main log's {@code cacheKey} and valid while its {@code logToken} is unchanged. */
    public static Base base(String mainKey, String sig, int baseStep, String baseLogHash, Supplier<List<String>> mainLines) {
        String key = mainKey + "|" + baseStep;
        Base b = BASES.get(key);
        if (b != null && b.mainSig().equals(sig) && b.baseLogHash().equals(baseLogHash)) return b;
        List<String> main = mainLines.get();
        b = new Base(sig, baseStep, baseLogHash, main.size() >= baseStep && DraftStore.prefixHash(main, baseStep).equals(baseLogHash), main.size());
        BASES.put(key, b);
        return b;
    }
}
