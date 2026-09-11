package com.gamma.control;

import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.etl.PipelineConfig;
import com.gamma.etl.RouteArming;
import com.gamma.etl.TypeFlow;
import com.gamma.query.MeasureCompiler;
import com.gamma.sql.SqlGuard;

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

    /** Guidance for an arming refusal on an ACTIVE config — the save itself is refused. */
    private static final String GUIDANCE_ACTIVE =
            "apply the fix the message names, or set active: false to keep authoring — an active "
                    + "config that cannot arm is refused at the save";

    /** Guidance for the same refusal on an INACTIVE draft — it saved; the refusal bites at activation. */
    private static final String GUIDANCE_INACTIVE =
            "the draft saved as a work in progress; apply the fix the message names before "
                    + "activating — the draft is inactive, so this refuses only once it is activated";

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
        // The message says what is WRONG (naming the schema sources checked); guidance says what to
        // DO — split, not duplicated, per R1's diagnostic contract.
        return List.of(new Finding(Severity.ERROR, "active",
                "active: true but no schema is configured (processing.schema_file, "
                        + "processing.schemas[], or a plugin ingester)",
                FindingCodes.ERR_ARMED_WITHOUT_SCHEMA,
                "keep the draft inactive until its schema is attached"));
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
            // The RouteArming message names the offending entities AND its own fix (shared verbatim
            // with prepare()'s throw); guidance carries the save-time what-to-do half that used to
            // ride fused into the inactive message.
            out.add(new Finding(severity, "route", refusal,
                    active ? FindingCodes.ERR_ROUTE_UNARMABLE : FindingCodes.WARN_ROUTE_UNARMABLE,
                    active ? GUIDANCE_ACTIVE : GUIDANCE_INACTIVE));
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
        Map<?, ?> dirs = draft.get("dirs") instanceof Map<?, ?> d ? d : Map.of();
        List<Finding> out = new ArrayList<>();
        for (String refusal : com.gamma.etl.StepDisableArming.refusals(disabled, parkable,
                com.gamma.etl.StepDisableArming.draftHasParkHome(dirs))) {
            out.add(new Finding(severity, "disabled_steps", refusal,
                    active ? FindingCodes.ERR_STEP_DISABLE_UNPARKABLE
                           : FindingCodes.WARN_STEP_DISABLE_UNPARKABLE,
                    active ? GUIDANCE_ACTIVE : GUIDANCE_INACTIVE));
        }
        return out;
    }

    /**
     * A windowed {@code dedup} ({@code scope: window(...)}) that would refuse to run (D-9) — either the
     * {@code scope:} spelling is malformed, or the window lacks its REQUIRED {@code order_by} tie-break
     * ({@link com.gamma.consignment.DedupScope#refusal}: against a <b>durable</b> ledger a
     * non-deterministic winner is unrepeatable data loss, not merely a latent bug). Checked in both
     * spellings a dedup config can take — the legacy {@code processing.dedup} block and each
     * {@code steps[]} entry of kind {@code dedup} — with exactly {@link #routeArmingFindings}' severity
     * split: ACTIVE ⇒ ERROR (the save is refused), inactive draft ⇒ WARNING (it saves; the refusal
     * bites at activation and again at run, {@code RowShaper.dedup}'s backstop).
     */
    static List<Finding> dedupWindowFindings(String type, Map<String, Object> draft) {
        if (!"pipeline".equals(type)) return List.of();
        boolean active = Boolean.parseBoolean(String.valueOf(draft.getOrDefault("active", "false")));
        Severity severity = active ? Severity.ERROR : Severity.WARNING;
        List<Finding> out = new ArrayList<>();
        Map<?, ?> proc = draft.get("processing") instanceof Map<?, ?> m ? m : Map.of();
        if (proc.get("dedup") instanceof Map<?, ?> dd)
            addDedupWindowFinding(out, severity, active, "processing.dedup", dd);
        if (draft.get("steps") instanceof List<?> steps) {
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i) instanceof Map<?, ?> entry && entry.get("dedup") instanceof Map<?, ?> dd)
                    addDedupWindowFinding(out, severity, active, "steps[" + i + "].dedup", dd);
            }
        }
        return out;
    }

    /** One dedup config block's windowed-scope refusal, if any, as a finding anchored at {@code field}. */
    private static void addDedupWindowFinding(List<Finding> out, Severity severity, boolean active,
                                              String field, Map<?, ?> dedup) {
        Object scopeRaw = dedup.get("scope");
        if (scopeRaw == null || String.valueOf(scopeRaw).isBlank()) return;
        Object orderBy = dedup.get("order_by");
        String refusal;
        try {
            refusal = com.gamma.consignment.DedupScope.refusal(
                    com.gamma.consignment.DedupScope.parse(String.valueOf(scopeRaw)),
                    orderBy == null ? null : String.valueOf(orderBy));
        } catch (IllegalArgumentException malformed) {
            refusal = malformed.getMessage();
        }
        if (refusal == null) return;
        out.add(new Finding(severity, field, refusal,
                active ? FindingCodes.ERR_DEDUP_WINDOW_UNARMABLE : FindingCodes.WARN_DEDUP_WINDOW_UNARMABLE,
                active ? GUIDANCE_ACTIVE : GUIDANCE_INACTIVE));
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
    /**
     * The columns a pipeline draft's declared schema carries, for the save-time checks that need to know
     * what a step may reference (`TYPEFLOW-CONSUMERS-1` (a)). Reads {@code processing.schema_file},
     * resolved exactly as {@link #schemaFileFindings} resolves it.
     *
     * <p>🔴 <b>Returns EMPTY — never a finding — whenever the schema cannot be read.</b> An unresolvable
     * reference is already a WARNING from {@link #schemaFileFindings} and is deliberately not fatal (the
     * file may be created after the save, or belong to another host). A checker that treated "no columns"
     * as "column missing" would refuse every save made before its schema file exists, which is the normal
     * authoring order. Callers must therefore treat empty as <em>nothing to say</em>, not as an empty
     * schema.
     *
     * <p>🔴 <b>Merges the structure sibling.</b> A split schema keeps {@code raw.fields[]} in
     * {@code <name>_structure.csv} (STRUCTURE-CSV-1), so reading the TOON alone sees ZERO fields — the
     * checks would then quietly pass everything, which is worse than not running. Same merge the
     * compatibility gate does in {@link ConfigWriteRoutes}.
     */
    static List<TypeFlow.Column> declaredColumns(Map<String, Object> draft, Path configDir) {
        if (!(draft.get("processing") instanceof Map<?, ?> proc)) return List.of();
        if (!(proc.get("schema_file") instanceof String ref) || ref.isBlank()) return List.of();
        Path file = resolvedPath(ref, configDir);
        if (file == null) return List.of();
        Map<String, Object> schema;
        try {
            schema = ConfigLoader.filesystem().decode(file.toString());
            ConfigFileSupport.mergeSiblingStructure(file, schema);
        } catch (Exception unreadable) {
            return List.of();   // malformed or vanished mid-save: silence, per the contract above
        }
        if (!(schema.get("raw") instanceof Map<?, ?> raw) || !(raw.get("fields") instanceof List<?> fields))
            return List.of();
        List<TypeFlow.Column> out = new ArrayList<>();
        for (Object f : fields)
            if (f instanceof Map<?, ?> m && m.get("name") != null) {
                Object type = m.get("type");
                out.add(new TypeFlow.Column(String.valueOf(m.get("name")),
                        type == null || String.valueOf(type).isBlank() ? "VARCHAR" : String.valueOf(type)));
            }
        return out;
    }

    /**
     * {@code route:} branch predicates that read a column the declared schema does not carry — the
     * save-time half of a failure that otherwise throws on the first row of a live run
     * (`TYPEFLOW-CONSUMERS-1` (a); the wiring `elt-final-amendment-plan.md` P2 S2 deferred to "S3+").
     *
     * <p>Binds rather than pattern-matches: {@code TypeFlow.describe} DESCRIBEs
     * {@code SELECT * FROM input WHERE <predicate>} against an empty table shaped by the declared
     * columns, so DuckDB's own binder decides — and its message names the offending column. A regex over
     * the predicate would have to know SQL's literals, functions and keywords to avoid flagging them.
     *
     * <p>🔴 {@code SqlGuard.check} FIRST, exactly as {@code ComponentRoutes.describeTransform} does.
     * {@code describe} opens a plain DuckDB connection with no guard of its own; {@code DESCRIBE} plans
     * without executing, but an authored predicate is untrusted input and this runs on every save.
     *
     * <p>Severity follows the arming convention ({@link #routeArmingFindings}): ERROR when the pipeline
     * is {@code active} and would really fail, WARNING on an inactive draft so mid-authoring saves work.
     */
    static List<Finding> routeColumnFindings(String type, Map<String, Object> draft, Path configDir) {
        if (!"pipeline".equals(type)) return List.of();
        if (!(draft.get("route") instanceof Map<?, ?> route)) return List.of();
        if (!(route.get("branches") instanceof List<?> branches) || branches.isEmpty()) return List.of();
        List<TypeFlow.Column> columns = declaredColumns(draft, configDir);
        if (columns.isEmpty()) return List.of();   // unknown ≠ empty — see declaredColumns
        boolean active = Boolean.parseBoolean(String.valueOf(draft.getOrDefault("active", "false")));
        Severity severity = active ? Severity.ERROR : Severity.WARNING;
        List<Finding> out = new ArrayList<>();
        for (Object b : branches) {
            if (!(b instanceof Map<?, ?> m)) continue;
            Object where = m.get("where");
            // A blank/absent predicate is routeArmingFindings' refusal, not this one — do not double-report.
            if (where == null || String.valueOf(where).isBlank()) continue;
            String predicate = String.valueOf(where);
            String probe = "SELECT * FROM \"input\" WHERE " + predicate;
            String fieldPath = "route.branches[" + m.get("key") + "].where";
            String code = active ? FindingCodes.ERR_ROUTE_PREDICATE_COLUMN
                                 : FindingCodes.WARN_ROUTE_PREDICATE_COLUMN;
            String guidance = active ? GUIDANCE_ACTIVE : GUIDANCE_INACTIVE;
            // 🔴 Guard the ASSEMBLED statement, never the bare predicate: SqlGuard requires SQL to BEGIN
            // with SELECT/WITH, so checking the fragment rejected every predicate ever written and the
            // bind below was skipped for all of them — the check silently passed everything it saw.
            if (!SqlGuard.check(probe).isEmpty()) {
                // Reported, not skipped. A predicate that is not a safe read-only expression (a second
                // statement, a file-reading function) is an authoring fault in its own right, and
                // staying quiet here would be the same silent hole in a different place.
                out.add(new Finding(severity, fieldPath,
                        "route: branch '" + m.get("key") + "' has a where: predicate that is not a safe "
                                + "read-only expression — it cannot be analysed and would be refused",
                        code, guidance));
                continue;
            }
            try {
                TypeFlow.describe(columns, probe);
            } catch (IllegalArgumentException doesNotBind) {
                out.add(new Finding(severity, fieldPath,
                        "route: branch '" + m.get("key") + "' has a where: predicate that does not bind "
                                + "against the declared schema — " + doesNotBind.getMessage(),
                        code, guidance));
            }
        }
        return out;
    }

    /**
     * {@code transform.summarize} measures aggregating a non-numeric declared field
     * (`TYPEFLOW-CONSUMERS-1` (a)). Only {@code sum}/{@code avg} are checked
     * ({@link MeasureCompiler#NUMERIC_AGGS}): {@code min}/{@code max} order dates and text perfectly
     * well and {@code count}/{@code countDistinct} ignore the value, so flagging those would be taste,
     * not a type error.
     *
     * <p>⚠ The shorthand is split by {@link MeasureCompiler#splitShorthand}, the grammar's own home —
     * NOT re-implemented here. A malformed entry is the executor's refusal to raise, so it is swallowed:
     * this check answers "is this field numeric", and reporting a second, differently-worded syntax error
     * from a type checker would be noise.
     */
    static List<Finding> summarizeMeasureFindings(String type, Map<String, Object> draft, Path configDir) {
        if (!"pipeline".equals(type)) return List.of();
        if (!(draft.get("processing") instanceof Map<?, ?> proc)) return List.of();
        if (!(proc.get("summarize") instanceof Map<?, ?> summarize)) return List.of();
        if (!(summarize.get("measures") instanceof List<?> measures) || measures.isEmpty()) return List.of();
        List<TypeFlow.Column> columns = declaredColumns(draft, configDir);
        if (columns.isEmpty()) return List.of();   // unknown ≠ empty — see declaredColumns
        List<Map<String, Object>> split;
        try {
            split = MeasureCompiler.splitShorthand(measures, "processing.summarize");
        } catch (IllegalArgumentException malformed) {
            return List.of();   // the executor's refusal, not this check's
        }
        boolean active = Boolean.parseBoolean(String.valueOf(draft.getOrDefault("active", "false")));
        Severity severity = active ? Severity.ERROR : Severity.WARNING;
        List<Finding> out = new ArrayList<>();
        for (Map<String, Object> m : split) {
            String agg = String.valueOf(m.get("agg"));
            Object fieldObj = m.get("field");
            if (!MeasureCompiler.NUMERIC_AGGS.contains(agg) || fieldObj == null) continue;
            String field = String.valueOf(fieldObj);
            String declared = columns.stream()
                    .filter(c -> c.name().equalsIgnoreCase(field)).map(TypeFlow.Column::type)
                    .findFirst().orElse(null);
            // A field the schema does not declare at all is a different fault; the run names it and
            // this check has no type to judge. Only a DECLARED, non-numeric field is reported here.
            if (declared == null || isNumeric(declared)) continue;
            out.add(new Finding(severity, "processing.summarize.measures",
                    "transform.summarize: measure '" + agg + "(" + field + ")' aggregates '" + field
                            + "', declared " + declared + " — " + agg + " needs a numeric field and the "
                            + "run fails when it is not",
                    active ? FindingCodes.ERR_SUMMARIZE_MEASURE_TYPE
                           : FindingCodes.WARN_SUMMARIZE_MEASURE_TYPE,
                    active ? GUIDANCE_ACTIVE : GUIDANCE_INACTIVE));
        }
        return out;
    }

    /** DuckDB's numeric family by declared name — width and DECIMAL(p,s) precision are irrelevant here. */
    private static boolean isNumeric(String declaredType) {
        String t = declaredType.trim().toUpperCase(java.util.Locale.ROOT);
        int paren = t.indexOf('(');
        if (paren > 0) t = t.substring(0, paren).trim();      // DECIMAL(18,2) → DECIMAL
        return switch (t) {
            case "TINYINT", "SMALLINT", "INTEGER", "INT", "INT2", "INT4", "INT8", "BIGINT", "HUGEINT",
                 "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT",
                 "FLOAT", "REAL", "FLOAT4", "FLOAT8", "DOUBLE", "DECIMAL", "NUMERIC" -> true;
            default -> false;
        };
    }

    /** {@link #resolves}' path half — the resolved file, or {@code null} when it resolves nowhere. */
    private static Path resolvedPath(String ref, Path configDir) {
        Path asAuthored = Path.of(ref);
        if (configDir != null && !asAuthored.isAbsolute()) {
            Path base      = configDir.toAbsolutePath().normalize();
            Path candidate = base.resolve(asAuthored).normalize();
            if (candidate.startsWith(base) && Files.isRegularFile(candidate)) return candidate;
        }
        return Files.isRegularFile(asAuthored) ? asAuthored : null;
    }

    private static boolean resolves(String ref, Path configDir) {
        return resolvedPath(ref, configDir) != null;
    }

    private static String unresolvable(String schemaPath) {
        return "schema file does not resolve on the server: '" + schemaPath
                + "' (a relative reference resolves beside its own config file first, then against"
                + " the server's working directory: " + Path.of("").toAbsolutePath() + ")";
    }
}
