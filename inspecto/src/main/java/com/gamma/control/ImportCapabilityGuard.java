package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.job.JobConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>An import is never a way around the direct route's gate</b> ({@code IMPORT-CONNECTION-JOB-GATE-1}, session
 * decision 2026-09-27). Every import door is {@code canAuthorWorkbench} (or {@code canAdminister} for a new Space),
 * but some kinds it can carry are written, on their own route, under a different capability. Each such item
 * needs THAT capability too:
 * <ul>
 *   <li>{@code connection} ({@code *_connection.toon}) — {@code canOnboardConnections}, as {@code /connections};</li>
 *   <li>{@code alert-rule} ({@code registry/alert-rules/}) — {@code canAuthorAlertRules}, as {@code /alerts/rules};</li>
 *   <li>{@code findings-spec} ({@code registry/findings-specs/}) — {@code canManageIncidents}, as
 *       {@code /components/findings-spec};</li>
 *   <li>an {@code event_prune} or {@code restore} Job ({@code *_job.toon}) — {@code canAdminister}, as {@code /jobs}
 *       ({@link JobRoutes#isAdministerOnlyMaintenance}).</li>
 * </ul>
 * Every door calls {@link #checkItems} or {@link #checkFiles} BEFORE its first write: the first missing capability
 * refuses the whole import (403, naming the kind and the capability). A no-op without a Subject (Personal), like
 * every capability check. The access config, the role table and the settings documents are not here: no import
 * writes them at all ({@code ReservedConfigPaths}).
 */
final class ImportCapabilityGuard {

    private ImportCapabilityGuard() {}

    /** Kind → the capability its direct write route requires beyond the import door's own. */
    static final Map<String, String> KIND_CAPABILITY = Map.of(
            "connection", Roles.CAN_ONBOARD_CONNECTIONS,
            "alert-rule", Roles.CAN_AUTHOR_ALERT_RULES,
            "findings-spec", Roles.CAN_MANAGE_INCIDENTS);

    /** A {@code /bundle/import} envelope's items ({@code {kind, id, content}}), plus each pipeline item's closure files. */
    static void checkItems(HttpExchange ex, List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            String kind = ApiContext.str(item, "kind");
            if (kind == null) continue;
            if (KIND_CAPABILITY.containsKey(kind)) require(ex, kind, KIND_CAPABILITY.get(kind));
            if ("job".equals(kind) && item.get("content") instanceof Map<?, ?> c) requireIfAdministerOnly(ex, c);
        }
    }

    /** A raw file bundle (config-relative path → bytes): the {@code /import} zip, satellites, a new Space's bundle. */
    static void checkFiles(HttpExchange ex, Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String rel = e.getKey().replace('\\', '/').toLowerCase(Locale.ROOT);
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            if (file.endsWith("_connection.toon")) require(ex, "connection", Roles.CAN_ONBOARD_CONNECTIONS);
            else if (file.endsWith("_job.toon")) requireIfAdministerOnly(ex, jobSection(e.getValue()));
            else if (rel.contains("registry/")) {
                String[] parts = rel.substring(rel.indexOf("registry/") + "registry/".length()).split("/");
                if (parts.length < 2) continue;
                for (Map.Entry<String, String> k : KIND_CAPABILITY.entrySet())
                    if (ComponentRegistry.dirForType(k.getKey()).filter(parts[0]::equals).isPresent())
                        require(ex, k.getKey(), k.getValue());
            }
        }
    }

    private static Map<?, ?> jobSection(byte[] bytes) {
        try {
            Map<String, Object> m = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
            return m.get("job") instanceof Map<?, ?> j ? j : m;
        } catch (RuntimeException unparseable) {
            return Map.of();   // the loader / spec gate refuses a malformed job; nothing to judge here
        }
    }

    /** Parsed exactly as the Job loader parses it, so the verdict is {@link JobRoutes#isAdministerOnlyMaintenance}'s;
     *  refused as {@code job (<task>)}, the task lower-cased as {@code MaintenanceJob} dispatches it. */
    @SuppressWarnings("unchecked")
    private static void requireIfAdministerOnly(HttpExchange ex, Map<?, ?> job) {
        Map<String, Object> j = new LinkedHashMap<>((Map<String, Object>) job);
        j.putIfAbsent("name", "import");
        try {
            JobConfig c = JobConfig.fromMap(Map.of("job", j));
            if (!JobRoutes.isAdministerOnlyMaintenance(c)) return;
            require(ex, "job (" + c.opt("task", "").toLowerCase(Locale.ROOT) + ")", Roles.CAN_ADMINISTER);
        } catch (ApiException denied) {
            throw denied;
        } catch (RuntimeException unparseable) {
            // the spec gate refuses a malformed job
        }
    }

    private static void require(HttpExchange ex, String kind, String capability) {
        try {
            ApiContext.requireCapability(ex, capability);
        } catch (ApiException denied) {
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "an import carrying a '" + kind
                    + "' item needs capability '" + capability + "' — the same gate as that kind's own route");
        }
    }
}
