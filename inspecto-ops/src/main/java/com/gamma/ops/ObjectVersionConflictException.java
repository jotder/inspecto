package com.gamma.ops;

/**
 * An {@link ObjectStore#update} lost an optimistic-lock race: the stored object is no longer at the version the
 * caller read it at, so another writer got there first. Nothing was written. Recoverable — re-read the object,
 * re-apply the change, retry ({@link ObjectService} does this for changes that are a pure function of the fresh
 * read; the HTTP edge answers any that remain as {@code 409 CONFLICT_STALE_VERSION}).
 *
 * <p>Deliberately not an {@link IllegalStateException}: routes map that to 422 "illegal move", and a lost race
 * is neither the caller's input nor a store fault.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public final class ObjectVersionConflictException extends RuntimeException {

    private final String objectId;
    private final long expectedVersion;

    public ObjectVersionConflictException(String objectId, long expectedVersion) {
        super("object '" + objectId + "' changed since it was read (expected version " + expectedVersion
                + "); re-read and retry");
        this.objectId = objectId;
        this.expectedVersion = expectedVersion;
    }

    public String objectId() { return objectId; }

    public long expectedVersion() { return expectedVersion; }
}
