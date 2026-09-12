package com.gamma.service;

import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which Spaces THIS pod hosts — phase C of the enterprise scale-out plan (§5.3), the static half of work
 * distribution.
 *
 * <h3>The invariant this exists to hold</h3>
 * <i>Every Space has exactly one owning pod at any moment; no pod polls an inbox it does not own.</i>
 * Partitioning is by <b>Space</b>, never by pipeline (D3): a Space is already the tenant boundary and the
 * namespace for every store, ledger and inbox, so splitting one across pods re-opens every §3.7 race.
 *
 * <h3>⚠ Absent the file, NOTHING changes</h3>
 * No {@code partition.toon} ⇒ {@link #hostsEverything()} ⇒ this pod boots every Space it can see, exactly
 * as before. ⛔ That default is load-bearing: Personal and single-node Standard must never need this file,
 * and must never behave differently for its absence.
 *
 * <h3>🔴 Why this does NOT copy {@code SchedulerSettings.read}'s fail-soft idiom</h3>
 * Every other global TOON file in this tree ({@code scheduler.toon}, {@code branding.toon}, …) swallows a
 * malformed or unreadable file and falls back to defaults, because for them the fallback is harmless. Here
 * the fallback would be <b>"host everything"</b> — i.e. every pod hosting every Space, which is precisely
 * the invariant violation the file exists to prevent, and it would appear as duplicate processing rather
 * than as a config error. So a {@code partition.toon} that is present but unreadable, malformed, or
 * unresolvable for this pod is a <b>boot failure</b>. ⛔ Do not "make it robust" by catching and defaulting.
 *
 * <h3>The three failure modes, and why they differ</h3>
 * <ul>
 *   <li><b>Map present, pod identity unknown → FAIL BOOT.</b> A pod that cannot know which Spaces are its
 *       own is misconfigured; hosting nothing would idle it silently, hosting everything would double-host.
 *       Same call as phase A's {@code -Dinspecto.topology=partitioned} boot failure (A1).</li>
 *   <li><b>Map present, a local Space unassigned → SKIP IT, loudly.</b> ⚠ Deliberately <em>not</em> a boot
 *       failure. Zero owners stalls one Space; refusing to boot takes down every other Space on this pod,
 *       so a ConfigMap that lags a new Space directory would turn a small mistake into a pod-wide outage.
 *       Skipping is the fail-closed choice here — the dangerous violation is <em>two</em> owners, never
 *       zero.</li>
 *   <li><b>Map names a Space this pod cannot see → IGNORE, silently.</b> Normal and expected: with per-pod
 *       volumes that Space's directory lives on its owner's volume.</li>
 * </ul>
 *
 * <h3>Format</h3>
 * <pre>
 * spaces:
 *   orders: 0
 *   events: 1
 * </pre>
 * Space id → pod ordinal. This pod's ordinal comes from {@code -Dinspecto.pod.ordinal}, else the trailing
 * {@code -N} of {@code HOSTNAME} (the Kubernetes StatefulSet convention, {@code inspecto-0}, {@code
 * inspecto-1}, …), so the usual deployment needs no extra flag.
 *
 * @since 5.x
 */
final class SpacePartition {

    private static final Logger log = LoggerFactory.getLogger(SpacePartition.class);

    /** The file, resolved beside the spaces root — the same place {@code scheduler.toon} lives. */
    static final String FILE = "partition.toon";
    /** This pod's ordinal, when the deployment states it outright rather than through {@code HOSTNAME}. */
    static final String ORDINAL_PROPERTY = "inspecto.pod.ordinal";

    /** Space id → owning pod ordinal; empty when there is no map (this pod then hosts everything). */
    private final Map<String, Integer> assignments;
    /** This pod's ordinal; {@code null} only when {@link #assignments} is empty (no map = none needed). */
    private final Integer ordinal;

    private SpacePartition(Map<String, Integer> assignments, Integer ordinal) {
        this.assignments = assignments;
        this.ordinal = ordinal;
    }

    /** The no-map default: host every Space this pod can see. */
    static SpacePartition hostEverything() {
        return new SpacePartition(Map.of(), null);
    }

    /**
     * Read {@code partition.toon} beside {@code spacesRoot}.
     *
     * @return {@link #hostEverything()} when the file is absent — the single-node default
     * @throws IllegalStateException when the file is present but unreadable, malformed, or this pod's
     *         ordinal cannot be determined. ⛔ Deliberately fatal: see the class note.
     */
    static SpacePartition load(Path spacesRoot) {
        Path file = spacesRoot.resolve(FILE);
        if (!Files.exists(file)) return hostEverything();

        Map<String, Integer> parsed = parse(file);
        Integer self = selfOrdinal();
        if (self == null)
            throw new IllegalStateException(FILE + " is present at " + file + ", but this pod's ordinal is "
                    + "unknown — set -D" + ORDINAL_PROPERTY + "=<n>, or run with a HOSTNAME ending in "
                    + "'-<n>' (the StatefulSet convention). Refusing to boot rather than guess which "
                    + "Spaces are this pod's: guessing wrong either idles the pod or double-hosts a Space.");

        log.info("SpacePartition: pod ordinal {} — {} of {} mapped space(s) are this pod's",
                self, parsed.values().stream().filter(o -> o.equals(self)).count(), parsed.size());
        return new SpacePartition(parsed, self);
    }

    /** True when no map applies, so every discovered Space is hosted (Personal / single-node Standard). */
    boolean hostsEverything() {
        return assignments.isEmpty();
    }

    /**
     * Whether this pod owns {@code spaceId}. An unmapped Space is <b>not</b> hosted — see the class note
     * on why that is a skip rather than a boot failure.
     */
    boolean hosts(String spaceId) {
        if (hostsEverything()) return true;
        return Objects.equals(assignments.get(spaceId), ordinal);
    }

    /** True when a map applies and {@code spaceId} appears nowhere in it — the "zero owners" case. */
    boolean isUnassigned(String spaceId) {
        return !hostsEverything() && !assignments.containsKey(spaceId);
    }

    private static Map<String, Integer> parse(Path file) {
        Map<String, Object> toon;
        try {
            toon = ToonHelper.load(file.toString());
        } catch (Exception e) {
            throw new IllegalStateException(FILE + " at " + file + " is present but unreadable: "
                    + e.getMessage() + ". ⛔ Refusing to boot — falling back to 'host everything' would "
                    + "make every pod host every Space, the exact thing this file prevents.", e);
        }
        Map<String, Object> section;
        try {
            section = ToonHelper.requireSection(toon, "spaces");
        } catch (RuntimeException e) {
            throw new IllegalStateException(FILE + " at " + file + " has no 'spaces:' section. Expected "
                    + "'spaces:' mapping each space id to its owning pod ordinal.", e);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        section.forEach((space, raw) -> {
            String text = raw == null ? "" : raw.toString().trim();
            try {
                out.put(space, Integer.valueOf(text));
            } catch (NumberFormatException e) {
                throw new IllegalStateException(FILE + " at " + file + ": space '" + space + "' maps to '"
                        + text + "', which is not a pod ordinal. Expected a non-negative integer.", e);
            }
        });
        if (out.isEmpty())
            throw new IllegalStateException(FILE + " at " + file + " has an EMPTY 'spaces:' section. ⛔ An "
                    + "empty map is refused rather than read as 'host everything': deleting the file says "
                    + "that deliberately, an empty section is far more likely to be a truncated render.");
        return out;
    }

    /**
     * This pod's ordinal: {@code -Dinspecto.pod.ordinal} first, else the trailing {@code -N} of
     * {@code HOSTNAME}. {@code null} when neither yields one.
     */
    private static Integer selfOrdinal() {
        String stated = System.getProperty(ORDINAL_PROPERTY, "").trim();
        if (!stated.isBlank()) {
            try {
                return Integer.valueOf(stated);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("-D" + ORDINAL_PROPERTY + "=" + stated
                        + " is not an integer.", e);
            }
        }
        return ordinalOfHostname(System.getenv("HOSTNAME"));
    }

    /** The trailing {@code -N} of a StatefulSet pod name ({@code inspecto-2} → 2); {@code null} if absent. */
    static Integer ordinalOfHostname(String hostname) {
        if (hostname == null) return null;
        int dash = hostname.lastIndexOf('-');
        if (dash < 0 || dash == hostname.length() - 1) return null;
        String tail = hostname.substring(dash + 1);
        try {
            int n = Integer.parseInt(tail);
            return n < 0 ? null : n;
        } catch (NumberFormatException e) {
            return null;   // a plain hostname like "laptop" is not a partitioned deployment
        }
    }
}
