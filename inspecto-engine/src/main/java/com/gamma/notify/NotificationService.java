package com.gamma.notify;

import com.gamma.event.Event;
import com.gamma.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Turns operational {@link Event}s into in-app {@link Notification}s. Registered as an
 * {@link com.gamma.event.EventLog} subscriber, so it sees every emitted event and applies the active
 * {@link NotificationRules}.
 *
 * <h3>Off-thread by design</h3>
 * {@code EventLog} notifies subscribers synchronously on the emitting thread — for a {@code BATCH_FAILED}
 * event that thread is inside the synchronous {@link com.gamma.etl.ConsignmentEventBus} publish while the
 * ingest path holds {@code ingestLock}. Doing real work inline would stall ingest (and risk the
 * documented bus/lock deadlock), so {@link #onEvent} only matches a rule and <b>hands off</b> to a
 * virtual-thread executor (the {@code JobService}/{@code triggerWorkers} idiom); rendering, dedup and
 * storage happen there.
 *
 * <p>Live {@link #addListener listeners} are invoked after a notification is stored — the seam the SSE
 * endpoint uses to push it to connected clients.
 *
 * @since 4.0.0
 */
public final class NotificationService implements NotificationAccess, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationStore store;
    private final NotificationRules rules;
    private final NotificationPreferences prefs;
    /** Caps identical notifications per rolling hour — the loop safeguard (beyond unread-dedup). */
    private final NotificationRateLimiter rateLimiter =
            new NotificationRateLimiter(NotificationRateLimiter.DEFAULT_MAX_PER_HOUR, NotificationRateLimiter.ONE_HOUR_MS);
    /** External SPI delivery channels (e.g. email), discovered via {@link ServiceLoader}; empty in the core. */
    private final List<NotificationChannel> channels;
    /** Live view of the operator's persisted {@link ChannelConfig} destinations (admin CRUD), resolved at
     *  dispatch time so channel edits take effect without a restart; {@code List::of} when none are wired. */
    private final Supplier<List<ChannelConfig>> channelConfigs;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    /** Per-destination digest buffers, keyed by {@link ChannelConfig#id()} — populated only for configs
     *  with {@code digestMinutes > 0}; flushed as one combined notification when the window elapses. */
    private final java.util.Map<String, DigestBuffer> digests = new java.util.LinkedHashMap<>();
    /** Lazily-started single daemon timer that fires each buffer's one-shot flush; null until first use. */
    private java.util.concurrent.ScheduledExecutorService digestTimer;
    /** Delivery-status receipts (D8), or {@code null} when tracking is not wired (the lean default). */
    private volatile DeliveryReceiptStore receipts;
    private final CopyOnWriteArrayList<Consumer<Notification>> listeners = new CopyOnWriteArrayList<>();
    /** Callbacks run on {@link #close()} to unblock open SSE streams (each interrupts its blocked thread). */
    private final CopyOnWriteArrayList<Runnable> streamClosers = new CopyOnWriteArrayList<>();

    /** Production constructor: external SPI channels are discovered via {@link ServiceLoader} (none in the
     *  core), with no persisted {@link ChannelConfig} destinations wired. */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs) {
        this(store, rules, prefs, discoverChannels(), List::of);
    }

    /** Production constructor: discover SPI channels and wire a live view of the operator's persisted
     *  {@link ChannelConfig} destinations (admin CRUD), delivered through the matching SPI transport by kind. */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs,
                               Supplier<List<ChannelConfig>> channelConfigs) {
        this(store, rules, prefs, discoverChannels(), channelConfigs);
    }

    /** Test/explicit constructor: inject the external channel list directly (no persisted destinations). */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs,
                               List<NotificationChannel> channels) {
        this(store, rules, prefs, channels, List::of);
    }

    /** Full constructor: explicit SPI channels + a persisted-{@link ChannelConfig} supplier. */
    public NotificationService(NotificationStore store, NotificationRules rules, NotificationPreferences prefs,
                               List<NotificationChannel> channels, Supplier<List<ChannelConfig>> channelConfigs) {
        this.store = store;
        this.rules = rules;
        this.prefs = prefs;
        this.channels = List.copyOf(channels);
        this.channelConfigs = channelConfigs;
        if (!this.channels.isEmpty())
            log.info("Notification channels: {}", this.channels.stream().map(NotificationChannel::id).toList());
    }

    private static List<NotificationChannel> discoverChannels() {
        List<NotificationChannel> found = new ArrayList<>();
        for (NotificationChannel ch : ServiceLoader.load(NotificationChannel.class)) {
            if (ch.configured()) found.add(ch);
            else log.debug("notification channel {} registered but not configured — skipped", ch.id());
        }
        return found;
    }

    /** The feed store (for the Control API routes). */
    public NotificationStore store() {
        return store;
    }

    /**
     * Wire delivery-status tracking (BACKLOG D8): a receipt is written per <em>external</em> destination
     * before the transport is called, and its {@code deliveryId} is handed to the transport to embed.
     *
     * <p>A setter rather than a sixth constructor: this is optional (Standard/Enterprise) and four public
     * constructors already exist. {@code null} — the default — means no receipts are written and delivery
     * behaves exactly as before.
     *
     * @since 4.0.0
     */
    public void deliveryReceipts(DeliveryReceiptStore receipts) {
        this.receipts = receipts;
    }

    /** The receipt store, or {@code null} when delivery-status tracking is not wired. */
    public DeliveryReceiptStore deliveryReceipts() {
        return receipts;
    }

    /**
     * Record an attempted external delivery and return the id to embed, or {@code null} when tracking is
     * off. In-app delivery gets no receipt — there is no provider to hear back from.
     */
    private String openReceipt(String notificationId, String channelConfigId, String target, boolean digest) {
        if (receipts == null) return null;
        String deliveryId = DeliveryReceipt.newDeliveryId();
        receipts.add(new DeliveryReceipt(deliveryId, notificationId, channelConfigId, target,
                System.currentTimeMillis(), java.util.Map.of(), null, digest));
        return deliveryId;
    }

    /** The {@link com.gamma.event.EventLog} subscriber. Cheap + non-blocking: filter, then hand off. */
    public void onEvent(Event e) {
        if (e == null || EventType.LOG.equals(e.type())) return;   // skip the high-volume capture stream
        rules.forEvent(e).ifPresent(rule -> {
            try {
                workers.execute(() -> dispatch(e, rule));
            } catch (RuntimeException ignore) {
                // executor shutting down — drop; notifications are best-effort, never block the emitter
            }
        });
    }

    /** Register a live listener invoked after each stored notification (e.g. the SSE pusher). */
    public void addListener(Consumer<Notification> listener) {
        if (listener != null) listeners.add(listener);
    }

    /** Remove a previously registered listener. */
    public void removeListener(Consumer<Notification> listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** Register a callback run on {@link #close()} to unblock an open SSE stream (e.g. interrupt its thread). */
    public void onClose(Runnable closer) {
        if (closer != null) streamClosers.add(closer);
    }

    /** Remove a previously registered close callback (when the stream ends normally). */
    public void removeOnClose(Runnable closer) {
        if (closer != null) streamClosers.remove(closer);
    }

    /**
     * The in-app emit — dedupe-collapse, store, push to live listeners — and the {@code notifications}
     * Platform Service (S1-3): a granted Run and the event dispatcher's in-app leg store through this
     * one path. Serialized so the dedupe check and the add are atomic across concurrent callers.
     */
    @Override
    public synchronized java.util.Optional<Notification> notify(Notification n) {
        if (store.hasActiveDuplicate(n.dedupeKey())) return java.util.Optional.empty();
        Notification stored = store.add(n);
        for (Consumer<Notification> l : listeners) {
            try { l.accept(stored); } catch (RuntimeException ex) {
                log.debug("notification listener failed: {}", ex.getMessage());
            }
        }
        return java.util.Optional.of(stored);
    }

    /** Render → dedup → store → notify, off the emitting thread. Serialized so the dedup check and the
     *  add are atomic across concurrent workers (the feed is low-volume; the executor exists to get off
     *  the emit thread, not for parallelism). */
    private synchronized void dispatch(Event e, NotificationRule rule) {
        try {
            Notification n = rule.render(e);
            if (store.hasActiveDuplicate(n.dedupeKey())) return;   // collapse identical unread alerts
            if (!rateLimiter.allow(n.dedupeKey())) return;          // cap identical alerts per rolling hour
            // In-app (intrinsic): the S1-3 service path (dedupe + store + listeners), unless the user
            // opted out of in-app for this category (critical categories are always delivered — bypass).
            if (prefs.enabled(n.category(), NotificationPreferences.IN_APP)) {
                notify(n);
            }
            // External SPI channels configured from notify.* flags: delivered only when enabled for this category.
            for (NotificationChannel ch : channels) {
                if (!prefs.enabled(n.category(), ch.id())) continue;
                // No ChannelConfig and no explicit target: the channel resolves its destination from its
                // own notify.* flags, so the receipt records the attempt with neither (D8).
                String deliveryId = openReceipt(n.id(), null, null, false);
                try {
                    if (deliveryId == null) ch.deliver(n);
                    else ch.deliver(n, null, deliveryId);
                } catch (Exception ex) {
                    log.warn("channel {} delivery failed: {}", ch.id(), ex.getMessage());
                }
            }
            // Persisted channel destinations (admin CRUD): deliver each enabled ChannelConfig through the SPI
            // transport whose id names its kind, to the config's own target — so an operator-managed
            // destination delivers without a restart. No transport for a kind ⇒ nothing to deliver through.
            // A channel with its own `template` renders that (against the rule's own context) in place of
            // the rule's default body; blank/null template ⇒ unchanged behaviour (§4.5.1).
            for (ChannelConfig cfg : channelConfigs.get()) {
                if (!cfg.enabled()) continue;
                NotificationChannel ch = channelByKind(cfg.kind());
                if (ch == null || !prefs.enabled(n.category(), ch.id())) continue;
                Notification toDeliver = cfg.template() == null || cfg.template().isBlank()
                        ? n
                        : n.withBody(NotificationTemplate.render(cfg.template(), NotificationRule.context(e)));
                // Digest window: buffer instead of delivering; a one-shot timer flushes the batch as a
                // single combined notification once the window elapses (0 = immediate, the default).
                if (cfg.digestMinutes() > 0) {
                    bufferForDigest(cfg, toDeliver);
                    continue;
                }
                String deliveryId = openReceipt(toDeliver.id(), cfg.id(), cfg.target(), false);
                try { ch.deliver(toDeliver, cfg.target(), deliveryId); } catch (Exception ex) {
                    log.warn("channel {} → {} delivery failed: {}", ch.id(), cfg.target(), ex.getMessage());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("failed to dispatch notification for event {}: {}", e.eventId(), ex.getMessage());
        }
    }

    // ── digest batching (per-destination, opt-in via ChannelConfig.digestMinutes) ───────────────────

    /** One destination's pending digest: the config it was armed with and the buffered notifications. */
    private record DigestBuffer(ChannelConfig cfg, java.util.List<Notification> pending) {}

    /** Buffer {@code n} for {@code cfg}'s digest; the first buffered item arms a one-shot flush timer for
     *  the config's window. Called from {@link #dispatch} (already {@code synchronized(this)}). */
    private void bufferForDigest(ChannelConfig cfg, Notification n) {
        DigestBuffer buf = digests.get(cfg.id());
        if (buf == null) {
            buf = new DigestBuffer(cfg, new java.util.ArrayList<>());
            digests.put(cfg.id(), buf);
            String id = cfg.id();
            if (digestTimer == null) {
                digestTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "inspecto-notify-digest");
                    t.setDaemon(true);
                    return t;
                });
            }
            digestTimer.schedule(() -> flushDigest(id), cfg.digestMinutes(), java.util.concurrent.TimeUnit.MINUTES);
        }
        buf.pending().add(n);
    }

    /** Deliver + clear one destination's pending digest as a single combined notification (no-op when
     *  empty/unknown). Package-private so tests can flush without waiting out the window. */
    synchronized void flushDigest(String configId) {
        DigestBuffer buf = digests.remove(configId);
        if (buf == null || buf.pending().isEmpty()) return;
        ChannelConfig cfg = buf.cfg();
        NotificationChannel ch = channelByKind(cfg.kind());
        if (ch == null) return;   // transport gone since arming — nothing to deliver through
        java.util.List<Notification> batch = buf.pending();
        StringBuilder body = new StringBuilder();
        for (Notification item : batch) {
            if (!body.isEmpty()) body.append('\n');
            body.append("• ").append(item.title()).append(" — ").append(item.body());
        }
        Notification digest = Notification.create(batch.get(0).category(), "notification.digest", null,
                "Digest: " + batch.size() + " notification" + (batch.size() == 1 ? "" : "s"),
                body.toString(),
                "digest:" + cfg.id() + ":" + System.nanoTime());
        // ONE receipt per digest delivery, keyed on the digest — not one per batched notification. This is
        // the single place the per-delivery model is lossy: a bounce tells us the digest bounced, not which
        // of its N notifications was in it (D8 §4.5, carried as a known residual).
        String deliveryId = openReceipt(digest.id(), cfg.id(), cfg.target(), true);
        try { ch.deliver(digest, cfg.target(), deliveryId); } catch (Exception ex) {
            log.warn("digest delivery to channel {} → {} failed ({} buffered): {}",
                    ch.id(), cfg.target(), batch.size(), ex.getMessage());
        }
    }

    /** The discovered SPI transport whose {@link NotificationChannel#id() id} names this persisted channel's
     *  {@code kind} (case-insensitive), or {@code null} when no such transport is on the classpath. */
    private NotificationChannel channelByKind(String kind) {
        if (kind == null) return null;
        for (NotificationChannel ch : channels)
            if (ch.id().equalsIgnoreCase(kind)) return ch;
        return null;
    }

    @Override
    public void close() {
        for (Runnable r : streamClosers) {   // unblock any open SSE streams first
            try { r.run(); } catch (RuntimeException ignore) { /* best effort */ }
        }
        workers.close();   // drain in-flight dispatches
        // Flush any pending digests rather than dropping them on shutdown, then stop the timer.
        synchronized (this) {
            for (String id : java.util.List.copyOf(digests.keySet())) flushDigest(id);
            if (digestTimer != null) digestTimer.shutdownNow();
        }
    }
}
