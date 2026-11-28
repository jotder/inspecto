package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
import com.sun.net.httpserver.HttpExchange;

import java.util.List;

/**
 * D-CO1 (`SPACE-COHERENCE-1` §7, "agents strict now, in every Space") for the save checks that carry their own split
 * until SC-2 makes it general: a finding the check marks refusable is refused for an agent Subject, naming what to fix,
 * so the agent can retry correctly; a person keeps it as a warning on the saved response (until D-LC3). This class is
 * that rule and nothing else, so SC-2 absorbs one place. ⚠ Not {@link LinkRefusals}: its modes and the D-LC3 flip are
 * untouched. Used by the Job save checks of `SIGNAL-BOARD-1` SB-2 ({@code JobRoutes}).
 */
final class AgentStrict {

    private AgentStrict() {}

    /** Whether an allowlisted agent client acts for this request (AK-2a). Basic, with no Subject, is a person. */
    static boolean isAgent(HttpExchange ex) {
        return ApiContext.attr(ex, ApiContext.ATTR_SUBJECT) instanceof Subject who && who.agent();
    }

    /**
     * Refuse the save for an agent when {@code refusable} is not empty: 422 {@code CONFIG_VALIDATION_FAILED}, the
     * first finding's code and key in the envelope, every problem and its fix in the message. A no-op for a person.
     *
     * @param what       the thing saved, e.g. {@code job 'nightly'}
     * @param refusable  each refusable finding as {code, key, message, fix}
     */
    static void refuseForAgent(HttpExchange ex, String what, List<Problem> refusable) {
        if (refusable.isEmpty() || !isAgent(ex)) return;
        StringBuilder msg = new StringBuilder(what).append(" not written (an agent save is refused on these):");
        for (Problem p : refusable) msg.append(" ").append(p.message()).append(" — fix: ").append(p.fix()).append(";");
        Problem first = refusable.getFirst();
        throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, msg.substring(0, msg.length() - 1),
                first.code(), first.key());
    }

    /** One refusable problem. */
    record Problem(String code, String key, String message, String fix) {}
}
