package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceManager;
import com.gamma.service.SpaceOrigin;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * SE-5a — a Space's origin ({@code docs/okf/product/module/config/spec-space-origin.md}). Reached as
 * {@code /spaces/{id}/origin…}: the Space seam binds the Space and strips the prefix.
 *
 * <ul>
 *   <li>{@code GET /origin} — the record and its report: files unchanged since the origin, files the Space changed, and,
 *       when the template's version moved, what the template changed since. Report-only; {@code canAuthorWorkbench}
 *       (it lists config paths, like {@code /integrity}); {@code status: none} for a Space without a record.</li>
 *   <li>{@code POST /origin/adopt {template, version?}} — match the Space against a template by file hash and record the
 *       origin. {@code canAdminister}. Gates: no Space folder 503 → body 400/422 → template id leaving the catalog 403 →
 *       no such template 404 → a record already there or a version that is not the template's current one 409 → write.
 *       Writes only {@code .inspecto/created-from.*}, never config.</li>
 *   <li>SE-5b {@code GET /origin/upgrade} — the upgrade proposal ({@link SpaceTemplateUpgrade}): per file {@code update},
 *       {@code add}, {@code conflict}, {@code kept} or {@code template-removed}. Report-only, {@code canAuthorWorkbench};
 *       {@code status} none / unavailable / missing / same-version as {@code /origin}, else {@code proposal}.</li>
 *   <li>SE-5b {@code POST /origin/upgrade {toVersion, files?, proposalId?}} — apply the chosen {@code update} / {@code add}
 *       files (default all of them; never a conflict), {@code canAdminister}. Never automatic. Gates in order: no Space
 *       folder 503 → body 400 / 422 → a named path that is not a plain relative path 403 → no origin 409 → template gone
 *       404 → already at the template's version, {@code toVersion} not the installed one, or {@code proposalId} not the
 *       current proposal's 409 → a named file no import may write 403 → a named file that is not an update or add, or
 *       nothing to apply 409 → the import gates (kind capability 403, Decision Rule gate) → the maker-checker hold (ONE
 *       Pending Change for the set, 202) → write through the import journal (all or nothing), then rewrite the origin
 *       record.</li>
 * </ul>
 */
final class SpaceOriginRoutes implements RouteModule {

    private static final Pattern TEMPLATE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    /** One adopt at a time, so the "no record yet" check and the write cannot interleave. */
    private static final Object ADOPT_LOCK = new Object();
    /** One upgrade at a time, so the proposal an apply checks is the one it writes and records. */
    private static final Object UPGRADE_LOCK = new Object();
    /** The kind and change a Space Template upgrade is held as when an approval policy holds its files. */
    static final String UPGRADE_KIND = "space-template-upgrade";

