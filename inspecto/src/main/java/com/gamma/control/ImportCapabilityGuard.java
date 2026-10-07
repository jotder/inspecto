package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.auth.Subject;
import com.gamma.spi.http.ApiContext;
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
import com.gamma.access.Roles;
import com.gamma.access.CapabilityManifest;

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

    /**
     * ASSURE-WORKFLOW-SLA-1: governance kinds written ONLY through their {@code canAdminister} literal routes — refused
     * on the generic {@code /components} door and on EVERY import, a new Space's included (unlike
     * {@link #DEDICATED_ONLY}). A Workflow decides how an Incident may finish; like the {@code *_workflow.toon} suffix
     * ({@code ImportPaths.REFUSED_SUFFIXES}) it never rides a bundle.
     */
    static final Map<String, String> GOVERNANCE_ONLY = Map.of(
            "workflow", "/components/workflow",
            "sla-policy", "/components/sla-policy",
            "escalation-rule", "/components/escalation-rule");

    /** The generic {@code /components/{kind}} write door: a dedicated-only kind is refused for any caller, a stricter
     *  kind needs its own route's capability (on top of the door's {@code canAuthorWorkbench}). */
    static void requireKind(HttpExchange ex, String kind) {
        if (kind == null) return;
        String k = kind.toLowerCase(Locale.ROOT);
        refuseDedicated(k, "kind '" + k + "'");
        refuseGovernance(k, "kind '" + k + "'");
        String capability = KIND_CAPABILITY.get(k);
        if (capability != null) ApiContext.requireCapability(ex, capability);
    }

    private static void refuseGovernance(String kind, String what) {
        String route = GOVERNANCE_ONLY.get(kind);
        if (route != null)
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, what + " is a governance change written through "
                    + route + " only (canAdminister) — no import carries it; nothing was written");
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
            refuseGovernance(kind, "an import carrying a '" + kind + "' item");
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
        // ASSURE-XLSX-ATTACHMENTS-1 round 4: a carried *_job.toon is judged AFTER its template is expanded — against
        // the templates this import carries AND the Space's own — so `template:` + `attach: "true"` cannot slip by.
        Map<String, com.gamma.job.JobTemplate> templates = new java.util.LinkedHashMap<>(
                com.gamma.job.AttachApprovals.templates(ex == null ? null : Roles.configRoot(ex)));
        for (Map.Entry<String, byte[]> e : entries.entrySet())
            if (normalizedPath(e.getKey()).endsWith("_job_template.toon")) {
                com.gamma.job.JobTemplate t = templateOf(e.getValue());
                if (t != null) templates.put(t.name(), t);
            }
        IMPORT_TEMPLATES.set(templates);
        try {
            checkEachFile(ex, entries, newSpace);
        } finally {
            IMPORT_TEMPLATES.remove();
        }
    }

    /** The templates the import in progress resolves against (set by {@link #checkFiles}); unset for item imports. */
    private static final ThreadLocal<Map<String, com.gamma.job.JobTemplate>> IMPORT_TEMPLATES = new ThreadLocal<>();

    /** A carried {@code *_job_template.toon}, parsed as {@code JobTemplate.load} parses a file; {@code null} if malformed. */
    private static com.gamma.job.JobTemplate templateOf(byte[] bytes) {
        try {
            Map<String, Object> m = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
            if (!(m.get("job_template") instanceof Map<?, ?> t) || !(t.get("job") instanceof Map<?, ?> job)) return null;
            Map<String, String> defaults = new LinkedHashMap<>();
            if (t.get("params") instanceof Map<?, ?> p)
                p.forEach((k, v) -> defaults.put(String.valueOf(k),
                        (v == null || (v instanceof Map<?, ?> mm && mm.isEmpty())) ? "" : String.valueOf(v)));
            @SuppressWarnings("unchecked") Map<String, Object> block = (Map<String, Object>) job;
            return new com.gamma.job.JobTemplate(String.valueOf(t.get("name")), defaults, block);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static void checkEachFile(HttpExchange ex, Map<String, byte[]> entries, boolean newSpace) {
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            String rel = normalizedPath(e.getKey());
            String file = rel.substring(rel.lastIndexOf('/') + 1);
            if (file.endsWith("_pipeline.toon") || file.endsWith("_enrich.toon")) refuseDataHomes(ex, e.getKey(), e.getValue());
            if (file.endsWith("_connection.toon")) require(ex, "connection", Roles.CAN_ONBOARD_CONNECTIONS);
            else if (file.endsWith("_job.toon")) requireIfAdministerOnly(ex, jobSection(e.getValue()));
            else if (file.endsWith("_job_template.toon")) refuseAttachingTemplate(e.getKey(), e.getValue());
            else if (rel.startsWith(PendingAlertRules.DIR + "/"))   // a deferred Alert Rule (TEMPLATE-RISK-SCORE-ALERT-RULE-1)
                require(ex, "alert-rule", KIND_CAPABILITY.get("alert-rule"));
            else if (rel.startsWith("registry/") || rel.contains("/registry/")) {
                String[] parts = rel.substring(rel.startsWith("registry/") ? 0 : rel.indexOf("/registry/") + 1).split("/");
                String kind = parts.length < 3 ? null : kindOfRegistryDir(parts[1]);
                if (kind == null && !rel.startsWith("registry/")) continue;   // a nested dir merely named registry
                if (kind == null)
                    throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "an import carrying '" + e.getKey()
                            + "' under registry/, whose kind cannot be told — nothing was written");
                if (!newSpace) refuseDedicated(kind, "an import carrying a '" + kind + "' item");
                refuseGovernance(kind, "an import carrying a '" + kind + "' item");   // a new Space's too
                if (KIND_CAPABILITY.containsKey(kind)) require(ex, kind, KIND_CAPABILITY.get(kind));
            }
        }
    }

    /** The Pipeline keys whose values become directories its sealed ingest connection may read. */
    private static final String[] DATA_HOME_KEYS = {"poll", "database", "backup", "temp", "errors", "quarantine",
            "markers", "status_dir", "log_dir"};

    /**
     * SEC-INGEST-EXPR-EXTERNAL-ACCESS-1: a carried Pipeline whose {@code dirs.*}, {@code sinks[].database} or
     * spill dir is a Space root, a {@code config/} tree or a {@code *.secrets} directory is refused (403) before
     * anything is written — the ingest seal allowlists those dirs, so an authored {@code fn: custom} expression
     * could read the Space's configs and its Pending Change key. A relative value is judged against the Space it
     * lands in (a stand-in base for a new Space); an absolute one against every hosted Space.
     */
    private static void refuseDataHomes(HttpExchange ex, String path, byte[] bytes) {
        Map<String, Object> m;
        try {
            m = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
        } catch (RuntimeException unparseable) {
            return;   // the loader refuses a malformed file
        }
        Map<?, ?> p = m.get("pipeline") instanceof Map<?, ?> nested ? nested : m;
        List<String[]> values = new ArrayList<>();
        if (p.get("dirs") instanceof Map<?, ?> dirs)
            for (String k : DATA_HOME_KEYS) if (dirs.get(k) != null) values.add(new String[]{"dirs." + k, String.valueOf(dirs.get(k))});
        if (p.get("sinks") instanceof List<?> sinks)
            for (int i = 0; i < sinks.size(); i++)
                if (sinks.get(i) instanceof Map<?, ?> s && s.get("database") != null)
                    values.add(new String[]{"sinks[" + i + "].database", String.valueOf(s.get("database"))});
        if (p.get("processing") instanceof Map<?, ?> proc && proc.get("duckdb") instanceof Map<?, ?> d
                && d.get("temp_directory") != null)
            values.add(new String[]{"processing.duckdb.temp_directory", String.valueOf(d.get("temp_directory"))});
        // An Enrichment (`_enrich.toon`): its sealed connection allowlists input/output.database and each path
        // reference's directory (SEC-ENRICH-TRANSFORM-SQL-UNSEALED-1).
        for (String sec : new String[]{"input", "output"})
            if (m.get(sec) instanceof Map<?, ?> s && s.get("database") != null)
                values.add(new String[]{sec + ".database", String.valueOf(s.get("database"))});
        if (m.get("references") instanceof Map<?, ?> refs)
            for (Map.Entry<?, ?> r : refs.entrySet())
                if (r.getValue() instanceof Map<?, ?> rv && rv.get("path") != null) {
                    String raw = String.valueOf(rv.get("path")).trim();
                    String dir;
                    try {
                        java.nio.file.Path parent = java.nio.file.Paths.get(raw).getParent();
                        dir = parent == null ? "." : parent.toString();
                    } catch (java.nio.file.InvalidPathException bad) {
                        dir = raw;   // refused (403) by the loop below, which parses it again
                    }
                    values.add(new String[]{"references." + r.getKey() + ".path", dir});
                }
        java.nio.file.Path configRoot = ex == null ? null : Roles.configRoot(ex);
        java.nio.file.Path stand = java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "import-space-stand-in")
                .toAbsolutePath().normalize();
        for (String[] kv : values) {
            String v = kv[1].trim();
            if (v.isEmpty() || com.gamma.config.safety.PathJail.isUri(v)) continue;
            try {
                java.nio.file.Path authored = java.nio.file.Paths.get(v);
                if (authored.isAbsolute()) {
                    List<java.nio.file.Path> spaces = new ArrayList<>(com.gamma.config.safety.DiscoveredRoots.all());
                    if (configRoot != null && configRoot.getParent() != null) spaces.add(configRoot.getParent());
                    com.gamma.config.safety.PathJail.refuseDataHome(authored.normalize(), null, spaces, v, kv[0]);
                } else {
                    com.gamma.config.safety.PathJail.refuseDataHome(stand.resolve(authored).normalize(), stand,
                            null, v, kv[0]);
                }
            } catch (com.gamma.config.safety.PathJail.Escape | java.nio.file.InvalidPathException refused) {
                throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "an import carrying '" + path + "': "
                        + refused.getMessage() + " — nothing was written");
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

    /**
     * ASSURE-XLSX-ATTACHMENTS-1 round 3 (defence in depth; the real lock is run-time, {@code AttachApprovals}): a
     * {@code *_job_template.toon} whose report job block carries any {@code attach} other than a literal false (a
     * {@code ${placeholder}} included) would expand into attaching Jobs at load with no Pending Change — refused.
     */
    private static void refuseAttachingTemplate(String path, byte[] bytes) {
        Map<?, ?> job;
        try {
            Map<String, Object> m = ConfigCodec.toMap(new String(bytes, StandardCharsets.UTF_8));
            job = m.get("job_template") instanceof Map<?, ?> t && t.get("job") instanceof Map<?, ?> j ? j : Map.of();
        } catch (RuntimeException unparseable) {
            return;   // the template loader refuses a malformed file
        }
        Object attach = job.get("attach");
        if ("report".equalsIgnoreCase(String.valueOf(job.get("type")).trim()) && attach != null
                && !"false".equalsIgnoreCase(String.valueOf(attach).trim()))
            throw new ApiException(403, ErrorCodes.PERMISSION_DENIED, "'" + path + "': a report job that attaches "
                    + "data needs approval; create it through /jobs");
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
        // ASSURE-BI-PUBLICATION-1: a publish.postgres Job always needs four-eyes approval, which a bulk import
        // cannot give — so no import (bundle, zip, Space, template) may carry one, whoever asks.
        if (PendingChanges.isPublication(job))
            throw new ApiException(409, ErrorCodes.CONFLICT, "an import carrying a publish.postgres Job is refused — "
                    + "create it with POST /jobs so it can be approved (four-eyes); nothing was written");
        // ASSURE-XLSX-ATTACHMENTS-1: an attaching report Job needs four-eyes approval, which N imported items cannot
        // get — refused outright (403), whatever the policy, before anything is written.
        JobWriteGuard.refuseImport(job, IMPORT_TEMPLATES.get() != null ? IMPORT_TEMPLATES.get()
                : com.gamma.job.AttachApprovals.templates(ex == null ? null : Roles.configRoot(ex)));
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
