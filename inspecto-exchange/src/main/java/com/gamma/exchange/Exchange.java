package com.gamma.exchange;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * The Exchange — the installation-scope, cross-Space sharing surface that lives under
 * {@code spaces/_shared/} (reserved, never itself a Space). It holds two flat ledgers:
 * <ul>
 *   <li>{@code offers.toon} — the Datasets/Widgets/saved Views each owner has listed as shareable (catalog
 *       metadata only, never rows);</li>
 *   <li>{@code grants.toon} — the {@link ShareGrant} lifecycle ledger tying an owner's offered item to a
 *       consumer Space.</li>
 * </ul>
 *
 * <p>Sharing is per-item, opt-in and grant-mediated: nothing is discoverable across Spaces unless its
 * owner offers it, and nothing is usable without an <em>active</em> grant — {@link #resolveForConsumer}
 * is fail-closed. This class is the pure ledger + lifecycle state machine; the HTTP edge
 * ({@code ExchangeRoutes}) owns capability gating, cross-Space validation, and audit/signal emission.
 *
 * <p><b>Single-tenant fail-closed:</b> with no {@code -Dspaces.root} there is no {@code _shared} dir and
 * no one to share with, so {@link #under(Path)} yields a {@linkplain #enabled() disabled} Exchange whose
 * every operation throws {@link IllegalStateException} — matching the packs-dir "off when unset" posture.
 *
 * <p><b>Concurrency:</b> read-modify-write of the ledgers is serialised on a process-wide lock (one
 * installation has one {@code _shared} dir); reads are lock-free (files are written atomically).
 */
public final class Exchange {

    private static final String OFFERS = "offers";
    private static final String GRANTS = "grants";

    /** The saved-view kind the Exchange carries (BACKLOG D9) — live-mode only; see {@link #effectiveMode}. */
    public static final String VIEW = "link-analysis-view";

    /**
     * Kinds that are <em>derived</em> — metadata reading one or more Datasets rather than owning rows of
     * their own. Their grant closure includes a grant for every Dataset they read (§3.5 for widgets,
     * generalized to saved views by D9): requesting one requests its Dataset grants, approving one activates
     * them atomically, and revoking a Dataset grant cascades back to every derived grant that reads it.
     */
    private static final java.util.Set<String> DERIVED_KINDS = java.util.Set.of("widget", VIEW);

    /** Whether {@code kind} is derived — i.e. its grant closure includes the Datasets it reads. */
    public static boolean isDerived(String kind) {
        return DERIVED_KINDS.contains(kind);
    }
    /** Serialises ledger mutations across the per-request {@link Exchange} instances (one _shared dir). */
    private static final Object LOCK = new Object();

    /** {@code spaces/_shared/}, or {@code null} in single-tenant mode (Exchange disabled). */
    private final Path root;

    private Exchange(Path root) {
        this.root = root;
    }

    /** The Exchange under a container root ({@code -Dspaces.root}); a {@code null} root yields a disabled one. */
    public static Exchange under(Path spacesRoot) {
        return new Exchange(spacesRoot == null ? null : spacesRoot.resolve("_shared").normalize());
    }

    /** Whether cross-Space sharing is available (false in single-tenant mode). */
    public boolean enabled() {
        return root != null;
    }

    /** The {@code spaces/_shared/} directory, or {@code null} when disabled. */
    public Path dir() {
        return root;
    }

    private void requireEnabled() {
        if (root == null)
            throw new IllegalStateException("the Exchange is disabled: this server hosts a single space");
    }

    // ── offers ─────────────────────────────────────────────────────────────────

    /** Every listed offer, catalog order (as stored). */
    public List<Offer> offers() {
        requireEnabled();
        return Ledger.read(root.resolve("offers.toon"), OFFERS).stream().map(Offer::fromMap).toList();
    }

    /** The offer for {@code (owner, kind, item)}, if listed. */
    public Optional<Offer> offer(String owner, String kind, String item) {
        String key = owner + "~" + kind + "~" + item;
        return offers().stream().filter(o -> o.key().equals(key)).findFirst();
    }

    /** List or update an offer (upsert by {@code (owner, kind, item)}); returns the stored offer. */
    public Offer putOffer(Offer offer) {
        requireEnabled();
        synchronized (LOCK) {
            List<Offer> kept = new ArrayList<>(offers().stream()
                    .filter(o -> !o.key().equals(offer.key())).toList());
            kept.add(offer);
            Ledger.write(root.resolve("offers.toon"), OFFERS, kept.stream().map(Offer::toMap).toList());
            return offer;
        }
    }

    // ── grants ─────────────────────────────────────────────────────────────────

    /** Every grant in the ledger. */
    public List<ShareGrant> grants() {
        requireEnabled();
        return Ledger.read(root.resolve("grants.toon"), GRANTS).stream().map(ShareGrant::fromMap).toList();
    }

    /** Grants where {@code space} is either the owner or the consumer (its "shared by/with me" view). */
    public List<ShareGrant> grantsForSpace(String space) {
        return grants().stream()
                .filter(g -> space.equals(g.owner()) || space.equals(g.consumer()))
                .toList();
    }

    /** One grant by id. */
    public Optional<ShareGrant> grant(String id) {
        return grants().stream().filter(g -> id.equals(g.id())).findFirst();
    }

    /**
     * Record a consumer's request to use an offered item. Idempotent when a request is already pending;
     * a previously {@code denied}/{@code revoked}/{@code expired} grant is reopened as {@code requested}.
     *
     * @throws IllegalStateException when an {@code active} grant already exists (nothing to request)
     */
    public ShareGrant request(String kind, String item, String owner, String consumer,
                              String requestedBy, String purpose, String mode) {
        requireEnabled();
        synchronized (LOCK) {
            String id = ShareGrant.idFor(kind, item, owner, consumer);
            Optional<ShareGrant> existing = grant(id);
            if (existing.isPresent() && ShareGrant.ACTIVE.equals(existing.get().status()))
                throw new IllegalStateException("an active grant already exists for " + id);
            if (existing.isPresent() && ShareGrant.REQUESTED.equals(existing.get().status()))
                return existing.get();   // idempotent re-request
            ShareGrant grant = new ShareGrant(id, kind, item, owner, consumer,
                    effectiveMode(kind, mode),
                    ShareGrant.REQUESTED, requestedBy, System.currentTimeMillis(), purpose,
                    null, 0L, null, null);
            upsert(grant);
            // Derived-kind grant closure (§3.5, generalized by D9): every Dataset it reads travels with it.
            // Ensure a dataset grant for the same consumer exists (created here as pending; approved
            // atomically with the derived grant).
            if (DERIVED_KINDS.contains(kind)) {
                for (String ds : boundDatasets(owner, kind, item)) {
                    String dgid = ShareGrant.idFor("dataset", ds, owner, consumer);
                    boolean livePair = grant(dgid).map(x -> ShareGrant.ACTIVE.equals(x.status())
                            || ShareGrant.REQUESTED.equals(x.status())).orElse(false);
                    if (!livePair)
                        upsert(new ShareGrant(dgid, "dataset", ds, owner, consumer, grant.mode(),
                                ShareGrant.REQUESTED, requestedBy, System.currentTimeMillis(), purpose,
                                null, 0L, null, null));
                }
            }
            return grant;
        }
    }

    /**
     * Owner approves a pending request → {@code active}. Approving a derived item (a widget, a saved view)
     * also activates the grants of every Dataset it reads, atomically (the closure travels together — §3.5,
     * generalized by D9).
     */
    public ShareGrant approve(String id, String approver) {
        synchronized (LOCK) {
            ShareGrant g = transition(id, ShareGrant.REQUESTED, ShareGrant.ACTIVE, approver, true);
            if (DERIVED_KINDS.contains(g.kind())) {
                for (String ds : boundDatasets(g.owner(), g.kind(), g.item())) {
                    String dgid = ShareGrant.idFor("dataset", ds, g.owner(), g.consumer());
                    grant(dgid).filter(x -> ShareGrant.REQUESTED.equals(x.status()))
                            .ifPresent(x -> transition(dgid, ShareGrant.REQUESTED, ShareGrant.ACTIVE, approver, true));
                }
            }
            return g;
        }
    }

    /** Owner denies a pending request → {@code denied}. */
    public ShareGrant deny(String id, String approver) {
        return transition(id, ShareGrant.REQUESTED, ShareGrant.DENIED, approver, true);
    }

    /**
     * Owner revokes an active grant → {@code revoked}. Revoking a Dataset grant cascades: every active
     * derived grant that reads it (same owner+consumer) is revoked too — fail-closed (§3.5, generalized by
     * D9, so a saved view stops resolving the moment any one of its Datasets is revoked).
     */
    public ShareGrant revoke(String id, String actor) {
        synchronized (LOCK) {
            ShareGrant g = transition(id, ShareGrant.ACTIVE, ShareGrant.REVOKED, actor, false);
            if ("dataset".equals(g.kind())) {
                for (ShareGrant w : grants()) {
                    if (DERIVED_KINDS.contains(w.kind()) && ShareGrant.ACTIVE.equals(w.status())
                            && g.owner().equals(w.owner()) && g.consumer().equals(w.consumer())
                            && boundDatasets(w.owner(), w.kind(), w.item()).contains(g.item()))
                        transition(w.id(), ShareGrant.ACTIVE, ShareGrant.REVOKED, actor, false);
                }
            }
            return g;
        }
    }

    /**
     * Whether {@code consumer} may render owner's shared derived item ({@code kind} = widget or saved view)
     * — fail-closed: the item's own grant <em>and</em> a grant for <b>every</b> Dataset it reads must be
     * active (§3.5, generalized by D9). A derived item with no bound Dataset offer can never render shared,
     * so an empty closure is a denial, never a free pass.
     */
    public boolean canRender(String consumer, String owner, String kind, String item) {
        if (activeGrant(consumer, owner, kind, item).isEmpty()) return false;
        List<String> datasets = boundDatasets(owner, kind, item);
        return !datasets.isEmpty()
                && datasets.stream().allMatch(ds -> activeGrant(consumer, owner, "dataset", ds).isPresent());
    }

    /** Whether {@code consumer} may render owner's shared widget {@code item} — see {@link #canRender}. */
    public boolean canRenderWidget(String consumer, String owner, String item) {
        return canRender(consumer, owner, "widget", item);
    }

    /** The Datasets a derived offer reads ({@link Offer#datasets}); empty when unknown/unoffered. */
    private List<String> boundDatasets(String owner, String kind, String item) {
        return offer(owner, kind, item).map(Offer::datasets).orElse(List.of())
                .stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    /**
     * The effective delivery mode for a new grant. A saved view owns no rows of its own, so snapshot
     * delivery is meaningless for it: an explicit non-{@code live} mode is <b>rejected</b> rather than
     * silently treated as live (D9), and an omitted mode defaults to {@code live} instead of the Dataset
     * default. Enforced here in the ledger rather than only at the HTTP edge, so no caller can bypass it.
     */
    private static String effectiveMode(String kind, String mode) {
        boolean unset = mode == null || mode.isBlank();
        if (VIEW.equals(kind)) {
            if (!unset && !ShareGrant.LIVE.equals(mode))
                throw new IllegalArgumentException("a " + VIEW + " grant is live-mode only (mode '" + mode
                        + "' rejected: a saved view has no rows of its own to snapshot)");
            return ShareGrant.LIVE;
        }
        return unset ? ShareGrant.SNAPSHOT : mode;
    }

    /**
     * The <em>effective</em> grant for a {@code (kind, item, owner, consumer)} quad — present only when it is
     * {@code active} and not past its {@code expiresAt} (S3 expiry enforcement). The single fail-closed gate
     * every resolution path consults.
     */
    public Optional<ShareGrant> activeGrant(String consumer, String owner, String kind, String item) {
        return grant(ShareGrant.idFor(kind, item, owner, consumer)).filter(Exchange::effectivelyActive);
    }

    /**
     * Resolve an offered item's metadata for a consumer — fail-closed: returns the {@link Offer} only when an
     * effective ({@link #activeGrant active, unexpired}) grant exists. No grant (or a non-active/expired one)
     * ⇒ empty, even if the offer itself exists.
     */
    public Optional<Offer> resolveForConsumer(String consumer, String owner, String kind, String item) {
        return activeGrant(consumer, owner, kind, item).flatMap(g -> offer(owner, kind, item));
    }

    /** Set (or clear, with {@code null}) an active grant's expiry ({@code epoch millis}); owner governance (S3). */
    public ShareGrant setExpiry(String id, Long expiresAt) {
        return mutate(id, g -> new ShareGrant(g.id(), g.kind(), g.item(), g.owner(), g.consumer(),
                g.mode(), g.status(), g.requestedBy(), g.requestedAt(), g.purpose(),
                g.approvedBy(), g.approvedAt(), g.pin(), expiresAt));
    }

    /** Set (or clear, with {@code null}) a grant's version pin — snapshot resolution then serves that version (S3). */
    public ShareGrant setPin(String id, String version) {
        return mutate(id, g -> new ShareGrant(g.id(), g.kind(), g.item(), g.owner(), g.consumer(),
                g.mode(), g.status(), g.requestedBy(), g.requestedAt(), g.purpose(),
                g.approvedBy(), g.approvedAt(), (version == null || version.isBlank()) ? null : version, g.expiresAt()));
    }

    /** True when a grant is active and not past its expiry. */
    private static boolean effectivelyActive(ShareGrant g) {
        return ShareGrant.ACTIVE.equals(g.status())
                && (g.expiresAt() == null || g.expiresAt() > System.currentTimeMillis());
    }

    // ── internals ────────────────────────────────────────────────────────────────

    private ShareGrant transition(String id, String from, String to, String actor, boolean stampApproval) {
        requireEnabled();
        synchronized (LOCK) {
            ShareGrant g = grant(id).orElseThrow(() -> new NoSuchElementException("no such grant '" + id + "'"));
            if (!from.equals(g.status()))
                throw new IllegalStateException(
                        "cannot move grant '" + id + "' from " + g.status() + " to " + to + " (expected " + from + ")");
            ShareGrant next = new ShareGrant(g.id(), g.kind(), g.item(), g.owner(), g.consumer(),
                    g.mode(), to, g.requestedBy(), g.requestedAt(), g.purpose(),
                    stampApproval ? actor : g.approvedBy(),
                    stampApproval ? System.currentTimeMillis() : g.approvedAt(),
                    g.pin(), g.expiresAt());
            upsert(next);
            return next;
        }
    }

    /** Apply {@code fn} to an existing grant and persist the result (used by the field setters). */
    private ShareGrant mutate(String id, java.util.function.UnaryOperator<ShareGrant> fn) {
        requireEnabled();
        synchronized (LOCK) {
            ShareGrant g = grant(id).orElseThrow(() -> new NoSuchElementException("no such grant '" + id + "'"));
            ShareGrant next = fn.apply(g);
            upsert(next);
            return next;
        }
    }

    /** Replace-or-append a grant by id (call under {@link #LOCK}). */
    private void upsert(ShareGrant grant) {
        List<ShareGrant> kept = new ArrayList<>(grants().stream()
                .filter(g -> !g.id().equals(grant.id())).toList());
        kept.add(grant);
        Ledger.write(root.resolve("grants.toon"), GRANTS, kept.stream().map(ShareGrant::toMap).toList());
    }
}
