package com.gamma.la.api;

import com.gamma.access.WriteGates;
import com.gamma.audit.Event;
import com.gamma.audit.EventSink;
import com.gamma.entitystore.EntityTypes;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LinkAnalysisSettings;
import com.gamma.entitystore.MaskTokens;
import com.gamma.la.core.InvestigationEvaluator;
import com.gamma.la.core.InvestigationStore;
import com.gamma.la.core.InvestigationStores;
import com.gamma.la.core.LinkEventTypes;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Third-party integration, INBOUND (LA-EDITIONS-1 plan §4, phase 5; roadmap I5): another product opens work in Inspecto LA.
 *
 * <ul>
 *   <li>{@code POST /inv/inbound/investigations} — a fraud product or case manager hands over its own record (an external
 *       reference {@code {system, type?, id, url?, label?}}) and the entities it is about ({@code "<type>:<key>"}, typed per the
 *       Space's Entity Types); Inspecto LA opens an Investigation seeded with them and answers its id and an
 *       "Open in Inspecto LA" link. Idempotent on {@code (system, id)}: a repeat answers the SAME Investigation.</li>
 *   <li>{@code POST /inv/deep-links/resolve} — validates an "Open in Inspecto LA" link's parameters ({@code entity},
 *       {@code window}, {@code investigation}) for the SPA's {@code /open} page, and reveals a masked entity only on request
 *       to a holder of {@code canRevealLinkEntities}.</li>
 * </ul>
 *
 * <p><b>Nothing new is trusted.</b> The inbound route is a composition of routes that already exist, through their own gates:
 * the Investigation is made by {@link InvestigationRoutes#create} (purpose, R3 Dataset gate, columns), seeded by
 * {@link InvestigationRoutes#appendOpOn} (the {@code seed} op, then an optional {@code window} op) and labelled by
 * {@link InvestigationReferenceRoutes#add} (the reference is caller text, never fetched, {@code trusted:false}).
 *
 * <p><b>Idempotency without a lock.</b> The Investigation id is DERIVED from the reference:
 * {@code ext-<first 32 hex of sha256(system \n id)>}. The store creates a header CREATE_NEW, so two racing calls for one
 * reference cannot both create - on any node, either store backend - and the loser reads as a repeat. A repeat that finds the
 * Investigation unseeded or unlabelled (its first call died between steps) completes it; one that finds it seeded changes
 * nothing, whatever entities it carries.
 *
 * <p><b>Auth.</b> {@code canManageIncidents}, as every Investigation write. The calling product authenticates like any other
 * Subject through the installed {@code Authenticator} (an OIDC service-account token works as-is); the creating Subject
 * becomes the lead. Machine-to-machine client-credentials trust is the outbound lane's and the security review's concern.
 */
public final class InvestigationInboundRoutes implements RouteModule {

    /** Gated by the geoLink feature: a Space whose modules.toon disables it answers 404 MODULE_DISABLED here. */
    @Override
    public Set<String> featureIds() {
        return Set.of("geoLink", "investigations");
    }

    /** The la-app route the links name (the console mounts the same page at {@code /studio/open}). */
    static final String OPEN_PATH = "/open";
    private static final Set<String> BINDING_KEYS = Set.of("dataset", "sourceCol", "targetCol", "linkKindCol", "timeCol",
            "timeColZone", "eventsCol");

    private final InvestigationRoutes investigations = new InvestigationRoutes();

    @Override
    public void register(ApiContext api) {
        // ⚠ String LITERALS on purpose — CapabilityManifestTest's scanner matches only a literal argument.
        api.post("/inv/inbound/investigations", ApiContext.withCapability("canManageIncidents",
                (e, m) -> inbound(api, e, api.body(e))));
        api.post("/inv/deep-links/resolve", (e, m) -> resolve(api, e, api.body(e)));
    }

    // ── POST /inv/inbound/investigations ──────────────────────────────────────────────────────────────────

    /**
     * Body {@code {reference: {system, type?, id, url?, label?}, entities: ["<type>:<key>", ...], purpose, title?,
     * window?: {from?, to?, ...}, binding?: {dataset, sourceCol, targetCol, linkKindCol?, timeCol?, timeColZone?, eventsCol?}}}.
     * Gates: {@code canManageIncidents} 403 → write root 503 → a malformed reference, entity or window 422 → then, only when the
     * reference is new: a malformed binding, or (no binding) no link mapping binds the first entity's type 422 → the create's own
     * gates; and for a repeat: an Investigation the caller cannot change 409.
     */
    private Object inbound(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "inbound investigation");
        LaLimitValues lim = SpaceLimits.of(writeRoot);
        Map<String, Object> reference = reference(body, lim);
        List<TypedEntity> entities = entities(writeRoot, body.get("entities"), SpaceLimits.get(lim, LaLimit.INPUT_MAX_IDS));
        Map<String, Object> window = body.get("window") == null ? null : InvestigationRoutes.window(body.get("window"), "window", lim);

        String system = String.valueOf(reference.get("system")), externalId = String.valueOf(reference.get("id"));
        String id = idFor(system, externalId);
        InvestigationStore store = InvestigationStores.of(writeRoot);
        boolean created = false;
        if (store.header(id).isEmpty()) {   // a repeat never re-derives a binding: the first call's stands
            Map<String, Object> create = createBody(ex, writeRoot, body, entities, window != null);
            create.put("id", id);
            try {
                investigations.create(api, ex, create);
                created = true;
            } catch (ApiException e) {
                if (e.status != 409) throw e;   // a racing call for the same reference created it first: a repeat
            }
        }
        InvestigationRoutes.Inv inv;
        try {
            inv = InvestigationRoutes.open(api, ex, id);
        } catch (ApiException e) {
            if (e.status != 404 && e.status != 403) throw e;
            throw new ApiException(409, ErrorCodes.CONFLICT, "external reference " + system + "/" + externalId
                    + " already opened an Investigation that you cannot change");
        }
        boolean seeded = !inv.store().log(InvestigationStore.Scope.main(id)).isEmpty();
        if (!seeded) {
            Map<String, List<String>> byType = new LinkedHashMap<>();
            for (TypedEntity t : entities) byType.computeIfAbsent(t.type().id(), k -> new ArrayList<>()).add(t.key());
            for (var e : byType.entrySet()) {
                Map<String, Object> op = new LinkedHashMap<>();
                op.put("op", "seed");
                op.put("ids", e.getValue());
                op.put("entityType", e.getKey());
                investigations.appendOpOn(api, ex, InvestigationRoutes.open(api, ex, id), op);
            }
            if (window != null)
                investigations.appendOpOn(api, ex, InvestigationRoutes.open(api, ex, id), Map.of("op", "window", "window", body.get("window")));
        }
        boolean labelled = false;
        for (Map<String, Object> r : InvestigationReferenceRoutes.parse(inv.store().references(id)))
            if (system.equals(r.get("system")) && externalId.equals(r.get("id"))) labelled = true;
        if (!labelled) InvestigationReferenceRoutes.add(api, ex, id, reference);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigationId", id);
        out.put("created", created);
        out.put("seeded", seeded ? 0 : entities.size());
        out.put("reference", Map.of("system", system, "type", reference.get("type"), "id", externalId));
        out.put("link", OPEN_PATH + "?investigation=" + URLEncoder.encode(id, StandardCharsets.UTF_8));
        out.put("note", created ? "opened" : "this external reference already opened this Investigation - nothing was added");
        return out;
    }

    /** The Investigation id a reference derives: stable, store-safe, and one per {@code (system, id)}. */
    static String idFor(String system, String externalId) {
        String hex = InvestigationEvaluator.sha256(system + "\n" + externalId);   // "sha256:<64 hex>"
        return "ext-" + hex.substring(hex.indexOf(':') + 1, hex.indexOf(':') + 33);
    }

    /** The reference, validated as {@code POST …/references} validates it, BEFORE anything is written. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> reference(Map<String, Object> body, LaLimitValues lim) {
        if (!(body.get("reference") instanceof Map<?, ?> raw))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'reference': {system, type?, id, url?, label?} "
                    + "- the calling system's own record");
        Map<String, Object> r = new LinkedHashMap<>((Map<String, Object>) raw);
        if (r.get("type") == null) r.put("type", "alert");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("system", InvestigationReferenceRoutes.name(r, "system"));
        out.put("type", InvestigationReferenceRoutes.name(r, "type"));
        out.put("id", InvestigationReferenceRoutes.text(r, "id", SpaceLimits.get(lim, LaLimit.INPUT_REFERENCE_ID_LENGTH), true));
        String url = InvestigationReferenceRoutes.text(r, "url", SpaceLimits.get(lim, LaLimit.INPUT_REFERENCE_URL_LENGTH), false);
        if (url != null) {
            InvestigationReferenceRoutes.checkUrl(url);
            out.put("url", url);
        }
        String label = InvestigationReferenceRoutes.text(r, "label", SpaceLimits.get(lim, LaLimit.INPUT_REFERENCE_LABEL_LENGTH), false);
        if (label != null) out.put("label", label);
        return out;
    }

    /** One {@code "<type>:<key>"} resolved: the Entity Type in force and the normalised key the seed carries. */
    record TypedEntity(EntityTypes.EntityType type, String key, boolean masked, String shown) { }

    private static List<TypedEntity> entities(Path writeRoot, Object raw, int max) {
        if (!(raw instanceof List<?> l) || l.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "body must include 'entities': a non-empty list of \"<type>:<key>\"");
        if (l.size() > max) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + max + " entities");
        List<TypedEntity> out = new ArrayList<>();
        int i = 0;
        for (Object o : l) {
            TypedEntity t = typed(writeRoot, o, "entities[" + i++ + "]", true);
            if (out.stream().noneMatch(x -> x.type().id().equals(t.type().id()) && x.key().equals(t.key()))) out.add(t);
        }
        return out;
    }

    /**
     * Parse and resolve {@code "<type>:<key>"}: the type must be an Entity Type in force; the key is a raw value or an exploration
     * alias ({@code masked:<16 hex>}) and must normalise to something. {@code rawAllowedWhenMasked} false refuses a raw key of a
     * masked type (a link travels in a URL, where a raw identifier never goes). A refusal never repeats the key.
     */
    static TypedEntity typed(Path writeRoot, Object raw, String where, boolean rawAllowedWhenMasked) {
        if (!(raw instanceof String s) || s.isBlank())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + " must be a string \"<type>:<key>\"");
        int colon = s.indexOf(':');
        if (colon <= 0 || colon == s.length() - 1)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + " must be \"<type>:<key>\", an Entity Type id, a colon and the key");
        String typeId = s.substring(0, colon).trim(), key = s.substring(colon + 1).trim();
        LinkAnalysisSettings settings = LinkAnalysisSettings.forRoot(writeRoot);
        List<EntityTypes.EntityType> types = settings.effectiveEntityTypes();
        EntityTypes.EntityType type = types.stream().filter(t -> t.id().equalsIgnoreCase(typeId)).findFirst()
                .orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + ": unknown Entity Type '"
                        + (typeId.length() > 64 ? typeId.substring(0, 64) : typeId) + "' - one of "
                        + types.stream().map(EntityTypes.EntityType::id).toList()));
        if (key.length() > SpaceLimits.get(writeRoot, LaLimit.INPUT_ID_LENGTH))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + ": the key is at most "
                    + SpaceLimits.get(writeRoot, LaLimit.INPUT_ID_LENGTH) + " chars");
        String mode = settings.effectiveMaskingMode();
        boolean masked = EntityProfiles.maskedUnder(mode, type);
        boolean alias = key.startsWith(MaskTokens.TOKEN_PREFIX);
        if (masked && !alias && !rawAllowedWhenMasked)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + ": Entity Type '" + type.id() + "' is masked, so a link "
                    + "names it by its alias (masked:<16 hex>), never by a raw value - get the alias from POST /inv/entities/" + type.id()
                    + "/resolve first");
        String value = alias ? ExplorationMasking.forced(writeRoot, mode, "entity key").in(key) : key;
        String normalised = EntityTypes.normalise(type.normaliser(), value);
        if (normalised.isEmpty())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, where + ": the key normalises to nothing under '"
                    + type.normaliser() + "' - it is not an Entity of type '" + type.id() + "'");
        String shown = !masked ? normalised : alias ? key : ExplorationMasking.alias(writeRoot, normalised);
        return new TypedEntity(type, normalised, masked, shown);
    }

    /**
     * The create body: the caller's {@code binding} when given, else the first link mapping the Space knows (a saved Link
     * Analysis view or a published index, the order {@link EntityTypeBindings} reads them) with the first entity's type bound
     * at a link end. Its kind and time columns come along; a {@code window} needs a time column.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> createBody(HttpExchange ex, Path writeRoot, Map<String, Object> body, List<TypedEntity> entities,
                                                  boolean windowed) {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("purpose", body.get("purpose"));
        create.put("title", body.get("title"));
        if (body.get("binding") != null) {
            if (!(body.get("binding") instanceof Map<?, ?> b))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'binding' must be an object {dataset, sourceCol, targetCol, ...}");
            for (Object k : b.keySet())
                if (!BINDING_KEYS.contains(String.valueOf(k)))
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'binding': unknown key '" + k + "' (allowed: " + BINDING_KEYS + ")");
            create.putAll((Map<String, Object>) b);
        } else {
            List<String> named = entities.stream().map(t -> t.type().id()).distinct().toList();
            String typeId = named.get(0);
            List<EntityTypes.EntityType> types = LinkAnalysisSettings.forRoot(writeRoot).effectiveEntityTypes();
            EntityTypeBindings.Resolved resolved = EntityTypeBindings.resolve(ex, writeRoot, types);
            EntityTypeBindings.LinkMapping chosen = null;
            for (EntityTypeBindings.LinkMapping m : resolved.mappings()) {
                EntityTypes.EntityType src = resolved.typeOf(m.dataset(), m.source()), tgt = resolved.typeOf(m.dataset(), m.target());
                if (named.stream().allMatch(n -> src != null && src.id().equals(n) || tgt != null && tgt.id().equals(n))) {
                    chosen = m;   // one mapping binds EVERY type the call names
                    break;
                }
            }
            if (chosen == null)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, named.size() == 1
                        ? "no Link Analysis view or index of this Space links a column of Entity Type '" + typeId
                                + "' - send 'binding': {dataset, sourceCol, targetCol}"
                        : "no single Link Analysis view or index of this Space links a column of EACH Entity Type the call names " + named
                                + " (an Investigation has one link mapping) - name one type per call, or send 'binding': {dataset, sourceCol, targetCol}");
            create.put("dataset", chosen.dataset());
            create.put("sourceCol", chosen.source());
            create.put("targetCol", chosen.target());
            if (chosen.kind() != null) create.put("linkKindCol", chosen.kind());
            String time = chosen.time();
            if (time == null)
                for (EntityTypeBindings.Binding b : resolved.of(typeId))
                    if (b.dataset().equals(chosen.dataset()) && b.timeColumn() != null) time = b.timeColumn();
            if (time != null) create.put("timeCol", time);
            EntityTypes.EntityType src = resolved.typeOf(chosen.dataset(), chosen.source()), tgt = resolved.typeOf(chosen.dataset(), chosen.target());
            if (src != null) create.put("sourceType", src.id());
            if (tgt != null) create.put("targetType", tgt.id());
        }
        if (windowed && create.get("timeCol") == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a 'window' needs a time column - the binding has none"
                    + (body.get("binding") != null ? "; add 'timeCol' to it" : ""));
        return create;
    }

    // ── POST /inv/deep-links/resolve ──────────────────────────────────────────────────────────────────────

    /**
     * Body {@code {entity?: "<type>:<key>", investigation?: id, window?: {from?, to?}, reveal?: boolean}} - the parameters of an
     * "Open in Inspecto LA" link, sent in the BODY so the server never logs them in a URL. Answers {@code {entity?: {type, key,
     * masked, revealed?}, investigation?: {id, title}, window?}}. Gates: write root 503 → neither entity nor investigation, a
     * malformed entity, a raw key of a masked type, an alias this server never minted or a bad window 422 → an Investigation the
     * caller cannot read 404 (absence) → {@code reveal} without {@code canRevealLinkEntities} 403. A reveal is audited as
     * {@code LINK_ENTITY_REVEALED} naming the alias, never the raw value. Reads no Dataset and persists nothing.
     */
    private static Object resolve(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "deep link");
        Object entity = body.get("entity"), invId = body.get("investigation");
        if (isBlank(entity) && isBlank(invId))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "a link names an 'entity' (\"<type>:<key>\"), an 'investigation', or both");
        Map<String, Object> out = new LinkedHashMap<>();
        TypedEntity t = isBlank(entity) ? null : typed(writeRoot, entity, "entity", false);
        if (body.get("window") != null)
            out.put("window", InvestigationRoutes.window(body.get("window"), "window", SpaceLimits.of(writeRoot)));
        if (!isBlank(invId)) {
            if (!(invId instanceof String s)) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'investigation' must be a string");
            InvestigationRoutes.Inv inv = InvestigationRoutes.openForRead(api, ex, s.trim());
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("id", inv.id());
            Object title = inv.header().get("title");
            i.put("title", title == null ? null : EntityMasking.of(inv, List.of()).inText(String.valueOf(title)));
            out.put("investigation", i);
        }
        if (t != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", t.type().id());
            e.put("key", t.shown());
            e.put("masked", t.masked());
            if (Boolean.TRUE.equals(body.get("reveal")) && t.masked()) {
                ApiContext.requireCapability(ex, "canRevealLinkEntities");
                e.put("revealed", t.key());
                try {
                    EventSink.current().emit(Event.builder(LinkEventTypes.LINK_ENTITY_REVEALED).source("inv")
                            .message("link.entity.revealed - deep link, 1 entity").actor(ApiContext.actor(ex))
                            .actorType(ApiContext.actorType(ex)).action("link.entity.revealed").actionCategory("analysis")
                            .attr("via", "deep-link").attr("tokens", t.shown()).attr("count", 1));
                } catch (RuntimeException ignored) {
                    // best effort, as every Link Analysis audit
                }
            }
            out.put("entity", e);
        }
        return out;
    }

    private static boolean isBlank(Object o) {
        return o == null || o instanceof String s && s.isBlank();
    }
}
