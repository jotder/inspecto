package com.gamma.control;

import com.gamma.audit.Event;
import com.gamma.audit.EventLog;
import com.gamma.audit.EventType;
import com.gamma.job.PublicationDestinations;
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
 * A Space's <b>publication destination allowlist</b> (ASSURE-BI-PUBLICATION-1, operator 2026-09-29): the exact hosts
 * a {@code publish.postgres} Job may write Datasets to, on top of the egress policy.
 * <pre>
 *   GET /settings/publication-destinations     {hosts: [...]}
 *   PUT /settings/publication-destinations     replace it — canAdminister, validated fail closed (422), audited
 * </pre>
 * EMPTY by default (no file ⇒ no destination), reserved from every import ({@code ReservedConfigPaths}), and, like
 * {@code /settings/egress}, a Space setting no approval policy governs.
 */
final class PublicationDestinationRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/settings/publication-destinations", (e, m) -> Map.of("hosts", PublicationDestinations.hosts(api.writeRoot())));
        api.put("/settings/publication-destinations",
                ApiContext.withCapability("canAdminister", (e, m) -> replace(api, e, api.body(e))));
    }

    private Object replace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "publication destination write");
        for (String k : body.keySet())
            if (!"hosts".equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' (expected hosts)");
        if (!(body.getOrDefault("hosts", List.of()) instanceof List<?> raw))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'hosts' must be a list of host names");
        if (raw.size() > PublicationDestinations.MAX_ENTRIES)
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "at most " + PublicationDestinations.MAX_ENTRIES + " hosts");
        List<String> next = new ArrayList<>();
        for (Object o : raw) {
            try {
                next.add(PublicationDestinations.validated(String.valueOf(o)));
            } catch (IllegalArgumentException bad) {
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, bad.getMessage());
            }
        }
        List<String> before = PublicationDestinations.hosts(root);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("hosts", next);
        AtomicFiles.write(root.resolve(PublicationDestinations.FILE), JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".pubdest-");
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " changed the publication destination allowlist")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("publication-destinations.changed").actionCategory("configuration")
                    .attr("before", ApiContext.JSON.writeValueAsString(before))
                    .attr("after", ApiContext.JSON.writeValueAsString(next)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best effort, like every audit emit on a request path
        }
        return Map.of("hosts", next);
    }
}
