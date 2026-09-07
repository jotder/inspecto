package com.gamma.notify;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Per-recipient delivery suppression (BACKLOG `D8-SUPPRESS-1`): stop sending to an address the provider
 * has already told us is bad.
 *
 * <p><b>Two rules, and the split between them is the whole point.</b>
 * <ul>
 *   <li><b>{@link DeliveryStatus#COMPLAINED} — permanent.</b> Someone pressed the spam button. Continuing
 *       to send is what gets a sending domain blocklisted, and no elapsed time makes it acceptable again.</li>
 *   <li><b>{@link DeliveryStatus#BOUNCED_HARD} — for a TTL</b> ({@code -Dnotify.suppression.bounce.ttl},
 *       an ISO-8601 period, default {@code P30D}). The destination did not exist <i>then</i>; addresses do
 *       get recreated, so this expires rather than being permanent.</li>
 * </ul>
 *
 * <p>⛔ <b>{@link DeliveryStatus#BOUNCED_SOFT} never suppresses.</b> A full or briefly-unavailable mailbox
 * is not a bad address — that distinction is why {@code DeliveryStatus} splits hard from soft at all, and
 * collapsing it here would silently stop mail to every recipient who was once over quota.
 *
 * <p>🔴 <b>Suppression requires a DURABLE receipt store and refuses to pretend otherwise.</b> It is armed
 * only when {@link DeliveryReceiptStore#durable()} — because the in-memory store is bounded and evicts
 * oldest-first, so the bounce that should suppress an address is exactly the record most likely to be gone
 * by the next send. A suppression list that consults an evicting cache reports "nothing to suppress" and
 * looks identical to one with nothing to do. {@link #armed()} says which state this is in, and
 * {@link NotificationService} logs a warning when a TTL is configured but the store cannot honour it —
 * the alternative, silently suppressing nothing, is this codebase's most-repeated failure shape.
 *
 * @since 4.0.0
 */
public final class SuppressionList {

    /** {@code -Dnotify.suppression.bounce.ttl} — how long a hard bounce suppresses an address. */
    public static final String TTL_PROPERTY = "notify.suppression.bounce.ttl";

    /** {@code -Dnotify.suppression=off} disables suppression even with a durable store. */
    public static final String ENABLED_PROPERTY = "notify.suppression";

    private static final Duration DEFAULT_TTL = Duration.ofDays(30);

    private final DeliveryReceiptStore receipts;
    private final long ttlMillis;
    private final boolean armed;

    private SuppressionList(DeliveryReceiptStore receipts, long ttlMillis, boolean armed) {
        this.receipts = receipts;
        this.ttlMillis = ttlMillis;
        this.armed = armed;
    }

    /**
     * The suppression list for {@code receipts}, reading its configuration from system properties.
     *
     * <p>Arms itself only for a durable store — see the class note. A {@code null} store, an in-memory one,
     * or {@code -Dnotify.suppression=off} all produce a disarmed list whose {@link #reasonToSuppress} is
     * always empty, so the caller needs no null check and no branch.
     */
    public static SuppressionList fromProperties(DeliveryReceiptStore receipts) {
        boolean off = "off".equalsIgnoreCase(
                System.getProperty(ENABLED_PROPERTY, "on").trim());
        boolean armed = receipts != null && receipts.durable() && !off;
        return new SuppressionList(receipts, ttlMillis(), armed);
    }

    /** Explicit construction for tests: arm over any store with any TTL. */
    public static SuppressionList of(DeliveryReceiptStore receipts, Duration hardBounceTtl) {
        return new SuppressionList(receipts,
                hardBounceTtl == null ? DEFAULT_TTL.toMillis() : hardBounceTtl.toMillis(),
                receipts != null);
    }

    /**
     * ⚠ An unparseable TTL falls back to the default rather than failing the boot, and does so at the
     * default's value — a suppression window is a deliverability nicety, never a reason a service does not
     * start. It is logged by the caller, not swallowed.
     */
    private static long ttlMillis() {
        String raw = System.getProperty(TTL_PROPERTY);
        if (raw == null || raw.isBlank()) return DEFAULT_TTL.toMillis();
        try {
            Duration d = Duration.parse(raw.trim().toUpperCase(Locale.ROOT));
            return d.isNegative() || d.isZero() ? DEFAULT_TTL.toMillis() : d.toMillis();
        } catch (RuntimeException e) {
            return DEFAULT_TTL.toMillis();
        }
    }

    /** Whether suppression can actually act. False ⇒ {@link #reasonToSuppress} is always empty. */
    public boolean armed() {
        return armed;
    }

    /** The configured hard-bounce window. */
    public Duration hardBounceTtl() {
        return Duration.ofMillis(ttlMillis);
    }

    /**
     * Why {@code target} must not be delivered to, or empty to deliver.
     *
     * <p>The returned string is operator-facing and names the evidence (which status, and when), because
     * "why did this notification not arrive" is the question a suppression list exists to be able to
     * answer. A suppression nobody can explain is indistinguishable from a bug.
     *
     * @param target the destination address; {@code null}/blank is never suppressed (an SPI channel that
     *               resolves its own destination gives us nothing to match on — see
     *               {@link NotificationService})
     * @param now    epoch millis, injected so the TTL boundary is testable
     */
    public Optional<String> reasonToSuppress(String target, long now) {
        if (!armed || target == null || target.isBlank()) return Optional.empty();

        // An operator override forgives everything up to its timestamp — and nothing after it. That
        // single comparison IS "cleared by the next suppressing event": a later bounce or complaint is
        // simply not covered, so it re-suppresses with no clearing job and no state to expire.
        long forgivenUpTo = receipts.unsuppressedAt(target).orElse(Long.MIN_VALUE);

        Optional<DeliveryReceipt> complaint = receipts.latestWithStatus(target, DeliveryStatus.COMPLAINED);
        if (complaint.isPresent()) {
            long at = complaint.get().statusAt().get(DeliveryStatus.COMPLAINED);
            if (at > forgivenUpTo) {
                return Optional.of("the recipient marked a previous message as spam (complaint recorded at "
                        + at + "); suppression is permanent");
            }
        }

        Optional<DeliveryReceipt> bounce = receipts.latestWithStatus(target, DeliveryStatus.BOUNCED_HARD);
        if (bounce.isPresent()) {
            long at = bounce.get().statusAt().get(DeliveryStatus.BOUNCED_HARD);
            long expires = at + ttlMillis;
            if (at > forgivenUpTo && now < expires) {
                return Optional.of("the address hard-bounced at " + at + "; suppressed until " + expires
                        + " (-D" + TTL_PROPERTY + "=" + hardBounceTtl() + ")");
            }
        }
        return Optional.empty();
    }

    /**
     * Every target currently suppressed, with why — the {@code GET /notifications/suppressions} body.
     *
     * <p>Built by asking the store for CANDIDATES (any target that ever complained or hard-bounced) and
     * then running {@link #reasonToSuppress} over each, so the list and the delivery-time decision can
     * never disagree: there is one authoritative check, called from both.
     *
     * <p>⚠ Bounded by {@code limit}. A diagnostic read must not become an unbounded export, and on a
     * long-lived deployment the candidate set grows with every address ever mistyped.
     *
     * @return targets in encounter order, capped at {@code limit}
     */
    public java.util.List<Suppressed> suppressions(long now, int limit) {
        java.util.List<Suppressed> out = new java.util.ArrayList<>();
        if (!armed || limit <= 0) return out;
        java.util.LinkedHashSet<String> candidates =
                new java.util.LinkedHashSet<>(receipts.targetsWithStatus(DeliveryStatus.COMPLAINED));
        candidates.addAll(receipts.targetsWithStatus(DeliveryStatus.BOUNCED_HARD));
        for (String target : candidates) {
            if (out.size() >= limit) break;
            reasonToSuppress(target, now).ifPresent(why -> out.add(new Suppressed(target, why)));
        }
        return out;
    }

    /** One suppressed destination and the operator-facing reason. */
    public record Suppressed(String target, String reason) {}
}
