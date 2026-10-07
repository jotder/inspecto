package com.gamma.exchange;

import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.audit.EventType;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.signal.Ref;
import com.gamma.signal.Severity;
import com.gamma.signal.Signal;
import org.slf4j.MDC;

import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Delivers a Signal across Spaces under a consented {@code signal} Share Grant — cross-Space consequence slice 3
 * ({@code docs/archived-documents/plans-archive/cross-space-consequence-design.md} §5.5). Only a Signal crosses (D1): the origin Space
 * announces, and the consumer Space decides what happens with its own {@code on_signal} Jobs.
 *
 * <p>An {@link EventLog#addTap process-wide tap}, so a Space created after the module registered is covered.
 * For each Signal on a hosted Space's ledger it finds the grants whose owner is that Space and whose item is
 * that exact type. Per grant:
 * <ul>
 *   <li><b>ACTIVE</b> (read from the ledger at delivery time, T9) <b>and the consumer hosted in this Pod</b>:
 *       emit on the CONSUMER's ledger, with the consumer's MDC bound and the caller's restored (T7), a new
 *       Signal: type {@code exchange.<origin>.<type>} (D4), actor {@code exchange-grant:<id>} (D6), only the
 *       offer's allowlisted payload keys (D5), {@code chainDepth} = origin + 1, the origin's correlation id
 *       (or its signal id), causation = the origin signal id, and origin provenance as ATTRIBUTES that only this
 *       class writes (T5). {@code exchange.signal.delivered} goes on the origin's ledger.</li>
 *   <li>not ACTIVE, consumer not hosted here, offer gone, or chain too deep: nothing is delivered, and
 *       {@code exchange.signal.undeliverable} (with a reason) goes on the origin's ledger. Never queued (D8).</li>
 * </ul>
 * No grant for the type: nothing happens and nothing is recorded (N3).
 *
 * <p>Loops (T4): a delivered type is {@code exchange.*}, which is never forwarded again, never offerable, and
 * never emittable by a Job or Decision Rule. A loop through Jobs on both sides is cut on the CHAIN's depth: the
 * delivered Signal carries the origin's {@code chainDepth} + 1, a Run triggered by it runs one deeper, and a
 * Run's emitted Signals always carry the Run's system depth (a payload value is overwritten), so a depth
 * beyond {@code jobs.signal.maxChainDepth} is undeliverable. Independent Signals that merely share a correlation
 * id (one Run emitting many, one Signal fanned out to many grants) are independent deliveries.
 */
public final class ExchangeSignalForwarder implements java.util.function.BiConsumer<EventLog, Event> {

    public static final String DELIVERED = "exchange.signal.delivered";
    public static final String UNDELIVERABLE = "exchange.signal.undeliverable";

    public static final String ATTR_ORIGIN_SPACE = "originSpace";
    public static final String ATTR_ORIGIN_SIGNAL = "originSignalId";
    public static final String ATTR_ORIGIN_ACTOR = "originActor";
    public static final String ATTR_GRANT = "exchangeGrant";

    private static final ExchangeSignalForwarder INSTANCE = new ExchangeSignalForwarder();

    /** The live SpaceManagers served (weak, so a closed test server does not pin its manager). */
    private final CopyOnWriteArrayList<WeakReference<SpaceManager>> managers = new CopyOnWriteArrayList<>();

    private ExchangeSignalForwarder() {}

    /** Serve {@code spaces}' ledgers (idempotent). Installed by {@code ExchangeRoutes}, so absent from Personal. */
    public static void install(SpaceManager spaces) {
        if (spaces == null || spaces.containerRoot() == null) return;
        INSTANCE.managers.removeIf(w -> w.get() == null);
        if (INSTANCE.managers.stream().noneMatch(w -> w.get() == spaces))
            INSTANCE.managers.add(new WeakReference<>(spaces));
        EventLog.addTap(INSTANCE);
    }

    @Override
    public void accept(EventLog log, Event event) {
        if (!EventType.SIGNAL.equals(event.type())) return;
        String type = event.attributes().get(Signal.ATTR_TYPE);
        if (type == null || type.startsWith("exchange.")) return;     // delivered + bookkeeping never re-forward
        for (WeakReference<SpaceManager> w : managers) {
            SpaceManager m = w.get();
            if (m == null) continue;
            Optional<SpaceContext> origin = hostOf(m, log);
            if (origin.isPresent()) {
                forward(m, origin.get(), log, Signal.fromEvent(event),
                        event.attributes().get(com.gamma.control.SignalOfferGrants.ATTR_OFFER_TO));
                return;
            }
        }
    }

    private static Optional<SpaceContext> hostOf(SpaceManager m, EventLog log) {
        try {
            return m.all().stream().filter(c -> c.service().eventLog() == log).findFirst();
        } catch (RuntimeException closed) {
            return Optional.empty();
        }
    }

    /** {@code offerTo} non-null (a Decision Rule's emit-signal named ONE Space): only that consumer's grant. */
    private void forward(SpaceManager m, SpaceContext origin, EventLog originLog, Signal sig, String offerTo) {
        Exchange ex = Exchange.under(m.containerRoot());
        String owner = origin.id().value();
        List<ShareGrant> grants = ex.grants().stream()
                .filter(g -> Exchange.SIGNAL.equals(g.kind()) && owner.equals(g.owner()) && sig.type().equals(g.item()))
                .filter(g -> offerTo == null || offerTo.equals(g.consumer()))
                .toList();
        for (ShareGrant g : grants) {
            String reason = deliver(m, ex, origin, g, sig);
            record(originLog, owner, g, sig, reason);
        }
    }

    /** Deliver under one grant; returns null when delivered, else the reason it was not. */
    private String deliver(SpaceManager m, Exchange ex, SpaceContext origin, ShareGrant g, Signal sig) {
        if (ex.activeGrant(g.consumer(), g.owner(), Exchange.SIGNAL, g.item()).isEmpty())
            return "grant " + g.status();
        Optional<Offer> offer = ex.offer(g.owner(), Exchange.SIGNAL, g.item());
        if (offer.isEmpty()) return "offer withdrawn";
        Optional<SpaceContext> target = SpaceId.isValid(g.consumer()) ? m.space(SpaceId.of(g.consumer())) : Optional.empty();
        if (target.isEmpty()) return "consumer not hosted here";
        String cid = sig.correlationId() == null || sig.correlationId().isBlank() ? sig.signalId() : sig.correlationId();
        int max = Integer.getInteger("jobs.signal.maxChainDepth", 8);
        int depth = intOf(sig.payload().get("chainDepth")) + 1;
        if (depth > max) return "chain depth " + max + " reached";

        Map<String, Object> payload = new LinkedHashMap<>();
        for (String k : offer.get().payloadKeys())
            if (sig.payload().containsKey(k)) payload.put(k, sig.payload().get(k));
        payload.put("chainDepth", depth);

        String type = "exchange." + origin.id().value() + "." + sig.type();
        Signal delivered = new Signal(null, type, Instant.now(), sig.severity(), Ref.of("exchange-grant", g.id()), null,
                cid, sig.signalId(), g.consumer(), Ref.of("exchange-grant", g.id()), type, payload, 1);
        Event base = delivered.toEvent();
        Map<String, String> attrs = new LinkedHashMap<>(base.attributes());
        attrs.put(ATTR_ORIGIN_SPACE, origin.id().value());
        attrs.put(ATTR_ORIGIN_SIGNAL, sig.signalId());
        if (sig.actor() != null) attrs.put(ATTR_ORIGIN_ACTOR, sig.actor().kind() + ":" + sig.actor().id());
        attrs.put(ATTR_GRANT, g.id());
        Event event = new Event(base.eventId(), base.ts(), base.level(), base.type(), base.source(), base.pipeline(),
                base.correlationId(), base.message(), attrs, base.payload());

        String prior = MDC.get(EventLog.SPACE_MDC_KEY);
        try {
            MDC.put(EventLog.SPACE_MDC_KEY, g.consumer());
            target.get().service().eventLog().emit(event);
        } finally {
            if (prior == null) MDC.remove(EventLog.SPACE_MDC_KEY);
            else MDC.put(EventLog.SPACE_MDC_KEY, prior);
        }
        return null;
    }

    private static void record(EventLog originLog, String owner, ShareGrant g, Signal sig, String reason) {
        String type = reason == null ? DELIVERED : UNDELIVERABLE;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grant", g.id());
        payload.put("consumer", g.consumer());
        payload.put("signalType", sig.type());
        if (reason != null) payload.put("reason", reason);
        originLog.emit(new Signal(null, type, Instant.now(), reason == null ? Severity.INFO : Severity.WARN,
                Ref.of("exchange-grant", g.id()), null, sig.correlationId(), sig.signalId(), owner, null,
                reason == null ? "delivered " + sig.type() + " to " + g.consumer()
                        : "not delivered " + sig.type() + " to " + g.consumer() + ": " + reason,
                payload, 1).toEvent());
    }

    private static int intOf(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? 0 : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