    @Override
    public void register(ApiContext api) {
        api.get("/origin", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> read(api)));
        api.post("/origin/adopt", ApiContext.withCapability("canAdminister", (e, m) -> adopt(api, api.body(e))));
        api.get("/origin/upgrade", ApiContext.withCapability("canAuthorWorkbench", (e, m) -> proposal(api)));
        api.post("/origin/upgrade", ApiContext.withCapability("canAdminister", (e, m) -> upgrade(api, e, api.body(e))));
    }

    private static SpaceContext space(ApiContext api) {
        SpaceManager spaces = HostContext.of(api).spaces();
        return spaces.space(SpaceId.of(EventLog.currentSpaceId())).orElseGet(spaces::current);
    }

    private static Object read(ApiContext api) throws IOException {
        SpaceManager spaces = HostContext.of(api).spaces();
        SpaceContext ctx = space(api);
        if (ctx.root().config() == null) return Map.of("status", "unavailable",
                "reason", "this Space has no Space folder (single-tenant -Dassist.write.root), so it has no origin");
        Path base = ctx.root().base();
        var origin = SpaceOrigin.read(base);
        if (origin.isEmpty()) return Map.of("status", "none");
        return SpaceOrigin.report(origin.get(), base, spaces.templateDir(origin.get().template()).orElse(null), ctx.id().value());
    }

    private static Object adopt(ApiContext api, Map<String, Object> body) throws IOException {
        SpaceManager spaces = HostContext.of(api).spaces();
        SpaceContext ctx = space(api);
        if (ctx.root().config() == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                    "this Space has no Space folder (single-tenant -Dassist.write.root), so it cannot record an origin");
        if (!(body.get("template") instanceof String template) || template.isBlank())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'template' (a Space Template id) is required");
        Object v = body.get("version");
        if (v != null && !(v instanceof String))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'version' must be a string");
        String version = (String) v;
        if (version != null && !version.equals(SpaceOrigin.UNVERSIONED) && !SpaceOrigin.VERSION.matcher(version).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'version' is not a template version: " + version);
        if (!TEMPLATE_ID.matcher(template).matches())
            throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "'template' must be a bare template id");
        Path tpl = spaces.templateDir(template)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no such space template '" + template + "'"));
        Path base = ctx.root().base();
        synchronized (ADOPT_LOCK) {
            var existing = SpaceOrigin.read(base);
            if (existing.isPresent())
                throw new ApiException(409, ErrorCodes.CONFLICT, "this Space already records its origin: template '"
                        + existing.get().template() + "' version " + existing.get().templateVersion());
            String current = SpaceOrigin.templateVersion(tpl);
            if (version != null && !version.equals(current))
                throw new ApiException(409, ErrorCodes.CONFLICT, "template '" + template + "' is at version " + current
                        + ", not " + version + "; only the version installed now can be matched");
            return SpaceOrigin.adopt(tpl, base, template, ctx.id().value()).view();
        }
    }

    // ── SE-5b upgrade ──

    private static Object proposal(ApiContext api) throws IOException {
        SpaceManager spaces = HostContext.of(api).spaces();
        SpaceContext ctx = space(api);
        if (ctx.root().config() == null) return Map.of("status", "unavailable",
                "reason", "this Space has no Space folder (single-tenant -Dassist.write.root), so it has no origin");
        Path base = ctx.root().base();
        var origin = SpaceOrigin.read(base);
        if (origin.isEmpty()) return Map.of("status", "none");
        SpaceOrigin.Origin o = origin.get();
        Path tpl = spaces.templateDir(o.template()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (tpl == null) {
            out.put("status", "missing");
        } else if (SpaceOrigin.templateVersion(tpl).equals(o.templateVersion())) {
            out.put("status", "same-version");
        } else {
            return SpaceTemplateUpgrade.propose(o, config(ctx), tpl, ctx.id().value()).view();
        }
        out.put("template", o.template());
        out.put("templateVersion", o.templateVersion());
        return out;
    }

    private static Path config(SpaceContext ctx) {
        return ctx.root().config().toAbsolutePath().normalize();
    }

    private static Object upgrade(ApiContext api, com.sun.net.httpserver.HttpExchange ex, Map<String, Object> body) throws IOException {
        SpaceManager spaces = HostContext.of(api).spaces();
        SpaceContext ctx = space(api);
        if (ctx.root().config() == null)
            throw new ApiException(503, ErrorCodes.CAPABILITY_UNAVAILABLE,
                    "this Space has no Space folder (single-tenant -Dassist.write.root), so it has no origin to upgrade");
        if (!(body.get("toVersion") instanceof String toVersion) || toVersion.isBlank())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'toVersion' (the template version the proposal was read at) is required");
        List<String> named = null;
        if (body.get("files") != null) {
            if (!(body.get("files") instanceof List<?> l) || l.isEmpty() || !l.stream().allMatch(f -> f instanceof String))
                throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'files' must be a non-empty list of config-relative paths (omit it for all)");
            named = l.stream().map(String::valueOf).distinct().toList();
        }
        Object pid = body.get("proposalId");
        if (pid != null && !(pid instanceof String))
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "'proposalId' must be a string");
        if (!toVersion.equals(SpaceOrigin.UNVERSIONED) && !SpaceOrigin.VERSION.matcher(toVersion).matches())
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'toVersion' is not a template version: " + toVersion);
        if (named != null)
            for (String f : named) {
                String why = com.gamma.service.ImportPaths.segmentRefusal(f);
                if (why != null)
                    throw new ApiException(403, ErrorCodes.PATH_JAIL_VIOLATION, "'" + f + "' is not a plain config-relative path: " + why);
            }
        Path base = ctx.root().base();
        Path config = config(ctx);
        synchronized (UPGRADE_LOCK) {
            SpaceOrigin.Origin o = SpaceOrigin.read(base).orElseThrow(() -> new ApiException(409, ErrorCodes.CONFLICT,
                    "this Space records no origin, so there is nothing to upgrade from — adopt a template first (POST /origin/adopt)"));
            Path tpl = spaces.templateDir(o.template()).orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND,
                    "the template '" + o.template() + "' this Space was made from is no longer installed"));
            String now = SpaceOrigin.templateVersion(tpl);
            if (now.equals(o.templateVersion()))
                throw new ApiException(409, ErrorCodes.CONFLICT, "this Space is already at version " + now + " of '" + o.template() + "'");
            if (!toVersion.equals(now))
                throw new ApiException(409, ErrorCodes.CONFLICT_STALE_VERSION, "template '" + o.template() + "' is now at version "
                        + now + ", not " + toVersion + " — read the proposal again");
            SpaceTemplateUpgrade.Proposal p = SpaceTemplateUpgrade.propose(o, config, tpl, ctx.id().value());
            if (pid != null && !pid.equals(p.id()))
                throw new ApiException(409, ErrorCodes.CONFLICT_STALE_VERSION,
                        "the template or the Space changed since that proposal was read — read the proposal again");
            List<String> chosen = named == null ? p.applicable() : named;
            if (named != null) {
                for (String f : named) {
                    SpaceTemplateUpgrade.Entry e = p.entry(f);
                    if (e != null && e.refused() != null)
                        throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "'" + f + "' may not be written by an upgrade: "
                                + e.refused() + " — change it through its own route");
                }
                for (String f : named) {
                    SpaceTemplateUpgrade.Entry e = p.entry(f);
                    if (e == null || !e.applicable())
                        throw new ApiException(409, ErrorCodes.CONFLICT, "'" + f + "' is " + (e == null ? "not in the proposal"
                                : "'" + e.change() + "' in the proposal") + " — only an update or add is applied; a conflict is resolved by hand");
                }
            }
            if (chosen.isEmpty())
                throw new ApiException(409, ErrorCodes.CONFLICT, "the proposal has no update or add to apply");

            // The import gates, as a Space bundle import runs them over raw config files: a template is never a way
            // around a kind's own route (capability per kind, administer-only Jobs, attaching Job templates), every
            // Decision Rule passes its one gate, and every Job is server-stamped.
            java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
            for (String f : chosen) entries.put(f, p.templateFiles().get(f));
            ImportCapabilityGuard.checkFiles(ex, entries);
            com.gamma.service.BundleImporter.Bundle bundle = DecisionRuleGuard.guardImport(ex, config,
                    new com.gamma.service.BundleImporter.Bundle(UPGRADE_KIND, Map.of(), entries, null));
            bundle = JobAuthority.stampBundle(ex, bundle);

            // Maker-checker: ONE Pending Change for the set when the policy holds any of its files. Pinned on the
            // template's hashes (proposed) over the Space's (current), so a replay passes only over the same base.
            Map<String, Object> proposed = new LinkedHashMap<>(), current = new LinkedHashMap<>();
            Map<String, Object> to = new TreeMap<>(), from = new TreeMap<>();
            for (String f : chosen) {
                SpaceTemplateUpgrade.Entry e = p.entry(f);
                to.put(f, e.template());
                from.put(f, e.space() == null ? "absent" : e.space());
            }
            proposed.put("template", o.template());
            proposed.put("toVersion", now);
            proposed.put("files", to);
            current.put("fromVersion", o.templateVersion());
            current.put("files", from);
            PendingChanges.holdSet(api, ex, chosen, UPGRADE_KIND, o.template(), proposed, current);

            // The shared raw-config writer (the import's): every file judged first, each written atomically through the
            // journal, all rolled back when one fails - and when the origin record cannot be rewritten after them.
            Map<String, byte[]> priorPipelines = new TreeMap<>();
            for (String f : bundle.configEntries().keySet()) {
                Path target = config.resolve(f);
                if (f.endsWith("_pipeline.toon") && java.nio.file.Files.isRegularFile(target))
                    priorPipelines.put(f, java.nio.file.Files.readAllBytes(target));
            }
            com.gamma.service.ImportJournal journal = new com.gamma.service.ImportJournal();
            try {
                com.gamma.service.BundleImporter.writeConfig(bundle, config, true, journal);   // rolls itself back on a failed write
            } catch (IllegalArgumentException judged) {   // the writer's own import judge (backstop); nothing was written
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, judged.getMessage() + " — apply it with the files it needs, or through its own route");
            }
            Map<String, String> written = new TreeMap<>();
            bundle.configEntries().forEach((f, b) -> written.put(f, SpaceOrigin.sha256(b)));
            try {
                SpaceOrigin.recordUpgrade(base, now, written);
            } catch (IOException | RuntimeException failed) {
                try {
                    journal.rollback();
                } catch (IOException rollbackFailed) {
                    failed.addSuppressed(rollbackFailed);
                }
                throw failed;
            }
            for (Map.Entry<String, byte[]> w : bundle.configEntries().entrySet())
                if (w.getKey().endsWith("_pipeline.toon")) {
                    Path target = config.resolve(w.getKey());
                    RefusalConfigAudit.audit(target, priorPipelines.get(w.getKey()), w.getValue());
                    PipelineHistory.record(config, target);   // PIPELINE-CONFIG-HISTORY-1
                }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("template", o.template());
            out.put("fromVersion", o.templateVersion());
            out.put("toVersion", now);
            out.put("applied", new ArrayList<>(written.keySet()));
            List<Map<String, Object>> left = new ArrayList<>();
            for (SpaceTemplateUpgrade.Entry e : p.files())
                if (!written.containsKey(e.path())) left.add(Map.of("path", e.path(), "change", e.change()));
            out.put("notApplied", left);
            return out;
        }
    }
}
