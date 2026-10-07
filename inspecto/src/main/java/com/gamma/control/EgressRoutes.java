package com.gamma.control;

import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.audit.Event;
import com.gamma.event.EventLog;
import com.gamma.audit.EventType;
import com.gamma.pipeline.exec.EgressAllowlist;
import com.gamma.util.egress.EgressPolicy;
import com.gamma.pipeline.exec.ModelEgress;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.gamma.access.WriteGates;

/**
 * A Space's <b>egress allowlist</b> ({@code ASSURE-ACTION-REQUESTS-1}, verification finding 1c): the host names and
 * CIDR ranges an Action Request may reach although the {@link EgressPolicy} denies their address class by default —
 * real targets (a CBS, a PCRF) often live on private networks. Persisted as {@code egress.toon} in the Space's
 * config tree ({@code allow: [tickets.internal, 10.20.0.0/16]}); read through {@link EgressAllowlist}, which seeds it
 * ONCE from the Space's current webhook targets when none was ever recorded ({@code WEBHOOK-EGRESS-POLICY-1}).
 * The same list governs the {@code sink.webhook} Step and the webhook notification channel.
 * <pre>
 *   GET /settings/egress     {allow: [...], models: [...]}
 *   PUT /settings/egress     replace the lists it names (an absent key keeps its list) — canAdminister,
 *                            validated fail closed (422), audited before/after
 * </pre>
 * <p>{@code models} is the model endpoint allowlist ({@link com.gamma.pipeline.exec.ModelEgress}): the only hosts
 * the intelligence agent's model gateway may dial, empty (none) by default (2026-09-29).
 * <p>⛔ Not under the approval policy: no policy kind covers Space settings ({@code ApprovalPolicy.GOVERNABLE}
 * excludes them, as it excludes {@code approval.toon}), so there is nothing to hold. Reserved from every import
 * ({@code ReservedConfigPaths}): an import that could widen egress would be an SSRF door. A file that is present
 * but unreadable reads as EMPTY — fail closed, since an empty list only ever denies more.
 */
final class EgressRoutes implements RouteModule {

    static final String FILE = EgressAllowlist.FILE;
    static final int MAX_ENTRIES = 200;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/egress", (e, m) -> Map.of("allow", entries(api.writeRoot()),
                ModelEgress.KEY, ModelEgress.entries(api.writeRoot())));
        api.put("/settings/egress", ApiContext.withCapability("canAdminister", (e, m) -> replace(api, e, api.body(e))));
    }

    /** The Space's allowlist entries — seeded once from its webhook targets if none were recorded ({@link EgressAllowlist}). */
    static List<String> entries(Path root) {
        return EgressAllowlist.entries(root);
    }

    /** The parsed allowlist of the Space whose config root is {@code root}. */
    static EgressPolicy.Allowlist allowlist(Path root) {
        return EgressAllowlist.of(root);
    }

    private Object replace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "egress allowlist write");
        for (String k : body.keySet())
            if (!"allow".equals(k) && !ModelEgress.KEY.equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' (expected allow, models)");
        List<String> before = entries(root);
        List<String> beforeModels = ModelEgress.entries(root);
        // An absent key keeps its list: a PUT of one list must never silently wipe the other.
        List<String> next = body.containsKey("allow") ? list(body, "allow") : before;
        List<String> nextModels = body.containsKey(ModelEgress.KEY) ? list(body, ModelEgress.KEY) : beforeModels;
        try {
            EgressPolicy.Allowlist.of(next);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
        }
        try {
            ModelEgress.parse(nextModels);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "models: " + refused.getMessage());
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("allow", next);
        doc.put(ModelEgress.KEY, nextModels);
        AtomicFiles.write(root.resolve(FILE), JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".egress-");
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " changed the egress allowlist")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("egress-allowlist.changed").actionCategory("configuration")
                    .attr("before", ApiContext.JSON.writeValueAsString(before))
                    .attr("after", ApiContext.JSON.writeValueAsString(next))
                    .attr("modelsBefore", ApiContext.JSON.writeValueAsString(beforeModels))
                    .attr("modelsAfter", ApiContext.JSON.writeValueAsString(nextModels)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best effort, like every audit emit on a request path
        }
        return Map.of("allow", next, ModelEgress.KEY, nextModels);
    }

    /** A list-valued body key, lower-cased and trimmed; 422 when it is not a list or is too long. */
    private static List<String> list(Map<String, Object> body, String key) {
        if (!(body.get(key) instanceof List<?> raw))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'" + key + "' must be a list of host names or CIDRs");
        if (raw.size() > MAX_ENTRIES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_ENTRIES + " entries");
        List<String> out = new ArrayList<>();
        for (Object o : raw) out.add(String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT));
        return out;
    }
}
