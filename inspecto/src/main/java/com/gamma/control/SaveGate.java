package com.gamma.control;

import com.gamma.spi.http.ApiContext;
import com.gamma.acquire.ConnectionProfile;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.config.spec.AcceptedConfigKeys;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.FindingCodes;
import com.gamma.config.spec.Severity;
import com.gamma.etl.EditionFeatures;
import com.gamma.etl.PipelineConfig;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE authoring gate: the one list of checks every config save path runs — {@code POST /config/write},
 * {@code POST /config/patch}, {@code PUT /pipelines/{name}/graph}, {@code POST /pipelines/import}, and since
 * `ASSURE-MAKER-CHECKER-1` S0 the four Pipeline edits {@code /label}, {@code /settings},
 * {@code /save-as-template} and {@code /rename} ({@link #introduced}) — and
 * that {@code POST /validate} reports for a draft (`PROCESSOR-RELEASE-READINESS-1` G3, 2026-09-23).
 *
 * <p>🔴 <b>Why one function and not a list per route.</b> Until this class each route carried its own
 * hand-kept copy of the same {@code findings.addAll(...)} sequence, and the copies had drifted: the graph
 * editor's save — whose comment claimed "the same gate {@code /config/write} runs" — ran neither the
 * unknown-Connection check nor the unknown-key census, bundle import ran neither of those nor the two
 * TypeFlow checks, and {@code /validate} ran four of the eleven. One config got different verdicts from
 * different doors. A check added here reaches every door at once, and {@code ControlApiSaveGateParityTest}
 * drives one fault through every door to hold that.
 *
 * <p>Route-specific gates stay on their routes, because they are not about the config's content: the
 * schema drift and BACKWARD-compatibility gates need the file being replaced, and the graph route's
 * lowering refusals happen before there is a config to judge.
 *
 * <p>⚠ Returns findings; the caller decides. A save refuses on any ERROR, {@code /validate} reports.
 */
final class SaveGate {

    private SaveGate() {}

    /**
     * Whether a reference to something that must already exist in this Space — a Connection — is refused
     * when it does not, or reported so the author can supply it before activation.
     */
    enum Referents {
        /** Every authoring save: a dangling reference throws on every run, so the save refuses it. */
        MUST_EXIST,
        /**
         * Bundle import only — a recorded decision, not a gap: a pipeline bundle never carries its
         * Connections (secrets never travel), the import always lands inactive, and refusing would make
         * promotion into a fresh Space impossible. A MISSING referent becomes a
         * {@link FindingCodes#WARN_UNRESOLVED_CONNECTION} (the collector's comes from
         * {@code PipelineBundleRoutes.classifyRequirements}, which also classifies the manifest's
         * requirements); a referent that exists but is the WRONG kind is still refused.
         */
        MAY_ARRIVE_LATER
    }

    /**
     * Every content finding for {@code draft}.
     *
     * @param type      the config type; the caller has already 404'd an unknown one
     * @param writeRoot the Space config root — the base a {@code job}'s relative paths resolve against
     * @param configDir the directory the config lives in (or will land in), so a config-relative
     *                  reference resolves the way the loader resolves it; {@code null} only where there is
     *                  no prospective home, which checks the working-directory form alone
     */
    static List<Finding> check(ApiContext api, String type, Map<String, Object> draft, Path writeRoot,
                               Path configDir, Referents referents) {
        return check(api, type, draft, writeRoot, configDir, referents, SafetyPolicy.defaultPolicy());
    }

    /**
     * {@link #check(ApiContext, String, Map, Path, Path, Referents)} judged against {@code policy} rather than the
     * calling Space's — for a config whose Space is not hosted yet (a template staged by {@link TemplateSeedGate}).
     */
    static List<Finding> check(ApiContext api, String type, Map<String, Object> draft, Path writeRoot,
                               Path configDir, Referents referents, SafetyPolicy policy) {
        List<Finding> f = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.forType(type), draft));
        f.addAll(safety(type, draft, writeRoot, configDir, policy));   // the hard-fail safety check (R6)
        // Arming split (SCHEMA-FILE-NAME-1): an ACTIVE pipeline whose schema_file resolves nowhere is
        // refused — it would register and then never load; an inactive draft is warned.
        f.addAll(ConfigRoutes.schemaArmingFindings(type, draft, configDir));
        // Arming (ERROR when active, WARNING on an inactive draft): an active config that cannot arm
        // registers and is then silently skipped every cycle.
        f.addAll(ConfigRoutes.armedWithoutSchemaFindings(type, draft));
        f.addAll(ConfigRoutes.routeArmingFindings(type, draft, configDir));
        f.addAll(ConfigRoutes.stepDisableFindings(type, draft));
        f.addAll(ConfigRoutes.dedupWindowFindings(type, draft));
        f.addAll(ConfigRoutes.stepConfigFindings(type, draft, configDir));   // G4: the run's step refusals
        f.addAll(ConfigRoutes.stepColumnMetadataFindings(type, draft));   // AUTHORING-REDESIGN-1 (e)
        f.addAll(ConfigRoutes.collectorFindings(type, draft));   // post_action MOVE target; inert remote keys
        f.addAll(ConfigRoutes.sinkLakeCollisionFindings(type, draft));
        f.addAll(editionFindings(type, draft));   // G9: Standard+ features on a Basic build
        // Referential: a collector bound to a Connection this Space does not have throws once per poll.
        if (referents == Referents.MUST_EXIST) f.addAll(ConfigRoutes.unknownConnectionFindings(type, draft, api));
        f.addAll(webhookFindings(type, draft, api, referents));
        f.addAll(excelFindings(type, draft));
        f.addAll(selectorFindings(type, draft, configDir));   // LA-ONBOARD-FIXES-1 (F-4)
        // A block no component reads is a SILENT LOSS (`DUCKLE-C3-DEAD-PROPERTY-1`).
        f.addAll(AcceptedConfigKeys.unknownKeyFindings(type, draft, Severity.ERROR));
        // TYPEFLOW-CONSUMERS-1 (a): need the declared columns, hence the config's own directory.
        f.addAll(ConfigRoutes.routeColumnFindings(type, draft, configDir));
        f.addAll(ConfigRoutes.summarizeMeasureFindings(type, draft, configDir));
        return f;
    }

    /**
     * The base {@link ConfigSafetyValidator} resolves a config's relative values against — <b>and it
     * means two different things depending on the kind</b>, which is the whole of
     * {@code JOB-PATH-PATCH-ROUTE-WRONG-BASE-1}.
     *
     * <ul>
     *   <li><b>pipeline / schema</b> — the config file's <em>own directory</em>, so a config
     *       <em>reference</em> ({@code schema_file}, {@code grammar}) resolves the way the loader
     *       resolves it: beside the config.</li>
     *   <li><b>job</b> — the <b>Space config root</b>. A job's relative path resolves against it and
     *       nothing else ({@code JOB-DIR-CWD-CONTAINMENT-1}, operator 2026-09-16), and
     *       {@link com.gamma.config.safety.PathJail#resolveJobPath} is the single rule the run-time
     *       tasks call too.</li>
     * </ul>
     *
     * <p>🔴 Passing a job its file's directory judged it from {@code <space>/config/jobs} whenever the
     * caller supplied {@code subdir:"jobs"} — the only shape that can address a job {@code POST /jobs}
     * wrote. Driven 2026-09-16: {@code backup_dir:"../../outside/backups"} was <b>refused 422</b> by
     * {@code PUT /jobs} and <b>written</b> by {@code /config/patch}, one directory level apart.
     *
     * <p>⚠ {@code writeRoot} <b>is</b> that root, not an approximation of it: {@code ControlApi.writeRoot()}
     * returns the bound space's {@code root().config()}, which is precisely what {@code SpaceBootstrap}
     * registers into {@code SpaceConfigRoot} for {@code JobRoutes} to read back. For a job it is the base
     * of every key EXCEPT the pipeline runner's two ({@code JOB-PATH-SINGLE-TENANT-GATE-BASE-1}):
     * {@code SpaceConfigRoot.jobPathBase} hands those the config READ root.
     */
    private static List<Finding> safety(String type, Map<String, Object> draft, Path writeRoot, Path configDir,
                                        SafetyPolicy policy) {
        if ("job".equalsIgnoreCase(type))
            return ConfigSafetyValidator.checkJob(draft, policy,
                    k -> com.gamma.pipeline.SpaceConfigRoot.jobPathBase(k, writeRoot));
        return ConfigSafetyValidator.check(type, draft, policy, configDir);
    }

    /**
     * The Standard+ features a Basic build refuses ({@link EditionFeatures}, `PROCESSOR-RELEASE-READINESS-1`
     * G9): a pipeline's {@code post_action: MOVE} archive and its DuckLake registration, and a legacy
     * {@code alert} config. ERROR regardless of {@code active} — activation does not change the edition.
     * Alert Rule components are refused on their own routes ({@code AlertRoutes}, {@code ComponentRoutes}).
     */
    static List<Finding> editionFindings(String type, Map<String, Object> draft) {
        List<Finding> out = new ArrayList<>();
        if ("pipeline".equals(type))
            for (EditionFeatures.Refusal r : EditionFeatures.pipelineRefusals(draft))
                out.add(new Finding(Severity.ERROR, r.field(), r.message(), FindingCodes.ERR_EDITION_FEATURE,
                        "remove it, or run the Standard or Advanced edition"));
        if ("alert".equals(type) && !EditionFeatures.present(EditionFeatures.ALERT_DISPATCH))
            out.add(new Finding(Severity.ERROR, "alert", EditionFeatures.refusal(EditionFeatures.ALERT_DISPATCH),
                    FindingCodes.ERR_EDITION_FEATURE, "run the Standard or Advanced edition"));
        return out;
    }

    /** Whether {@code findings} carries an ERROR — the one verdict every save path refuses on. */
    static boolean refuses(List<Finding> findings) {
        return findings.stream().anyMatch(x -> x.severity() == Severity.ERROR);
    }

    /**
     * The ERROR findings in {@code after} that {@code before} did not already carry — the verdict for an EDIT
     * of a config that is already on disk ({@code POST /pipelines/{name}/label}, {@code /settings},
     * {@code /rename}; `ASSURE-MAKER-CHECKER-1` S0). Both lists come from {@link #check}, so those routes run
     * the whole gate instead of the spec + safety pair they used to hand-roll, while a config on disk that was
     * never judged by today's gate is not re-punished for findings the edit did not cause.
     */
    static List<Finding> introduced(List<Finding> after, List<Finding> before) {
        java.util.Set<String> pre = new java.util.HashSet<>();
        for (Finding f : before) pre.add(PipelineSupport.findingKey(f));
        return after.stream().filter(f -> f.severity() == Severity.ERROR)
                .filter(f -> !pre.contains(PipelineSupport.findingKey(f))).toList();
    }

    /**
     * The {@code excel:} block ({@code sink.excel}), judged at save as {@code ExcelSink.plan} judges it at run
     * time: the parser's own refusals (path, sheet names, row cap, keys) and {@code SqlGuard} on every sheet's
     * {@code sql}. Regardless of {@code active}: a draft that cannot load is not a draft worth keeping.
     */
    static List<Finding> excelFindings(String type, Map<String, Object> draft) {
        if (!"pipeline".equals(type) || draft.get("excel") == null) return List.of();
        String fix = "keep path (relative, .xlsx), sheets [{name, sql}] and max_rows; sql is one read-only SELECT over input";
        if (!(draft.get("excel") instanceof Map<?, ?> block))
            return List.of(new Finding(Severity.ERROR, "excel", "excel: must be a map ({path, sheets, max_rows})",
                    FindingCodes.ERR_EXCEL_INVALID, fix));
        PipelineConfig.Excel e;
        try {
            e = PipelineConfig.Excel.fromMap(block);
        } catch (IllegalArgumentException refused) {
            return List.of(new Finding(Severity.ERROR, "excel", refused.getMessage(), FindingCodes.ERR_EXCEL_INVALID, fix));
        }
        List<Finding> out = new ArrayList<>();
        for (int i = 0; i < e.sheets().size(); i++) {
            String problem = com.gamma.pipeline.exec.ExcelSink.sqlProblem(e.sheets().get(i).sql());
            if (problem != null)
                out.add(new Finding(Severity.ERROR, "excel.sheets[" + i + "].sql", "sheet '"
                        + e.sheets().get(i).name() + "' refused: " + problem, FindingCodes.ERR_EXCEL_INVALID, fix));
        }
        return out;
    }

    /**
     * A delimited / fixed-width pipeline whose schema addresses a column by NAME — the load refuses it ("a
     * selector must be a zero-based column index"), so registration would refuse what this save accepted
     * (LA-ONBOARD-FIXES-1, F-4). Judged by the load itself ({@link PipelineConfig#fromMap(Map, Path)}, pure,
     * from the config's own directory) so the frontend and schema resolution cannot drift from it; only
     * that one refusal is reported here — every other parse refusal belongs to the check that owns it, or
     * (an unresolvable schema on an inactive draft) is deliberately not an error at the save. Refused
     * regardless of {@code active}, like {@link #excelFindings}: the schema file exists and the config
     * cannot load.
     */
    static List<Finding> selectorFindings(String type, Map<String, Object> draft, Path configDir) {
        if (!"pipeline".equals(type) || !(draft.get("processing") instanceof Map<?, ?>)) return List.of();
        try {
            PipelineConfig.fromMap(draft, configDir);
        } catch (PipelineConfig.SelectorNotIndexException refused) {
            String field = "schema_file".equals(refused.schemaRef()) ? "processing.schema_file" : "processing.schemas";
            return List.of(new Finding(Severity.ERROR, field, refused.getMessage(),
                    FindingCodes.ERR_SELECTOR_NOT_INDEX,
                    "write the schema's raw.fields selectors as zero-based column indexes (0, 1, 2, ...) — "
                            + "POST /config/suggest/schema proposes them for a delimited sample"));
        } catch (Exception notThisCheck) {
            // Not the selector refusal: the owning check reports it (or it is not a save-time error).
        }
        return List.of();
    }

    /**
     * {@link #selectorFindings} from the SCHEMA's side ({@code LA-ONBOARD-DRIFT-1}): a schema written on its own, to
     * {@code schemaFile}, that a delimited / fixed-width Pipeline under {@code writeRoot} already reads must keep
     * zero-based column INDEX selectors, or that Pipeline stops loading at its next reload. Only the Pipeline write was
     * judged before. Judged by the load itself, as there: each Pipeline whose {@code processing.schema_file} or
     * {@code processing.schemas[].schema_file} resolves (beside the Pipeline) to {@code schemaFile} is parsed with that
     * reference pointed at the candidate schema, written to a scratch file beside the target and removed after. A
     * schema whose every selector is already an index costs nothing. A Pipeline that cannot be read is skipped: its own
     * save and load report it.
     */
    static List<Finding> referencedSchemaSelectorFindings(Path writeRoot, Path schemaFile, Map<String, Object> schema) {
        if (!hasNameSelector(schema)) return List.of();
        Path target = schemaFile.toAbsolutePath().normalize();
        Path scratch = target.resolveSibling(".selector-check-" + java.util.UUID.randomUUID() + ".toon");
        List<Finding> out = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(writeRoot, 4)) {
            List<Path> pipelines = walk.filter(java.nio.file.Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith("_pipeline.toon"))
                    .filter(f -> !writeRoot.relativize(f).startsWith("registry")).sorted().toList();
            for (Path p : pipelines) {
                Map<String, Object> retargeted;
                try {
                    retargeted = retargetSchemaRefs(ConfigLoader.filesystem().decode(p.toString()), p.getParent(), target, scratch);
                } catch (RuntimeException unreadable) {
                    continue;
                }
                if (retargeted == null) continue;
                if (!java.nio.file.Files.exists(scratch))
                    com.gamma.util.AtomicFiles.write(scratch, com.gamma.config.io.ConfigCodec.toToon(schema)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8), ".sel-");
                try {
                    PipelineConfig.fromMap(retargeted, p.getParent());
                } catch (PipelineConfig.SelectorNotIndexException refused) {
                    out.add(new Finding(Severity.ERROR, "raw.fields", "Pipeline '" + p.getFileName().toString()
                            .replaceFirst("_pipeline\\.toon$", "") + "' reads this schema by column position: "
                            + refused.getMessage().replaceFirst("^Config error in <config>: \\S+ ", ""),
                            FindingCodes.ERR_SELECTOR_NOT_INDEX,
                            "write the schema's raw.fields selectors as zero-based column indexes (0, 1, 2, ...)"));
                } catch (Exception notThisCheck) {
                    // Not the selector refusal: the Pipeline's own save and load report it.
                }
            }
        } catch (java.io.IOException | java.io.UncheckedIOException unreadable) {
            // The tree cannot be walked (a folder mid-walk too): nothing can be said about who reads this schema.
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(scratch);
            } catch (java.io.IOException ignored) {
                // a leftover scratch file is not a config (no _pipeline / _schema suffix) and is replaced next time
            }
        }
        return out;
    }

    /** Whether any {@code raw.fields[].selector} is not a zero-based index (a blank one counts, as the load counts it). */
    private static boolean hasNameSelector(Map<String, Object> schema) {
        if (!(schema.get("raw") instanceof Map<?, ?> raw) || !(raw.get("fields") instanceof List<?> fields)) return false;
        for (Object o : fields)
            if (o instanceof Map<?, ?> f && !String.valueOf(f.get("selector")).trim().matches("\\d+")) return true;
        return false;
    }

    /**
     * A copy of the Pipeline map {@code raw} with every schema reference that resolves beside {@code dir} to
     * {@code target} pointed at {@code scratch}, or {@code null} when none does.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> retargetSchemaRefs(Map<String, Object> raw, Path dir, Path target, Path scratch) {
        if (!(raw.get("processing") instanceof Map<?, ?> procRaw)) return null;
        Map<String, Object> proc = new java.util.LinkedHashMap<>((Map<String, Object>) procRaw);
        String to = scratch.toString().replace('\\', '/');
        boolean hit = false;
        if (proc.get("schema_file") instanceof String ref && resolvesTo(ref, dir, target)) {
            proc.put("schema_file", to);
            hit = true;
        }
        if (proc.get("schemas") instanceof List<?> entries) {
            List<Object> copy = new ArrayList<>();
            for (Object e : entries) {
                if (e instanceof Map<?, ?> m && m.get("schema_file") instanceof String ref && resolvesTo(ref, dir, target)) {
                    Map<String, Object> entry = new java.util.LinkedHashMap<>((Map<String, Object>) m);
                    entry.put("schema_file", to);
                    copy.add(entry);
                    hit = true;
                } else copy.add(e);
            }
            proc.put("schemas", copy);
        }
        if (!hit) return null;
        Map<String, Object> out = new java.util.LinkedHashMap<>(raw);
        out.put("processing", proc);
        return out;
    }

    private static boolean resolvesTo(String ref, Path dir, Path target) {
        if (ref.isBlank() || ref.startsWith("schema/")) return false;   // a registry schema is never a /config write target
        try {
            return dir.resolve(ref.trim()).toAbsolutePath().normalize().equals(target);
        } catch (RuntimeException badPath) {
            return false;
        }
    }

    /**
     * The {@code webhook:} block, judged at save as {@code WebhookSink.plan} judges it at run time: it must
     * parse (an authored {@code url:}/{@code token:} or an unknown key is refused by the parser, and the
     * pipeline would then not load at all), and its {@code connection} must name a Connection this Space
     * holds whose connector is {@code https}. Refused regardless of {@code active}, like the collector's
     * Connection: the author is present now, and at the first run nobody is.
     *
     * <p>⚠ Checked against the live {@code ConnectionProfileRegistry} ({@code api.service().connections()}),
     * the source the collector check uses; the run resolves through {@code ConnectionRegistry}, which every
     * Connection write updates in the same request. The one blind spot is the collector check's: a
     * {@code *_connection.toon} copied onto disk with no restart since.
     *
     * <p>Only the Connection's existence and kind are checked here — not its host, tunnel or proxy, which
     * are the Connection's own validation, run when it is written.
     */
    static List<Finding> webhookFindings(String type, Map<String, Object> draft, ApiContext api, Referents referents) {
        if (!"pipeline".equals(type) || draft.get("webhook") == null) return List.of();
        if (!(draft.get("webhook") instanceof Map<?, ?> block))
            return List.of(new Finding(Severity.ERROR, "webhook",
                    "webhook: must be a map ({connection, batch_size, retry})", FindingCodes.ERR_WEBHOOK_INVALID,
                    "author the target as an https Connection and name it in webhook.connection"));
        PipelineConfig.Webhook w;
        try {
            w = PipelineConfig.Webhook.fromMap(block);
        } catch (IllegalArgumentException refused) {
            return List.of(new Finding(Severity.ERROR, "webhook", refused.getMessage(),
                    FindingCodes.ERR_WEBHOOK_INVALID,
                    "the endpoint and its token belong on an https Connection; keep only connection, "
                            + "batch_size and retry here"));
        }
        if (api == null) return List.of();
        ConnectionProfile p = HostContext.of(api).service().connections().get(w.connection());
        if (p == null) {
            if (referents == Referents.MAY_ARRIVE_LATER)
                return List.of(new Finding(Severity.WARNING, "webhook.connection",
                        "unknown connection '" + w.connection() + "' — this space has no such connection "
                                + "profile, so the imported pipeline's webhook cannot send",
                        FindingCodes.WARN_UNRESOLVED_CONNECTION,
                        "register the https connection before activating the pipeline"));
            return List.of(new Finding(Severity.ERROR, "webhook.connection",
                    "unknown connection '" + w.connection() + "' — no such connection profile in this space",
                    FindingCodes.ERR_WEBHOOK_CONNECTION_UNKNOWN,
                    "create the https connection first, or name one that exists"));
        }
        if (!com.gamma.pipeline.exec.WebhookSink.CONNECTOR.equals(p.connector()))
            return List.of(new Finding(Severity.ERROR, "webhook.connection",
                    "connection '" + p.id() + "' is a '" + p.connector() + "' connection — a webhook target "
                            + "must be an '" + com.gamma.pipeline.exec.WebhookSink.CONNECTOR + "' connection",
                    FindingCodes.ERR_WEBHOOK_CONNECTION_NOT_HTTPS,
                    "name an https connection, or create one for this endpoint"));
        return List.of();
    }
}
