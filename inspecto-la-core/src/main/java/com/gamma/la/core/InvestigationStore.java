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

    /** Every saved Investigation Template, raw, in id order (LD-5: the list route filters to the caller's own). */
    List<String> templates() throws IOException;

    /**
     * Compare-and-set of one pending-expand record: replace it with {@code newJson} only if its stored text is still exactly
     * {@code expectedJson} (the read the caller decided over). False, and nothing written, when another decider got there first
     * or the record is absent. This is what makes a four-eyes decision happen once: the status moves BEFORE the append it
     * authorises, so a retried or racing decide finds it already moved.
     */
    boolean replacePending(String investigationId, String requestId, String expectedJson, String newJson) throws IOException;

    /**
     * The Investigation's pseudonym key (D-U6 entity masking): 32 random bytes minted on first use and then the same for every caller,
     * even two racing for the first one. A SECRET: never served, and a backend must protect it like any credential.
     */
    byte[] maskKey(String investigationId) throws IOException;

    // ── per-pod cache identity ─────────────────────────────────────────────────────────────────────────

    /** A stable key naming the scope in per-process caches (checkpoints, cached Working Set relations). Opaque to the caller. */
    String cacheKey(Scope scope);

    /** An opaque token that changes whenever the scope's log does (an append, a rebase swap, a close): a cache valid at one token is stale at another. */
    String logToken(Scope scope) throws IOException;

    // ── Drafts ─────────────────────────────────────────────────────────────────────────────────────────

    /** The lifecycle state of a Draft; precedence promoted, discarded (an expiry is a discard), hibernated, open. */
    enum DraftState {
        OPEN, HIBERNATED, DISCARDED, PROMOTED;

        public String wire() { return name().toLowerCase(java.util.Locale.ROOT); }

        public boolean closed() { return this == DISCARDED || this == PROMOTED; }
    }

    /** What {@link #createDraft} decided. {@code detail} is the live Draft's id for ACTOR_HAS_LIVE and the open count for SPACE_FULL. */
    record DraftCreation(Created outcome, String detail) {
        public enum Created { CREATED, ID_TAKEN, ACTOR_HAS_LIVE, SPACE_FULL }
    }

    /** Create a Draft unless the actor already has a live one on this Investigation (D17) or the Space holds {@code spaceCap} open Drafts (D21); the checks and the create are one act. */
    DraftCreation createDraft(String investigationId, String draftId, String headerJson, String actor, int spaceCap) throws IOException;

    Optional<String> draftHeader(String investigationId, String draftId) throws IOException;

    /** Every Draft's compact header (everything but {@code baseLogHash} and {@code rebases[]}), by draft id. A rebuildable listing, never a record. */
    java.util.Map<String, java.util.Map<String, Object>> draftHeaders(String investigationId) throws IOException;

    /** Ids of this Investigation's Drafts that are not closed. */
    List<String> openDraftIds(String investigationId);

    /** Open Drafts across the whole Space (the D21 cap's count). */
    int openDraftCount();

    DraftState draftState(String investigationId, String draftId);

    /** True when the Draft's discard marker was written by the idle expiry, not by a person. */
    boolean draftExpired(String investigationId, String draftId);

    Optional<String> discardMarker(String investigationId, String draftId) throws IOException;

    Optional<String> promoteMarker(String investigationId, String draftId) throws IOException;

    /** One step's sealed set of a Draft's OWN log, verbatim, or empty. */
    Optional<String> draftSet(String investigationId, String draftId, int step) throws IOException;

    /** Record an authorised use of the Draft (keeps it from idling out). Best effort: never fails the read it rides on. */
    void touchDraft(String investigationId, String draftId);

    /** Wake a hibernated Draft; true when it WAS hibernated (the next read is then a cold fold). */
    boolean rehydrateDraft(String investigationId, String draftId) throws IOException;

    java.time.Instant draftLastAccess(String investigationId, String draftId);

    java.time.Duration draftIdle(String investigationId, String draftId);

    /** Hibernate the Draft if it is open and idle for at least {@code after}; true when this call hibernated it. */
    boolean hibernateDraft(String investigationId, String draftId, java.time.Duration after) throws IOException;

    /**
     * Close a Draft (discard / expiry): the marker first, then the evidence is deleted, one act, idempotent. {@code marker}
     * builds the marker text from the Draft's own log lines (a pure function, called while the Draft cannot change).
     * {@code idleAtLeast}, when set, makes it conditional on the Draft still being open and idle that long (the expiry sweep).
     * Empty = not applicable (conditional and no longer true, or the Draft is closed); true = this call closed it; false = it was already discarded.
     */
    Optional<Boolean> closeDraft(String investigationId, String draftId, java.time.Duration idleAtLeast,
                                 java.util.function.Function<List<String>, String> marker) throws IOException;

    /**
     * Promote a Draft: append {@code lines} (steps {@code expectedMainVersion + 1 ...}) and their sets to the MAIN log and close the
     * Draft as promoted ({@code markerJson}), all or nothing. {@code sets.get(i) == null} means "seal the Draft's own set of that
     * step as it is" (a hard link on the filesystem: LA-DRAFT-PROMOTE-COST-1). The caller computed everything OUTSIDE; the store
     * verifies, atomically with the write, that the main log still holds exactly {@code expectedMainVersion} entries hashing to
     * {@code expectedMainHash} and that the Draft's own log still hashes to {@code expectedDraftLogHash} (both
     * {@link DraftStore#prefixHash}); otherwise {@link InvestigationVersionConflictException}. A closed Draft throws
     * {@link DraftClosedException}. A failure part-way leaves the main log as it was.
     */
    void promoteDraft(String investigationId, String draftId, long expectedMainVersion, String expectedMainHash,
                      String expectedDraftLogHash, List<String> lines, List<String> sets, String markerJson) throws IOException;

    /**
     * Swap a rebased Draft in (header, log, sets) under the same preconditions style: the main log must still hold
     * {@code expectedMainVersion} entries hashing to {@code expectedMainHash}, and the Draft's own log must still hash to
     * {@code expectedDraftLogHash}; otherwise {@link InvestigationVersionConflictException}. A failed swap leaves the Draft as it was.
     */
    void replaceDraft(String investigationId, String draftId, long expectedMainVersion, String expectedMainHash,
                      String expectedDraftLogHash, String headerJson, List<String> lines, List<String> sets, List<Integer> setSteps)
            throws IOException;

    /**
     * Housekeeping that must run before Drafts are listed or opened: remove scratch left by a crashed fork or rebase, and finish or
     * undo a promote a crash interrupted (complete it when every main step landed, otherwise put the main log back). Idempotent.
     */
    void recoverDrafts(String investigationId) throws IOException;
}
