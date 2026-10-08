package com.gamma.control;

import com.gamma.decision.ConsequenceContext;
import com.gamma.spi.http.ApiContext;

/**
 * The host's {@link ConsequenceContext}: a consequence contributed by an optional module that needs the request's
 * Space (its write root, its linked-subject providers) reaches it through {@link #api()}. {@code ApiContext} never
 * crosses the engine SPI itself, so a provider tests {@code ctx instanceof HostConsequenceContext} and answers
 * {@code unavailable} when it is not (a test double, a non-host caller).
 */
public interface HostConsequenceContext extends ConsequenceContext {

    /** The route context of the Space the rule is applied in. */
    ApiContext api();
}
