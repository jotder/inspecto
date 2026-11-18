package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceLocations;
import com.gamma.service.SpaceManager;
import com.gamma.service.SpaceSamples;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-global, <b>un-prefixed</b> space-management CRUD — the one route group that addresses the
 * {@link SpaceManager container} itself rather than a single space's engine:
 * <pre>
 *   GET    /spaces                 list every hosted space's manifest                              [v4.7.0]
 *   GET    /spaces/_meta           server capabilities: {multiSpace} — true when CRUD is supported [v4.9.0]
 *   GET    /spaces/templates       the shipped template gallery (spaces/_templates/&lt;id&gt;/template.toon)
 *   POST   /spaces                 body {id, display_name?, description?, template?, loadSamples?} — create + boot
 *                                  a space, optionally seeded from a shipped template               [v4.7.0]
 *   POST   /spaces/import?id={id}  create + boot a new space seeded from an uploaded bundle zip    [v4.8.0]
 *   PUT    /spaces/{id}            body {display_name?, description?} — rename/re-describe a space  [v4.10.0]
 *   DELETE /spaces/{id}[?purge=]   deregister + drain a space; ?purge=true also deletes its files  [v4.7.0]
 *                                  (409 when ?purge=true would remove the last space dir on disk)
 *   POST   /spaces/_locations/check  body {path, kind: space|data, id?} - check a folder, write nothing
 *   POST   /spaces/attach          body {path, id?} - attach a Space folder kept elsewhere, live
 *   POST   /spaces/{id}/detach     stop a Space booted through its &lt;id&gt;.ref and drop the reference; files stay
 *   PUT    /spaces/{id}/data-root  body {path|null} - point a Space that holds no data at a data folder
 * </pre>
 *
 * <p>Space locations ({@code SPACE-LOCATIONS-1}): every route that takes a folder is {@code canAdminister} (403), needs
 * the multi-Space server (409), the launch setting {@code spaces.allowed.roots} (409 {@code SPACE_LOCATIONS_DISABLED}),
 * a well-formed body (422), then passes {@link SpaceLocations}' checks (403 outside the allowed folders, 409 otherwise).
 * {@code POST /spaces} takes the same {@code path} and {@code dataRoot}. {@code _locations} is an underscore sentinel
 * like {@code _meta}, because {@code /spaces/locations/check} would be taken for the Space {@code locations}; the
 * {@code /detach} and {@code /data-root} tails are kept server-global by {@code ControlApi.SPACE_PREFIX}.
 *
 * <p>{@code _meta} is a non-{@link SpaceId} sentinel (underscore is outside the id charset), so the
 * capability probe never collides with a real space. The UI reads it once at startup to decide whether to
 * show the space-switcher + CRUD — robust even when the space list is empty (a fresh discover-mode server).
 *
 * <p>({@code import} is therefore a reserved space id for the {@code POST} verb — {@code POST /spaces/import}
 * always creates-from-bundle; a space named {@code import} is still listable/deletable/scopable as normal.)
 *
 * <p>These coexist with the per-space request seam: {@code ControlApi.dispatch} only treats a path as
 * space-scoped when it has a trailing segment ({@code /spaces/{id}/<rest>}), so the bare {@code /spaces} and
 * {@code /spaces/{id}} forms fall through to the routes here. CRUD requires the multi-space (discover) runtime —
 * a single-tenant server ({@code SpaceManager.single}) rejects create/delete with {@code 409}. This is also the
 * natural seam for the future access-control / superuser editions (an SPI consulted here), with no core
 * {@code if(edition==…)}.
 */
