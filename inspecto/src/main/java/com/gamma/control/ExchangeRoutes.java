package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.exchange.Exchange;
import com.gamma.exchange.ExchangeSnapshots;
import com.gamma.exchange.ExchangeSnapshotWriter;
import com.gamma.exchange.Offer;
import com.gamma.exchange.ShareGrant;
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
 *   POST /exchange/requests                        consumer requests use               [canRequestShares]
 *   POST /exchange/grants/{id}/{approve|deny|revoke}  owner acts on a grant            [canApproveShares]
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
final class ExchangeRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/exchange/offers", (e, m) -> listOffers(api, e));
        api.post("/exchange/offers", ApiContext.withCapability("canOfferDatasets",
                (e, m) -> putOffer(api, e)));
        api.post("/exchange/refresh", ApiContext.withCapability("canOfferDatasets",
                (e, m) -> refresh(api, e)));
        api.post("/exchange/requests", ApiContext.withCapability("canRequestShares",
                (e, m) -> requestGrant(api, e)));
        api.post("/exchange/grants/([^/]+)/(approve|deny|revoke)", ApiContext.withCapability("canApproveShares",
                (e, m) -> actOnGrant(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
        api.post("/exchange/grants/([^/]+)/pin", ApiContext.withCapability("canRequestShares",
                (e, m) -> pinGrant(api, e, ApiContext.name(m))));
        api.post("/exchange/grants/([^/]+)/expiry", ApiContext.withCapability("canApproveShares",
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
            throw new ApiException(404, "no offered dataset " + owner + "/" + item);
        SpaceContext ctx = api.spaces().space(SpaceId.of(owner))
                .orElseThrow(() -> new ApiException(404, "no such space '" + owner + "'"));
        java.nio.file.Path config = ctx.root().config();
        if (config == null) throw new ApiException(409, "space '" + owner + "' has no registry");
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
            throw new ApiException(422, bad.getMessage());
        } catch (Exception fail) {
            throw new ApiException(500, "snapshot failed: " + fail.getMessage());
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
        String owner = requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String item  = requireItem(body);
        ComponentStore registry = ownerRegistry(api, owner);
        // The offered component must actually exist in the owner Space's registry (cross-Space read is
        // legitimate here — the Exchange is the one surface that spans Spaces).
        ComponentRegistry.Component component = registry.get(kind, item)
                .orElseThrow(() -> new ApiException(404, "no " + kind + " '" + item + "' in space '" + owner + "'"));

        // A derived item (a Widget, a saved View) shares render-only, and the grants of every Dataset it
        // reads travel with it (§3.5, generalized by D9): each must already be offered by the same owner.
        java.util.List<String> datasets = java.util.List.of();
        if (Exchange.isDerived(kind)) {
            datasets = boundDatasetsOf(kind, component.content());
            if (datasets.isEmpty())
                throw new ApiException(422, noBindingMessage(kind, item, component.content()));
            for (String ds : datasets)
                if (ex.offer(owner, "dataset", ds).isEmpty())
                    throw new ApiException(409, "offer the " + kind + "'s dataset '" + ds
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

    // ── grant lifecycle ──────────────────────────────────────────────────────────

    private Object requestGrant(ApiContext api, HttpExchange e) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        Map<String, Object> body = api.body(e);
        String kind     = requireKind(body);
        String owner    = requireSpace(api, ApiContext.str(body, "owner"), "owner");
        String consumer = requireSpace(api, ApiContext.str(body, "consumer"), "consumer");
        String item     = requireItem(body);
        if (owner.equals(consumer))
            throw new ApiException(400, "a space cannot request a share from itself");
        if (ex.offer(owner, kind, item).isEmpty())
            throw new ApiException(404, "no offer for " + kind + " " + owner + "/" + item);
        try {
            ShareGrant g = ex.request(kind, item, owner, consumer, ApiContext.actor(e),
                    ApiContext.str(body, "purpose"), ApiContext.str(body, "mode"));
            signal(e, EventType.EXCHANGE_REQUESTED, "requested " + kind + " " + owner + "/" + item,
                    owner, consumer, kind, item);
            return g.toMap();
        } catch (IllegalStateException conflict) {
            throw new ApiException(409, conflict.getMessage());
        } catch (IllegalArgumentException bad) {
            // e.g. mode 'snapshot' on a saved view — meaningless, so rejected rather than coerced to live.
            throw new ApiException(422, bad.getMessage());
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
                default        -> throw new ApiException(400, "unknown grant action '" + action + "'");
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
            throw new ApiException(404, notFound.getMessage());
        } catch (IllegalStateException conflict) {
            throw new ApiException(409, conflict.getMessage());
        }
    }

    /** Consumer sets/clears a version pin on its grant — snapshot resolution then serves that version (S3). */
    private Object pinGrant(ApiContext api, HttpExchange e, String id) throws java.io.IOException {
        Exchange ex = requireExchange(api);
        try {
            return ex.setPin(id, ApiContext.str(api.body(e), "version")).toMap();
        } catch (NoSuchElementException nf) {
            throw new ApiException(404, nf.getMessage());
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
                throw new ApiException(400, "'expiresAt' must be epoch millis");
            }
        }
        try {
            return ex.setExpiry(id, expiresAt).toMap();
        } catch (NoSuchElementException nf) {
            throw new ApiException(404, nf.getMessage());
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
                .orElseThrow(() -> new ApiException(404, "no offered dataset " + owner + "/" + item));
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
        if (consumer == null) throw new ApiException(400, "'consumer' query param is required");
        if (!ex.canRenderWidget(consumer, owner, item))
            throw new ApiException(403, "no active grant to render widget " + owner + "/" + item);
        ComponentRegistry.Component c = ownerRegistry(api, owner).get("widget", item)
                .orElseThrow(() -> new ApiException(404, "no widget '" + item + "' in space '" + owner + "'"));
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
        if (consumer == null) throw new ApiException(400, "'consumer' query param is required");
        if (!ex.canRender(consumer, owner, Exchange.VIEW, item))
            throw new ApiException(403, "no active grant to render view " + owner + "/" + item);
        ComponentRegistry.Component c = ownerRegistry(api, owner).get(Exchange.VIEW, item)
                .orElseThrow(() -> new ApiException(404, "no view '" + item + "' in space '" + owner + "'"));
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
        Exchange ex = Exchange.under(api.spaces().containerRoot());
        if (!ex.enabled())
            throw new ApiException(409, "cross-space sharing needs the multi-space runtime (-Dspaces.root)");
        return ex;
    }

    private static ComponentStore ownerRegistry(ApiContext api, String owner) {
        SpaceContext ctx = api.spaces().space(SpaceId.of(owner))
                .orElseThrow(() -> new ApiException(404, "no such space '" + owner + "'"));
        java.nio.file.Path config = ctx.root().config();
        if (config == null) throw new ApiException(409, "space '" + owner + "' has no registry");
        return new ComponentStore(config.resolve("registry"));
    }

    private static String requireKind(Map<String, Object> body) {
        String kind = ApiContext.str(body, "kind");
        if (!"dataset".equals(kind) && !"widget".equals(kind) && !Exchange.VIEW.equals(kind))
            throw new ApiException(400, "'kind' must be 'dataset', 'widget' or '" + Exchange.VIEW + "'");
        return kind;
    }

    /** Validate a body {@code item} id (component-id charset) — the offered/requested component name. */
    private static String requireItem(Map<String, Object> body) {
        String item = ApiContext.str(body, "item");
        if (item == null || item.contains("..") || !item.matches("[A-Za-z0-9][A-Za-z0-9._-]*"))
            throw new ApiException(400, "'item' must be a valid component id");
        return item;
    }

    /** Validate a space id from the body exists as a hosted Space. */
    private static String requireSpace(ApiContext api, String id, String field) {
        if (id == null || !SpaceId.isValid(id))
            throw new ApiException(400, "'" + field + "' must be a valid space id");
        if (api.spaces().space(SpaceId.of(id)).isEmpty())
            throw new ApiException(404, "no such space '" + id + "'");
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
