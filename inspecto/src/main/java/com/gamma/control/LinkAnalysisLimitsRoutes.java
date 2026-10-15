package com.gamma.control;

import com.gamma.access.Roles;
import com.gamma.entitystore.LaLimit;
import com.gamma.entitystore.LaLimitValues;
import com.gamma.entitystore.LaLimits;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Link Analysis limits catalogue over HTTP ({@code LA-CONFIG-LIMITS-1}, plan §6.2) — one item per {@link LaLimit}:
 * <pre>
 *   GET /settings/link-analysis/limits    every item {key, layer, browser, group, unit, default, min, max, value, inForce,
 *                                         editable, description}, ordered by group, then catalogue order
 *   PUT /settings/link-analysis/limits    set or clear (null) SPACE items of the bound Space (canAuthorWorkbench)
 *   PUT /system/link-analysis/limits      set or clear (null) INSTALL items, server-wide (canAdminister; Pod-scoped)
 * </pre>
 * A PUT body is {@code {"<key>": number | null}}. An unknown key, a key of the other layer, or a value outside the
 * item's {@code min..max} is refused 422 naming the key and the range; nothing is written unless every key passes. A
 * PUT answers the GET shape. {@code value} is the stated value ({@code null} = inherit the default); {@code editable}
 * is whether THIS caller may change the item (SPACE: {@code canAuthorWorkbench} and a writable Space; INSTALL:
 * {@code canAdminister} and a server-wide document home).
 *
 * <p>⚠ <b>The INSTALL values are the answering Pod's</b> ({@code POD-SCOPE-DIVERGENCE-1}, the {@code /system/scheduler}
 * posture): the server-wide document is read on this Pod, so both answers carry {@code metadata.podScoped}.
 */
final class LinkAnalysisLimitsRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/settings/link-analysis/limits", (e, m) -> {
            ApiContext.podScoped(e);
            return ETags.respond(e, shape(api, e));
        });
        api.put("/settings/link-analysis/limits", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> writeSpace(api, e, api.body(e))));
        api.put("/system/link-analysis/limits", ApiContext.withCapability("canAdminister",
                (e, m) -> writeInstall(api, e, api.body(e))));
    }

    private Object writeSpace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = boundWriteRoot(api);
        if (root == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY, "link-analysis limits write disabled: no writable space is bound");
        Map<LaLimit, Number> changes = changes(body, LaLimit.Layer.SPACE);
        Map<String, Object> merged = load(root.resolve(com.gamma.entitystore.LinkAnalysisSettings.FILE));
        // A cross-item rule is checked only when this write touches one of its items: a hand edit elsewhere in the
        // document must not block an unrelated save (it already reads as the defaults).
        boolean related = changes.keySet().stream().anyMatch(l -> l.group().equals("drafts") || l.group().equals("embed"));
        String problem = related ? LaLimits.consistencyProblem(LaLimits.apply(merged, changes)) : null;
        if (problem != null) throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, problem);
        if (!changes.isEmpty()) LaLimits.writeSpace(root, changes);
        ApiContext.podScoped(ex);
        return shape(api, ex);
    }

    private Object writeInstall(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        if (boundWriteRoot(api) == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY, "link-analysis limits write disabled: no writable space is bound");
        Path doc = installDoc(api);
        if (doc == null)
            throw new ApiException(503, ErrorCodes.CONTROL_PLANE_READ_ONLY, "No home for the server-wide Link Analysis limits "
                    + "(-Dsystem.config.dir, spaces root and write root all unset)");
        Map<LaLimit, Number> changes = changes(body, LaLimit.Layer.INSTALL);
        load(doc);   // a document that does not parse is refused before anything is written
        if (!changes.isEmpty()) LaLimits.writeInstall(doc, changes);
        ApiContext.podScoped(ex);
        return shape(api, ex);
    }

    /** Every key of {@code body} checked against the catalogue and {@code layer}, all before anything is written. */
    private static Map<LaLimit, Number> changes(Map<String, Object> body, LaLimit.Layer layer) {
        Map<LaLimit, Number> out = new EnumMap<>(LaLimit.class);
        for (Map.Entry<String, Object> e : body.entrySet()) {
            LaLimit l = LaLimit.byPath(e.getKey()).orElseThrow(() -> new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED,
                    "unknown Link Analysis limit '" + e.getKey() + "'"));
            if (l.layer() != layer)
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + l.path() + "' is "
                        + (l.layer() == LaLimit.Layer.INSTALL ? "an install-wide limit: set it with PUT /system/link-analysis/limits"
                        : "a Space limit: set it with PUT /settings/link-analysis/limits"));
            try {
                out.put(l, l.parse(e.getValue()));
            } catch (IllegalArgumentException bad) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
            }
        }
        return out;
    }

    /** The GET shape: every catalogue item for this caller. */
    private Object shape(ApiContext api, HttpExchange ex) {
        Path root = boundWriteRoot(api);
        boolean installable = installDoc(api) != null;   // resolves (and installs) the server-wide document before the read
        LaLimitValues v = LaLimits.forSpace(root);
        boolean space = root != null && can(ex, Roles.CAN_AUTHOR_WORKBENCH);
        boolean install = root != null && installable && can(ex, Roles.CAN_ADMINISTER);
        List<LaLimit> order = new ArrayList<>(List.of(LaLimit.values()));
        Map<String, Integer> groupRank = new LinkedHashMap<>();
        for (LaLimit l : order) groupRank.putIfAbsent(l.group(), groupRank.size());
        order.sort(Comparator.comparingInt((LaLimit l) -> groupRank.get(l.group())).thenComparingInt(Enum::ordinal));
        List<Map<String, Object>> items = new ArrayList<>();
        for (LaLimit l : order) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", l.path());
            m.put("layer", l.layer().wire());
            m.put("browser", l.browser());
            m.put("group", l.group());
            m.put("unit", l.unit());
            m.put("default", l.defaultValue());
            m.put("min", l.min());
            m.put("max", l.max());
            m.put("value", v.stated(l));
            m.put("inForce", v.inForce(l));
            m.put("editable", l.layer() == LaLimit.Layer.SPACE ? space : install);
            m.put("description", l.description());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        return out;
    }

    private static boolean can(HttpExchange ex, String capability) {
        return ApiContext.subject(ex).map(s -> s.capabilities().contains(capability)).orElse(true);
    }

    /** The installed server-wide document, else the one this request resolves (and installs, so the code sites read it too). */
    private static Path installDoc(ApiContext api) {
        Path p = LaLimits.installDocPath();
        if (p != null) return p;
        try {
            p = SchedulerRoutes.systemDocPath(api, LaLimits.INSTALL_FILE);
        } catch (IllegalStateException noSpaces) {
            return null;
        }
        if (p != null) LaLimits.installDocPath(p);
        return p;
    }

    /** The raw document, or 409 when it exists but does not parse (a write must never silently replace a hand edit). */
    private static Map<String, Object> load(Path doc) {
        try {
            return LaLimits.loadRaw(doc);
        } catch (IOException e) {
            throw new ApiException(409, ErrorCodes.CONFLICT, e.getMessage() + " - fix the document by hand before saving limits");
        }
    }

    /** The bound Space's write root, or {@code null} when writes are disabled or no Space is bound (never a 500). */
    private static Path boundWriteRoot(ApiContext api) {
        try {
            return api.writeRoot();
        } catch (IllegalStateException noSpaces) {
            return null;
        }
    }
}
