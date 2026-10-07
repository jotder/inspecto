package com.gamma.exchange;

import com.gamma.control.HostContext;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.RouteModule;
import com.gamma.control.WorkingSetWidgets;

import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.sun.net.httpserver.HttpExchange;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The Exchange — installation-scope, <b>un-prefixed</b> routes for cross-Space Dataset/Widget/saved-View
 * sharing (design-of-record {@code docs/superpower/storage-layout-and-sharing-plan.md} §3; the saved-view
 * kind was added by BACKLOG D9). Like {@link SpaceRoutes} these address the {@code spaces/_shared/} surface
 * rather than one Space's engine, so they fall through {@code ControlApi.dispatch}'s {@code /spaces/{id}}
 * seam untouched.
 *
 * <pre>
 *   GET  /exchange/offers[?owner=]                 the shareable catalog (metadata only, never rows)
 *   POST /exchange/offers                          owner lists/updates an offer        [canOfferDatasets]
 *   POST /exchange/signal-offers                   owner offers a Signal type          [canOfferSignals]
 *   POST /exchange/requests                        consumer requests use               [canRequestShares]
 *   POST /exchange/grants/{id}/{approve|deny|revoke}  owner acts on a grant            [canApproveShares]
 *   (every capability is checked in the OWNING Space's role table — owner, or consumer for request/pin)
 *   GET  /exchange/grants[?space=]                 the grant ledger (shared by/with a Space)
 *   GET  /exchange/datasets/{owner}/{item}[?consumer=]  one item's metadata (+ grant status)
 *   GET  /exchange/widgets/{owner}/{item}?consumer=  render-only view of a shared Widget
 *   GET  /exchange/views/{owner}/{item}?consumer=    render-only view of a shared saved View
 * </pre>
 *
 * <p>Fail-closed: every route 409s in single-tenant mode (no {@code _shared} dir, no one to share with —
 * mirroring {@link SpaceRoutes#requireMultiSpace}). Reads are open; writes are capability-gated
 * ({@link ApiContext#withCapability}) — a no-op on Personal edition (no {@link Subject}), enforced on
 * Standard. Every mutation emits an {@code EXCHANGE_*} signal (audit rides the central {@code AuditTrail}).
 */
/*
 * ⚠ Relocated from com.gamma.control (inspecto) on 2026-09-07, EDG-01 cell 4 — EDITIONS SEC-10 is "not for
 * Personal", and this class shipped in every bundle because it sat in the core. It reaches ControlApi only
 * through the public RouteModule SPI (META-INF/services), from a module the Personal build does not include,
 * and it installs the SharedRefResolver seam that used to be wired in ControlApi's constructor. It joined the
 * com.gamma.exchange package it already served, so no package is split across jars. Handlers are unchanged.
 */
public final class ExchangeRoutes implements RouteModule {

    @Override
    public java.util.Set<String> featureIds() {
        return java.util.Set.of("exchange");
    }

    @Override
    public void register(ApiContext api) {
        // The host-wide installs (SharedRefResolver, signal forwarder, SignalOfferGrants, SharedItemConsumers) live in
        // ExchangeBootHook, so register() only registers routes and runs on any ApiContext (MODULE-REORG-P5-TCKS).
        api.get("/exchange/offers", (e, m) -> listOffers(api, e));
        // 🔴 Every write is gated in the Space that OWNS what it acts on (EXCHANGE-OWNING-SPACE-AUTHZ-1).
        // These routes carry no /spaces/{id} prefix, so the attached Subject holds the DEFAULT Space's
        // grants: until 2026-09-24 a default-Space steward approved/revoked/offered for ANY Space, and the
        // owning Space's own approver was refused. Owner acts → the body's/grant's owner; consumer acts
        // (request, pin) → the consumer.
        api.post("/exchange/offers", ApiContext.withCapability("canOfferDatasets",
                (e, m) -> spaceRoles(api, bodySpace(api, e, "owner")),
                (e, m) -> putOffer(api, e)));
        // Cross-Space consequence D7: offering a Signal type is its own verb, not canOfferDatasets.
        api.post("/exchange/signal-offers", ApiContext.withCapability("canOfferSignals",
                (e, m) -> spaceRoles(api, signalBodySpace(api, e, "owner", "canOfferSignals")),
                (e, m) -> putSignalOffer(api, e)));
        api.post("/exchange/refresh", ApiContext.withCapability("canOfferDatasets",
                (e, m) -> spaceRoles(api, bodySpace(api, e, "owner")),
                (e, m) -> refresh(api, e)));
        api.post("/exchange/requests", ApiContext.withCapability("canRequestShares",
                (e, m) -> spaceRoles(api, signalBodySpace(api, e, "consumer", "canRequestShares")),
                (e, m) -> requestGrant(api, e)));
        api.post("/exchange/grants/([^/]+)/(approve|deny|revoke)", ApiContext.withCapability("canApproveShares",
                (e, m) -> spaceRoles(api, grantOf(api, ApiContext.name(m)).owner()),
                (e, m) -> actOnGrant(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
        api.post("/exchange/grants/([^/]+)/pin", ApiContext.withCapability("canRequestShares",
                (e, m) -> spaceRoles(api, grantOf(api, ApiContext.name(m)).consumer()),
                (e, m) -> pinGrant(api, e, ApiContext.name(m))));
        api.post("/exchange/grants/([^/]+)/expiry", ApiContext.withCapability("canApproveShares",
                (e, m) -> spaceRoles(api, grantOf(api, ApiContext.name(m)).owner()),
                (e, m) -> expireGrant(api, e, ApiContext.name(m))));
        api.get("/exchange/grants", (e, m) -> listGrants(api, e));
        api.get("/exchange/datasets/([^/]+)/([^/]+)", (e, m) ->
                datasetMeta(api, e, ApiContext.param(m, 1), ApiContext.param(m, 2)));
        api.get("/exchange/widgets/([^/]+)/([^/]+)", (e, m) ->
                widgetRender(api, e, ApiContext.param(m, 1), ApiContext.param(m, 2)));
        api.get("/exchange/views/([^/]+)/([^/]+)", (e, m) ->
                viewRender(api, e, ApiContext.param(m, 1), ApiContext.param(m, 2)));
    }

    // ── offers ─────────────────────────────────────────────────────────────────

    private Object listOffers(ApiContext api, HttpExchange e) {
        Exchange ex = requireExchange(api);
        String owner = ApiContext.query(e, "owner");
        return ex.offers().stream()
                .filter(o -> owner == null || owner.equals(o.owner()))
                .map(o -> withFreshness(ex, o.toMap(), o.owner(), o.item()))
                .toList();
    }

    /** Refresh an offered Dataset's Exchange snapshot from the owner's current data (S2 snapshot mode). */
    private Object refresh(ApiContext api, HttpExchange e) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Map<String, Object> body = api.body(e);
        String owner = requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String item  = requireItem(body);
        if (ex.offer(owner, "dataset", item).isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no offered dataset " + owner + "/" + item);
        SpaceContext ctx = HostContext.of(api).spaces().space(SpaceId.of(owner))
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + owner + "'"));
        java.nio.file.Path config = ctx.root().config();
        if (config == null) throw new ApiException(409, ErrorCodes.CONFLICT, "space '" + owner + "' has no registry");
        try {
            ExchangeSnapshots.SnapshotMeta meta = ExchangeSnapshotWriter.publish(
                    ex.dir(), owner, config.resolve("registry"),
                    java.nio.file.Path.of(ctx.root().dataDir()), ctx.root().base().resolve("views"), item);
            Event.Builder b = Event.builder(EventType.EXCHANGE_REFRESHED).source("exchange")
                    .message("refreshed dataset " + owner + "/" + item + " → " + meta.version())
                    .actor(ApiContext.actor(e)).actorType("user")
                    .attr("owner", owner).attr("kind", "dataset").attr("item", item)
                    .attr("version", meta.version()).attr("rows", meta.rows());
            EventLog.current().emit(b);
            return meta.toMap();
        } catch (IllegalArgumentException bad) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        } catch (Exception fail) {
            throw com.gamma.control.ServerFaults.internal("snapshot failed", fail);
        }
    }

    /** Merge the live snapshot's freshness into an offer/metadata map, when a snapshot has been published. */
    private static Map<String, Object> withFreshness(Exchange ex, Map<String, Object> out, String owner, String item) {
        ExchangeSnapshots.readCurrent(ExchangeSnapshots.itemDir(ex.dir(), owner, item))
                .ifPresent(meta -> out.put("freshness", meta.toMap()));
        return out;
    }

    private Object putOffer(ApiContext api, HttpExchange e) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Map<String, Object> body = api.body(e);
        String kind  = requireKind(body);
        if (Exchange.SIGNAL.equals(kind))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST,
                    "a Signal type is offered through POST /exchange/signal-offers (capability canOfferSignals)");
        String owner = requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String item  = requireItem(body);
        ComponentStore registry = ownerRegistry(api, owner);
        // The offered component must actually exist in the owner Space's registry (cross-Space read is
        // legitimate here — the Exchange is the one surface that spans Spaces).
        ComponentRegistry.Component component = registry.get(kind, item)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no " + kind + " '" + item + "' in space '" + owner + "'"));

        // A derived item (a Widget, a saved View) shares render-only, and the grants of every Dataset it
        // reads travel with it (§3.5, generalized by D9): each must already be offered by the same owner.
        java.util.List<String> datasets = java.util.List.of();
        // LA-21 / D-E6: a Working Set Widget never leaves its Space through the Exchange (the reason differs by mode).
        if ("widget".equals(kind)) {
            java.util.Optional<String> refusal = WorkingSetWidgets.exchangeRefusal(item, component.content());
            if (refusal.isPresent()) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refusal.get());
        }
        if (Exchange.isDerived(kind)) {
            datasets = boundDatasetsOf(kind, component.content());
            if (datasets.isEmpty())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, noBindingMessage(kind, item, component.content()));
            for (String ds : datasets)
                if (ex.offer(owner, "dataset", ds).isEmpty())
                    throw new ApiException(409, ErrorCodes.CONFLICT, "offer the " + kind + "'s dataset '" + ds
                            + "' before the " + kind);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resultSet = body.get("resultSet") instanceof Map<?, ?> rs
                ? (Map<String, Object>) rs : Map.of();
        Offer offer = new Offer(kind, item, owner, ApiContext.str(body, "description"),
                resultSet, ApiContext.actor(e), System.currentTimeMillis(), datasets);
        ex.putOffer(offer);
        signal(e, EventType.EXCHANGE_OFFERED, "offered " + kind + " " + owner + "/" + item,
                owner, null, kind, item);
        return offer.toMap();
    }

    /**
     * {@code POST /exchange/signal-offers} — the owner Space announces a Signal type another Space may ask to
     * receive. {@code payloadKeys} is the allowlist of payload keys that cross (D5, empty by default: then
     * only the fact that the Signal happened crosses). Nothing is delivered until a consumer requests and the
     * owner approves (two-party consent, D2). Exact types only — a {@code prefix.*} offer is deferred.
     */
    private Object putSignalOffer(ApiContext api, HttpExchange e) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Map<String, Object> body = api.body(e);
        String owner = requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String type = ApiContext.str(body, "item");
        if (type == null || !type.matches(SIGNAL_TYPE))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST,
                    "'item' must be a dotted Signal type (e.g. fraud.alert)");
        if (type.startsWith("exchange."))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "a delivered Signal (exchange.*) cannot be re-offered: a Signal crosses one grant, never relayed");
        java.util.List<String> keys = new java.util.ArrayList<>();
        Object raw = body.get("payloadKeys");
        if (raw != null && !(raw instanceof java.util.List<?>))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'payloadKeys' must be a list of payload keys");
        if (raw instanceof java.util.List<?> l)
            for (Object k : l) {
                String key = k == null ? null : k.toString();
                if (key == null || !key.matches(PAYLOAD_KEY))
                    throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "invalid payload key '" + key + "'");
                if (!keys.contains(key)) keys.add(key);
            }
        Offer offer = new Offer(Exchange.SIGNAL, type, owner, ApiContext.str(body, "description"), Map.of(),
                ApiContext.actor(e), System.currentTimeMillis(), java.util.List.of(), keys);
        ex.putOffer(offer);
        signal(e, EventType.EXCHANGE_OFFERED, "offered signal " + owner + "/" + type, owner, null, Exchange.SIGNAL, type);
        return offer.toMap();
    }

    /** A dotted Signal type: lower-case segments of the component-id charset. */
    static final String SIGNAL_TYPE = "[a-z0-9][a-z0-9_-]*(\\.[a-z0-9_-]+)*";
    /** A payload key an offer may allowlist. */
    static final String PAYLOAD_KEY = "[A-Za-z_][A-Za-z0-9_-]{0,63}";

    // ── grant lifecycle ──────────────────────────────────────────────────────────

    private Object requestGrant(ApiContext api, HttpExchange e) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Map<String, Object> body = api.body(e);
        String kind     = requireKind(body);
        String owner    = Exchange.SIGNAL.equals(kind)
                ? requireSignalSpace(api, ApiContext.str(body, "owner"), "owner", "canRequestShares")
                : requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String consumer = requireSpace(api, ApiContext.str(body, "consumer"), "consumer");
        String item     = requireItem(body);
        if (owner.equals(consumer))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "a space cannot request a share from itself");
        if (ex.offer(owner, kind, item).isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no offer for " + kind + " " + owner + "/" + item);
        try {
            ShareGrant g = ex.request(kind, item, owner, consumer, ApiContext.actor(e),
                    ApiContext.str(body, "purpose"), ApiContext.str(body, "mode"));
            signal(e, EventType.EXCHANGE_REQUESTED, "requested " + kind + " " + owner + "/" + item,
                    owner, consumer, kind, item);
            return g.toMap();
        } catch (IllegalStateException conflict) {
            throw new ApiException(409, ErrorCodes.CONFLICT, conflict.getMessage());
        } catch (IllegalArgumentException bad) {
            // e.g. mode 'snapshot' on a saved view — meaningless, so rejected rather than coerced to live.
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
        }
    }

    private Object actOnGrant(ApiContext api, HttpExchange e, String id, String action) {
        Exchange ex = requireExchange(api);
        String actor = ApiContext.actor(e);
        try {
            ShareGrant g = switch (action) {
                case "approve" -> ex.approve(id, actor);
                case "deny"    -> ex.deny(id, actor);
                case "revoke"  -> ex.revoke(id, actor);
                default        -> throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "unknown grant action '" + action + "'");
            };
            String type = switch (action) {
                case "approve" -> EventType.EXCHANGE_GRANTED;
                case "deny"    -> EventType.EXCHANGE_DENIED;
                default        -> EventType.EXCHANGE_REVOKED;
            };
            signal(e, type, action + "d " + g.kind() + " " + g.owner() + "/" + g.item(),
                    g.owner(), g.consumer(), g.kind(), g.item());
            return g.toMap();
        } catch (NoSuchElementException notFound) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, notFound.getMessage());
        } catch (IllegalStateException conflict) {
            throw new ApiException(409, ErrorCodes.CONFLICT, conflict.getMessage());
        }
    }

    /** Consumer sets/clears a version pin on its grant — snapshot resolution then serves that version (S3). */
    private Object pinGrant(ApiContext api, HttpExchange e, String id) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        try {
            return ex.setPin(id, ApiContext.str(api.body(e), "version")).toMap();
        } catch (NoSuchElementException nf) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, nf.getMessage());
        }
    }

    /** Owner sets/clears a grant's expiry (epoch millis; null/absent clears) — governance (S3). */
    private Object expireGrant(ApiContext api, HttpExchange e, String id) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Object v = api.body(e).get("expiresAt");
        Long expiresAt;
        if (v == null) expiresAt = null;
        else if (v instanceof Number n) expiresAt = n.longValue();
        else {
            try {
                expiresAt = Long.parseLong(v.toString().trim());
            } catch (NumberFormatException bad) {
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'expiresAt' must be epoch millis");
            }
        }
        try {
            return ex.setExpiry(id, expiresAt).toMap();
        } catch (NoSuchElementException nf) {
            throw new ApiException(404, ErrorCodes.NOT_FOUND, nf.getMessage());
        }
    }

    private Object listGrants(ApiContext api, HttpExchange e) {
        Exchange ex = requireExchange(api);
        String space = ApiContext.query(e, "space");
        var grants = (space == null ? ex.grants() : ex.grantsForSpace(space));
        return grants.stream().map(ShareGrant::toMap).toList();
    }

    private Object datasetMeta(ApiContext api, HttpExchange e, String owner, String item) {
        Exchange ex = requireExchange(api);
        Offer offer = ex.offer(owner, "dataset", item)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no offered dataset " + owner + "/" + item));
        Map<String, Object> out = withFreshness(ex, new LinkedHashMap<>(offer.toMap()), owner, item);
        String consumer = ApiContext.query(e, "consumer");
        if (consumer != null)
            ex.grant(ShareGrant.idFor("dataset", item, owner, consumer))
                    .ifPresent(g -> out.put("grant", g.toMap()));
        return out;
    }

    /**
     * Render-only view of a shared Widget for a consumer — fail-closed on {@link Exchange#canRenderWidget}
     * (both the widget grant and its bound-Dataset grant must be active). Returns the widget's (immutable)
     * config plus the {@code shared/<owner>/<dataset>} ref the consumer binds through — so the UI can place
     * and render it read-only, and degrade to an "access revoked" empty-state the moment the grant drops.
     */
    private Object widgetRender(ApiContext api, HttpExchange e, String owner, String item) {
        Exchange ex = requireExchange(api);
        String consumer = ApiContext.query(e, "consumer");
        if (consumer == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'consumer' query param is required");
        if (!ex.canRenderWidget(consumer, owner, item))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "no active grant to render widget " + owner + "/" + item);
        ComponentRegistry.Component c = ownerRegistry(api, owner).get("widget", item)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no widget '" + item + "' in space '" + owner + "'"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("item", item);
        out.put("content", c.content());
        out.put("readOnly", true);
        ex.offer(owner, "widget", item).map(Offer::datasets).stream().flatMap(java.util.List::stream)
                .findFirst().ifPresent(ds -> out.put("dataset", "shared/" + owner + "/" + ds));
        return out;
    }

    /**
     * Render-only view of a shared saved View for a consumer — fail-closed on {@link Exchange#canRender}
     * (the view's own grant <em>and</em> every one of its Datasets' grants must be active — D9). Returns the
     * view's (immutable) config with its {@code query} roots rewritten to the {@code shared/<owner>/<dataset>}
     * refs the consumer resolves through, so the UI can load it read-only and degrade to an "access revoked"
     * empty-state the moment any grant in the closure drops.
     */
    private Object viewRender(ApiContext api, HttpExchange e, String owner, String item) {
        Exchange ex = requireExchange(api);
        String consumer = ApiContext.query(e, "consumer");
        if (consumer == null) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'consumer' query param is required");
        if (!ex.canRender(consumer, owner, Exchange.VIEW, item))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "no active grant to render view " + owner + "/" + item);
        ComponentRegistry.Component c = ownerRegistry(api, owner).get(Exchange.VIEW, item)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no view '" + item + "' in space '" + owner + "'"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("owner", owner);
        out.put("item", item);
        out.put("content", withSharedDatasets(owner, c.content()));
        out.put("readOnly", true);
        out.put("datasets", ex.offer(owner, Exchange.VIEW, item).map(Offer::datasets)
                .orElse(java.util.List.of()).stream().map(ds -> "shared/" + owner + "/" + ds).toList());
        return out;
    }

    /**
     * A copy of a saved view's content whose projection-mapping {@code datasetId}s are rewritten to
     * {@code shared/<owner>/<id>} consumer refs — the same plain-string ref shape a local Dataset uses, so
     * {@code POST /inv/projection} takes it unchanged and it stays grant-checked by
     * {@link ExchangeRefResolver} (fail-closed: revoke the Dataset grant and the ref stops resolving).
     */
    private static Map<String, Object> withSharedDatasets(String owner, Map<String, Object> content) {
        Map<String, Object> out = new LinkedHashMap<>(content);
        if (!(content.get("query") instanceof Map<?, ?> q)) return out;
        Map<String, Object> query = new LinkedHashMap<>();
        q.forEach((k, v) -> query.put(String.valueOf(k), v));
        if (query.get("projection") instanceof Map<?, ?> one)
            query.put("projection", sharedMapping(owner, one));
        if (query.get("projections") instanceof java.util.List<?> l)
            query.put("projections", l.stream()
                    .map(p -> p instanceof Map<?, ?> pm ? sharedMapping(owner, pm) : p).toList());
        out.put("query", query);
        return out;
    }

    /** One projection mapping with its {@code datasetId} pointed at the consumer's shared ref. */
    private static Map<String, Object> sharedMapping(String owner, Map<?, ?> mapping) {
        Map<String, Object> out = new LinkedHashMap<>();
        mapping.forEach((k, v) -> out.put(String.valueOf(k), v));
        String ds = scalar(out.get("datasetId"));
        if (ds != null) out.put("datasetId", "shared/" + owner + "/" + ds);
        return out;
    }

    /**
     * The Datasets a derived component reads. A Widget binds exactly one, found as the first
     * {@code dataset}/{@code datasetId} key anywhere in its content tree.
     *
     * <p>A saved View is Dataset-backed <b>only</b> for the {@code entity-projection} source, and there the
     * Datasets are its <em>projection mappings</em> — {@code query.projections[].datasetId} (multi-mapping,
     * which is exactly how one view legitimately reads several Datasets) falling back to the single
     * {@code query.projection.datasetId}. ⚠ <b>Not</b> {@code query.roots}/{@code query.from}: those are the
     * {@code lineage}/{@code provenance} sources' shape, and their roots are catalog assets and Pipelines
     * respectively — neither is a Dataset the Exchange can grant. A view on any other source therefore
     * yields an empty closure and is refused at offer time rather than shared with a closure that could
     * never be enforced.
     */
    private static java.util.List<String> boundDatasetsOf(String kind, Map<String, Object> content) {
        if (!Exchange.VIEW.equals(kind)) {
            String one = findKey(content, java.util.Set.of("dataset", "datasetId"));
            return one == null ? java.util.List.of() : java.util.List.of(one);
        }
        if (!"entity-projection".equals(scalar(content.get("sourceId")))) return java.util.List.of();
        if (!(content.get("query") instanceof Map<?, ?> query)) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        if (query.get("projections") instanceof java.util.List<?> l)
            for (Object p : l)
                if (p instanceof Map<?, ?> pm) {
                    String ds = scalar(pm.get("datasetId"));
                    if (ds != null) out.add(ds);
                }
        if (out.isEmpty() && query.get("projection") instanceof Map<?, ?> pm) {
            String ds = scalar(pm.get("datasetId"));
            if (ds != null) out.add(ds);
        }
        return out.stream().distinct().toList();
    }

    /** Why a derived component could not be offered — a view on the wrong source needs its own explanation. */
    private static String noBindingMessage(String kind, String item, Map<String, Object> content) {
        if (!Exchange.VIEW.equals(kind))
            return kind + " '" + item + "' has no dataset binding to share";
        String source = scalar(content.get("sourceId"));
        if (!"entity-projection".equals(source))
            return "only an entity-projection saved view can be shared (its mappings name Datasets); view '"
                    + item + "' reads the '" + (source == null ? "unset" : source)
                    + "' source, whose roots are Pipelines/catalog assets the Exchange cannot grant";
        return "saved view '" + item + "' has no dataset mappings "
                + "(query.projection/query.projections) to share";
    }

    /** A non-blank trimmed scalar, or {@code null} — a {@code datasetId} or {@code sourceId}. */
    private static String scalar(Object v) {
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String findKey(Object node, java.util.Set<String> keys) {
        if (node instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> en : m.entrySet()) {
                if (keys.contains(String.valueOf(en.getKey())) && en.getValue() != null) {
                    String v = en.getValue().toString();
                    if (!v.isBlank()) return v;
                }
            }
            for (Object v : m.values()) {
                String r = findKey(v, keys);
                if (r != null) return r;
            }
        } else if (node instanceof java.util.List<?> l) {
            for (Object v : l) {
                String r = findKey(v, keys);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** The Exchange for this installation, or a 409 in single-tenant mode (fail-closed). */
    private static Exchange requireExchange(ApiContext api) {
        Exchange ex = Exchange.under(HostContext.of(api).spaces().containerRoot());
        if (!ex.enabled())
            throw new ApiException(409, ErrorCodes.CONFLICT, "cross-space sharing needs the multi-space runtime (-Dspaces.root)");
        return ex;
    }

    /** The grant a grant-scoped route acts on — 404 when absent (it has no owner to authorize against). */
    private static ShareGrant grantOf(ApiContext api, String id) {
        return requireExchange(api).grant(id)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no such grant '" + id + "'"));
    }

    /** The validated, hosted Space a body field names (the handler's own 400/404, raised before the gate). */
    private static String bodySpace(ApiContext api, HttpExchange e, String field) throws java.io.IOException {
        requireExchange(api);
        return requireSpace(api, ApiContext.str(api.body(e), field), field);
    }

    /**
     * {@link #bodySpace} for a route that may carry the {@code signal} kind: on that kind a Space that is not
     * hosted answers exactly like the capability gate's refusal (403, same code and message), so the signal
     * surface is not an oracle for which Spaces exist (D12). Other kinds keep their 404.
     */
    private static String signalBodySpace(ApiContext api, HttpExchange e, String field, String capability)
            throws java.io.IOException {
        requireExchange(api);
        Map<String, Object> body = api.body(e);
        boolean signal = e.getRequestURI().getPath().endsWith("/signal-offers")
                || Exchange.SIGNAL.equals(ApiContext.str(body, "kind"));
        return signal ? requireSignalSpace(api, ApiContext.str(body, field), field, capability)
                : requireSpace(api, ApiContext.str(body, field), field);
    }

    private static String requireSignalSpace(ApiContext api, String id, String field, String capability) {
        if (id == null || !SpaceId.isValid(id))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'" + field + "' must be a valid space id");
        if (HostContext.of(api).spaces().space(SpaceId.of(id)).isEmpty()) throw notPermitted(capability);
        return id;
    }

    /** The capability gate's own refusal, verbatim ({@code ApiContext.requireCapabilityIn}). */
    static ApiException notPermitted(String capability) {
        return new ApiException(403, ErrorCodes.PERMISSION_DENIED,
                "missing capability '" + capability + "' in the owning space");
    }

    /** The config root holding {@code space}'s role table — what its capability gate is decided by. */
    private static java.nio.file.Path spaceRoles(ApiContext api, String space) {
        SpaceContext ctx = HostContext.of(api).spaces().space(SpaceId.of(space))
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + space + "'"));
        java.nio.file.Path config = ctx.root().config();
        if (config == null) throw new ApiException(409, ErrorCodes.CONFLICT, "space '" + space + "' has no config root");
        return config;
    }

    private static ComponentStore ownerRegistry(ApiContext api, String owner) {
        SpaceContext ctx = HostContext.of(api).spaces().space(SpaceId.of(owner))
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + owner + "'"));
        java.nio.file.Path config = ctx.root().config();
        if (config == null) throw new ApiException(409, ErrorCodes.CONFLICT, "space '" + owner + "' has no registry");
        return new ComponentStore(config.resolve("registry"));
    }

    private static String requireKind(Map<String, Object> body) {
        String kind = ApiContext.str(body, "kind");
        if (!"dataset".equals(kind) && !"widget".equals(kind) && !Exchange.VIEW.equals(kind) && !Exchange.SIGNAL.equals(kind))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'kind' must be 'dataset', 'widget', '"
                    + Exchange.VIEW + "' or '" + Exchange.SIGNAL + "'");
        return kind;
    }

    /** Validate a body {@code item} id (component-id charset) — the offered/requested component name. */
    private static String requireItem(Map<String, Object> body) {
        String item = ApiContext.str(body, "item");
        if (item == null || item.contains("..") || !item.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'item' must be a valid component id");
        return item;
    }

    /** Validate a space id from the body exists as a hosted Space. */
    private static String requireSpace(ApiContext api, String id, String field) {
        if (id == null || !SpaceId.isValid(id))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'" + field + "' must be a valid space id");
        if (HostContext.of(api).spaces().space(SpaceId.of(id)).isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + id + "'");
        return id;
    }

    /** Emit an {@code EXCHANGE_*} lifecycle signal (the audit trail is recorded centrally by dispatch). */
    private static void signal(HttpExchange e, String type, String message,
                               String owner, String consumer, String kind, String item) {
        Event.Builder b = Event.builder(type).source("exchange").message(message)
                .actor(ApiContext.actor(e)).actorType("user")
                .attr("owner", owner).attr("kind", kind).attr("item", item);
        if (consumer != null) b.attr("consumer", consumer);
        EventLog.current().emit(b);
    }
}
