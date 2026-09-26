package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.pipeline.exec.EgressPolicy;
import com.gamma.util.AtomicFiles;
import com.gamma.util.ToonHelper;
import com.sun.net.httpserver.HttpExchange;
import dev.toonformat.jtoon.JToon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Space's <b>egress allowlist</b> ({@code ASSURE-ACTION-REQUESTS-1}, verification finding 1c): the host names and
 * CIDR ranges an Action Request may reach although the {@link EgressPolicy} denies their address class by default —
 * real targets (a CBS, a PCRF) often live on private networks. Persisted as {@code egress.toon} in the Space's
 * config tree ({@code allow: [tickets.internal, 10.20.0.0/16]}); default EMPTY.
 * <pre>
 *   GET /settings/egress     {allow: [...]}
 *   PUT /settings/egress     replace it — canAdminister, validated fail closed (422), audited before/after
 * </pre>
 * <p>⛔ Not under the approval policy: no policy kind covers Space settings ({@code ApprovalPolicy.GOVERNABLE}
 * excludes them, as it excludes {@code approval.toon}), so there is nothing to hold. Reserved from every import
 * ({@code ReservedConfigPaths}): an import that could widen egress would be an SSRF door. A file that is present
 * but unreadable reads as EMPTY — fail closed, since an empty list only ever denies more.
 */
final class EgressRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(EgressRoutes.class);
    static final String FILE = "egress.toon";
    static final int MAX_ENTRIES = 200;

    @Override
    public void register(ApiContext api) {
        api.get("/settings/egress", (e, m) -> Map.of("allow", entries(api.writeRoot())));
        api.put("/settings/egress", ApiContext.withCapability("canAdminister", (e, m) -> replace(api, e, api.body(e))));
    }

    /** The Space's allowlist entries as written; empty when none (or unreadable). */
    static List<String> entries(Path root) {
        if (root == null) return List.of();
        Path f = root.resolve(FILE);
        if (!Files.exists(f)) return List.of();
        try {
            Object allow = ToonHelper.load(f.toString()).get("allow");
            List<String> out = new ArrayList<>();
            if (allow instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
            EgressPolicy.Allowlist.of(out);   // validate
            return out;
        } catch (Exception bad) {
            log.warn("[EGRESS] {} is unreadable or invalid ({}) — treating the egress allowlist as EMPTY", f, bad.getMessage());
            return List.of();
        }
    }

    /** The parsed allowlist of the Space whose config root is {@code root}. */
    static EgressPolicy.Allowlist allowlist(Path root) {
        return EgressPolicy.Allowlist.of(entries(root));
    }

    private Object replace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "egress allowlist write");
        for (String k : body.keySet())
            if (!"allow".equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' (expected allow)");
        if (!(body.getOrDefault("allow", List.of()) instanceof List<?> raw))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'allow' must be a list of host names or CIDRs");
        if (raw.size() > MAX_ENTRIES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + MAX_ENTRIES + " entries");
        List<String> next = new ArrayList<>();
        for (Object o : raw) next.add(String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT));
        try {
            EgressPolicy.Allowlist.of(next);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
        }
        List<String> before = entries(root);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("allow", next);
        AtomicFiles.write(root.resolve(FILE), JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".egress-");
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " changed the egress allowlist")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("egress-allowlist.changed").actionCategory("configuration")
                    .attr("before", ApiContext.JSON.writeValueAsString(before))
                    .attr("after", ApiContext.JSON.writeValueAsString(next)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best effort, like every audit emit on a request path
        }
        return Map.of("allow", next);
    }
}
