package com.gamma.control;

/**
 * Whether the bound Space may send a Signal type to a named Space — the seam a Decision Rule's
 * {@code emit-signal} with {@code offerTo} asks before it emits (cross-Space consequence slice 5, operator
 * 2026-09-28: the emitter is a Decision Rule {@code emit-signal} payload, not a named Collector).
 *
 * <p>Same installed-singleton shape as {@link SharedItemConsumers}: the optional {@code inspecto-exchange}
 * module installs the real check when it registers its routes. {@link #NONE} answers {@code false}, so a build
 * without the Exchange refuses an {@code offerTo} (fail closed) instead of emitting a Signal nobody delivers.
 */
@FunctionalInterface
public interface SignalOfferGrants {

    /** Event attribute naming the ONE consumer Space a Signal is for; the Exchange forwarder delivers only there. */
    String ATTR_OFFER_TO = "offerTo";

    /** No Exchange in this build ⇒ nothing can be offered ⇒ nothing is granted. */
    SignalOfferGrants NONE = (owner, consumer, type) -> false;

    /** True when {@code owner} offers {@code type} and {@code consumer} holds an ACTIVE grant on it. */
    boolean granted(String owner, String consumer, String type);

    /** Install the process-wide implementation. Idempotent; last call wins. */
    static void install(SignalOfferGrants impl) {
        Holder.CURRENT = impl == null ? NONE : impl;
    }

    /** The installed implementation, or {@link #NONE}. */
    static SignalOfferGrants global() {
        return Holder.CURRENT;
    }

    /** Holder so the interface can carry mutable static state. */
    final class Holder {
        private Holder() {}
        private static volatile SignalOfferGrants CURRENT = NONE;
    }
}