final class SpaceRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        // POD-SCOPE-DIVERGENCE-1: this roster is the ANSWERING Pod's, not the fleet's — under
        // partitioning (C1) each Pod owns a subset of Spaces, so a reload landing elsewhere sees a
        // different list. ⚠ The declaration rides in the v1 envelope's metadata because this response is a
        // BARE ARRAY: there is nowhere in the body to put it without changing the type, which would break
        // every consumer doing `response.map(...)`.
        api.get("/spaces", (e, m) -> {
            ApiContext.podScoped(e);
            return HostContext.of(api).spaces().all().stream()
                    .filter(c -> SpaceAdmission.admits(e, c))   // a closed Space is listed only to the people it admits
                    .sorted(java.util.Comparator.comparing(c -> c.id().value()))
                    .map(c -> withLocation(api, e, c, withIntegrity(e, c, manifest(c))))
                    .toList();
        });

        // Server capability probe — lets the UI distinguish the discover (CRUD-capable) runtime from a
        // single-tenant server without inferring it from the (possibly empty) space list. See class javadoc.
        // locationsEnabled (SPACE-LOCATIONS-1): the launch setting spaces.allowed.roots is set, so the folder routes work.
        api.get("/spaces/_meta", (e, m) -> Map.of("multiSpace", HostContext.of(api).spaces().supportsCrud(),
                "locationsEnabled", !SpaceLocations.allowedRoots().isEmpty()));

        // The shipped-template gallery. Empty (not 409) on a single-tenant server — the UI hides the
        // gallery there, and an empty list is the honest capability answer either way.
        api.get("/spaces/templates", (e, m) -> HostContext.of(api).spaces().templates());

        // canAdminister ALWAYS, the zero-Space recovery create included (TEMPLATE-RECOVERY-IMPORT-GATE-1,
        // operator 2026-10-03; it replaces the 2026-09-15 "recovery needs no capability" decision). Recovery is
        // not bricked: with no Space hosted ControlApi.authenticate binds no roles root, so Roles.effective serves
        // the SEED table and an IdP subject in the `admin` role holds canAdminister (the bootstrap admin).
        api.post("/spaces", ApiContext.withCapability("canAdminister", (e, m) -> createSpace(api, e, api.body(e))));

        api.post("/spaces/import", ApiContext.withCapability("canAdminister", (e, m) -> importSpace(api, e)));

        api.put("/spaces/([^/]+)", ApiContext.withCapability("canAdminister",
                (e, m) -> updateSpace(api, api.body(e), ApiContext.name(m))));

        api.delete("/spaces/([^/]+)", ApiContext.withCapability("canAdminister",
                (e, m) -> deleteSpace(api, e, ApiContext.name(m))));

        // SPACE-LOCATIONS-1 (plan D3-D7): where a Space folder and its data folder are kept.
        api.post("/spaces/_locations/check", ApiContext.withCapability("canAdminister",
                (e, m) -> checkLocation(api, api.body(e))));

        api.post("/spaces/attach", ApiContext.withCapability("canAdminister",
                (e, m) -> attachSpace(api, e, api.body(e))));

        api.post("/spaces/([^/]+)/detach", ApiContext.withCapability("canAdminister",
                (e, m) -> detachSpace(api, ApiContext.name(m))));

        api.put("/spaces/([^/]+)/data-root", ApiContext.withCapability("canAdminister",
                (e, m) -> changeDataRoot(api, e, api.body(e), ApiContext.name(m))));
    }

    /** {@code {path, kind: "space"|"data", id?}}: every check with a plain reason; nothing is written. */
    private Object checkLocation(ApiContext api, Map<String, Object> body) {
        requireLocations(api);
        String kind = ApiContext.str(body, "kind");
        if (!"space".equals(kind) && !"data".equals(kind))
            throw unprocessable("'kind' must be \"space\" (a Space folder) or \"data\" (a data folder)");
        String id = optionalId(body);
        Path path = folder(body, "path");
        var checks = HostContext.of(api).spaces().checkLocation(path,
                "space".equals(kind) ? SpaceLocations.Use.SPACE : SpaceLocations.Use.DATA, id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", path.toString());
        out.put("kind", kind);
        out.put("ok", checks.stream().allMatch(SpaceLocations.Check::ok));
        out.put("checks", checks.stream().map(SpaceLocations.Check::toMap).toList());
        return out;
    }

    /** {@code {path, id?}}: attach the Space folder at {@code path} (its name is the id) and start it. */
    private Object attachSpace(ApiContext api, HttpExchange e, Map<String, Object> body) throws IOException {
        requireLocations(api);
        String id = optionalId(body);
        Path path = folder(body, "path");
        if (id != null && !id.equals(String.valueOf(path.getFileName())))
            throw unprocessable("the Space folder's name '" + path.getFileName() + "' must be the Space id '" + id + "'");
        try {
            SpaceContext ctx = HostContext.of(api).spaces().attach(path);
            return withLocation(api, e, ctx, manifest(ctx));
        } catch (SpaceLocations.Refused r) {
            throw refused(r);
        }
    }

    /** Stop a Space booted through its {@code <id>.ref} and drop the reference; its folders stay on disk. */
    private Object detachSpace(ApiContext api, String id) throws IOException {
        requireLocations(api);
        if (!SpaceId.isValid(id)) throw unprocessable("invalid Space id '" + id + "'");
        try {
            if (!HostContext.of(api).spaces().detach(SpaceId.of(id)))
                throw new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + id + "'");
        } catch (IllegalStateException inSpacesRoot) {
            throw new ApiException(409, ErrorCodes.CONFLICT, inSpacesRoot.getMessage());
        }
        return Map.of("id", id, "detached", true);
    }

    /** {@code {path}}: point a Space that holds no data at a data folder; {@code {path: null}} puts it back under its Space folder. */
    private Object changeDataRoot(ApiContext api, HttpExchange e, Map<String, Object> body, String id) throws IOException {
        requireLocations(api);
        if (!SpaceId.isValid(id)) throw unprocessable("invalid Space id '" + id + "'");
        if (body == null || !body.containsKey("path"))
            throw unprocessable("body must include 'path': a data folder, or null to keep the data under the Space folder");
        Path path = body.get("path") == null ? null : folder(body, "path");
        try {
            SpaceContext ctx = HostContext.of(api).spaces().changeDataRoot(SpaceId.of(id), path);
            if (ctx == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + id + "'");
            return withLocation(api, e, ctx, manifest(ctx));
        } catch (SpaceLocations.Refused r) {
            throw refused(r);
        }
    }

    /** The gates every folder-taking route shares after {@code canAdminister}: the multi-Space server, then the setting. */
    private static void requireLocations(ApiContext api) {
        requireMultiSpace(api);
        try {
            SpaceLocations.requireEnabled();
        } catch (SpaceLocations.Refused r) {
            throw refused(r);
        }
    }

    /** {@code field} of the body as a folder path (absolute, or relative to the app root); 422 when it is not one. */
    private static Path folder(Map<String, Object> body, String field) {
        Object v = body == null ? null : body.get(field);
        if (!(v instanceof String s)) throw unprocessable("'" + field + "' must be a folder path");
        try {
            return SpaceLocations.parse(s, field);
        } catch (IllegalArgumentException bad) {
            throw unprocessable(bad.getMessage());
        }
    }

    private static String optionalId(Map<String, Object> body) {
        Object v = body == null ? null : body.get("id");
        if (v == null) return null;
        if (!(v instanceof String s) || !SpaceId.isValid(s))
            throw unprocessable("'id' must be a valid Space id ([a-z0-9-], 1-63 chars, not starting with '-')");
        return s;
    }

    private static ApiException unprocessable(String message) {
        return new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, message);
    }

    private static ApiException refused(SpaceLocations.Refused r) {
        return new ApiException(r.status(), r.code(), r.getMessage());
    }

    /**
     * D8: {@code location {spaceFolder, dataFolder, referenced, holdsData}} on a Space row, only for a caller who may see
     * where things are on the server's disk ({@link ServerPaths#administers}); anyone else gets no paths.
     */
    private static Map<String, Object> withLocation(ApiContext api, HttpExchange e, SpaceContext ctx, Map<String, Object> m) {
        if (!ServerPaths.administers(e)) return m;
        Map<String, Object> loc = new LinkedHashMap<>();
        loc.put("spaceFolder", ctx.root().base().toString());
        loc.put("dataFolder", ctx.root().dataRoot().toString());
        loc.put("referenced", HostContext.of(api).spaces().isReferenced(ctx));
        loc.put("holdsData", SpaceManager.holdsData(ctx));
        m.put("location", loc);
        return m;
    }

    /** Create + boot a new space seeded from an uploaded bundle zip; the new id comes from {@code ?id=}. */
    private Object importSpace(ApiContext api, HttpExchange e) throws IOException {
        requireMultiSpace(api);
        String id = ApiContext.query(e, "id");
        if (id == null || !SpaceId.isValid(id))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "query param 'id' is required and must be a valid space id ([a-z0-9-], 1-63 chars)");
        try {
            return manifest(HostContext.of(api).spaces().createFromBundle(SpaceId.of(id), e.getRequestBody().readAllBytes(),
                    b -> {   // IMPORT-CONNECTION-JOB-GATE-1: each carried kind needs its own route's gate
                        ImportCapabilityGuard.checkFiles(e, b.configEntries(), true);   // a NEW Space may seed dedicated-only kinds
                        // a NEW Space: no stored rule, no live registry; carried Jobs server-stamped (MAINT-TASK-AUTHORITY-1)
                        return JobAuthority.stampBundle(e, DecisionRuleGuard.guardImport(e, null, b));
                    }, creator(e)));
        } catch (com.gamma.service.UnknownClassificationException unknown) {   // no Space and no directory were left
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, unknown.getMessage());
        } catch (IllegalArgumentException badBundle) {   // not a bundle / invalid manifest / zip-slip
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, badBundle.getMessage());
        } catch (IllegalStateException conflict) {       // id / directory already exists
            throw new ApiException(409, ErrorCodes.CONFLICT, conflict.getMessage());
        }
    }

    /** Create + boot a space from {@code {id, display_name?, description?, template?}} — a {@code template}
     *  seeds the new space from {@code spaces/_templates/<template>/} (400 when no such template ships). */
    private Object createSpace(ApiContext api, HttpExchange e, Map<String, Object> body) throws IOException {
        requireMultiSpace(api);
        String id = ApiContext.str(body, "id");
        if (id == null || !SpaceId.isValid(id))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include a valid 'id' ([a-z0-9-], 1-63 chars, not starting with '-')");
        String template = ApiContext.str(body, "template");
        boolean loadSamples = Boolean.TRUE.equals(body.get("loadSamples"));
        if (loadSamples) requireSamples(api, template);   // refused before anything is created
        // SPACE-LOCATIONS-1 D6: optional chosen folders, under the same gates as the other folder-taking routes
        Path folder = null, dataRoot = null;
        if (body.get("path") != null || body.get("dataRoot") != null) {
            requireLocations(api);
            if (body.get("path") != null) folder = folder(body, "path");
            if (body.get("dataRoot") != null) dataRoot = folder(body, "dataRoot");
            if (folder != null && !id.equals(String.valueOf(folder.getFileName())))
                throw unprocessable("the Space folder's name '" + folder.getFileName() + "' must be the Space id '" + id + "'");
        }
        try {
            SpaceContext ctx = template == null
                    ? HostContext.of(api).spaces().create(SpaceId.of(id),
                            ApiContext.str(body, "display_name"), ApiContext.str(body, "description"), creator(e),
                            folder, dataRoot)
                    : HostContext.of(api).spaces().createFromTemplate(SpaceId.of(id),
                            ApiContext.str(body, "display_name"), ApiContext.str(body, "description"), template,
                            base -> TemplateSeedGate.require(e, base,   // every seeded kind meets its own route
                                    HostContext.of(api).spaces().templateHasBasicTier(template)), creator(e),
                            folder, dataRoot);
            Map<String, Object> out = manifest(ctx);
            com.gamma.util.OnboardingCounters.record(com.gamma.util.OnboardingCounters.Event.SPACE_CREATED);   // OB-13
            // OB-12: load the template's samples and run each Pipeline that received files, once. The Space stays
            // created whatever a run answers; each Pipeline's outcome is reported.
            if (loadSamples)
                out.put("samples", Map.of("pipelines", SpaceSamples.load(ctx).stream().map(SpaceSamples.PipelineLoad::toMap).toList()));
            return out;
        } catch (SpaceLocations.Refused r) {   // a chosen folder failed its checks
            throw refused(r);
        } catch (IllegalArgumentException badTemplate) { // unknown template id
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, badTemplate.getMessage());
        } catch (IllegalStateException conflict) {   // already exists (single-mode was rejected above)
            throw new ApiException(409, ErrorCodes.CONFLICT, conflict.getMessage());
        }
    }

    /** Rename / re-describe a space (its id/folder is immutable). The {@code default} space is not editable. */
    private Object updateSpace(ApiContext api, Map<String, Object> body, String id) throws IOException {
        requireMultiSpace(api);
        if (!SpaceId.isValid(id)) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "invalid space id '" + id + "'");
        if ("default".equals(id)) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "the default space cannot be edited");
        SpaceContext ctx = HostContext.of(api).spaces().update(SpaceId.of(id),
                ApiContext.str(body, "display_name"), ApiContext.str(body, "description"));
        if (ctx == null) throw new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + id + "'");
        return manifest(ctx);
    }

    /** Deregister + drain a space; {@code ?purge=true} also deletes its directory tree — refused with a
     *  {@code 409} when it is the last space dir on disk (its tree is the last copy). */
    private Object deleteSpace(ApiContext api, HttpExchange e, String id) throws IOException {
        requireMultiSpace(api);
        if (!SpaceId.isValid(id)) throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "invalid space id '" + id + "'");
        boolean purge = "true".equalsIgnoreCase(ApiContext.query(e, "purge"));
        // Purging the last space DIRECTORY is irrecoverable over HTTP: its tree is the last copy. (Its first reason —
        // main() refused to boot an empty -Dspaces.root — is gone since 2026-09-25: zero Spaces is a clean state.)
        // Deregister-only stays allowed — it leaves the files for re-discovery.
        if (purge && HostContext.of(api).spaces().space(SpaceId.of(id)).isPresent() && HostContext.of(api).spaces().isLastOnDisk(SpaceId.of(id)))
            throw new ApiException(409, ErrorCodes.CONFLICT, "refusing to purge '" + id + "' — it is the last space on disk; "
                    + "delete it without ?purge=true (the files stay for re-discovery), or create another space first");
        if (purge) refuseHeldPurge(api, e, id);
        if (!HostContext.of(api).spaces().delete(SpaceId.of(id), purge))
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no such space '" + id + "'");
        return Map.of("id", id, "deleted", true, "purged", purge);
    }

    /**
     * LEGAL-HOLD-SCOPE-1: purging a Space removes its whole tree — every Incident, Case, note, attachment, Investigation and
     * the audit history with them — so it is refused (409, audited) while anything in it is under legal hold. A hold that
     * cannot be read refuses too. Deregister-only (no {@code ?purge}) stays allowed: the files are kept.
     */
    private static void refuseHeldPurge(ApiContext api, HttpExchange e, String id) {
        var objects = HostContext.of(api).spaces().space(SpaceId.of(id)).flatMap(s -> s.service().objects());
        if (objects.isEmpty()) return;   // no operational objects in this build: nothing can be held
        java.util.Map<String, Long> held;
        try {
            held = objects.get().legalHolds();
        } catch (RuntimeException unreadable) {
            throw new ApiException(409, ErrorCodes.CONFLICT, "refusing to purge '" + id
                    + "' — its legal holds could not be read (" + unreadable.getMessage() + "); nothing was removed");
        }
        if (held.isEmpty()) return;
        java.util.List<String> ids = held.keySet().stream().sorted().limit(5).toList();
        String reason = held.size() + " object(s) in Space '" + id + "' are under legal hold (" + String.join(", ", ids)
                + (held.size() > ids.size() ? ", …" : "") + ")";
        throw new ApiException(409, ErrorCodes.CONFLICT, com.gamma.objects.LegalHoldException
                .audited("purge Space '" + id + "'", reason, "space", id, ApiContext.actor(e)).getMessage());
    }

    /** {@code loadSamples} needs a template that ships sample data: 422 naming the reason otherwise (400 for an unknown one). */
    private static void requireSamples(ApiContext api, String template) throws IOException {
        if (template == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "loadSamples needs a 'template': only a Space Template ships sample data; nothing was created");
        boolean ships;
        try {
            ships = HostContext.of(api).spaces().templateShipsSamples(template);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, unknown.getMessage());
        }
        if (!ships)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "template '" + template
                    + "' ships no sample data (nothing under data/samples or data/inbox), so loadSamples cannot load any; "
                    + "create the Space without loadSamples and add your own data; nothing was created");
    }

    /**
     * The signed-in person creating a Space, or null with no sign-in. A Space they create records them and is closed
     * ({@link SpaceAdmission}); with no sign-in nothing is recorded and the Space is open, as before.
     */
    private static String creator(HttpExchange e) {
        return ApiContext.subject(e).map(com.gamma.spi.auth.Subject::id).orElse(null);
    }

    private static void requireMultiSpace(ApiContext api) {

        if (!HostContext.of(api).spaces().supportsCrud())
            throw new ApiException(409, ErrorCodes.CONFLICT, "this server hosts a single space; launch with -Dspaces.root to manage many");
    }

    /**
     * LC-4 — the Spaces-screen count: {@code integrity} {status, broken, duplicates, changedOutside} from the Space lock's
     * report in memory ({@link SpaceIntegrityRoutes#summary}), only for a caller who may read that Space's
     * {@code /integrity} ({@code canAuthorWorkbench} in THAT Space's role table; everyone on Basic). One list request
     * instead of one report request per row, and no config path leaves the server.
     */
    private static Map<String, Object> withIntegrity(HttpExchange e, SpaceContext ctx, Map<String, Object> m) {
        try {
            ApiContext.requireCapabilityIn(e, ctx.root().config(), "canAuthorWorkbench");
        } catch (ApiException notAnAuthorThere) {
            return m;
        }
        m.put("integrity", SpaceIntegrityRoutes.summary(ctx));
        return m;
    }

    /** The id + display metadata returned for a space. */
    private static Map<String, Object> manifest(SpaceContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ctx.id().value());
        m.put("displayName", ctx.manifest().displayName());
        m.put("description", ctx.manifest().description());
        m.put("createdAt", ctx.manifest().createdAt());
        if (ctx.manifest().closed()) {   // only on a closed Space, so every other row is as it was
            m.put("admission", ctx.manifest().admission());
            m.put("createdBy", ctx.manifest().createdBy());
        }
        return m;
    }
}
