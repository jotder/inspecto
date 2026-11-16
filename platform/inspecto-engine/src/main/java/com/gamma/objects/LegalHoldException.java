package com.gamma.objects;

import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.audit.EventType;

/**
 * A delete or expiry refused because the evidence it would remove is under <b>legal hold</b>
 * (LEGAL-HOLD-SCOPE-1). The message names the hold, so the edge can hand it to the caller unchanged; every route
 * maps it to {@code 409 CONFLICT}.
 *
 * <p>It extends {@link IllegalStateException} so a caller that already treats a refused state change as "not now"
 * keeps doing so, but a route must catch it <b>before</b> a generic {@code IllegalStateException} arm, or a hold
 * reads as a 422 validation failure.
 *
 * <p>Build it with {@link #audited}: a refusal under hold is itself audit evidence (someone tried to remove held
 * records), so it is recorded before it is thrown.
 */
public final class LegalHoldException extends IllegalStateException {

    /** The audit action every legal-hold refusal is recorded under. */
    public static final String AUDIT_ACTION = "legal_hold.refused";

    private LegalHoldException(String message) {
        super(message);
    }

    /**
     * Record the refusal as an {@code AUDIT} event ({@link #AUDIT_ACTION}, category {@code retention}) and return the
     * exception to throw. {@code what} is the refused act ("delete link", "purge Space"), {@code reason} the hold
     * ({@link ObjectAccess#legalHold}). Auditing is best effort: a failing sink never turns the refusal into a pass.
     */
    public static LegalHoldException audited(String what, String reason, String targetKind, String targetId, String actor) {
        String message = what + " refused: " + reason + " — release the legal hold first";
        try {
            EventSink.current().emit(Event.builder(EventType.AUDIT)
                    .source("legal-hold").message(message)
                    .actor(actor == null ? "system" : actor).actorType(actor == null ? "system" : "user")
                    .action(AUDIT_ACTION).actionCategory("retention")
                    .target(targetKind, targetId)
                    .attr("refused", what).attr("hold", reason));
        } catch (RuntimeException ignored) {
            // best effort: the refusal stands whether or not it could be recorded
        }
        return new LegalHoldException(message);
    }
}
