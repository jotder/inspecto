package com.gamma.la.core;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The home of an Investigation's sidecar records (design: {@code docs/superpower/investigation-store-design.md}, row
 * {@code LA-INVESTIGATION-STORE-DESIGN-1}): header, sealed log, per-step Working Set sets, members, references, and the
 * workflow records beside them. Keyed by <b>ids and a {@link Scope}</b>, never a {@code Path}, so a backend that is not a
 * directory tree (Postgres, for multi-pod deployments) has something to implement. {@link FsInvestigationStore} is the
 * filesystem implementation and the only one today.
 *
 * <p><b>No lock is exposed.</b> A caller that reads, computes and then writes carries the version it read as a
 * <em>precondition</em> ({@code expectedVersion}); the store verifies it inside the write and answers
 * {@link InvestigationVersionConflictException} when another writer got there first, and the caller re-reads and retries.
 * What a backend uses to make "verify then write" atomic (a JVM monitor, a row lock, a unique key) is its own business.
 *
 * <p><b>Byte identity.</b> Every line and set is handed back exactly as it was written: no trimming, no re-encoding, no key
 * reordering. The sealed guarantees are defined over those bytes ({@code DraftStore.prefixHash} over the log lines each
 * followed by one {@code \n}; {@code DraftPromote.sealedSet} re-hashing a set's stored text), so an implementation must never
 * normalise (a Postgres one stores {@code text}, never {@code jsonb}).
 *
 * <p>Every method may throw {@link IOException}: an unavailable backend fails closed - the caller answers 503, it never falls
 * back to another backend, because a silent fallback on one node would fork the evidence.
 *
 * <p>⚠ MIGRATION STATE (S1): this port covers the Investigation's own records and the main-or-Draft <em>log</em>. Draft
 * lifecycle (create, close, promote, rebase swap, index, sweeps) is still the Path-keyed {@code Draft*} statics; see the
 * remaining-call-site checklist in the design doc.
 */
public interface InvestigationStore {

    /**
     * Which log an operation addresses: an Investigation's main log ({@code draftId == null}) or one of its Drafts' own
     * logs. A Draft's own log continues the main numbering: its first step is {@code baseStep + 1}.
     */
    record Scope(String investigationId, String draftId) {
        public static Scope main(String investigationId) { return new Scope(investigationId, null); }

        public static Scope draft(String investigationId, String draftId) { return new Scope(investigationId, draftId); }

        public boolean isDraft() { return draftId != null; }
    }

    /** Thrown by {@link #append} on a Draft that was discarded or promoted between the caller's gate and the write. */
    final class DraftClosedException extends RuntimeException {
        public DraftClosedException(String draftId) {
            super("draft '" + draftId + "' was closed (discarded or promoted)");
        }
    }

    /** Outcomes of {@link #appendReference}. */
    enum Appended { ADDED, DUPLICATE, FULL }

    // ── identity and the immutable record ───────────────────────────────────────────────────────────────

    /** Create an Investigation's header. False when the id already exists, never an overwrite (409). */
    boolean create(String id, String headerJson) throws IOException;

    /** The header's raw JSON, or empty when the Investigation was never created. */
    Optional<String> header(String id) throws IOException;

    /** Every Investigation id with a header, in natural (code-point) order. */
    List<String> ids() throws IOException;

    /**
     * Create an Investigation together with its whole log and sets, all or nothing: a failure leaves nothing under the id.
     * {@code sets.get(i)} is the set of step {@code i + 1}. False when the id is already taken.
     */
    boolean createFork(String id, String headerJson, List<String> lines, List<String> sets) throws IOException;

    // ── the sealed log (append-only) ────────────────────────────────────────────────────────────────────

    /**
     * The scope's log version: the number of entries its OWN log holds (the whole log for the main scope; only the Draft's
     * own entries for a Draft scope). Cheap, and the value a writer passes back as {@code expectedVersion}.
     */
    long version(Scope scope) throws IOException;

    /** The scope's OWN log lines, verbatim, in step order, no blank lines (empty for a fresh log). */
    List<String> log(Scope scope) throws IOException;

    /** One step's sealed Working Set document of the MAIN log, verbatim, or empty when it was never written. */
    Optional<String> set(String investigationId, int step) throws IOException;

    /**
     * Append one step and seal its Working Set document ({@code step} names the set; for the main scope it is
     * {@code expectedVersion + 1}, for a Draft it continues the main numbering). Verifies, atomically with the write, that the
     * scope's log still holds exactly {@code expectedVersion} entries; otherwise nothing is written and
     * {@link InvestigationVersionConflictException} is thrown. A closed Draft throws {@link DraftClosedException}.
     * No set is ever stored without its step; a failed Draft append leaves the Draft as it was.
     */
    void append(Scope scope, long expectedVersion, int step, String lineJson, String setJson) throws IOException;

    // ── members and references (append-only) ────────────────────────────────────────────────────────────

    /** The Investigation's membership lines (grants and revokes), verbatim, in order; empty when it never had any. */
    List<String> members(String investigationId) throws IOException;

    /**
     * Append one membership line, verifying atomically that exactly {@code expectedCount} lines exist (the read the caller
     * folded its last-lead check over); otherwise {@link InvestigationVersionConflictException}.
     */
    void appendMember(String investigationId, long expectedCount, String lineJson) throws IOException;

    /**
     * Append one reference line unless its {@code key} is already present (DUPLICATE) or {@code maxCount} lines exist (FULL);
     * the check and the append are one atomic act. The line must start {@code {"key":<key>,}}.
     */
    Appended appendReference(String investigationId, String key, String lineJson, int maxCount) throws IOException;

    /** The Investigation's reference lines, verbatim, in the order added. */
    List<String> references(String investigationId) throws IOException;

    // ── workflow records (rewritten whole; last writer wins, as today) ──────────────────────────────────

    /** Record (or replace) the binding of one Alert Rule to one Investigation. */
    void bindAlertRule(String investigationId, String rule, String json) throws IOException;

    Optional<String> alertRuleBinding(String investigationId, String rule) throws IOException;

    /** Write (or rewrite) one pending-expand record. */
    void writePending(String investigationId, String requestId, String json) throws IOException;

    Optional<String> pending(String investigationId, String requestId) throws IOException;

    /** Every pending-expand record of one Investigation, raw, in request-id order. */
    List<String> listPending(String investigationId) throws IOException;

    /** Write (or replace) the Investigation's Case link. */
    void writeCaseLink(String investigationId, String json) throws IOException;

    Optional<String> caseLink(String investigationId) throws IOException;

    /** Remove the Investigation's Case link; false when it had none. */
    boolean deleteCaseLink(String investigationId) throws IOException;

    /** Save one Investigation Template. False when the id is already taken, never an overwrite (409). */
    boolean createTemplate(String id, String json) throws IOException;

    Optional<String> template(String id) throws IOException;
}
