package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.notify.MailAttachDomains;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import dev.toonformat.jtoon.JToon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Space's <b>attachment recipient domain allowlist</b> (operator decision 2026-09-29, ASSURE-XLSX-ATTACHMENTS-1),
 * stored exactly as the egress allowlist is ({@link EgressRoutes}): {@value MailAttachDomains#FILE} in the Space
 * config root, EMPTY by default — so a mail carrying an attachment is refused until an admin names a domain.
 * <pre>
 *   GET /settings/mail-attachments     {allow: [...]}
 *   PUT /settings/mail-attachments     replace it — canAdminister, validated fail closed (422), audited before/after
 * </pre>
 * <p>⛔ Not under the approval policy, for the egress allowlist's reason: no policy kind covers Space settings.
 * Reserved from every import ({@code ReservedConfigPaths}): an import that widened it would be an exfiltration door.
 */
final class MailAttachmentRoutes implements RouteModule {

    @Override
    public void register(ApiContext api) {
        api.get("/settings/mail-attachments", (e, m) -> Map.of("allow", MailAttachDomains.entries(api.writeRoot())));
        api.put("/settings/mail-attachments",
                ApiContext.withCapability("canAdminister", (e, m) -> replace(api, e, api.body(e))));
    }

    private Object replace(ApiContext api, HttpExchange ex, Map<String, Object> body) throws IOException {
        Path root = WriteGates.requireWriteRoot(api, "mail attachment domain allowlist write");
        for (String k : body.keySet())
            if (!"allow".equals(k))
                throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "unknown key '" + k + "' (expected allow)");
        if (!(body.getOrDefault("allow", List.of()) instanceof List<?> raw))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "'allow' must be a list of domain names");
        List<String> next;
        try {
            next = MailAttachDomains.normaliseAll(raw);
        } catch (IllegalArgumentException refused) {
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, refused.getMessage());
        }
        List<String> before = MailAttachDomains.entries(root);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("allow", next);
        AtomicFiles.write(root.resolve(MailAttachDomains.FILE), JToon.encode(doc).getBytes(StandardCharsets.UTF_8), ".mailatt-");
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message(ApiContext.actor(ex) + " changed the mail attachment domain allowlist")
                    .actor(ApiContext.actor(ex)).actorType(ApiContext.actorType(ex))
                    .action("mail-attachment-domains.changed").actionCategory("configuration")
                    .attr("before", ApiContext.JSON.writeValueAsString(before))
                    .attr("after", ApiContext.JSON.writeValueAsString(next)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // best effort, like every audit emit on a request path
        }
        return Map.of("allow", next);
    }
}
