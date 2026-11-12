package com.gamma.control;

import com.gamma.spi.auth.ApiException;
import com.gamma.spi.auth.ErrorCodes;
import com.gamma.spi.http.ApiContext;
import com.gamma.spi.http.RouteModule;
import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.Finding;
import com.gamma.etl.PipelineConfig;
import com.gamma.audit.Event;
import com.gamma.audit.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.pipeline.LinkCensus;
import com.gamma.pipeline.LinkGraph;
import com.gamma.service.DbStatusStore;
import com.gamma.service.ReservedConfigPaths;
import com.gamma.util.AtomicFiles;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import com.gamma.access.WriteGates;

/**
 * Pipeline identity-migration routes ({@code POST /pipelines/{name}/rename},
 * {@code POST /pipelines/rename/resume}, T3, plan §3): the full move of a pipeline's id — config file,
 * commit log, audit CSVs, acquisition ledger, status mirror and dependent configs — bracketed by
 * {@code rename.journal}. Extracted verbatim from {@code PipelineRoutes}: identical routes, order,
 * HTTP statuses and validation.
 */
final class PipelineRenameRoutes implements RouteModule {

    private static final Logger log = LoggerFactory.getLogger(PipelineRenameRoutes.class);

    @Override
    public void register(ApiContext api) {
        // T3: the full identity migration `label` deliberately doesn't do — see `rename`'s javadoc.
        api.post("/pipelines/([^/]+)/rename", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> rename(api, e, ApiContext.name(m), api.body(e))));
        // The finishing move for an interrupted rename — reads rename.journal back (resumeRename's javadoc).
        // No {name} segment: after a mid-migration crash the pipeline may be registered under neither id.
        api.post("/pipelines/rename/resume", ApiContext.withCapability("canAuthorWorkbench",
                (e, m) -> resumeRename(api, e, api.body(e))));
    }

    /**
     * {@code POST /pipelines/{name}/rename} — <b>full identity migration</b> (T3, plan §3): moves the id
     * itself, not just the display name. {@code relabel} deliberately stops short of this because most
     * renames don't need it; this route is for the rest — every artifact keyed by the old id moves too, so
     * a re-scan under the new id still recognises files it already ingested (the acquisition ledger, S5/S6)
     * and the run history under the new id includes everything recorded under the old one (the commit log
     * and audit CSVs, S2/S3; the DuckDB status mirror, S4).
     *
     * <p>{@code dirs.*} are deliberately left pointing where they already do (plan §1.1) — relocating a
     * Stage-1 output tree is a bulk data move with real blast radius, not this route's job; a caller that
     * asks for it ({@code relocateDirs: true}) gets a 422 rather than a silently-ignored request.
     *
     * <p>Steps 2–7 below are not one transaction and cannot be (DuckDB, the filesystem and the config write
     * are three different failure domains) — see the {@code catch} block for the failure posture this
     * implies. Body: {@code { newId, newName?, relocateDirs?: false, rewriteDependents?: true }}.
     *
     * <p>Fail-closed gate order: write-root 503 → source unknown 404 → path jail 403 → {@code newId} shape
     * 400/422 → source active 409 → source running 409 → {@code newId} taken 409 → {@code relocateDirs}
     * unsupported 422 → migrate.
     */
    private Object rename(ApiContext api, HttpExchange e, String source, Map<String, Object> body)
            throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path srcPath = HostContext.of(api).service().pathFor(source)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no pipeline named '" + source + "'"));
        WriteGates.jail(writeRoot, srcPath, "config path");
        PipelineConfig live = HostContext.of(api).service().configFor(source)
                .orElseThrow(() -> new ApiException(404, ErrorCodes.NOT_FOUND, "no pipeline named '" + source + "'"));

        String rawId = ApiContext.str(body, "newId");
        if (rawId == null || rawId.isBlank())
            throw new ApiException(400, ErrorCodes.MALFORMED_REQUEST, "body must include 'newId'");
        String newId = rawId.trim().toLowerCase();
        if (!newId.matches("[a-z0-9][a-z0-9_]*"))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "newId '" + newId
                    + "' must match [a-z0-9][a-z0-9_]* (lowercase letters, digits and underscores)");

        String oldId = live.identity().pipelineName();
        WriteGates.conflictIf(live.active(),
                "pipeline '" + oldId + "' is active; deactivate (active: false) before renaming");
        WriteGates.conflictIf(HostContext.of(api).service().isRunning(oldId),
                "pipeline '" + oldId + "' is currently running; wait for it to finish before renaming");
        WriteGates.conflictIf(HostContext.of(api).service().pathFor(newId).isPresent(),
                "pipeline id '" + newId + "' is already registered");
        String newFileName = WriteGates.safeName(newId, "pipeline id") + "_pipeline.toon";
        Path newPath = WriteGates.jail(writeRoot, writeRoot.resolve(newFileName), "resolved path");
        WriteGates.conflictIf(Files.exists(newPath), "file exists: " + newFileName);

        if (Boolean.parseBoolean(String.valueOf(body.getOrDefault("relocateDirs", "false"))))
            throw new ApiException(422, ErrorCodes.CONFIG_VALIDATION_FAILED, "relocateDirs is not yet supported — rename leaves dirs.* pointing "
                    + "where they already do (plan §1.1); relocate the data tree manually if that's needed");
        boolean rewriteDependents = !"false".equalsIgnoreCase(
                String.valueOf(body.getOrDefault("rewriteDependents", "true")));

        String newNameRaw = ApiContext.str(body, "newName");
        // Maker-checker (ASSURE-MAKER-CHECKER-1): held BEFORE step 0 — nothing of the migration may move for
        // a rename that is still waiting for approval. The Pending Change shows the config as it will be
        // rewritten; the approved replay re-runs every gate above and then the whole migration.
        refuseGovernedDependents(writeRoot, oldId, newId, rewriteDependents);   // finding 2: no side-door rewrite
        Map<String, Object> srcDoc = ConfigLoader.filesystem().decode(srcPath.toString());
        PendingChanges.hold(api, e, "pipeline", oldId, renamedDoc(srcDoc, oldId, newId, newNameRaw), srcDoc);

        List<String> journal = new ArrayList<>();
        Path journalFile = writeRoot.resolve("rename.journal");

        // Step 0: bracket the migration in the journal BEFORE any state moves. `begin` records the source
        // file name and the request parameters; `completed` (after step 9) closes the bracket. A begin with
        // no completed is what POST /pipelines/rename/resume looks for — and the params recorded here are
        // what let it finish the job. newName stays last on the line: it may contain spaces.
        journalStep(journalFile, oldId, newId, "begin src=" + srcPath.getFileName()
                + " rewriteDependents=" + rewriteDependents
                + (newNameRaw == null || newNameRaw.isBlank() ? "" : " newName=" + newNameRaw.trim()), journal);

        // Step 1 (S9): evict per-pipeline bookkeeping + the run registry. Cheaply reversible on failure —
        // re-registering the same path restores exactly what this undid.
        HostContext.of(api).service().unregisterPipeline(srcPath);
        journalStep(journalFile, oldId, newId, "unregistered source", journal);

        try {
            // Step 2 (S5, S6): ledger fingerprints + DB-export watermark.
            int ledgerRows = com.gamma.acquire.AcquisitionLedgers.shared().renameSource(oldId, newId);
            journalStep(journalFile, oldId, newId, "ledger rows moved: " + ledgerRows, journal);

            // Step 3 (S2, S3): the persistent commit log + run-timestamped audit CSVs.
            int auditFiles = renameAuditFiles(live, oldId, newId);
            journalStep(journalFile, oldId, newId, "audit files renamed: " + auditFiles, journal);

            // Step 4 (S4): the DuckDB status mirror, when DB-backed; no-op for the file-only default.
            if (HostContext.of(api).service().statusStore() instanceof DbStatusStore db) {
                db.renamePipeline(oldId, newId);
                journalStep(journalFile, oldId, newId, "status DB rows updated", journal);
            }

            // Step 6: write the new config — the SAME spec + safety gate POST /config/write runs — then
            // remove the old file. Landing this after the state moves (not before) is deliberate: a crash
            // here leaves the old config's file in place, so the catch block's recovery has something to
            // re-register.
            ConfigWrite cw = writeRenamedConfig(api, e, srcPath, newPath, newFileName,
                    oldId, newId, newNameRaw, journalFile, journal);
            if (cw.refused() != null) return cw.refused();
            String label = cw.label();
            List<Finding> findings = cw.findings();

            // Step 7: dependent configs (plan §1 table) — enrich/job triggers, expectation/decision-rule
            // targets, dataset store references. Best-effort per file (see rewriteDependents); never
            // throws, so it never leaves the migration stuck between a written config and registration.
            int dependents = rewriteDependents ? rewriteDependents(writeRoot, oldId, newId) : 0;
            if (rewriteDependents)
                journalStep(journalFile, oldId, newId, "dependents rewritten: " + dependents, journal);

            // Step 8 (S7): re-register under the new identity — fires catalog invalidation as a side effect.
            HostContext.of(api).service().registerPipeline(newPath);
            journalStep(journalFile, oldId, newId, "registered " + newId, journal);

            // Step 9 (S10 stays untouched — history keeps recording what was true then).
            HostContext.of(api).service().eventLog().emit(Event.builder(EventType.PIPELINE_RENAMED)
                    .source(PipelineRenameRoutes.class.getName()).pipeline(newId)
                    .message("Pipeline '" + oldId + "' renamed to '" + newId + "'")
                    .attr("oldId", oldId).attr("newId", newId));
            journalStep(journalFile, oldId, newId, "completed", journal);
            log.info("[PIPELINE-RENAME] '{}' -> '{}' ({} ledger row(s), {} audit file(s), {} dependent(s))",
                    oldId, newId, ledgerRows, auditFiles, dependents);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("written", true);
            r.put("oldId", oldId);
            r.put("id", newId);
            r.put("name", label);
            r.put("path", writeRoot.relativize(newPath).toString().replace('\\', '/'));
            r.put("ledgerRowsMoved", ledgerRows);
            r.put("auditFilesRenamed", auditFiles);
            r.put("dependentsRewritten", dependents);
            r.put("findings", findings);
            r.put("journal", journal);
            return r;
        } catch (RuntimeException | IOException ex) {
            // Steps 2-7 are not one transaction (plan §3.3 "Failure posture"). The old config file is still
            // on disk in every failure path except the one already handled above (which restores it
            // itself), so re-registering it keeps the pipeline reachable rather than silently vanishing
            // from the registry — though state already moved under steps completed before the failure
            // (named in `journal`) stays moved; this is the plan's documented residual risk, not a bug.
            if (Files.exists(srcPath)) HostContext.of(api).service().registerPipeline(srcPath);
            log.warn("[PIPELINE-RENAME] '{}' -> '{}' failed after {}", oldId, newId, journal, ex);
            throw new ApiException(500, ErrorCodes.INTERNAL, "rename of '" + oldId + "' to '" + newId + "' failed after "
                    + journal.size() + " step(s) — see server log / rename.journal for detail: " + ex.getMessage());
        }
    }

    /** One incomplete migration recovered from {@code rename.journal}: a {@code begin} line (which records
     *  the source file name and the request parameters) with no matching {@code completed}. */
    private record PendingRename(String oldId, String newId, String srcFileName,
                                 boolean rewriteDependents, String newName) {}

    private static final java.util.regex.Pattern JOURNAL_LINE =
            java.util.regex.Pattern.compile("^\\S+ (\\S+) -> (\\S+) : (.*)$");
    private static final java.util.regex.Pattern BEGIN_STEP =
            java.util.regex.Pattern.compile("^begin src=(\\S+) rewriteDependents=(true|false)(?: newName=(.*))?$");

    /**
     * Read {@code rename.journal} back into the still-open migrations, in journal order. A later
     * {@code begin} for the same id pair supersedes an earlier open one (a retried rename); a
     * {@code completed} closes the pair. Lines from before the begin/completed bracket existed never open a
     * bracket, so pre-bracket-era migrations are invisible here — they stay manual-reconciliation cases.
     */
    private List<PendingRename> readPendingRenames(Path journalFile) throws IOException {
        if (!Files.exists(journalFile)) return List.of();
        Map<String, PendingRename> open = new LinkedHashMap<>();
        try (java.io.BufferedReader r = Files.newBufferedReader(journalFile, StandardCharsets.UTF_8)) {
            for (String line; (line = r.readLine()) != null; ) {   // streamed: the journal only grows
                java.util.regex.Matcher m = JOURNAL_LINE.matcher(line);
                if (!m.matches()) continue;
                String key = m.group(1) + " -> " + m.group(2);
                String step = m.group(3);
                java.util.regex.Matcher b = BEGIN_STEP.matcher(step);
                if (b.matches())
                    open.put(key, new PendingRename(m.group(1), m.group(2), b.group(1),
                            Boolean.parseBoolean(b.group(2)), b.group(3)));
                else if ("completed".equals(step))
                    open.remove(key);
            }
        }
        return List.copyOf(open.values());
    }

    /**
     * {@code POST /pipelines/rename/resume} — finish an interrupted identity migration. {@code rename}'s
     * steps 2–7 are not one transaction (see its javadoc), and two of its failure windows a plain retry
     * cannot heal: a crash between writing the new config and deleting the old leaves both files on disk
     * (retry → 409 file-exists), and a failure after the old config is deleted leaves the pipeline
     * registered under neither id (retry → 404). This route reads {@code rename.journal} back — a
     * {@code begin} with no {@code completed} is an incomplete migration — and re-runs the remaining steps.
     * Every step is idempotent (the ledger/status-mirror renames match zero rows once moved; the audit-file
     * and dependent rewrites match nothing once rewritten), so resuming after ANY failure point is safe and
     * a resume that itself fails can be resumed again. The journal supplies discovery + the recorded
     * parameters; the on-disk file state decides what the config-write step still owes.
     *
     * <p>Deliberately an explicit operator action, never a startup hook — an automatic state migration at
     * boot would act without operator intent. The {@code PIPELINE_RENAMED} event is at-least-once: a crash
     * between the emit and the {@code completed} line duplicates it on the next resume — history noise,
     * never state corruption.
     *
     * <p>Body (optional): {@code { oldId, newId }} to pick one migration when several are incomplete.
     */
    private Object resumeRename(ApiContext api, HttpExchange e, Map<String, Object> body) throws IOException {
        Path writeRoot = WriteGates.requireWriteRoot(api, "pipeline write");
        Path journalFile = writeRoot.resolve("rename.journal");

        String selOld = ApiContext.str(body, "oldId");
        String selNew = ApiContext.str(body, "newId");
        List<PendingRename> pending = readPendingRenames(journalFile).stream()
                .filter(p -> selOld == null || selOld.isBlank() || p.oldId().equals(selOld.trim().toLowerCase()))
                .filter(p -> selNew == null || selNew.isBlank() || p.newId().equals(selNew.trim().toLowerCase()))
                .toList();
        if (pending.isEmpty())
            throw new ApiException(404, ErrorCodes.NOT_FOUND, "no incomplete rename found in rename.journal"
                    + (selOld != null || selNew != null ? " matching the given oldId/newId" : ""));
        if (pending.size() > 1)
            throw new ApiException(409, ErrorCodes.CONFLICT, "several incomplete renames — specify {oldId, newId}: "
                    + pending.stream().map(p -> p.oldId() + " -> " + p.newId()).toList());
        PendingRename p = pending.get(0);
        String oldId = p.oldId(), newId = p.newId();

        Path srcPath = WriteGates.jail(writeRoot, writeRoot.resolve(p.srcFileName()), "source path");
        String newFileName = WriteGates.safeName(newId, "pipeline id") + "_pipeline.toon";
        Path newPath = WriteGates.jail(writeRoot, writeRoot.resolve(newFileName), "resolved path");
        boolean srcExists = Files.exists(srcPath);
        boolean newExists = Files.exists(newPath);
        if (!srcExists && !newExists)
            throw new ApiException(409, ErrorCodes.CONFLICT, "cannot resume '" + oldId + "' -> '" + newId + "': neither "
                    + p.srcFileName() + " nor " + newFileName + " exists — manual reconciliation needed");

        // Fail-closed identity checks before touching anything: each surviving file must still be the
        // migration's own — the operator may have replaced either since the failed attempt.
        if (newExists) {
            Map<String, Object> chk = ConfigLoader.filesystem().decode(newPath.toString());
            if (!newId.equals(chk.get("id")))
                throw new ApiException(409, ErrorCodes.CONFLICT, newFileName + " exists but is not this rename's product "
                        + "(id: " + chk.get("id") + ") — manual reconciliation needed");
        }
        PipelineConfig srcCfg = null;
        if (srcExists) {
            srcCfg = PipelineConfig.load(srcPath.toString());
            if (!oldId.equals(srcCfg.identity().pipelineName()))
                throw new ApiException(409, ErrorCodes.CONFLICT, p.srcFileName() + " no longer carries id '" + oldId
                        + "' (now '" + srcCfg.identity().pipelineName() + "') — manual reconciliation needed");
            // The failed attempt's recovery re-registers the source, and it may have been reactivated or
            // started since — the same lifecycle gates a fresh rename runs.
            WriteGates.conflictIf(srcCfg.active(), "pipeline '" + oldId
                    + "' is active; deactivate (active: false) before resuming the rename");
            WriteGates.conflictIf(HostContext.of(api).service().isRunning(oldId), "pipeline '" + oldId
                    + "' is currently running; wait for it to finish before resuming the rename");
        }
        Optional<Path> registeredNew = HostContext.of(api).service().pathFor(newId);
        if (registeredNew.isPresent()
                && !registeredNew.get().toAbsolutePath().normalize().equals(newPath.toAbsolutePath().normalize()))
            throw new ApiException(409, ErrorCodes.CONFLICT, "pipeline id '" + newId + "' is registered to a different config ("
                    + registeredNew.get().getFileName() + ") — manual reconciliation needed");

        refuseGovernedDependents(writeRoot, oldId, newId, p.rewriteDependents());   // finding 2, before any step
        List<String> journal = new ArrayList<>();
        journalStep(journalFile, oldId, newId, "resume", journal);
        if (srcExists) HostContext.of(api).service().unregisterPipeline(srcPath);
        try {
            int ledgerRows = com.gamma.acquire.AcquisitionLedgers.shared().renameSource(oldId, newId);
            journalStep(journalFile, oldId, newId, "ledger rows moved: " + ledgerRows, journal);

            // dirs.* are identical on both sides (rename never relocates them), so whichever config file
            // survives supplies the audit-file locations; renameAuditFiles derives file NAMES from oldId.
            PipelineConfig cfgForDirs = srcCfg != null ? srcCfg : PipelineConfig.load(newPath.toString());
            int auditFiles = renameAuditFiles(cfgForDirs, oldId, newId);
            journalStep(journalFile, oldId, newId, "audit files renamed: " + auditFiles, journal);

            if (HostContext.of(api).service().statusStore() instanceof DbStatusStore db) {
                db.renamePipeline(oldId, newId);
                journalStep(journalFile, oldId, newId, "status DB rows updated", journal);
            }

            String label;
            List<Finding> findings = List.of();
            if (srcExists && !newExists) {
                ConfigWrite cw = writeRenamedConfig(api, e, srcPath, newPath, newFileName,
                        oldId, newId, p.newName(), journalFile, journal);
                if (cw.refused() != null) return cw.refused();
                label = cw.label();
                findings = cw.findings();
            } else {
                if (srcExists) {
                    // The crash window between AtomicFiles.write(newPath) and deleting the source: the new
                    // config is already written (identity verified above) — only the delete is owed.
                    Files.deleteIfExists(srcPath);
                    journalStep(journalFile, oldId, newId,
                            "removed source config (new config already written)", journal);
                }
                // The history moves with the write (writeRenamedConfig); here the write already happened,
                // so it may still be owed. Idempotent: "none to move" once it has.
                journalStep(journalFile, oldId, newId, PipelineHistory.rename(writeRoot, oldId, newId), journal);
                Map<String, Object> written = ConfigLoader.filesystem().decode(newPath.toString());
                label = String.valueOf(written.getOrDefault("name", newId));
            }

            int dependents = p.rewriteDependents() ? rewriteDependents(writeRoot, oldId, newId) : 0;
            if (p.rewriteDependents())
                journalStep(journalFile, oldId, newId, "dependents rewritten: " + dependents, journal);

            HostContext.of(api).service().registerPipeline(newPath);
            journalStep(journalFile, oldId, newId, "registered " + newId, journal);

            HostContext.of(api).service().eventLog().emit(Event.builder(EventType.PIPELINE_RENAMED)
                    .source(PipelineRenameRoutes.class.getName()).pipeline(newId)
                    .message("Pipeline '" + oldId + "' renamed to '" + newId + "' (resumed)")
                    .attr("oldId", oldId).attr("newId", newId));
            journalStep(journalFile, oldId, newId, "completed", journal);
            log.info("[PIPELINE-RENAME] resumed '{}' -> '{}' ({} ledger row(s), {} audit file(s), {} dependent(s))",
                    oldId, newId, ledgerRows, auditFiles, dependents);

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("written", true);
            r.put("resumed", true);
            r.put("oldId", oldId);
            r.put("id", newId);
            r.put("name", label);
            r.put("path", writeRoot.relativize(newPath).toString().replace('\\', '/'));
            r.put("ledgerRowsMoved", ledgerRows);
            r.put("auditFilesRenamed", auditFiles);
            r.put("dependentsRewritten", dependents);
            r.put("findings", findings);
            r.put("journal", journal);
            return r;
        } catch (RuntimeException | IOException ex) {
            // Same posture as rename's catch: keep the pipeline reachable if its old config survives; the
            // bracket stays open, so the NEXT resume picks up from here.
            if (Files.exists(srcPath)) HostContext.of(api).service().registerPipeline(srcPath);
            log.warn("[PIPELINE-RENAME] resume '{}' -> '{}' failed after {}", oldId, newId, journal, ex);
            throw new ApiException(500, ErrorCodes.INTERNAL, "resume of '" + oldId + "' to '" + newId + "' failed after "
                    + journal.size() + " step(s) — see server log / rename.journal for detail: " + ex.getMessage());
        }
    }

    /** Outcome of {@link #writeRenamedConfig}: on success {@code label} + {@code findings}; on refusal only
     *  {@code refused} — the 422 (findings included) has already been sent on the exchange. */
    private record ConfigWrite(String label, List<Finding> findings, Object refused) {}

    /**
     * Step 6 of the identity migration, shared by {@code rename} and {@code resume}: build the renamed
     * config from the source file, gate on ERROR findings the rewrite <em>introduces</em> (as in
     * {@code relabel}: never pre-existing ones — {@code dirs.*} are untouched, so a config whose data lives
     * outside the default allowed roots was never subject to the write-time policy, and re-punishing it
     * here would make any such deployment unrenameable), write atomically to {@code newPath}, then delete
     * the source file.
     */
    private ConfigWrite writeRenamedConfig(ApiContext api, HttpExchange e, Path srcPath, Path newPath,
            String newFileName, String oldId, String newId, String newNameRaw,
            Path journalFile, List<String> journal) throws IOException {
        Map<String, Object> src = ConfigLoader.filesystem().decode(srcPath.toString());
        Map<String, Object> out = renamedDoc(src, oldId, newId, newNameRaw);
        String label = String.valueOf(out.get("name"));

        // The whole SaveGate (ASSURE-MAKER-CHECKER-1 S0), before and after.
        Path writeRoot = api.writeRoot();
        List<Finding> findings = SaveGate.check(api, "pipeline", out, writeRoot, newPath.getParent(),
                SaveGate.Referents.MUST_EXIST);
        List<Finding> introduced = SaveGate.introduced(findings, SaveGate.check(api, "pipeline", src, writeRoot,
                srcPath.getParent(), SaveGate.Referents.MUST_EXIST));
        if (!introduced.isEmpty()) {
            journalStep(journalFile, oldId, newId,
                    "refused: renamed config introduces ERROR findings — source restored", journal);
            HostContext.of(api).service().registerPipeline(srcPath);   // the config write never happened — restore visibility
            return new ConfigWrite(null, null, ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "the renamed config introduces ERROR-level findings; not written",
                    "findings", introduced, "journal", journal)));
        }
        byte[] bytes = ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8);
        RefusalConfigAudit.write(newPath, bytes, ".cfg-");
        // PIPELINE-CONFIG-HISTORY-1: the history follows the identity — moved only AFTER the renamed config
        // landed, so a refused rename (above) leaves it under the id the pipeline still has — and the rename
        // is itself a version, filed under newId.
        journalStep(journalFile, oldId, newId, PipelineHistory.rename(api.writeRoot(), oldId, newId), journal);
        PipelineHistory.record(api.writeRoot(), newPath);
        Files.deleteIfExists(srcPath);
        journalStep(journalFile, oldId, newId, "wrote " + newFileName + "; removed source config", journal);
        return new ConfigWrite(label, findings, null);
    }

    /** The renamed config: {@code name} (the new display name, else the old one) and {@code id} first, the rest verbatim. */
    private static Map<String, Object> renamedDoc(Map<String, Object> src, String oldId, String newId, String newNameRaw) {
        String label = (newNameRaw == null || newNameRaw.isBlank())
                ? String.valueOf(src.getOrDefault("name", oldId)) : newNameRaw.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", label);
        out.put("id", newId);
        src.forEach((k, v) -> {
            if (!"name".equals(k) && !"id".equals(k)) out.put(k, v);
        });
        return out;
    }

    /** Append one line to {@code <writeRoot>/rename.journal} (plan §3.3) — best-effort; a journal write
     *  failure must never abort a migration step that already succeeded. */
    private void journalStep(Path journalFile, String oldId, String newId, String step, List<String> journal) {
        journal.add(step);
        try {
            Files.writeString(journalFile, Instant.now() + " " + oldId + " -> " + newId + " : " + step + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("[PIPELINE-RENAME] could not append to rename.journal: {}", ex.getMessage());
        }
    }

    /**
     * Rename the persistent commit log and every run-timestamped audit CSV from an {@code oldId} prefix to
     * a {@code newId} prefix, in place (S2, S3) — {@code dirs.*} themselves are untouched (plan §1.1), only
     * each filename's identity prefix moves. Mirrors the glob {@link com.gamma.service.FileStatusStore}
     * itself uses to read them, so a rename here is exactly what makes them findable under the new id
     * afterwards. Returns the count of files renamed.
     */
    private int renameAuditFiles(PipelineConfig cfg, String oldId, String newId) throws IOException {
        int count = 0;
        Path statusParent = null;
        String commitLogPath = cfg.dirs().commitLogPath();
        if (commitLogPath != null && !commitLogPath.isBlank()) {
            // The commit-log FILE name is derived from oldId, not taken from cfg: commitLogPath is always
            // <parent>/<pipelineName>_commits.log (PipelineConfigParser), and resume may only have the NEW
            // config to read dirs from — whose own commitLogPath already carries the new id.
            statusParent = Path.of(commitLogPath).getParent();
            Path oldLog = statusParent.resolve(oldId + "_commits.log");
            if (Files.exists(oldLog)) {
                Files.move(oldLog, statusParent.resolve(newId + "_commits.log"));
                count++;
            }
        }
        if (statusParent == null) {
            String statusFile = cfg.dirs().statusFilePath();
            if (statusFile == null || statusFile.isBlank()) return count;
            statusParent = Path.of(statusFile).toAbsolutePath().getParent();
        }
        if (statusParent == null || !Files.isDirectory(statusParent)) return count;
        for (String infix : List.of("_status_", "_batches_", "_lineage_")) {
            List<Path> matches = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(statusParent, oldId + infix + "*.csv")) {
                ds.forEach(matches::add);
            }
            for (Path p : matches) {
                String name = p.getFileName().toString();
                Files.move(p, statusParent.resolve(newId + name.substring(oldId.length())));
                count++;
            }
        }
        return count;
    }

    /**
     * Rewrite every dependent config's reference to {@code oldId} into {@code newId}: each link
     * {@link LinkCensus#renamedWithPipeline()} names — {@code onRename = REWRITE}, naming the Pipeline or its store —
     * found by one {@link LinkGraph} of the write root (`SPACE-LINK-CENSUS-1` LC-2): Enrichment triggers and by-name
     * references, Job {@code on_pipeline} (one member of a comma list), Expectation / Decision Rule pipeline targets,
     * Dataset store references (and a virtual Dataset's SQL) and Alert Rule {@code onPipeline}. Best-effort per
     * file — one malformed sibling must never abort a rename whose state-moving steps already committed. Returns
     * the count of files rewritten (a file with several links is one).
     */
    private int rewriteDependents(Path writeRoot, String oldId, String newId) {
        return rewrite(writeRoot, oldId, newId, kind -> true, null);
    }

    /**
     * The dependents a rename would rewrite whose KIND this Space's approval policy holds
     * (`ASSURE-MAKER-CHECKER-1` verification finding 2): the rename's own hold is on {@code pipeline}, so
     * without this an Expectation, Decision Rule, Dataset, Alert Rule, Enrichment or Job under a policy would be
     * rewritten with no Pending Change of its own. The same selection as the rewrite, run without writing.
     */
    private List<String> governedDependents(Path writeRoot, String oldId, String newId) {
        ApprovalPolicy policy = ApprovalPolicy.forRoot(writeRoot);
        if (!policy.holdsAnything()) return List.of();
        List<String> planned = new ArrayList<>();
        rewrite(writeRoot, oldId, newId, kind -> policy.ruleFor(kind) != null, planned);
        return planned;
    }

    /** Refuse (409) a rename or resume whose dependent rewrite would change governed components without approval. */
    private void refuseGovernedDependents(Path writeRoot, String oldId, String newId, boolean rewriteDependents) {
        if (!rewriteDependents) return;
        List<String> governed = governedDependents(writeRoot, oldId, newId);
        if (!governed.isEmpty())
            throw new ApiException(409, ErrorCodes.CONFLICT, "renaming '" + oldId + "' would rewrite " + governed
                    + ", which this Space's approval policy holds for approval — change each of them on its own "
                    + "first (so it can be approved), or rename with rewriteDependents:false");
    }

    /** One file or registry component holding renamed links, with the links that name the old id. */
    private record Holder(String kind, Path file, String name, Set<LinkCensus.Link> links) {}

    /**
     * Rewrite (or, with {@code planned}, only list) every holder of a kind {@code kinds} accepts. Holders come
     * in graph order — Enrichments, Jobs, then Expectations, Decision Rules, Datasets, Alert Rules — and a
     * registry component is written through its store, a config file atomically in place.
     */
    private int rewrite(Path writeRoot, String oldId, String newId, Predicate<String> kinds, List<String> planned) {
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        Map<String, Map<String, ComponentRegistry.Component>> listed = new HashMap<>();
        int count = 0;
        for (Holder h : holders(writeRoot, oldId, kinds)) {
            if (!ComponentStore.WRITABLE_TYPES.contains(h.kind())) {
                try {
                    Map<String, Object> raw = ConfigLoader.filesystem().decode(h.file().toString());
                    Map<String, Object> out = rewritten(h, raw, oldId, newId);
                    if (out == raw) continue;   // changed on disk since the graph read it
                    if (planned != null) {
                        planned.add(h.kind() + " '" + h.file().getFileName() + "'");
                        continue;
                    }
                    // temp-file prefix as before: .enr- / .job-
                    AtomicFiles.write(h.file(), ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8),
                            "." + h.kind().substring(0, 3) + "-");
                    count++;
                } catch (Exception ex) {
                    log.warn("[PIPELINE-RENAME] skipping unreadable {} {}: {}", h.kind(), h.file(), ex.getMessage());
                }
                continue;
            }
            ComponentRegistry.Component c = listed.computeIfAbsent(h.kind(), k -> {
                Map<String, ComponentRegistry.Component> byName = new HashMap<>();
                for (ComponentRegistry.Component x : store.list(k)) byName.putIfAbsent(x.name(), x);
                return byName;
            }).get(h.name());
            if (c == null) continue;
            Map<String, Object> updated = rewritten(h, c.content(), oldId, newId);
            if (updated == c.content()) continue;
            // A rename moves target only; a decision-rule's invoke-api consequences (and its stamped makers) stay.
            if (DecisionRuleGuard.TYPE.equals(h.kind())) DecisionRuleGuard.checkTargetRewrite(c.content(), updated);
            if (planned != null) {
                planned.add(h.kind() + " '" + c.name() + "'");
                continue;
            }
            try {
                store.write(h.kind(), c.name(), updated);
                count++;
            } catch (IOException ex) {
                log.warn("[PIPELINE-RENAME] could not rewrite {} '{}': {}", h.kind(), c.name(), ex.getMessage());
            }
        }
        return count;
    }

    /**
     * The holders of a renamed link naming {@code oldId}, in graph order. An Expectation / Decision Rule
     * {@code target} counts only when it names a Pipeline ({@code targetType} {@code pipeline}, the default).
     */
    private static List<Holder> holders(Path writeRoot, String oldId, Predicate<String> kinds) {
        Set<LinkCensus.Link> renamed = Set.copyOf(LinkCensus.renamedWithPipeline());
        Map<List<Object>, Holder> out = new LinkedHashMap<>();
        for (LinkGraph.Edge e : LinkGraph.of(writeRoot, ReservedConfigPaths::reserved).edges()) {
            if (!renamed.contains(e.link()) || !e.toKind().equals(e.link().toKind()) || !kinds.test(e.fromKind())
                    || !names(e.link(), e.value(), oldId)) continue;
            out.computeIfAbsent(List.of(e.fromKind(), String.valueOf(e.file()), e.fromName()),
                    k -> new Holder(e.fromKind(), e.file(), e.fromName(), new HashSet<>())).links().add(e.link());
        }
        return List.copyOf(out.values());
    }

    /**
     * Whether one link value names {@code oldId}, case-insensitively after trimming: a store reference by its
     * first path segment ({@code physicalRef}, as {@code DataSourceBundleResolver.datasetReadsStore} reads it),
     * an Alert Rule's {@code onPipeline} also with a space read as {@code _} ({@code AlertService}). A SQL text
     * never selects its holder — it follows the holder's {@code sourceName} ({@link #rewritten}).
     */
    private static boolean names(LinkCensus.Link l, String value, String oldId) {
        String v = value.trim();
        if (l.type() == LinkCensus.Type.SQL_RELATION) return false;
        if (l.keyPath().equals("physicalRef")) {
            int slash = v.indexOf('/');
            return !"null".equals(v) && (slash < 0 ? v : v.substring(0, slash)).equalsIgnoreCase(oldId);
        }
        return v.equalsIgnoreCase(oldId)
                || l.fromKind().equals("alert-rule") && v.replace(' ', '_').equalsIgnoreCase(oldId);
    }

    /** One link value with {@code oldId} replaced, or {@code null} when it does not name it. A comma list
     *  ({@link LinkCensus.Type#ID_LIST}) keeps every other member and its spacing as written. */
    private static String renamedValue(LinkCensus.Link l, String value, String oldId, String newId) {
        if (l.type() == LinkCensus.Type.ID_LIST && value.contains(",")) {
            String[] parts = value.split(",", -1);
            boolean hit = false;
            for (int i = 0; i < parts.length; i++) {
                if (!names(l, parts[i], oldId)) continue;
                String p = parts[i];
                int lead = p.length() - p.stripLeading().length(), trail = p.length() - p.stripTrailing().length();
                parts[i] = p.substring(0, lead) + newId + p.substring(p.length() - trail);
                hit = true;
            }
            return hit ? String.join(",", parts) : null;
        }
        if (!names(l, value, oldId)) return null;
        String v = value.trim();
        int slash = v.indexOf('/');
        return l.keyPath().equals("physicalRef") && slash >= 0 ? newId + v.substring(slash) : newId;
    }

    /**
     * {@code doc} with the holder's links rewritten (a copy; {@code doc} itself when nothing changed). A Job's
     * keys are read inside its {@code job:} section when it has one ({@link LinkGraph#jobSection}).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> rewritten(Holder h, Map<String, Object> doc, String oldId, String newId) {
        Map<String, Object> out = doc;
        boolean nested = h.kind().equals("job") && doc.get("job") instanceof Map<?, ?>;
        for (LinkCensus.Link l : h.links()) {
            String path = (nested ? "job." : "") + l.keyPath();
            out = (Map<String, Object>) rewriteAt(out, path.split("\\."), 0, v -> renamedValue(l, v, oldId, newId));
        }
        // VIRTUAL-DATASET-SQL-RENAME-1: a virtual Dataset's SQL FROM names the store its sourceName names; rewrite
        // it via the DuckDB parse tree (unparseable SQL is left as is and keeps failing closed at read).
        Object sn = doc.get("sourceName");
        if (h.kind().equals("dataset") && doc.get("sql") != null && !Objects.equals(sn, out.get("sourceName")))
            out.put("sql", com.gamma.sql.SqlGuard.renameBaseTable(
                    String.valueOf(doc.get("sql")), String.valueOf(sn).trim(), newId));
        return out;
    }

    /**
     * {@code node} with {@code fn} applied to the scalar at {@code segs[i..]} (census key-path syntax: a map key, or
     * {@code *} for each value of a map). Copies only the maps on a changed path; returns {@code node} itself when
     * {@code fn} changed nothing ({@code fn} answers {@code null} for "not this value").
     */
    private static Object rewriteAt(Object node, String[] segs, int i, UnaryOperator<String> fn) {
        if (i == segs.length) {
            if (node == null || node instanceof Map<?, ?> || node instanceof List<?>) return node;
            String v = fn.apply(String.valueOf(node));
            return v == null ? node : v;
        }
        if (!(node instanceof Map<?, ?> map)) return node;
        Map<String, Object> copy = null;
        for (Map.Entry<?, ?> en : map.entrySet()) {
            String key = String.valueOf(en.getKey());
            if (!segs[i].equals("*") && !segs[i].equals(key)) continue;
            Object v = rewriteAt(en.getValue(), segs, i + 1, fn);
            if (v == en.getValue()) continue;
            if (copy == null) {
                copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> c : map.entrySet()) copy.put(String.valueOf(c.getKey()), c.getValue());
            }
            copy.put(key, v);
        }
        return copy == null ? node : copy;
    }
}
