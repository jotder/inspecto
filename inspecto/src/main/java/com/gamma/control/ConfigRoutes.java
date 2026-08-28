package com.gamma.control;

import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RouteArming;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shared pre-flight findings helpers for declarative-config drafts. The HTTP routes that used to
 * live here moved to {@link ConfigPreviewRoutes}, {@link ConfigWriteRoutes} and
 * {@link ConfigReadRoutes} (pure relocation — identical routes, statuses, gating order and
 * on-disk behaviour); the static helpers stay because other route modules
 * ({@code PipelineRoutes}, {@code RunRoutes}, {@code DataSourceRoutes}) and tests call them here.
 *
 * <p>{@link #schemaFileFindings} is shared with the pipeline-registration route that stays on
 * {@link ControlApi}; it lives here with the rest of the config-validation logic.
 */
final class ConfigRoutes {

    private ConfigRoutes() {}

    /**
     * Pre-flight check that a pipeline draft's schema reference(s) resolve on <em>this server's</em>
     * filesystem (v4.1.0). A draft that validates clean can otherwise still fail at registration
     * with an opaque 422 — this surfaces it early, as a structured finding anchored to the field.
     * Checks both the legacy {@code processing.schema_file} and the multi-schema
     * {@code processing.schemas[].schema_file}. No-op for non-pipeline types.
     *
     * <p>⚠ This must resolve references <b>exactly</b> the way {@link PipelineConfig#load} does, or it
     * becomes a gate that rejects configs the engine would happily run. Since W1b that means
     * config-relative first, working-directory second — hence {@code configDir}.
     *
     * @param severity  WARNING at validate/save time (the file may be created later, or the config
     *                  may be destined for another host); ERROR at register time (it will fail)
     * @param configDir directory the config lives in, so a config-relative reference resolves; {@code null}
     *                  for a draft with no home yet, which checks the working-directory form only
     */
    static List<Finding> schemaFileFindings(String type, Map<String, Object> draft, Severity severity,
                                            Path configDir) {
        if (!"pipeline".equals(type)) return List.of();
        Object procObj = draft.get("processing");
        if (!(procObj instanceof Map<?, ?> proc)) return List.of();
        List<Finding> out = new ArrayList<>();
        if (proc.get("schema_file") instanceof String s && !s.isBlank() && !resolves(s, configDir))
            out.add(new Finding(severity, "processing.schema_file", unresolvable(s)));
        if (proc.get("schemas") instanceof List<?> defs) {
            for (int i = 0; i < defs.size(); i++) {
                if (defs.get(i) instanceof Map<?, ?> def
                        && def.get("schema_file") instanceof String s && !s.isBlank()
                        && !resolves(s, configDir))
                    out.add(new Finding(severity, "processing.schemas[" + i + "].schema_file",
                            unresolvable(s)));
            }
        }
        return out;
    }

    /**
     * {@code active: true} with no schema source at all — the one draft shape that {@link
     * PipelineConfig#load} hard-throws on but spec validation accepts. Left unchecked, the write
     * succeeds, {@code ConfigRegistry.rebuild} logs a single WARN and omits the pipeline, and the
     * scheduler then skips it every cycle forever: no run, no failure, no operator signal.
     *
     * <p>Deliberately narrower than a full {@code PipelineConfig.fromMap} gate — parsing the draft
     * here would also hard-fail an <em>unresolvable</em> schema reference, which
     * {@link #schemaFileFindings} intentionally keeps a WARNING (the file may be created after the
     * save, or belong to another host). Mirrors {@code PipelineConfigParser}'s three schema sources.
     */
    static List<Finding> armedWithoutSchemaFindings(String type, Map<String, Object> draft) {
        if (!"pipeline".equals(type)) return List.of();
        if (!Boolean.parseBoolean(String.valueOf(draft.getOrDefault("active", "false"))))
            return List.of();
        Object procObj = draft.get("processing");
        Map<?, ?> proc = procObj instanceof Map<?, ?> m ? m : Map.of();
        Object parsingObj = draft.get("parsing");
        Map<?, ?> parsing = parsingObj instanceof Map<?, ?> m ? m : Map.of();
        Object plugin = parsing.get("plugin") instanceof Map<?, ?> pm ? pm.get("ingester") : null;
        boolean hasSchema =
                (proc.get("schema_file") instanceof String s && !s.isBlank())
                || (proc.get("schemas") instanceof List<?> l && !l.isEmpty())
                || (proc.get("ingester") instanceof String i && !i.isBlank())
                || (plugin instanceof String p && !p.isBlank());
        if (hasSchema) return List.of();
        return List.of(new Finding(Severity.ERROR, "active",
                "active: true but no schema is configured (processing.schema_file, "
                        + "processing.schemas[], or a plugin ingester) — keep the draft inactive "
                        + "until its schema is attached"));
    }

    /**
     * A {@code route:} block that would refuse to ARM. Until now these six rules fired only at
     * {@code PipelineConfig.prepare()} — i.e. at REGISTRATION, after the save returned
     * {@code written:true} — so an operator authored a branch tree, saved it happily, and learned it
     * was unarmable when the next run threw. The whole point of a fail-closed gate is that the
     * operator can act on it; one that fires after they have moved on is a log line.
     *
     * <p>Severity follows what the save would actually cause, which is why it is not always an ERROR:
     * <ul>
     *   <li>{@code active: true} → <b>ERROR</b>. This config cannot run. Writing it produces exactly
     *       the outcome {@code armedWithoutSchemaFindings} above exists to prevent — a pipeline that
     *       registers and then fails, or is skipped, every cycle.</li>
     *   <li>{@code active: false} → <b>WARNING</b>. An inactive draft is a legitimate work in
     *       progress; {@code prepare()} does not check it either. But it is worth saying now that
     *       activating it will refuse, rather than at the moment the operator flips the switch.</li>
     * </ul>
     *
     * <p>All refusals are reported, not just the first: {@code prepare()} throws one because
     * registration is all-or-nothing, but an author fixing a branch list wants the whole list rather
     * than a one-at-a-time game.
     */
    static List<Finding> routeArmingFindings(String type, Map<String, Object> draft) {
        if (!"pipeline".equals(type)) return List.of();
        if (!(draft.get("route") instanceof Map<?, ?> route)) return List.of();
        boolean active = Boolean.parseBoolean(String.valueOf(draft.getOrDefault("active", "false")));
        Severity severity = active ? Severity.ERROR : Severity.WARNING;
        Map<?, ?> proc    = draft.get("processing") instanceof Map<?, ?> m ? m : Map.of();
        Map<?, ?> parsing = draft.get("parsing")    instanceof Map<?, ?> m ? m : Map.of();
        List<Finding> out = new ArrayList<>();
        for (String refusal : RouteArming.refusals(route,
                RouteArming.draftSinkDatabases(draft.get("sinks")),
                RouteArming.draftIsMultiSchema(proc, parsing))) {
            out.add(new Finding(severity, "route", active
                    ? refusal
                    : refusal + " (the draft is inactive, so this refuses only once it is activated)"));
        }
        return out;
    }

    /**
     * {@code processing.disabled_steps} arming findings (Phase 4 S4 / D-13) — the save-time half of
     * {@link com.gamma.etl.StepDisableArming}, with exactly {@link #routeArmingFindings}' severity
     * split: an active pipeline cannot run with a disabled step until park/drain ships (ERROR), an
     * inactive draft is a legitimate work in progress (WARNING, so mid-authoring saves keep working).
     */
    static List<Finding> stepDisableFindings(String type, Map<String, Object> draft) {
        if (!"pipeline".equals(type)) return List.of();
        Map<?, ?> proc = draft.get("processing") instanceof Map<?, ?> m ? m : Map.of();
        List<String> disabled = com.gamma.etl.StepDisableArming.draftDisabledSteps(proc);
        if (disabled.isEmpty()) return List.of();
        boolean active = Boolean.parseBoolean(String.valueOf(draft.getOrDefault("active", "false")));
        Severity severity = active ? Severity.ERROR : Severity.WARNING;
        Map<?, ?> route = draft.get("route") instanceof Map<?, ?> r ? r : null;
        List<String> parkable = com.gamma.etl.StepDisableArming.parkableSinkIds(
                route, RouteArming.draftSinkDatabases(draft.get("sinks")));
        List<Finding> out = new ArrayList<>();
        for (String refusal : com.gamma.etl.StepDisableArming.refusals(disabled, parkable)) {
            out.add(new Finding(severity, "disabled_steps", active
                    ? refusal
                    : refusal + " (the draft is inactive, so this refuses only once it is activated)"));
        }
        return out;
    }

    /**
     * A <b>remote</b> collector whose {@code connection} names a profile this space does not have. Left
     * unchecked the dangling id reaches the poll cycle, where {@code CollectorConnectors.forConfig} resolves
     * it to {@code null} and the connector factory throws — on <em>every</em> cycle, never once, and never
     * at the moment the operator could fix it. Bundle import has always refused this
     * ({@code DataSourceRoutes.referentialFindings}, same field path); a plain save did not.
     *
     * <p><b>Only when the connector is remote</b>, because that is the only case that resolves the binding:
     * {@code CollectorConnectors.forConfig} short-circuits to the local connector first and never looks the
     * id up, so a {@code connection} left behind on a {@code local} collector is inert, not broken. Refusing
     * it would reject configs that run fine today — and does: it fails five {@code /config/patch} fixtures
     * whose seed is exactly that shape. A blank/absent connector is the legacy no-{@code collector:}-block
     * case and counts as local, matching {@code CollectorConnectors.isRemote}. Flipping such a config to a
     * remote connector goes through this same gate (write or patch), which is where it starts to matter.
     *
     * <p>Checked against the live {@code ConnectionProfileRegistry} — the same source of truth the import
     * path uses, updated in the same request by every connection write. One blind spot: a
     * {@code *_connection.toon} copied straight onto disk with no restart since. There is no rescan route,
     * so it is invisible here exactly as it is to the import check and to the run itself.
     */
    static List<Finding> unknownConnectionFindings(String type, Map<String, Object> draft, ApiContext api) {
        if (!"pipeline".equals(type) || api == null) return List.of();
        if (!(draft.get("collector") instanceof Map<?, ?> collector)) return List.of();
        Object scheme = collector.get("connector");
        String connector = scheme == null ? "" : String.valueOf(scheme).trim();
        if (connector.isEmpty() || "local".equalsIgnoreCase(connector)) return List.of();
        Object id = collector.get("connection");
        if (id == null) return List.of();
        String conn = String.valueOf(id).trim();
        if (conn.isEmpty() || api.service().connections().containsKey(conn)) return List.of();
        return List.of(new Finding(Severity.ERROR, "collector.connection",
                "unknown connection '" + conn + "' — no such connection profile in this space;"
                        + " create the connection first, or clear collector.connection"));
    }

    /**
     * For a draft with no directory to be relative to — a lowered graph or a template body that is
     * not landing anywhere yet. A config-relative reference cannot be checked, so this reports on the
     * CWD alone. ⚠ Prefer the 4-arg form wherever the prospective directory IS known: the portable
     * bare `<name>.toon` the UI writes resolves config-relative FIRST and will look unresolvable here.
     */
    static List<Finding> schemaFileFindings(String type, Map<String, Object> draft, Severity severity) {
        return schemaFileFindings(type, draft, severity, null);
    }

    /** Mirrors {@code PipelineConfigParser.resolveSchemaRef}: config-relative first, then the CWD. */
    private static boolean resolves(String ref, Path configDir) {
        Path asAuthored = Path.of(ref);
        if (configDir != null && !asAuthored.isAbsolute()) {
            Path base      = configDir.toAbsolutePath().normalize();
            Path candidate = base.resolve(asAuthored).normalize();
            if (candidate.startsWith(base) && Files.isRegularFile(candidate)) return true;
        }
        return Files.isRegularFile(asAuthored);
    }

    private static String unresolvable(String schemaPath) {
        return "schema file does not resolve on the server: '" + schemaPath
                + "' (a relative reference resolves beside its own config file first, then against"
                + " the server's working directory: " + Path.of("").toAbsolutePath() + ")";
    }
}
