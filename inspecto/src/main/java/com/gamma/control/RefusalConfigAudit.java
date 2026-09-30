package com.gamma.control;

import com.gamma.event.Event;
import com.gamma.event.EventLog;
import com.gamma.event.EventType;
import com.gamma.util.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Every control-plane write of a Pipeline config goes through {@link #write}: the file is written atomically as before,
 * and when the write CHANGES any content-refusal key ({@code processing.refusal}, {@code refusal_scan},
 * {@code refusal_scan_exempt}, {@code refusal_retention_days}) one AUDIT event {@code pipeline.refusal.changed} names
 * the change (before / after of those keys only). Turning the card scan off, or widening its exempt list, can then
 * never happen silently. A config that does not parse on either side is judged by the write's own gates, not here.
 */
final class RefusalConfigAudit {

    static final List<String> KEYS = List.of("refusal", "refusal_retention_days", "refusal_scan", "refusal_scan_exempt");

    private RefusalConfigAudit() {}

    static void write(Path target, byte[] bytes, String tempPrefix) throws IOException {
        Map<String, Object> before = Files.isRegularFile(target) ? keys(Files.readAllBytes(target)) : Map.of();
        AtomicFiles.write(target, bytes, tempPrefix);
        Map<String, Object> after = keys(bytes);
        if (Objects.equals(before, after)) return;
        try {
            EventLog.current().emit(Event.builder(EventType.AUDIT).source("audit")
                    .message("Pipeline config '" + target.getFileName() + "' changed its content refusal: "
                            + before + " -> " + after)
                    .action("pipeline.refusal.changed").actionCategory("security")
                    .attr("file", target.getFileName().toString())
                    .attr("before", ApiContext.JSON.writeValueAsString(before))
                    .attr("after", ApiContext.JSON.writeValueAsString(after)));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException auditDown) {
            // the write succeeded; the generic AuditTrail row still records the request
        }
    }

    /** The refusal keys of a Pipeline TOON's {@code processing} block (absent keys omitted); empty if it does not parse. */
    static Map<String, Object> keys(byte[] toon) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Object decoded = com.gamma.config.io.ConfigCodec.toMap(new String(toon, java.nio.charset.StandardCharsets.UTF_8));
            if (decoded instanceof Map<?, ?> m && m.get("processing") instanceof Map<?, ?> proc)
                for (String k : KEYS) if (proc.get(k) != null) out.put(k, String.valueOf(proc.get(k)));
        } catch (RuntimeException unparseable) {
            return out;
        }
        return out;
    }
}
