package com.gamma.job;

import com.gamma.api.PublicApi;

import java.util.Optional;
import java.util.Set;

/**
 * The typed lookup through which a Job — and later a ConsignmentProcessor subset and a stage-2
 * executable Step — reaches the engine facilities granted to it (platform-services plan §3). This is
 * the data-plane façade the P0 job-framework design named {@code JobServices} and never built.
 *
 * <p>Deliberately flat: no scopes, no lifecycle, no proxies, no annotation-driven injection — a
 * lookup built once at boot and <em>filtered per grant</em> when handed to a consumer (plan R3).
 * Grants are honest because undeclared lookups fail: a service that exists in the registry but was
 * not declared in the consumer's {@code requires:} list is invisible here (plan R4).
 *
 * <p>Absence is modeled, not crashed on (§3.5): {@link #find} is empty both when the service was not
 * granted and when it is not available in this build flavor. Optional use = {@code find()},
 * mandatory use = a {@code requires:} declaration + {@code get()}.
 */
@PublicApi(since = "5.1.0")
public interface PlatformServices {

    /** The granted service, or empty — absent when not granted OR not available in this build. */
    <T> Optional<T> find(Class<T> type);

    /** The granted service; throws {@link IllegalStateException} naming the missing grant. */
    default <T> T get(Class<T> type) {
        return find(type).orElseThrow(() -> new IllegalStateException(
                "Platform Service not granted: " + type.getSimpleName()
                        + " — declare it in the Job Type's requires: list (granted: " + granted() + ")"));
    }

    /** Every interface granted to this consumer — for logs, diagnostics, the UI grants panel. */
    Set<Class<?>> granted();

    /** The empty grant — every consumer's view until its declared {@code requires:} resolves (S1-2). */
    static PlatformServices none() {
        return new PlatformServices() {
            @Override public <T> Optional<T> find(Class<T> type) { return Optional.empty(); }
            @Override public Set<Class<?>> granted()             { return Set.of(); }
        };
    }
}
