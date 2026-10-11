package com.gamma.exchange;

import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.event.EventLog;
import com.gamma.exchange.ExchangeSnapshots.SnapshotMeta;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.util.Scheduler;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The scheduled snapshot refresh of SHARE-SNAPSHOT-ON-APPROVE-1: a {@code dataset} offer whose owner set a
 * {@link Offer#refresh() refresh} cadence ({@code hourly}/{@code daily}) is republished through the same
 * {@link ExchangeSnapshotWriter#publish} path as the manual Refresh once its interval has elapsed since the last
 * attempt. Only offers with at least one {@code active} snapshot-mode grant are refreshed — with no consumer
 * there is nobody to keep fresh. Each attempt's outcome lands in {@code last-refresh.toon}
 * ({@link ExchangeSnapshots#readLastRefresh}), which is also what keeps a failing Dataset from being retried
 * every tick: a failure counts as an attempt, so it waits a full interval like a success.
 *
 * <p>Gated like every other module background work: the tick asks {@code ModuleGate.paused} for the OWNING Space
 * ({@link #BACKGROUND_ID}, declared in this module's {@code provides.background}) and skips that Space's offers
 * when the {@code exchange} module is switched off there. The host knows nothing of it: {@link ExchangeBootHook}
 * starts the one tick per JVM.
 */
public final class ExchangeSnapshotSweep {

    /** This module's background-work id ({@code module.toon provides.background}). */
    public static final String BACKGROUND_ID = "exchange-snapshot-refresh";

    /** How often the tick looks for due offers (the cadences are hours, so a minute is plenty). */
    private static final long TICK_SECONDS = 60;

    private static final System.Logger LOG = System.getLogger(ExchangeSnapshotSweep.class.getName());
    private static final AtomicReference<Scheduler> TICK = new AtomicReference<>();
    private static volatile SpaceManager spaces;

    private ExchangeSnapshotSweep() {}

    /** One publish, injected so the sweep is testable without a Space tree. */
    @FunctionalInterface
    interface Publisher {
        SnapshotMeta publish(String owner, String item) throws Exception;
    }

    /** Start the per-JVM tick over {@code spaces} (idempotent: a second boot only re-points it). */
    static void start(SpaceManager over) {
        spaces = over;
        Scheduler fresh = new Scheduler();
        if (!TICK.compareAndSet(null, fresh)) {
            fresh.close();
            return;
        }
        fresh.everySeconds(BACKGROUND_ID, TICK_SECONDS, TICK_SECONDS, ExchangeSnapshotSweep::tick);
    }

    private static void tick() {
        SpaceManager sm = spaces;
        if (sm == null) return;
        Exchange ex = Exchange.under(sm.containerRoot());
        if (!ex.enabled()) return;
        sweep(ex, System.currentTimeMillis(),
                owner -> sm.space(SpaceId.of(owner))
                        .map(ctx -> com.gamma.control.ModuleGate.paused(BACKGROUND_ID, ctx.root().config()))
                        .orElse(true),
                (owner, item) -> publishFrom(sm.space(SpaceId.of(owner))
                        .orElseThrow(() -> new IllegalStateException("no such space '" + owner + "'")), ex.dir(), owner, item));
    }

    /**
     * Republish every due offer; returns the {@code owner/item} of each one attempted. A failure is recorded and
     * logged, never thrown — one broken Dataset must not starve the rest of the sweep.
     *
     * @param ownerPaused whether the owning Space has this background work switched off
     */
    static List<String> sweep(Exchange ex, long now, Predicate<String> ownerPaused, Publisher publisher) {
        List<String> attempted = new ArrayList<>();
        for (Offer o : ex.offers()) {
            if (!due(ex, o, now) || ownerPaused.test(o.owner())) continue;
            attempted.add(o.owner() + "/" + o.item());
            Path itemDir = ExchangeSnapshots.itemDir(ex.dir(), o.owner(), o.item());
            try {
                SnapshotMeta meta = publisher.publish(o.owner(), o.item());
                recordOutcome(itemDir, "schedule", meta, null, now);
                emitRefreshed(o.owner(), o.item(), meta);
            } catch (Exception fail) {
                recordOutcome(itemDir, "schedule", null, fail, now);
                LOG.log(System.Logger.Level.WARNING, "scheduled snapshot of " + o.owner() + "/" + o.item()
                        + " failed; next attempt in one " + o.refresh() + " interval", fail);
            }
        }
        return attempted;
    }

    /** Whether {@code o} has a cadence, a consumer to refresh for, and a full interval since its last attempt. */
    static boolean due(Exchange ex, Offer o, long now) {
        long interval = o.refreshIntervalMillis();
        if (!"dataset".equals(o.kind()) || interval <= 0) return false;
        boolean consumed = ex.grants().stream().anyMatch(g -> "dataset".equals(g.kind())
                && o.item().equals(g.item()) && o.owner().equals(g.owner())
                && ShareGrant.ACTIVE.equals(g.status()) && ShareGrant.SNAPSHOT.equals(g.mode()));
        if (!consumed) return false;
        long last = lastAttempt(ExchangeSnapshots.itemDir(ex.dir(), o.owner(), o.item()));
        return now - last >= interval;
    }

    /** The later of the last recorded attempt and the live snapshot's publish time; 0 when neither exists. */
    private static long lastAttempt(Path itemDir) {
        long at = ExchangeSnapshots.readLastRefresh(itemDir).map(m -> Ledger.asLong(m.get("at"))).orElse(0L);
        long published = ExchangeSnapshots.readCurrent(itemDir).map(m -> {
            try {
                return Instant.parse(m.refreshedAt()).toEpochMilli();
            } catch (RuntimeException unparseable) {
                return 0L;
            }
        }).orElse(0L);
        return Math.max(at, published);
    }

    /** Publish {@code item} from the owner Space's own registry and data (the path every trigger shares). */
    static SnapshotMeta publishFrom(SpaceContext owner, Path sharedDir, String ownerId, String item) throws Exception {
        Path config = owner.root().config();
        if (config == null) throw new IllegalStateException("space '" + ownerId + "' has no registry");
        return ExchangeSnapshotWriter.publish(sharedDir, ownerId, config.resolve("registry"),
                Path.of(owner.root().dataDir()), owner.root().base().resolve("views"), item);
    }

    /** Record one publish attempt's outcome ({@code meta} on success, {@code fail} otherwise). */
    static void recordOutcome(Path itemDir, String trigger, SnapshotMeta meta, Exception fail, long at) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at);
        m.put("trigger", trigger);
        if (fail == null) {
            m.put("status", "published");
            m.put("version", meta.version());
        } else {
            m.put("status", "failed");
            m.put("error", String.valueOf(fail.getMessage()));
        }
        ExchangeSnapshots.writeLastRefresh(itemDir, m);
    }

    private static void emitRefreshed(String owner, String item, SnapshotMeta meta) {
        try {
            EventLog.current().emit(Event.builder(EventType.EXCHANGE_REFRESHED).source("exchange")
                    .message("scheduled refresh of dataset " + owner + "/" + item + " → " + meta.version())
                    .actor("system").actorType("system")
                    .attr("owner", owner).attr("kind", "dataset").attr("item", item)
                    .attr("version", meta.version()).attr("rows", meta.rows()));
        } catch (RuntimeException ignore) {
            // the audit signal is best-effort here; the outcome is already recorded
        }
    }
}
