package com.gamma.ops;

import java.util.Locale;
import static com.gamma.util.Values.trimToNull;

/**
 * A filter + page over the {@link ObjectStore} — the query model behind {@code GET /objects}. Every
 * field is optional except the paging bounds; {@code null} means "no constraint on this dimension".
 * Mirrors {@link com.gamma.event.EventQuery}: the same instance drives both {@link #matches} (the
 * in-memory store) and SQL {@code WHERE} generation ({@code DbObjectStore}), so both backends return
 * the same rows.
 *
 * @param objectType    exact {@link ObjectType}, or {@code null}
 * @param status        exact {@link OperationalObject#status()} (case-insensitive), or {@code null}
 * @param severity      exact {@link OperationalObject#severity()} (case-insensitive), or {@code null}
 * @param assignee      exact {@link OperationalObject#assignee()} (case-insensitive), or {@code null}
 * @param owner         exact {@link OperationalObject#owner()} (case-insensitive), or {@code null}
 * @param correlationId exact {@link OperationalObject#correlationId()}, or {@code null}
 * @param textContains  case-insensitive substring of {@code title} or {@code description}, or {@code null}
 * @param limit         maximum rows to return (clamped to {@code [1, }{@value #MAX_LIMIT}{@code ]})
 * @param offset        rows to skip from the newest (paging; clamped to {@code >= 0})
 * @param closedBefore  when {@code > 0}, keep only objects already closed <b>before</b> this epoch-millis
 *                      instant — i.e. {@code 0 < closedAt < closedBefore}. {@code 0} means no constraint.
 *                      ⚠ The {@code closedAt > 0} half is load-bearing: {@code closedAt == 0} means "not
 *                      closed", so a reopened object must never satisfy a cutoff (MNT-14 G1).
 * @param oldestFirst   {@code true} to order oldest-first by {@code createdAt} instead of the default
 *                      newest-first
 * @since 4.3.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public record ObjectQuery(ObjectType objectType, String status, String severity, String assignee,
                          String owner, String correlationId, String textContains,
                          int limit, int offset, long closedBefore, boolean oldestFirst) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 10_000;

    /** Clamp paging bounds into range. */
    public ObjectQuery {
        limit = Math.max(1, Math.min(MAX_LIMIT, limit == 0 ? DEFAULT_LIMIT : limit));
        offset = Math.max(0, offset);
        closedBefore = Math.max(0, closedBefore);
    }

    /** The pre-MNT-14 shape: no retention cutoff, newest-first. */
    public ObjectQuery(ObjectType objectType, String status, String severity, String assignee,
                       String owner, String correlationId, String textContains, int limit, int offset) {
        this(objectType, status, severity, assignee, owner, correlationId, textContains, limit, offset,
                0L, false);
    }

    /** An unfiltered query returning the newest {@code limit} objects. */
    public static ObjectQuery recent(int limit) {
        return new Builder().limit(limit).build();
    }

    /** This query's filters with the paging bounds widened to all matches ({@link #MAX_LIMIT}, offset 0) —
     *  the ordered source set a caller slices itself (keyset pagination, scope-filtered analytics). */
    public ObjectQuery unbounded() {
        return new ObjectQuery(objectType, status, severity, assignee, owner, correlationId, textContains,
                MAX_LIMIT, 0, closedBefore, oldestFirst);
    }

    /**
     * The purge-eligibility selection behind the MNT-14 retention sweep: the oldest {@code limit} objects
     * of {@code type} sitting in {@code status} whose retention window closed before {@code cutoff}.
     *
     * <p>Use this rather than re-deriving the predicate — the {@code closedAt > 0} guard and the
     * oldest-first ordering are both easy to get wrong, and the failure is silent. Note that
     * <b>correctness comes from the cutoff, not the ordering</b>: because the cutoff is part of the
     * {@code WHERE}, every returned row is eligible whichever end of the corpus it came from, so a sweep
     * that reads a single page can no longer report "0 prunable" against a fully-expired corpus. The
     * ordering only decides <i>which</i> eligible objects a capped sweep takes first.
     *
     * <p>⚠ Eligibility here is age-only. A legal hold lives in the attribute bag, not in SQL, so the
     * caller must still filter with {@link ObjectService#hasLegalHold}.
     */
    public static ObjectQuery purgeEligible(ObjectType type, String status, long cutoff, int limit) {
        return new Builder().objectType(type).status(status).closedBefore(cutoff).oldestFirst(true)
                .limit(limit).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@code true} when {@code o} satisfies every set constraint of this query. */
    public boolean matches(OperationalObject o) {
        if (objectType != null && o.objectType() != objectType) return false;
        if (status != null && !status.equalsIgnoreCase(o.status())) return false;
        if (severity != null && !severity.equalsIgnoreCase(o.severity())) return false;
        if (assignee != null && !assignee.equalsIgnoreCase(o.assignee())) return false;
        if (owner != null && !owner.equalsIgnoreCase(o.owner())) return false;
        if (correlationId != null && !correlationId.equals(o.correlationId())) return false;
        // closedAt == 0 means "not closed", so a reopened object can never satisfy a retention cutoff.
        if (closedBefore > 0 && !(o.closedAt() > 0 && o.closedAt() < closedBefore)) return false;
        if (textContains != null && !textContains.isBlank()) {
            String needle = textContains.toLowerCase(Locale.ROOT);
            boolean inTitle = o.title() != null && o.title().toLowerCase(Locale.ROOT).contains(needle);
            boolean inDesc = o.description() != null && o.description().toLowerCase(Locale.ROOT).contains(needle);
            if (!inTitle && !inDesc) return false;
        }
        return true;
    }

    /** Fluent builder; all filters default to "no constraint". */
    public static final class Builder {
        private ObjectType objectType;
        private String status, severity, assignee, owner, correlationId, textContains;
        private int limit = DEFAULT_LIMIT;
        private int offset = 0;
        private long closedBefore = 0L;
        private boolean oldestFirst = false;

        public Builder objectType(ObjectType t) { this.objectType = t; return this; }
        public Builder status(String s) { this.status = trimToNull(s); return this; }
        public Builder severity(String s) { this.severity = trimToNull(s); return this; }
        public Builder assignee(String s) { this.assignee = trimToNull(s); return this; }
        public Builder owner(String s) { this.owner = trimToNull(s); return this; }
        public Builder correlationId(String c) { this.correlationId = trimToNull(c); return this; }
        public Builder textContains(String q) { this.textContains = trimToNull(q); return this; }
        public Builder limit(int n) { this.limit = n; return this; }
        public Builder offset(int n) { this.offset = n; return this; }
        public Builder closedBefore(long epochMillis) { this.closedBefore = epochMillis; return this; }
        public Builder oldestFirst(boolean b) { this.oldestFirst = b; return this; }

        public ObjectQuery build() {
            return new ObjectQuery(objectType, status, severity, assignee, owner, correlationId,
                    textContains, limit, offset, closedBefore, oldestFirst);
        }
    }
}
