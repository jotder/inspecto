package com.gamma.la.core;

/**
 * An {@link InvestigationStore} write lost an optimistic-lock race: the log (or member list) it was computed against is no
 * longer at the version the caller read it at, so another writer got there first. Nothing was written. Recoverable - re-read,
 * re-compute, retry; the HTTP edge answers any that remain as {@code 409 CONFLICT_STALE_VERSION}.
 *
 * <p>The same convention as {@code inspecto-ops}' {@code ObjectVersionConflictException} (a monotonic {@code long version},
 * a typed exception, a 409 at the edge), named the same way. Like it, deliberately NOT an {@link IllegalStateException}:
 * routes map that to 422 "illegal move", and a lost race is neither the caller's input nor a store fault. {@code la-core}
 * cannot depend on {@code inspecto-ops}, so the type is this module's own.
 *
 * <p>For a log, the version is its entry count: dense, 1-based and monotonic, so "the log is at version n" and "the head is
 * step n" are the same statement.
 */
public final class InvestigationVersionConflictException extends RuntimeException {

    private final String investigationId;
    private final long expectedVersion;
    private final long actualVersion;

    public InvestigationVersionConflictException(String investigationId, long expectedVersion, long actualVersion) {
        super("investigation '" + investigationId + "' changed since it was read (expected version " + expectedVersion
                + ", found " + actualVersion + "); re-read and retry");
        this.investigationId = investigationId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    public String investigationId() { return investigationId; }

    public long expectedVersion() { return expectedVersion; }

    public long actualVersion() { return actualVersion; }
}
