package com.gamma.control;

import com.gamma.spi.http.ApiContext;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceManager;

/**
 * The host-side half of the route context: the three services only the Inspecto host can provide. {@link ApiContext}
 * (the SPI, in {@code inspecto-http-spi}) names none of them, so a route module that does not need the host — Link
 * Analysis's own routes — compiles without {@code inspecto-processor}. {@link ControlApi} implements this, and a
 * module that does need the host (Cases, Alert Rules, Spaces) obtains it with {@link #of}.
 */
@com.gamma.api.PublicApi(since = "4.0.0")
public interface HostContext extends ApiContext {

    /** The running service host the routes act on (the request's bound space, per the {@code /spaces/{id}} seam). */
    CollectorService service();

    /** The live SSE streams this host ends on close (see {@link SseStreams}). */
    SseStreams sseStreams();

    /** The container of all hosted spaces — for the server-global {@code SpaceRoutes} CRUD group, and the
     *  {@code canAdminister}-gated {@code SpaceComparisonRoutes} (which resolves Spaces only past that gate). */
    SpaceManager spaces();

    /** The host view of {@code api}. Fails loudly — never a null — when the context is not a host one (a test double). */
    static HostContext of(ApiContext api) {
        if (api instanceof HostContext host) return host;
        throw new IllegalStateException("this route needs the Inspecto host services, but the ApiContext ("
                + (api == null ? "null" : api.getClass().getName()) + ") is not a HostContext");
    }
}
