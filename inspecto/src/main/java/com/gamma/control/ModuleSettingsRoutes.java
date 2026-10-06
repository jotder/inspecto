package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The per-Space <b>module Enabled gate</b> over HTTP (MODULE-REORG-1 P2b, D-MR10) - the middle gate
 * <em>Installed -&gt; Enabled -&gt; Permitted</em>; see {@link ModuleSettings} for the document.
 * <pre>
 *   GET /settings/modules   {disabled: [...], inert: [...], installed: [...], unreadable: bool}
 *   PUT /settings/modules   {disabled: [...]} replaces the disabled list - canAdminister, validated fail closed (422),
 *                           audited before/after
 * </pre>
 * <p>{@code installed} is every feature id an installed module declares ({@link ApiContext#registeredFeatures()}); only
 * those can be disabled. A core feature, or an id no installed module declares, is a 422. {@code inert} is the stored
 * ids no installed module declares (the module was removed): they disable nothing and survive every save.
 * <p>A disabled module's routes answer 404 {@code MODULE_DISABLED} in that Space (the dispatch check in
 * {@code ControlApi}), distinct from the 503 {@code CAPABILITY_UNAVAILABLE} of a module that is not installed.
 * <p>⛔ Not under the approval policy: a Space setting, like {@code egress.toon}, and reserved from every import
 * ({@code ReservedConfigPaths}) - an import must not be able to switch a module on or off.
 */
final class ModuleSettingsRoutes implements RouteModule {

    static final int MAX_ENTRIES = 200;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/modules", (e, m) -> view(api, api.writeRoot()));
        api.put("/settings/modules", ApiContext.withCapability("canAdminister", (e, m) -> replace(api, e, api.body(e))));
    }

    private static Map<String, Object> view(ApiContext api, Path root) {
        Set<String> installed = api.registeredFeatures();
        Set<String> stored = ModuleSettings.disabled(root);
        List<String> inert = new ArrayList<>();
        for (String id : stored) if (!installed.contains(id)) inert.add(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("disabled", new ArrayList<>(stored));
        out.put("inert", inert);
        out.put("installed", new ArrayList<>(new TreeSet<>(installed)));
        out.put("unreadable", ModuleSettings.unreadable(root));
        return out;
    }

    private Object replace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "module settings write");
        for (String k : body.keySet())
            if (!ModuleSettings.KEY.equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' (expected " + ModuleSettings.KEY + ")");
        if (!(body.get(ModuleSettings.KEY) instanceof List<?> raw))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + ModuleSettings.KEY + "' must be a list of feature ids");
        if (raw.size() > MAX_ENTRIES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_ENTRIES + " entries");
        Set<String> installed = api.registeredFeatures();
        Set<String> wanted = new LinkedHashSet<>();
        for (Object o : raw) {
            if (!(o instanceof String s) || s.isBlank())
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + ModuleSettings.KEY + "' must be a list of feature ids");
            String id = s.trim();
            if (!installed.contains(id))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + id + "' is not a feature of an installed module "
                        + "(only an installed module's feature can be disabled; installed: " + new TreeSet<>(installed) + ")");
            wanted.add(id);
        }
        List<String> before = new ArrayList<>(ModuleSettings.disabled(root));
        List<String> after = ModuleSettings.write(root, installed, wanted);
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " changed the disabled modules")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("module-settings.changed").actionCategory("configuration")
                    .attr("before", ApiContext.JSON.writeValueAsString(before))
                    .attr("after", ApiContext.JSON.writeValueAsString(after)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best effort, like every audit emit on a request path
        }
        return view(api, root);
    }
}
