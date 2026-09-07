package com.gamma.control;

import java.util.List;

/**
 * Who else is still consuming a shared component — the seam behind {@code ComponentRoutes}' delete fence
 * (EDG-01 cell 4, 2026-09-07).
 *
 * <p><b>Why this exists.</b> `DELETE /components/{type}/{id}` refuses to remove an item that another Space
 * still holds an active Exchange grant on: deleting it would break the consumer with no warning. That check
 * asked {@code com.gamma.exchange.Exchange} directly, which made the core depend on the exchange domain — the
 * one coupling that kept SEC-10 in every bundle. ⚠ It was invisible to an import-based census because
 * {@code ComponentRoutes} referred to the package by <b>fully-qualified name</b>, never importing it.
 *
 * <p>Same installed-singleton shape as {@link com.gamma.query.SharedRefResolver}: {@link #NONE} is the
 * default and the optional {@code inspecto-exchange} module installs the real implementation when it
 * registers its routes.
 *
 * <p>🔴 <b>{@link #NONE} returning an empty list is CORRECT, not degraded — and that is worth being explicit
 * about, because "empty means allow" usually is not.</b> The fence protects consumers of Exchange grants.
 * With no exchange module there is no Exchange, so nothing can have been offered, so no grant can exist and
 * no consumer can be harmed. The fence has nothing to guard, rather than failing to guard something.
 *
 * @since 4.0.0
 */
@com.gamma.api.PublicApi(since = "4.0.0")
@FunctionalInterface
public interface SharedItemConsumers {

    /** No sharing mechanism installed ⇒ nobody consumes anything. See the class note on why this is right. */
    SharedItemConsumers NONE = (type, id) -> List.of();

    /** Space ids holding an ACTIVE grant on {@code type/id} owned by the currently-bound Space. */
    List<String> consumersOf(String type, String id);

    /** Install the process-wide implementation. Idempotent; last call wins, mirroring `SharedRefResolver`. */
    static void install(SharedItemConsumers impl) {
        Holder.CURRENT = impl == null ? NONE : impl;
    }

    /** The installed implementation, or {@link #NONE}. */
    static SharedItemConsumers global() {
        return Holder.CURRENT;
    }

    /** Holder so the interface can carry mutable static state without an initialisation cycle. */
    final class Holder {
        private Holder() {}
        private static volatile SharedItemConsumers CURRENT = NONE;
    }
}
