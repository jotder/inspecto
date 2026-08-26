package com.gamma.control;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.safety.SchemaCompatibility;
import com.gamma.config.spec.ConfigSpec;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.ops.findings.FindingsSpec;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.MappingRules;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineReferences;
import com.gamma.pipeline.exec.ComponentPreview;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component registry CRUD + scratch preview/test ({@code /components*}, T19/T18, §7.1):
 * grammar/schema/transform/sink components under {@code <write-root>/registry}. Extracted verbatim
 * from {@link ControlApi}: identical routes, HTTP statuses and safe-delete semantics. Safe-delete
 * checks flow references via the shared {@link PipelineSupport#liftedPipelines} projection; previews run on a
 * throwaway DuckDB and never touch production output.
 */
final class ComponentRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(ComponentRoutes.class);

    @Override
    public void register(ApiContext api) {
        api.get("/components/([^/]+)", (e, m) -> componentList(api, e, ApiContext.name(m)));
        api.get("/components/([^/]+)/([^/]+)", (e, m) -> componentById(api, e, ApiContext.name(m), ApiContext.param(m, 2)));
        // Writes require canAuthorWorkbench (W6; a no-op on Personal — no Subject is ever attached there).
        api.post("/components/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> createComponent(api, e, ApiContext.name(m), api.body(e))));
        api.put("/components/([^/]+)/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> updateComponent(api, e, ApiContext.name(m), ApiContext.param(m, 2), api.body(e))));
        api.delete("/components/([^/]+)/([^/]+)", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> deleteComponent(api, e, ApiContext.name(m), ApiContext.param(m, 2))));
        // MET-5 version history: list prior saved copies + restore one (restore is an authoring write).
        api.get("/components/([^/]+)/([^/]+)/versions", (e, m) -> listVersions(api, e, ApiContext.name(m), ApiContext.param(m, 2)));
        api.post("/components/([^/]+)/([^/]+)/versions/([^/]+)/restore", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> restoreVersion(api, e, ApiContext.name(m), ApiContext.param(m, 2), ApiContext.param(m, 3))));
        // T18 dry-run/test: preview a component over a sample through the production logic (scratch-only).
        api.post("/components/transform/([^/]+)/test", (e, m) -> previewTransform(api, e, ApiContext.name(m), api.body(e)));
        api.post("/components/grammar/([^/]+)/test", (e, m) -> previewGrammar(api, e, ApiContext.name(m), api.body(e)));
        api.post("/components/sink/([^/]+)/test", (e, m) -> previewSink(api, e, ApiContext.name(m), api.body(e)));
        // The INLINE arm of the same previews: the body carries the config instead of naming a stored
        // component. A pipeline node authors its config inline (AUTHORED-vs-DERIVED), so without this
        // the only testable configs were the registered ones — the gap this closes. Literal paths, so
        // they cannot be reached by a component whose id happens to be "preview".
        api.post("/components/transform/preview", (e, m) -> previewInlineTransform(api.body(e)));
        api.post("/components/grammar/preview", (e, m) -> previewInlineGrammar(api.body(e)));
        api.post("/components/sink/preview", (e, m) -> previewInlineSink(api.body(e)));
        // S6b: validate mapping rules WITHOUT writing — the import loop's gate. Un-gated like the
        // /test previews (it reads nothing and writes nothing) and never touches the registry.
        api.post("/components/mapping/validate", (e, m) -> validateMapping(api.body(e)));
    }

    /**
     * {@code POST /components/mapping/validate} — check a draft mapping's {@code rules} against
     * {@link MappingRules} and return the findings, writing nothing (ELT amendment UI plan §2.5, S6b).
     * Findings are anchored to {@code rules[N].<key>} so the grid editor can mark the exact cell.
     * 400 if {@code rules} is absent or is not a list. Mirrors the {@code /validate} response shape.
     */
    @SuppressWarnings("unchecked")
    private Object validateMapping(Map<String, Object> body) {
        Object rulesObj = body.get("rules");
        if (!(rulesObj instanceof List<?> list))
            throw new ApiException(400, "body must include 'rules' (a list of mapping rules)");
        for (Object row : list) {
            if (!(row instanceof Map<?, ?>))
                throw new ApiException(400, "every entry of 'rules' must be an object");
        }
        List<Finding> findings = MappingRules.validate((List<Map<String, Object>>) rulesObj);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", "mapping");
        r.put("findings", findings);
        r.put("clean", findings.isEmpty());
        return r;
    }

    /** The registry root under the write root, or {@code null} when writes are disabled (no write root). */
    private Path componentRootOrNull(ApiContext api) {
        return api.writeRoot() == null ? null : api.writeRoot().resolve("registry");
    }

    private ComponentStore componentStore(ApiContext api) {
        return new ComponentStore(WriteGates.requireWriteRoot(api, "component write").resolve("registry"));
    }

    /** The JSON shape for one component: identity + version metadata (W3: contentHash/created/modified) + content. */
    private static Map<String, Object> componentDoc(ComponentRegistry.Component c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", c.type());
        m.put("name", c.name());
        m.put("ref", c.ref());
        m.put("contentHash", ContentHash.of(c.content()));   // = the ETag / optimistic-lock token
        addFileTimes(m, c.path());                           // created/modified from filesystem attrs
        m.put("content", c.content());
        return m;
    }

    /** Add ISO-8601 {@code created}/{@code modified} from the file's attributes ({@code null} if unavailable). */
    private static void addFileTimes(Map<String, Object> m, Path p) {
        String created = null, modified = null;
        try {
            var attrs = java.nio.file.Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class);
            created = attrs.creationTime().toInstant().toString();
            modified = attrs.lastModifiedTime().toInstant().toString();
        } catch (IOException | UnsupportedOperationException ignored) {
            // filesystem without creation time, or a transient read error — timestamps are best-effort
        }
        m.put("created", created);
        m.put("modified", modified);
    }

    /** {@code GET /components/{type}} — list components of a type (empty when no registry/write root).
     *  Components shared away from this subject are filtered out (R3 — same list contract as SEC-7d). */
    private Object componentList(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type) {
        Path root = componentRootOrNull(api);
        if (root == null) return List.of();
        try {
            return new ComponentStore(root).list(type).stream()
                    .filter(c -> ComponentAccess.canView(ex, c.content()))
                    .map(ComponentRoutes::componentDoc).toList();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /**
     * {@code GET /components/{type}/{id}} — one component; 404 if absent. Carries a strong {@code ETag}
     * (= the content hash); a matching {@code If-None-Match} yields {@code 304} (W3 caching).
     */
    private Object componentById(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id) throws IOException {
        Path root = componentRootOrNull(api);
        ComponentRegistry.Component c;
        try {
            c = root == null ? null : new ComponentStore(root).get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        ComponentAccess.requireView(ex, type, id, c.content());   // R3: shared-away ⇒ indistinguishable 404
        // SEC-7(b): the only verbs on a registry component are the Workbench-authoring family.
        ApiContext.resourcePermissions(ex, java.util.Set.of("canAuthorWorkbench"));
        String etag = ETags.of(ContentHash.of(c.content()));
        if (ETags.isFresh(ex, etag)) return ETags.notModified(ex, etag);
        ETags.set(ex, etag);
        return componentDoc(c);
    }

    /** {@code POST /components/{type}} — create a component (id from body {@code id}/{@code name}); 409 if it exists. */
    private Object createComponent(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, Map<String, Object> body) throws IOException {
        ComponentStore store = componentStore(api);
        String id = ApiContext.str(body, "id");
        if (id == null || id.isBlank()) id = ApiContext.str(body, "name");
        if (id == null || id.isBlank()) throw new ApiException(400, "body must include 'id' (or 'name')");
        if (componentExists(store, type, id))
            throw new ApiException(409, type + " component '" + id + "' already exists (use PUT to update)");
        // R3: validate the sharing envelope + stamp owner from the authenticated subject (provenance).
        return writeComponent(api, store, ex, type, id, ComponentAccess.onCreate(ex, body));
    }

    /**
     * {@code PUT /components/{type}/{id}} — replace a component; 404 if absent. Honours an optional
     * {@code If-Match} precondition against the current content hash → {@code 409 CONFLICT_STALE_VERSION}
     * on a stale write (W3 optimistic locking).
     */
    private Object updateComponent(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id, Map<String, Object> body) throws IOException {
        ComponentStore store = componentStore(api);
        ComponentRegistry.Component current = existing(store, type, id);
        if (current == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        // R3: edit access against the current envelope, carry owner/shares forward, owner-only envelope changes.
        Map<String, Object> merged = ComponentAccess.onUpdate(ex, type, id, current.content(), body);
        ETags.requireMatch(ex, ETags.of(ContentHash.of(current.content())));
        Object incompatible = schemaCompatibilityGate(ex, type, current.content(), merged);
        if (incompatible != null) return incompatible;
        return writeComponent(api, store, ex, type, id, merged);
    }

    /**
     * The BACKWARD-compatibility save-gate for a {@code schema} component — the other half of the parity
     * with {@code POST /config/write {type:"schema"}}, whose identical gate diffs old→new and 422s a
     * breaking edit (a removed field, a narrowed type, a moved selector) with cell-level findings.
     * Returns a 422 response to return, or {@code null} to proceed.
     *
     * <p><b>The override is a QUERY parameter, {@code ?compatibility=none}</b>, deliberately — not a body
     * key. A component body <em>is</em> the content, so a {@code compatibility} key placed there would be
     * persisted into the schema itself; {@code /config/write} can carry it in the body only because its
     * body is an envelope ({@code {type, config, …}}) around the draft.
     *
     * <p>⚠ Scoped to {@code updateComponent} on purpose, which is what makes the other two write paths
     * exempt for the right reasons rather than by oversight:
     * <ul>
     *   <li><b>create</b> has no previous version to be compatible WITH;</li>
     *   <li><b>restore</b> ({@code /versions/{v}/restore}) is a ROLLBACK — a recovery action, whose target
     *       was itself a valid schema. Gating it would let a bad edit lock an operator out of the version
     *       that fixes it. It still passes the structural + safety gates in {@link #validateKind}.</li>
     * </ul>
     */
    private static Object schemaCompatibilityGate(com.sun.net.httpserver.HttpExchange ex, String type,
                                                  Map<String, Object> current, Map<String, Object> draft)
            throws IOException {
        if (!"schema".equals(type)) return null;
        if ("none".equalsIgnoreCase(ApiContext.query(ex, "compatibility"))) return null;
        List<Finding> breaking = SchemaCompatibility.check(current, draft);
        if (breaking.isEmpty()) return null;
        return ApiContext.respondJson(ex, 422, Map.of(
                "type", type, "written", false,
                "error", "schema edit is not BACKWARD-compatible; not written"
                        + " (pass ?compatibility=none to override)",
                "findings", breaking));
    }

    /** The current component or {@code null}; maps a bad type to the standard 400. */
    private static ComponentRegistry.Component existing(ComponentStore store, String type, String id) {
        try {
            return store.get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** {@code DELETE /components/{type}/{id}} — safe-delete; 404 if absent, 409 if a flow references it. */
    private Object deleteComponent(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id) throws IOException {
        ComponentStore store = componentStore(api);
        ComponentRegistry.Component current = existing(store, type, id);
        if (current == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        ComponentAccess.requireDelete(ex, type, id, current.content());   // R3: shared ⇒ owner-only delete
        List<String> refs = PipelineReferences.referencedBy(type + "/" + id, PipelineSupport.liftedPipelines(api.service()));
        if (!refs.isEmpty())
            throw new ApiException(409, type + " component '" + id + "' is referenced by pipeline(s): "
                    + String.join(", ", refs));
        // Deletion fence extends to the Exchange: an offered item still shared with other Spaces cannot be
        // deleted out from under its consumers (fail-closed; revoke the grant(s) first).
        List<String> consumers = activeConsumers(api, type, id);
        if (!consumers.isEmpty())
            throw new ApiException(409, type + " component '" + id + "' is shared with space(s): "
                    + String.join(", ", consumers) + " — revoke the grant(s) first");
        boolean removed;
        try {
            removed = store.delete(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        return Map.of("type", type, "id", id, "deleted", true, "fileRemoved", removed);
    }

    /** {@code GET /components/{type}/{id}/versions} — prior saved copies, newest first (MET-5); 404 if absent. */
    private Object listVersions(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id) {
        Path root = componentRootOrNull(api);
        if (root == null) return List.of();
        ComponentStore store = new ComponentStore(root);
        ComponentRegistry.Component current = existing(store, type, id);
        if (current == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        ComponentAccess.requireView(ex, type, id, current.content());   // R3: history is as private as the doc
        try {
            return store.versions(type, id).stream().map(v -> versionDoc(type, id, v)).toList();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /**
     * {@code POST /components/{type}/{id}/versions/{v}/restore} — write an archived version back as the
     * current component (MET-5). The restore is itself a versioned write (the outgoing copy is archived).
     * 400 if {@code v} isn't an integer, 404 if the component or that version is absent.
     */
    private Object restoreVersion(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id, String versionStr) throws IOException {
        ComponentStore store = componentStore(api);
        int version;
        try {
            version = Integer.parseInt(versionStr);
        } catch (NumberFormatException e) {
            throw new ApiException(400, "version must be an integer, got '" + versionStr + "'");
        }
        ComponentRegistry.Component current = existing(store, type, id);
        if (current == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        Map<String, Object> content;
        try {
            content = store.versionContent(type, id, version).orElseThrow(
                    () -> new ApiException(404, "no version " + version + " of " + type + " component '" + id + "'"));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        // R3: a restore is an update — edit access, envelope carried forward, owner-only envelope changes.
        return writeComponent(api, store, ex, type, id, ComponentAccess.onUpdate(ex, type, id, current.content(), content));
    }

    /** The JSON shape for one archived version: identity + version metadata + the archived content. */
    private static Map<String, Object> versionDoc(String type, String id, ComponentStore.ComponentVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("version", v.version());
        m.put("savedAt", v.savedAt() == null ? null : v.savedAt().toString());
        m.put("contentHash", ContentHash.of(v.content()));
        m.put("content", v.content());
        return m;
    }

    /**
     * {@code POST /components/transform/{id}/test} — dry-run a transform component over {@code sampleRows}
     * through the production {@link com.gamma.pipeline.exec.RowShaper} on a throwaway DuckDB (T18, §7.2). 404 if
     * the component is absent, 422 if it is not a {@code transform.*} type, 400 on a bad sample / unsupported
     * operator. Never touches production output.
     */
    @SuppressWarnings("unchecked")
    private Object previewTransform(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String id, Map<String, Object> body) {
        Path root = componentRootOrNull(api);
        ComponentRegistry.Component c;
        try {
            c = root == null ? null : new ComponentStore(root).get("transform", id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no transform component '" + id + "'");
        ComponentAccess.requireView(ex, "transform", id, c.content());   // R3
        String type = ApiContext.str(c.content(), "type");
        if (type == null || !type.startsWith("transform."))
            throw new ApiException(422, "component '" + id + "' is not a transform ('type: transform.*' required)");

        PipelineNode node = new PipelineNode(id, type, c.content(), null);
        return RouteErrors.mapPreviewErrors(() -> ComponentPreview.transform(node, ApiContext.sampleRows(body)));
    }

    /**
     * {@code POST /components/grammar/{id}/test} — parse raw {@code sampleText} with a grammar component's CSV
     * dialect through the production {@code read_csv} on a throwaway DuckDB (T18, §7.2). 404 if absent, 400 on
     * empty input, 422 on a parse error. Never touches production output.
     */
    private Object previewGrammar(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String id, Map<String, Object> body) {
        ComponentRegistry.Component c = requireComponent(api, ex, "grammar", id);
        return RouteErrors.mapPreviewErrors(() -> ComponentPreview.grammar(c.content(), sampleText(body)));
    }

    /**
     * {@code POST /components/sink/{id}/test} — scratch-validate a sink component against {@code sampleRows}
     * (store/format/partition checks; row count + bounded sample, no write) (T18, §7.2). 404 if absent, 400 on
     * a bad sample.
     */
    private Object previewSink(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String id, Map<String, Object> body) {
        ComponentRegistry.Component c = requireComponent(api, ex, "sink", id);
        try {
            return ComponentPreview.sink(c.content(), ApiContext.sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /**
     * {@code POST /components/{family}/preview} — the same three previews as {@code /{id}/test}, but
     * over a config carried in the request body rather than one loaded from the registry.
     *
     * <p>The pipeline editor authors node configs INLINE; only a registered component could be tested,
     * so exactly the configs an operator is in the middle of writing were the ones they could not try.
     * These run the identical {@link ComponentPreview} entry points on a throwaway DuckDB and touch no
     * production output.
     *
     * <p>No write-root gate and no {@link ComponentAccess} check: nothing is read from the registry and
     * nothing is written, so there is no stored object to authorize — the caller is previewing a config
     * it already possesses. Same posture as {@code /components/mapping/validate}. 400 on a missing or
     * non-object {@code config}, 422 on a preview failure, exactly as the by-id arm.
     */
    private Object previewInlineTransform(Map<String, Object> body) {
        Map<String, Object> config = requireInlineConfig(body);
        String type = ApiContext.str(config, "type");
        if (type == null || !type.startsWith("transform."))
            throw new ApiException(422, "inline config is not a transform ('type: transform.*' required)");
        return RouteErrors.mapPreviewErrors(() -> ComponentPreview.transform(
                new PipelineNode(INLINE_ID, type, config, null), ApiContext.sampleRows(body)));
    }

    private Object previewInlineGrammar(Map<String, Object> body) {
        Map<String, Object> config = requireInlineConfig(body);
        return RouteErrors.mapPreviewErrors(() -> ComponentPreview.grammar(config, sampleText(body)));
    }

    private Object previewInlineSink(Map<String, Object> body) {
        Map<String, Object> config = requireInlineConfig(body);
        try {
            return ComponentPreview.sink(config, ApiContext.sampleRows(body));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** The node id an inline preview reports under — it has no identity of its own to borrow. */
    private static final String INLINE_ID = "(inline)";

    /** The {@code config} object every inline preview requires, or the standard 400. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireInlineConfig(Map<String, Object> body) {
        Object cfg = body.get("config");
        if (!(cfg instanceof Map<?, ?>))
            throw new ApiException(400, "body must include 'config' (the node config object to preview)");
        return (Map<String, Object>) cfg;
    }

    /** Load a component by {@code type}/{@code id} or fail with the standard 400/404 (shared by the preview handlers). */
    private ComponentRegistry.Component requireComponent(ApiContext api, com.sun.net.httpserver.HttpExchange ex, String type, String id) {
        Path root = componentRootOrNull(api);
        ComponentRegistry.Component c;
        try {
            c = root == null ? null : new ComponentStore(root).get(type, id).orElse(null);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        if (c == null) throw new ApiException(404, "no " + type + " component '" + id + "'");
        ComponentAccess.requireView(ex, type, id, c.content());   // R3
        return c;
    }

    /** Extract raw {@code sampleText} from a request body (the text a grammar would parse); empty if absent. */
    private static String sampleText(Map<String, Object> body) {
        Object t = body.get("sampleText");
        return t == null ? "" : t.toString();
    }

    /** Consumer Spaces holding an <em>active</em> Exchange grant on {@code type/id} owned by the bound Space. */
    private static List<String> activeConsumers(ApiContext api, String type, String id) {
        com.gamma.exchange.Exchange ex = com.gamma.exchange.Exchange.under(api.spaces().containerRoot());
        if (!ex.enabled()) return List.of();
        String owner = com.gamma.event.EventLog.currentSpaceId();
        return ex.grants().stream()
                .filter(g -> com.gamma.exchange.ShareGrant.ACTIVE.equals(g.status())
                        && type.equals(g.kind()) && id.equals(g.item()) && owner.equals(g.owner()))
                .map(com.gamma.exchange.ShareGrant::consumer)
                .toList();
    }

    private static boolean componentExists(ComponentStore store, String type, String id) {
        try {
            return store.exists(type, id);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    /** Write a component: the body is the content (the routing-only {@code id} key is stripped); 422 on bad input.
     *  The written resource's new {@code ETag} rides the response so a client can chain a conditional update. */
    private Object writeComponent(ApiContext api, ComponentStore store, com.sun.net.httpserver.HttpExchange ex, String type, String id, Map<String, Object> body) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>(body);
        content.remove("id");   // routing key, not content (the store stamps name=id)
        try {
            validateKind(type, id, content);
            // D7 (c): a widget's `tags` array is a projection of the assignment store, so it is derived
            // here rather than taken from the body — adopted on create, overwritten on update.
            WidgetTags.project(api, type, id, content, !componentExists(store, type, id),
                    name -> TagRoutes.ensureTag(api, name));
            ComponentRegistry.Component c = store.write(type, id, content);
            log.info("[COMPONENT-WRITE] wrote {}", c.ref());
            ETags.set(ex, ETags.of(ContentHash.of(c.content())));
            return componentDoc(c);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, e.getMessage());
        }
    }

    /**
     * Per-kind content validation, for the kinds that have a model to validate against. Fail-closed at
     * <b>authoring</b> time — the caller maps {@link IllegalArgumentException} to 422 — rather than leaving a
     * malformed component to degrade at render/dispatch time. Kinds with no model stay unconstrained, so
     * this is a hook, not a schema registry.
     */
    private static void validateKind(String type, String id, Map<String, Object> content) {
        if ("findings-spec".equals(type)) {
            // The store stamps name=id, and GET /findings/{type} resolves by that id, so validate the
            // content as it will be persisted and refuse a spec whose objectType disagrees with its id.
            Map<String, Object> stamped = new LinkedHashMap<>(content);
            stamped.put("name", id);
            String declared = ApiContext.str(content, "objectType");
            if (declared != null && !declared.trim().equalsIgnoreCase(id))
                throw new IllegalArgumentException("findings-spec objectType '" + declared
                        + "' must match the component id '" + id + "' (one spec per object type)");
            FindingsSpec.fromMap(stamped);
        }
        // A `schema` component is the SAME FILE the engine parses as a pipeline's schema:
        // `/components/schema/{id}` and `POST /config/write {type:"schema"}` both land on
        // `registry/schemas/<id>.toon`, which `PipelineConfigParser.resolveSchemaRef` loads for a
        // `schema_file: schema/<id>` ref. Only the /config/write side ran the structural + safety gates,
        // so this route was an ungated back door to a live, engine-executed artifact — the
        // gate-on-one-route-but-not-its-sibling shape. The gates below are exactly its sibling's.
        //
        // ⚠ The BACKWARD-compatibility gate is deliberately NOT mirrored here. /config/write pairs it
        // with a `compatibility:"none"` override in its body envelope; a component body IS the content,
        // so there is nowhere to put one, and a compat gate with no escape hatch would make this route
        // STRICTER than its sibling — refusing edits the config route allows. Closing that half needs an
        // override channel (a query parameter) and is an API-shape decision, not a patch.
        //
        // ⛔ Do NOT "fix" this by retiring `schema` from ComponentStore.WRITABLE_TYPES instead. The
        // InspectoTools javadoc claims that already happened; it has not, and it cannot be done blind:
        // `validateType` guards `list`/`read`/`versions` too, so dropping the type would break the
        // Components pane's reads, not just this write.
        if ("schema".equals(type)) {
            Map<String, Object> stamped = new LinkedHashMap<>(content);
            stamped.put("name", id);   // the store stamps it; validate what will actually be persisted
            ConfigSpec spec = ConfigSpecs.forType("schema");
            List<Finding> schemaFindings = new java.util.ArrayList<>(
                    ConfigLoader.filesystem().validate(spec, stamped));
            schemaFindings.addAll(ConfigSafetyValidator.check("schema", stamped, SafetyPolicy.defaultPolicy()));
            List<Finding> errors = schemaFindings.stream()
                    .filter(f -> f.severity() == Severity.ERROR).toList();
            if (!errors.isEmpty())
                throw new IllegalArgumentException("schema is invalid: " + errors.stream()
                        .map(f -> (f.fieldPath().isEmpty() ? "" : f.fieldPath() + ": ") + f.message())
                        .collect(java.util.stream.Collectors.joining("; ")));
        }

        // S6b: a mapping whose rules break a TransformCompiler precondition cannot run — it either
        // fails the batch or (CONCAT_DT without a separator) throws mid-materialize. Refuse it at
        // authoring time, so /components/mapping/validate is a PREVIEW of this gate, not the only one.
        if ("mapping".equals(type) && content.containsKey("rules")) {
            Object rules = content.get("rules");
            if (!(rules instanceof List<?> list))
                throw new IllegalArgumentException("mapping 'rules' must be a list");
            List<Map<String, Object>> typed = new java.util.ArrayList<>();
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m))
                    throw new IllegalArgumentException("every entry of mapping 'rules' must be an object");
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                typed.add(cast);
            }
            List<Finding> findings = MappingRules.validate(typed);
            if (!findings.isEmpty())
                throw new IllegalArgumentException("mapping rules are invalid: " + findings.stream()
                        .map(f -> (f.fieldPath().isEmpty() ? "" : f.fieldPath() + ": ") + f.message())
                        .collect(java.util.stream.Collectors.joining("; ")));
        }
    }
}
