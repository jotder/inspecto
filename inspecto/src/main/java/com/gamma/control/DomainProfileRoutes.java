package com.gamma.control;

import com.gamma.access.WriteGates;
import com.gamma.entitystore.IndustryPack;
import com.gamma.entitystore.IndustryPacks;
import com.gamma.entitystore.LinkAnalysisSettings;
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

/**
 * The Space's <b>Domain Profile</b> over HTTP (LA-EDITIONS-1 phase 4, settles {@code LDP-D7}): which installed
 * {@link IndustryPack} relabels Link Analysis for this Space.
 * <pre>
 *   GET /settings/link-analysis/domain-profile   {active, stated, extraPacks[], profile, installed[], refused[]} - the active
 *                                                pack's terminology in full ({@code profile}, null = the neutral vocabulary)
 *   PUT /settings/link-analysis/domain-profile   {"domainProfile": "&lt;pack id&gt;" | "generic" | null,
 *                                                 "extraPacks"?: ["&lt;pack id&gt;", ...]} (canAuthorWorkbench)
 * </pre>
 * {@code extraPacks} (ENTITY-TYPE-CATALOG-1 Q-5) are further installed packs whose Entity Types join the Domain Profile's,
 * merged by id (a telecom operator that runs mobile money); the Domain Profile's pack alone gives the vocabulary. Left out
 * of a PUT, the stored ones are kept; {@code []} clears them; an id that is not installed is refused like the profile's.
 * {@code active} is the id in force ({@code generic} when none is stated, or when the stated one is not installed —
 * then {@code diagnostic} says so); {@code stated} is the stored value. The PUT refuses an id that is not installed
 * (absent, or refused at load) with 422 naming the installed ones, and answers the GET shape. It writes only
 * {@code domain_profile}, keeping every other key of {@code link-analysis.toon}.
 */
final class DomainProfileRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/settings/link-analysis/domain-profile", (e, m) -> ETags.respond(e, shape(boundWriteRoot(api))));
        api.put("/settings/link-analysis/domain-profile", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> write(api, api.body(e))));
    }

    private static Object write(ApiContext api, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "domain profile write");
        if (body == null || !body.containsKey("domainProfile"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "domainProfile is required: a pack id, 'generic' or null");
        Object raw = body.get("domainProfile");
        if (raw != null && !(raw instanceof String))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "domainProfile must be a string or null");
        String id = raw == null ? null : ((String) raw).trim();
        if (id != null && (id.isEmpty() || id.equals(IndustryPack.GENERIC))) id = null;
        IndustryPacks.Catalogue packs = IndustryPacks.bundled();
        if (id != null && packs.find(id) == null)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "domainProfile '" + id
                    + "' is not an installed Industry pack; installed: " + packs.installed().stream().map(IndustryPack::id).toList());
        List<String> extra = null;
        if (body.get("extraPacks") != null) {
            if (!(body.get("extraPacks") instanceof List<?> l))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "extraPacks must be a list of pack ids");
            extra = new ArrayList<>();
            for (Object o : l) {
                if (!(o instanceof String s) || packs.find(s.trim()) == null)
                    throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "extraPacks: '" + o
                            + "' is not an installed Industry pack; installed: " + packs.installed().stream().map(IndustryPack::id).toList());
                if (!s.trim().equals(id) && !extra.contains(s.trim())) extra.add(s.trim());
            }
        }
        // the chosen packs must read together (installed packs are checked at load, so this is the last line of defence)
        List<IndustryPack> chosen = new ArrayList<>();
        if (id != null) chosen.add(packs.find(id));
        List<String> extraInForce = extra != null ? extra
                : LinkAnalysisSettings.forRoot(root).extraPacks();
        for (String x : extraInForce) if (packs.find(x) != null && !chosen.contains(packs.find(x))) chosen.add(packs.find(x));
        try {
            com.gamma.entitystore.EntityTypes.validate(IndustryPack.overlay(chosen, com.gamma.entitystore.EntityTypes.DEFAULTS),
                    Integer.MAX_VALUE);
        } catch (IllegalArgumentException clash) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "these packs do not read together: " + clash.getMessage());
        }
        try {
            LinkAnalysisSettings.writeDomainProfile(root.resolve(LinkAnalysisSettings.FILE), id, extra);
        } catch (IOException unparseable) {
            throw new ApiException(409, ErrorCodes.CONFLICT, LinkAnalysisSettings.FILE
                    + " does not parse, so it was left as it is: " + unparseable.getMessage());
        }
        return shape(root);
    }

    private static Map<String, Object> shape(Path root) {
        LinkAnalysisSettings s = root == null ? LinkAnalysisSettings.EMPTY : LinkAnalysisSettings.read(root.resolve(LinkAnalysisSettings.FILE));
        IndustryPacks.Catalogue packs = IndustryPacks.bundled();
        IndustryPack active = s.activePack();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", active != null ? active.id() : IndustryPack.GENERIC);
        m.put("stated", s.domainProfile());
        m.put("extraPacks", s.extraPacks());
        if (s.domainProfile() != null && active == null)
            m.put("diagnostic", "domain profile '" + s.domainProfile() + "' is not installed; the neutral vocabulary is in force");
        m.put("profile", active != null ? active.toMap() : null);
        List<Map<String, Object>> installed = new ArrayList<>();
        for (IndustryPack p : packs.installed()) installed.add(p.toMap());
        m.put("installed", installed);
        List<Map<String, Object>> refused = new ArrayList<>();
        for (IndustryPacks.Refused r : packs.refused()) refused.add(Map.of("id", r.id(), "reason", r.reason()));
        m.put("refused", refused);
        return m;
    }

    private static Path boundWriteRoot(ApiContext api) {
        try {
            return api.writeRoot();
        } catch (IllegalStateException noSpaces) {
            return null;
        }
    }
}
