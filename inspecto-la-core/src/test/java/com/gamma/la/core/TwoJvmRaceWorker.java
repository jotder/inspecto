package com.gamma.la.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;

/**
 * One of the TWO real JVMs of {@link InvestigationStoreTwoJvmRace} (LA-INVESTIGATION-STORE-DESIGN-1, slice S7). Launched with
 * {@code ProcessBuilder}; it opens its OWN {@link InvestigationStore} (its own monitors, its own connections - nothing is shared
 * with its sibling but the backing storage), waits for the {@code go} file so both start together, runs one scenario hammering
 * the shared state, and writes one {@code KEY value} fact per line to its result file for the launcher to assert on.
 *
 * <p>Args: {@code openerClass spec goFile outFile workerId scenario n}. A non-zero exit means the scenario itself threw.
 */
public final class TwoJvmRaceWorker {

    /** Builds a store from a backend-specific spec (a directory, or a schema name). Public, no-arg constructor. */
    public interface Opener {
        InvestigationStore open(String spec) throws Exception;
    }

    /** The two-step main every promote scenario starts from, and the Draft that is promoted onto it. */
    static final List<String> MAIN = List.of("{\"step\":1}", "{\"step\":2}");
    static final List<String> DRAFT_OWN = List.of("{\"step\":3,\"d\":1}", "{\"step\":4,\"d\":1}", "{\"step\":5,\"d\":1}");
    static final List<String> PROMOTED = List.of("{\"step\":3,\"p\":1}", "{\"step\":4,\"p\":2}", "{\"step\":5,\"p\":3}");
    static final List<String> PROMOTED_SETS = Arrays.asList(null, "{\"fresh\":4}", null);

    public static void main(String[] a) throws Exception {
        Opener opener = (Opener) Class.forName(a[0]).getDeclaredConstructor().newInstance();
        InvestigationStore store = opener.open(a[1]);
        Path go = Path.of(a[2]), out = Path.of(a[3]);
        String worker = a[4], scenario = a[5];
        int n = Integer.parseInt(a[6]);
        for (long t = System.currentTimeMillis(); !Files.exists(go); ) {
            if (System.currentTimeMillis() - t > 60_000) throw new IllegalStateException("no go signal");
            Thread.sleep(2);
        }
        switch (scenario) {
            case "append" -> append(store, out, worker, n);
            case "decide" -> decide(store, out, worker, n);
            case "mask" -> mask(store, out, n);
            case "promote" -> promote(store, out, worker, n);
            default -> throw new IllegalArgumentException(scenario);
        }
    }

    private static void fact(Path out, String k, String v) throws Exception {
        Files.writeString(out, k + " " + v + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** N appends to ONE main log, each chained to the exact prefix it read (the line carries {@code prefixHash} of what precedes it). */
    private static void append(InvestigationStore s, Path out, String w, int n) throws Exception {
        var scope = InvestigationStore.Scope.main("race");
        int conflicts = 0;
        for (int i = 1; i <= n; i++) {
            while (true) {
                List<String> log = s.log(scope);
                int v = log.size();
                String line = "{\"step\":" + (v + 1) + ",\"w\":\"" + w + "\",\"i\":" + i + ",\"prev\":\"" + DraftStore.prefixHash(log, v) + "\"}";
                try {
                    s.append(scope, v, v + 1, line, "{\"set\":" + (v + 1) + ",\"w\":\"" + w + "\",\"i\":" + i + "}");
                    break;
                } catch (InvestigationVersionConflictException lost) {
                    conflicts++;
                }
            }
        }
        fact(out, "APPENDED", String.valueOf(n));
        fact(out, "CONFLICTS", String.valueOf(conflicts));
    }

    /** The compare-and-set: every request {@code r0..r(n-1)} is decided by exactly one of the two workers. */
    private static void decide(InvestigationStore s, Path out, String w, int n) throws Exception {
        for (int r = 0; r < n; r++) {
            String id = "r" + r;
            if (s.replacePending("dec", id, "{\"state\":\"pending\"}", "{\"state\":\"decided\",\"by\":\"" + w + "\"}"))
                fact(out, "WON", id);
        }
    }

    /** First use of a mask key on a fresh Investigation, from both JVMs at once: they must end with the same key. */
    private static void mask(InvestigationStore s, Path out, int n) throws Exception {
        for (int k = 0; k < n; k++)
            fact(out, "KEY", "mk" + k + " " + java.util.HexFormat.of().formatHex(s.maskKey("mk" + k)));
    }

    /** Both JVMs promote the SAME Draft onto the same 2-step main: exactly one may, the other must be refused. */
    private static void promote(InvestigationStore s, Path out, String w, int n) throws Exception {
        for (int k = 0; k < n; k++) {
            String inv = "pr" + k;
            String draft = Files.readString(out.resolveSibling("draft-" + inv), StandardCharsets.UTF_8).trim();
            try {
                s.promoteDraft(inv, draft, MAIN.size(), DraftStore.prefixHash(MAIN, MAIN.size()), DraftStore.prefixHash(DRAFT_OWN, DRAFT_OWN.size()),
                        PROMOTED, PROMOTED_SETS, "{\"promotedBy\":\"" + w + "\"}");
                fact(out, "PROMOTED", inv);
            } catch (InvestigationVersionConflictException | InvestigationStore.DraftClosedException refused) {
                fact(out, "REFUSED", inv + " " + refused.getClass().getSimpleName());
            }
        }
    }
}
