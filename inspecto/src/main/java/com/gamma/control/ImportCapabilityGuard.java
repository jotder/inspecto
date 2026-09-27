package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.job.JobConfig;
import com.gamma.pipeline.ComponentRegistry;
import com.sun.net.httpserver.HttpExchange;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 *
 * <p>The SAME table gates the generic {@code /components/{kind}} door ({@link #requireKind}), so
 * {@code /components/alert-rule} is never the wider door either; and {@link #DEDICATED_ONLY} kinds are refused there
 * and on every import but a new Space's, for ANY caller ({@code IMPORT-DEDICATED-ONLY-KINDS-1}).
 * {@code ImportCapabilityGuardTest} derives the table's completeness from {@link CapabilityManifest}.
 */
final class ImportCapabilityGuard {

    private ImportCapabilityGuard() {}

    /** Kind → the capability its direct write route requires beyond the import door's own. */
    static final Map<String, String> KIND_CAPABILITY = Map.of(
            "connection", Roles.CAN_ONBOARD_CONNECTIONS,
            "alert-rule", Roles.CAN_AUTHOR_ALERT_RULES,
            "findings-spec", Roles.CAN_MANAGE_INCIDENTS);

    /**
     * kind → its dedicated route, the ONLY door that writes it. Capability parity is not enough: the dedicated route
     * carries semantics a plain content write would skip.
     * <ul>
     *   <li>{@code access-profile} / {@code access-catalog} — {@code /access/*} ({@code canConfigureAccess}) validates
     *       them; deleting a profile through {@code /components} (a builder's door) WIDENS that subject's access.</li>
     *   <li>{@code requirement} — a triage lifecycle: anyone submits, only {@code canTriageRequirements} decides or
     *       delivers; a content write could set {@code status: delivered}.</li>
     * </ul>
     * A new Space ({@code /spaces/import}, {@code canAdminister}) may still carry them: it seeds a tree (operator
     * decision 2026-09-27).
     */
    static final Map<String, String> DEDICATED_ONLY = Map.of(
            "access-profile", "/access/profiles/{subject}",
            "access-catalog", "/access/catalog",
            "requirement", "/requirements (submit) and /requirements/{id}/decision|deliver (triage)");

    /** The generic {@code /components/{kind}} write door: a dedicated-only kind is refused for any caller, a stricter
     *  kind needs its own route's capability (on top of the door's {@code canAuthorWorkbench}). */
    static void requireKind(HttpExchange ex, String kind) {
        if (kind == null) return;
        String k = kind.toLowerCase(Locale.ROOT);
        refuseDedicated(k, "kind '" + k + "'");
        String capability = KIND_CAPABILITY.get(k);
        if (capability != null) ApiContext.requireCapability(ex, capability);
    }

    private static void refuseDedicated(String kind, String what) {
        String dedicated = DEDICATED_ONLY.get(kind);
        if (dedicated != null)
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, what + " is written through " + dedicated
                    + " only — nothing was written");
    }

    /** A {@code /bundle/import} envelope's items ({@code {kind, id, content}}), plus each pipeline item's closure files. */
    static void checkItems(HttpExchange ex, List<Map<String, Object>> items) {
        for (Map<String, Object> item : items) {
            String kind = ApiContext.str(item, "kind");
            if (kind == null) continue;
            kind = kind.toLowerCase(Locale.ROOT);
            refuseDedicated(kind, "an import carrying a '" + kind + "' item");
            if (KIND_CAPABILITY.containsKey(kind)) require(ex, kind, KIND_CAPABILITY.get(kind));
            if ("job".equals(kind) && item.get("content") instanceof Map<?, ?> c) requireIfAdministerOnly(ex, c);
        }
    }

    /** A raw file bundle (config-relative path → bytes): the {@code /import} zip, pipeline satellites. */
    static void checkFiles(HttpExchange ex, Map<String, byte[]> entries) {
        checkFiles(ex, entries, false);
    }

    /**
     * {@link #checkFiles(HttpExchange, Map)}; {@code newSpace} (only {@code /spaces/import}) lets the
     * {@link #DEDICATED_ONLY} kinds through. Each path is classified as the filesystem resolves it
     * ({@link #normalizedPath}), and a file under {@code registry/} whose kind cannot be told is refused.
     */
    static void checkFiles(HttpExchange ex, Map<String, byte[]> entries, boolean newSpace) {
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String rel = normalizedPath(e.getKey());
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            if (file.endsWith("_connection.toon")) require(ex, "connection", Roles.CAN_ONBOARD_CONNECTIONS);
            else if (file.endsWith("_job.toon")) requireIfAdministerOnly(ex, jobSection(e.getValue()));
            else if (rel.startsWith("registry/") || rel.contains("/registry/")) {
                String[] parts = rel.substring(rel.startsWith("registry/") ? 0 : rel.indexOf("/registry/") + 1).split("/");
                String kind = parts.length < 3 ? null : kindOfRegistryDir(parts[1]);
                if (kind == null && !rel.startsWith("registry/")) continue;   // a nested dir merely named registry
                if (kind == null)
                    throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "an import carrying '" + e.getKey()
                            + "' under registry/, whose kind cannot be told — nothing was written");
                if (!newSpace) refuseDedicated(kind, "an import carrying a '" + kind + "' item");
                if (KIND_CAPABILITY.containsKey(kind)) require(ex, kind, KIND_CAPABILITY.get(kind));
            }
        }
    }

    private static String kindOfRegistryDir(String dir) {
        if ("connections".equals(dir)) return "connection";
        for (String type : com.gamma.pipeline.ComponentStore.WRITABLE_TYPES)
            if (ComponentRegistry.dirForType(type).filter(dir::equals).isPresent()) return type;
        return null;
    }

    /**
     * {@code relPath} as the filesystem resolves it, for classification: {@code /} separators, no empty or {@code .}
     * segments, each segment lower-cased and stripped of the trailing dots and spaces Windows drops — so
     * {@code Registry/findings-specs./x.toon} is {@code registry/findings-specs/x.toon}.
     */
    static String normalizedPath(String relPath) {
        List<String> out = new ArrayList<>();
        for (String seg : relPath.replace('\\', '/').split("/")) {
            String s = seg.toLowerCase(Locale.ROOT);
            int end = s.length();
            while (end > 0 && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) end--;
            if (end > 0) out.add(s.substring(0, end));
        }
        return String.join("/", out);
    }

    private static Map<?, ?> jobSection(byte[] bytes) {
        try {
            Map<String, Object> m = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
            return m.get("job") instanceof Map<?, ?> j ? j : m;
        } catch (RuntimeException unparseable) {
            return Map.of();   // the loader / spec gate refuses a malformed job; nothing to judge here
        }
    }

    /** Parsed exactly as the Job loader parses it, so the verdict is {@link JobRoutes#requiresAdminister}'s;
     *  refused as {@code job (<task>)}, the task lower-cased as {@code MaintenanceJob} dispatches it. */
    @SuppressWarnings("unchecked")
    private static void requireIfAdministerOnly(HttpExchange ex, Map<?, ?> job) {
        Map<String, Object> j = new LinkedHashMap<>((Map<String, Object>) job);
        j.putIfAbsent("name", "import");
        try {
            JobConfig c = JobConfig.fromMap(Map.of("job", j));
            if (!JobRoutes.requiresAdminister(c, Roles.configRoot(ex))) return;
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
