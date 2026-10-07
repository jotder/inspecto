package com.gamma.exchange;

import com.gamma.control.HostBootHook;
import com.gamma.service.SpaceManager;

/**
 * The host-wide seams the Exchange installs once the host has its Spaces. They used to be installed inside
 * {@link ExchangeRoutes#register}, which made the module's routes undrivable on any {@code ApiContext} but the
 * host's. Doing it from a {@link HostBootHook} keeps it edition-correct: without this module
 * {@code SharedRefResolver} stays {@code NONE} (every {@code shared/<owner>/<item>} ref fails closed),
 * {@code SignalOfferGrants} refuses, and {@code SharedItemConsumers} reports no consumer (with no Exchange nothing
 * can have been offered, so no consumer can be harmed). Every install is an idempotent static.
 */
public final class ExchangeBootHook implements HostBootHook {

    @Override
    public void afterRoutes(SpaceManager spaces) {
        com.gamma.query.SharedRefResolver.install(new ExchangeRefResolver(spaces));
        // Cross-Space consequence slice 3: deliver consented Signals between this installation's Spaces.
        ExchangeSignalForwarder.install(spaces);
        // Slice 5: a Decision Rule emit-signal with offerTo asks this before it emits (absent module => refused).
        com.gamma.control.SignalOfferGrants.install((owner, consumer, type) -> {
            Exchange ex = Exchange.under(spaces.containerRoot());
            return ex.enabled() && ex.activeGrant(consumer, owner, Exchange.SIGNAL, type).isPresent()
                    && ex.offer(owner, Exchange.SIGNAL, type).isPresent();
        });
        // The core's delete fence (ComponentRoutes) asks THIS for "is the item still shared?".
        com.gamma.control.SharedItemConsumers.install((type, id) -> {
            Exchange ex = Exchange.under(spaces.containerRoot());
            if (!ex.enabled()) return java.util.List.of();
            String owner = com.gamma.event.EventLog.currentSpaceId();
            return ex.grants().stream()
                    .filter(g -> ShareGrant.ACTIVE.equals(g.status())
                            && type.equals(g.kind()) && id.equals(g.item()) && owner.equals(g.owner()))
                    .map(ShareGrant::consumer)
                    .toList();
        });
    }
}
