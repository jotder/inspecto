package com.gamma.control;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-space UI settings — small preference documents the UI fetches and saves per space:
 * <pre>
 *   GET /settings/branding   the space's {logoDataUrl, caption, footerText} (nulls = shipped defaults) [v4.10.0]
 *   PUT /settings/branding   replace the space's branding (write-root gated, capability-gated)         [v4.10.0]
 *   GET /settings/geo        the space's {tileServerUrl} (null = no self-hosted tile server)
 *   PUT /settings/geo        replace the space's geo/tile-server config (same gates as branding)
 *   GET /settings/link-analysis   the space's {projectionNodeCap, analysisNodeCap, suspicionNodeCap,
 *                                 maskingMode, fourEyesBudgetAbove, fourEyesFanOutAbove} (nulls = shipped defaults)
 *   PUT /settings/link-analysis   replace the space's Link Analysis settings (same gates as branding)
 *   GET /settings/pipeline-history   the space's {keep, effectiveKeep, defaultKeep, maxKeep} — Pipeline config
 *                                    versions kept per Pipeline (keep null = the shipped default)
 *   PUT /settings/pipeline-history   replace it (same gates as branding)
 *   GET /config/icon-map     the space's processor-icon map { "&lt;type&gt;": {glyph,color}, … } ({} = none) [v5.0.0]
 *   PUT /config/icon-map     replace the space's icon map (same gates as branding)                          [v5.0.0]
 * </pre>
 *
 * <p>Space-scoped through the standard {@code /spaces/{id}/…} request seam: the UI calls the bare
 * {@code /settings/branding} for the active space, or {@code /spaces/{id}/settings/branding} to edit any
 * space. Stored as {@code branding.toon} / {@code geo.toon} / {@code link-analysis.toon} / {@code icon-map.toon} in the bound space's
 * config tree ({@link ApiContext#writeRoot()}), so settings writes share the same read-only ({@code 503}) gate
 * as config writes. ({@code /config/icon-map} keeps the UI's existing {@code IconMapService} path; it is a
 * per-space preference document like branding/geo, not a runnable-config route — hence its home here.)
 */
final class SettingsRoutes implements RouteModule {

    private static final String BRANDING_FILE = "branding.toon";
    private static final String GEO_FILE = "geo.toon";
    private static final String ICON_MAP_FILE = "icon-map.toon";
    /** Sanity ceiling for a Link Analysis node cap — far above any graph a browser can lay out (the
     *  shipped defaults are in the hundreds/low thousands), small enough to catch a unit mistake
     *  (someone writing an edge count, a byte count or a millisecond budget into a node slot). Mirrors
     *  {@code SchedulerRoutes.MAX_CAP}'s reasoning. A bad value is refused (422), never clamped: this is
     *  a persisted setting an operator explicitly typed, so it must come back for them to fix. */
    private static final int MAX_NODE_CAP = 100_000;
    /** Reject an over-large inline logo (defence-in-depth; the UI already caps ~200 KB). */
    private static final int MAX_LOGO_CHARS = 512 * 1024;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/branding", (e, m) -> ETags.respond(e, readBranding(api)));
        api.put("/settings/branding", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeBranding(api, api.body(e))));
        api.get("/settings/geo", (e, m) -> ETags.respond(e, readGeo(api)));
        api.put("/settings/geo", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeGeo(api, api.body(e))));
        api.get("/settings/link-analysis", (e, m) -> ETags.respond(e, readLinkAnalysis(api)));
        api.put("/settings/link-analysis", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeLinkAnalysis(api, api.body(e))));
        api.get("/settings/pipeline-history", (e, m) -> ETags.respond(e, readPipelineHistory(api)));
        api.put("/settings/pipeline-history", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writePipelineHistory(api, api.body(e))));
        api.get("/config/icon-map", (e, m) -> ETags.respond(e, readIconMap(api)));
        api.put("/config/icon-map", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeIconMap(api, api.body(e))));
    }

    private Object readBranding(ApiContext api) {
        Path root = api.writeRoot();
        BrandingSettings b = root == null ? BrandingSettings.EMPTY : BrandingSettings.read(root.resolve(BRANDING_FILE));
        return shape(b);
    }

    private Object writeBranding(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "branding write");
        String logo = ApiContext.str(body, "logoDataUrl");
        if (logo != null && logo.length() > MAX_LOGO_CHARS)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "logoDataUrl too large (max " + MAX_LOGO_CHARS + " characters)");
        BrandingSettings b = new BrandingSettings(logo, ApiContext.str(body, "caption"), ApiContext.str(body, "footerText"));
        b.write(root.resolve(BRANDING_FILE));
        return shape(b);
    }

    /** The wire shape the UI's {@code BrandingService} expects — nulls kept so the client falls back to defaults. */
    private static Map<String, Object> shape(BrandingSettings b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("logoDataUrl", b.logoDataUrl());
        m.put("caption", b.caption());
        m.put("footerText", b.footerText());
        return m;
    }

    private Object readGeo(ApiContext api) {
        Path root = api.writeRoot();
        GeoSettings g = root == null ? GeoSettings.EMPTY : GeoSettings.read(root.resolve(GEO_FILE));
        return geoShape(g);
    }

    private Object writeGeo(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "geo settings write");
        GeoSettings g = new GeoSettings(ApiContext.str(body, "tileServerUrl"));
        g.write(root.resolve(GEO_FILE));
        return geoShape(g);
    }

    /** The wire shape the UI's {@code GeoSettingsService} expects — null means "no self-hosted tile server". */
    private static Map<String, Object> geoShape(GeoSettings g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tileServerUrl", g.tileServerUrl());
        return m;
    }

    private Object readLinkAnalysis(ApiContext api) {
        Path root = api.writeRoot();
        LinkAnalysisSettings s = root == null ? LinkAnalysisSettings.EMPTY
                : LinkAnalysisSettings.read(root.resolve(LinkAnalysisSettings.FILE));
        return linkAnalysisShape(s);
    }

    private Object writeLinkAnalysis(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "link-analysis settings write");
        LinkAnalysisSettings s = new LinkAnalysisSettings(nodeCap(body, "projectionNodeCap"),
                nodeCap(body, "analysisNodeCap"), nodeCap(body, "suspicionNodeCap"), maskingMode(body),
                nodeCap(body, "fourEyesBudgetAbove"), nodeCap(body, "fourEyesFanOutAbove"), entityTypes(body));
        s.write(root.resolve(LinkAnalysisSettings.FILE));
        return linkAnalysisShape(s);
    }

    /** The wire shape the UI's Link Analysis settings expect — null means "inherit the shipped default". */
    private static Map<String, Object> linkAnalysisShape(LinkAnalysisSettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectionNodeCap", s.projectionNodeCap());
        m.put("analysisNodeCap", s.analysisNodeCap());
        m.put("suspicionNodeCap", s.suspicionNodeCap());
        m.put("maskingMode", s.maskingMode());
        m.put("fourEyesBudgetAbove", s.fourEyesBudgetAbove());
        m.put("fourEyesFanOutAbove", s.fourEyesFanOutAbove());
        m.put("entityTypes", s.entityTypes() == null ? null : EntityTypes.shape(s.entityTypes()));
        m.put("entityTypesInForce", EntityTypes.shape(s.effectiveEntityTypes()));
        return m;
    }

    /** A stated masking mode (D-U6): {@code null}/absent = inherit the default ({@code typed}); otherwise one of
     *  {@link com.gamma.config.spec.ConfigSpecs#LINK_ANALYSIS_MASKING_MODES} or the write is refused (422). */
    private static String maskingMode(Map<String, Object> body) {
        Object raw = body.get("maskingMode");
        if (raw == null) return null;
        String v = LinkAnalysisSettings.maskingMode(String.valueOf(raw));
        if (v == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "maskingMode must be one of "
                    + com.gamma.config.spec.ConfigSpecs.LINK_ANALYSIS_MASKING_MODES + ", got '" + raw + "'");
        return v;
    }

    /** Stated Entity Types (LA-17): {@code null}/absent = inherit {@link EntityTypes#DEFAULTS}; otherwise a list
     *  that passes {@link EntityTypes#parse} or the write is refused (422) — never trimmed to fit. */
    private static List<EntityTypes.EntityType> entityTypes(Map<String, Object> body) {
        Object raw = body.get("entityTypes");
        if (raw == null) return null;
        try {
            return EntityTypes.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, e.getMessage());
        }
    }

    /** A stated node cap: {@code null}/absent = inherit the shipped default; otherwise an int in
     *  {@code 1..MAX_NODE_CAP} or the write is refused (422), never silently clamped. */
    private static Integer nodeCap(Map<String, Object> body, String key) {
        Object raw = body.get(key);
        if (raw == null) return null;
        int v;
        try {
            v = Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, key + " must be an integer, got '" + raw + "'");
        }
        if (v < 1 || v > MAX_NODE_CAP)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, key + " must be 1.." + MAX_NODE_CAP + ", got " + v);
        return v;
    }

    private Object readPipelineHistory(ApiContext api) {
        return pipelineHistoryShape(PipelineHistorySettings.forRoot(api.writeRoot()));
    }

    /** {@code keep}: {@code null}/absent = the shipped default; else an int in {@code 1..MAX_KEEP} or 422 — never clamped. */
    private Object writePipelineHistory(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "pipeline-history settings write");
        Object raw = body.get("keep");
        Integer keep = null;
        if (raw != null) {
            try {
                keep = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "keep must be an integer, got '" + raw + "'");
            }
            if (keep < 1 || keep > PipelineHistorySettings.MAX_KEEP)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "keep must be 1.." + PipelineHistorySettings.MAX_KEEP + ", got " + keep);
        }
        PipelineHistorySettings s = new PipelineHistorySettings(keep);
        s.write(root.resolve(PipelineHistorySettings.FILE));
        return pipelineHistoryShape(s);
    }

    private static Map<String, Object> pipelineHistoryShape(PipelineHistorySettings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keep", s.keep());
        m.put("effectiveKeep", s.effectiveKeep());
        m.put("defaultKeep", PipelineHistorySettings.DEFAULT_KEEP);
        m.put("maxKeep", PipelineHistorySettings.MAX_KEEP);
        return m;
    }

    private Object readIconMap(ApiContext api) {
        Path root = api.writeRoot();
        IconMapSettings s = root == null ? IconMapSettings.EMPTY : IconMapSettings.read(root.resolve(ICON_MAP_FILE));
        return s.toWire();
    }

    @SuppressWarnings("unchecked")
    private Object writeIconMap(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "icon-map write");
        Map<String, IconMapSettings.Rule> rules = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> sub))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "icon-map entry '" + entry.getKey() + "' must be an object {glyph, color}");
            String glyph = ApiContext.str((Map<String, Object>) sub, "glyph");
            String color = ApiContext.str((Map<String, Object>) sub, "color");
            if (glyph == null || glyph.isBlank() || color == null || color.isBlank())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "icon-map entry '" + entry.getKey() + "' needs a glyph and a color");
            rules.put(entry.getKey(), new IconMapSettings.Rule(glyph.trim(), color.trim()));
        }
        IconMapSettings s = new IconMapSettings(rules);
        s.write(root.resolve(ICON_MAP_FILE));
        return s.toWire();
    }
}
