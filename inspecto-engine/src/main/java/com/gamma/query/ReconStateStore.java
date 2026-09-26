package com.gamma.query;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gamma.config.safety.PathJail;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Reconciliation's <b>operational state</b> — its last run and its Break lifecycle — kept OUT of the
 * authored config (R2-03, operator 2026-09-26, reversing C9). One JSON document per Reconciliation at
 * {@code <write-root>/recon-state/<reconciliationId>.json}:
 * <pre>
 *   { "reconciliation": "&lt;id&gt;", "lastRunAt": "&lt;ISO instant&gt;" | null, "runs": n,
 *     "breaks": [ { pair, key, keyValues?, type, column?, leftValue?, rightValue?, diff?, status, note?, firstSeenAt?,
 *                   lastSeenAt?, occurrences, recurrences, assignee? } ] }
 * </pre>
 * {@code status} is {@code open | assigned | resolved | auto_closed}. A document written before
 * {@code ASSURE-BREAK-LIFECYCLE-1} (no counters, no assignee) reads with defaults — see
 * {@link ReconBreaks.Break#fromMap}. {@code ageDays} is never stored: {@link State#toWire} derives it at read time.
 *
 * <p>🔴 <b>Why it left the config.</b> The lifecycle used to be merged in the browser and written back
 * through the whole-body {@code PUT /components/reconciliation/{id}}, which is gated {@code canAuthorWorkbench}
 * — so a user holding only the {@code operations} role (who runs reconciliations) got a 403 and no run was
 * ever recorded. Operational state is written by the operate routes ({@code canOperateRuns}) and the scheduled
 * {@code recon.run} Job; the authoring PUT never sees it. Same split as an Expectation's {@code lastResult}
 * and {@code expectation/BaselineProfileStore}, which this class mirrors.
 *
 * <p>Fail closed: no in-memory fallback, and an unreadable document is an {@link IOException}, never "no
 * state" — reading a corrupt file as empty would auto-close nothing and silently restart every Break's age.
 * The id must be a bare name ({@link IllegalArgumentException} otherwise) and the file is jailed under the
 * write root with {@link PathJail#contains} (a {@link SecurityException} on an escape, e.g. a symlinked
 * {@code recon-state/}). Writes are temp-file +
 * atomic move ({@link AtomicFiles}); read-modify-write is serialised on one lock.
 */
public final class ReconStateStore {

    /** The most Breaks one Reconciliation's state may hold — a run with more is refused, never truncated. */
    public static final int MAX_BREAKS = 50_000;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object LOCK = new Object();

    private final Path writeRoot;
    private final Path dir;

    public ReconStateStore(Path writeRoot) {
        this.writeRoot = writeRoot.toAbsolutePath().normalize();
        this.dir = this.writeRoot.resolve("recon-state");
    }

    /** One Reconciliation's recorded state; {@code lastRunAt} null and no Breaks when it was never run. */
    public record State(String reconciliation, String lastRunAt, long runs, List<ReconBreaks.Break> breaks) {

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reconciliation", reconciliation);
            m.put("lastRunAt", lastRunAt);
            m.put("runs", runs);
            List<Map<String, Object>> list = new ArrayList<>(breaks.size());
            for (ReconBreaks.Break b : breaks) list.add(b.toMap());
            m.put("breaks", list);
            return m;
        }

        /** {@link #toMap} with every Break's read-time {@code ageDays} ({@link ReconBreaks.Break#toWire}) — the read routes' shape. */
        public Map<String, Object> toWire(java.time.Instant now) {
            Map<String, Object> m = toMap();
            List<Map<String, Object>> list = new ArrayList<>(breaks.size());
            for (ReconBreaks.Break b : breaks) list.add(b.toWire(now));
            m.put("breaks", list);
            return m;
        }
    }

    /** A run instant as the SPA writes one ({@code toISOString}'s millisecond precision), for {@link #record}. */
    public static String now() {
        return java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    /** The recorded state of {@code reconciliationId} (an empty state when nothing was recorded). */
    public State read(String reconciliationId) throws IOException {
        synchronized (LOCK) {
            return load(reconciliationId);
        }
    }

    /**
     * Record a run: merge {@code fresh} into the stored Breaks ({@link ReconBreaks#merge}), stamp
     * {@code lastRunAt = runAt}, count the run, write atomically. Returns the new state.
     */
    public State record(String reconciliationId, List<ReconBreaks.Break> fresh, String runAt) throws IOException {
        synchronized (LOCK) {
            State prev = load(reconciliationId);
            State next = new State(reconciliationId, runAt, prev.runs() + 1,
                    ReconBreaks.merge(prev.breaks(), fresh, runAt));
            save(next);
            return next;
        }
    }

    /**
     * Resolve ({@code status = resolved}), re-open ({@code open}) or assign ({@code assigned}, to
     * {@code assignee}) one Break by identity, replacing its note ({@code null} clears it). {@code pair}
     * ({@link ReconBreaks#PAIR_AB} / {@link ReconBreaks#PAIR_AC}) is part of the identity — resolving an A↔C
     * Break leaves the same-key A↔B one as it was. The assignee: {@code assigned} sets it, {@code resolved}
     * keeps the one on record (who owned it), {@code open} clears it. A Break no run has recorded yet is
     * appended identity-only — the Breaks page can act on a live Break before the Board records one. Returns
     * the updated Break. Occurrences, sightings and recurrences are the runs' to change, never this.
     *
     * @throws IllegalArgumentException the state already holds {@link #MAX_BREAKS} and this would append
     */
    public ReconBreaks.Break setStatus(String reconciliationId, String pair, String type, String key, String column,
                                       String status, String note, String assignee) throws IOException {
        synchronized (LOCK) {
            State prev = load(reconciliationId);
            String id = ReconBreaks.lifecycleId(pair, type, key, column);
            List<ReconBreaks.Break> breaks = new ArrayList<>(prev.breaks().size() + 1);
            ReconBreaks.Break updated = null;
            for (ReconBreaks.Break b : prev.breaks()) {
                if (b.id().equals(id)) {
                    b = b.withStatus(status, note).withAssignee(assigneeAfter(status, b.assignee(), assignee));
                    updated = b;
                }
                breaks.add(b);
            }
            if (updated == null) {
                if (breaks.size() >= MAX_BREAKS)
                    throw new IllegalArgumentException("reconciliation '" + reconciliationId + "' already records "
                            + MAX_BREAKS + " Breaks — record a run before changing a Break it has not seen");
                updated = ReconBreaks.Break.identityOnly(pair, type, key, column, status, note,
                        assigneeAfter(status, null, assignee));
                breaks.add(updated);
            }
            save(new State(reconciliationId, prev.lastRunAt(), prev.runs(), breaks));
            return updated;
        }
    }

    /** Drop the state — its Reconciliation was deleted, and a re-created one of the same id must not inherit it. */
    public void delete(String reconciliationId) throws IOException {
        synchronized (LOCK) {
            Files.deleteIfExists(file(reconciliationId));
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** {@link #setStatus}'s assignee rule: assign sets it, resolve keeps it, re-open clears it. */
    private static String assigneeAfter(String status, String recorded, String requested) {
        if (ReconBreaks.ASSIGNED.equals(status)) return requested;
        return ReconBreaks.RESOLVED.equals(status) ? recorded : null;
    }

    @SuppressWarnings("unchecked")
    private State load(String reconciliationId) throws IOException {
        Path f = file(reconciliationId);
        if (!Files.exists(f)) return new State(reconciliationId, null, 0, List.of());
        try {
            Map<String, Object> doc = JSON.readValue(f.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
            List<ReconBreaks.Break> breaks = new ArrayList<>();
            if (doc.get("breaks") instanceof List<?> list)
                for (Object o : list) breaks.add(ReconBreaks.Break.fromMap((Map<String, Object>) o));
            Object last = doc.get("lastRunAt");
            long runs = doc.get("runs") instanceof Number n ? n.longValue() : 0;
            return new State(reconciliationId, last == null ? null : last.toString(), runs, List.copyOf(breaks));
        } catch (IOException | RuntimeException corrupt) {
            throw new IOException("recorded state of reconciliation '" + reconciliationId + "' is unreadable ("
                    + corrupt.getMessage() + ") — refusing to treat it as a reconciliation that never ran", corrupt);
        }
    }

    private void save(State state) throws IOException {
        AtomicFiles.write(file(state.reconciliation()), JSON.writeValueAsBytes(state.toMap()), ".recon-state-");
    }

    /** The state file of {@code reconciliationId}: a bare name, jailed under the write root. */
    private Path file(String reconciliationId) {
        String id = reconciliationId == null ? "" : reconciliationId.trim();
        if (id.isEmpty() || id.contains("..") || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new IllegalArgumentException("unsafe reconciliation id '" + reconciliationId + "'");
        Path f = dir.resolve(id + ".json");
        // Against the WRITE ROOT, not recon-state/: a recon-state directory that is itself a symlink out of the
        // root must be caught, and PathJail compares real paths, so jailing to that directory would not.
        if (!PathJail.contains(writeRoot, f))
            throw new SecurityException("reconciliation state for '" + id + "' escapes the write root");
        return f;
    }
}
