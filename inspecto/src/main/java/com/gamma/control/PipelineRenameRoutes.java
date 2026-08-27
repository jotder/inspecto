package com.gamma.control;

import com.gamma.config.io.ConfigCodec;
import com.gamma.config.io.ConfigLoader;
import com.gamma.config.spec.ConfigSpecs;
import com.gamma.config.spec.Finding;
import com.gamma.config.spec.Severity;
import com.gamma.config.safety.ConfigSafetyValidator;
import com.gamma.config.safety.SafetyPolicy;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.Event;
import com.gamma.event.EventType;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.DbStatusStore;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import static com.gamma.util.Values.mapAt;

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
        Path srcPath = api.service().pathFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));
        WriteGates.jail(writeRoot, srcPath, "config path");
        PipelineConfig live = api.service().configFor(source)
                .orElseThrow(() -> new ApiException(404, "no pipeline named '" + source + "'"));

        String rawId = ApiContext.str(body, "newId");
        if (rawId == null || rawId.isBlank())
            throw new ApiException(400, "body must include 'newId'");
        String newId = rawId.trim().toLowerCase();
        if (!newId.matches("[a-z0-9][a-z0-9_]*"))
            throw new ApiException(422, "newId '" + newId
                    + "' must match [a-z0-9][a-z0-9_]* (lowercase letters, digits and underscores)");

        String oldId = live.identity().pipelineName();
        WriteGates.conflictIf(live.active(),
                "pipeline '" + oldId + "' is active; deactivate (active: false) before renaming");
        WriteGates.conflictIf(api.service().isRunning(oldId),
                "pipeline '" + oldId + "' is currently running; wait for it to finish before renaming");
        WriteGates.conflictIf(api.service().pathFor(newId).isPresent(),
                "pipeline id '" + newId + "' is already registered");
        String newFileName = WriteGates.safeName(newId, "pipeline id") + "_pipeline.toon";
        Path newPath = WriteGates.jail(writeRoot, writeRoot.resolve(newFileName), "resolved path");
        WriteGates.conflictIf(Files.exists(newPath), "file exists: " + newFileName);

        if (Boolean.parseBoolean(String.valueOf(body.getOrDefault("relocateDirs", "false"))))
            throw new ApiException(422, "relocateDirs is not yet supported — rename leaves dirs.* pointing "
                    + "where they already do (plan §1.1); relocate the data tree manually if that's needed");
        boolean rewriteDependents = !"false".equalsIgnoreCase(
                String.valueOf(body.getOrDefault("rewriteDependents", "true")));

        List<String> journal = new ArrayList<>();
        Path journalFile = writeRoot.resolve("rename.journal");
        String newNameRaw = ApiContext.str(body, "newName");

        // Step 0: bracket the migration in the journal BEFORE any state moves. `begin` records the source
        // file name and the request parameters; `completed` (after step 9) closes the bracket. A begin with
        // no completed is what POST /pipelines/rename/resume looks for — and the params recorded here are
        // what let it finish the job. newName stays last on the line: it may contain spaces.
        journalStep(journalFile, oldId, newId, "begin src=" + srcPath.getFileName()
                + " rewriteDependents=" + rewriteDependents
                + (newNameRaw == null || newNameRaw.isBlank() ? "" : " newName=" + newNameRaw.trim()), journal);

        // Step 1 (S9): evict per-pipeline bookkeeping + the run registry. Cheaply reversible on failure —
        // re-registering the same path restores exactly what this undid.
        api.service().unregisterPipeline(srcPath);
        journalStep(journalFile, oldId, newId, "unregistered source", journal);

        try {
            // Step 2 (S5, S6): ledger fingerprints + DB-export watermark.
            int ledgerRows = com.gamma.acquire.AcquisitionLedgers.shared().renameSource(oldId, newId);
            journalStep(journalFile, oldId, newId, "ledger rows moved: " + ledgerRows, journal);

            // Step 3 (S2, S3): the persistent commit log + run-timestamped audit CSVs.
            int auditFiles = renameAuditFiles(live, oldId, newId);
            journalStep(journalFile, oldId, newId, "audit files renamed: " + auditFiles, journal);

            // Step 4 (S4): the DuckDB status mirror, when DB-backed; no-op for the file-only default.
            if (api.service().statusStore() instanceof DbStatusStore db) {
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
            api.service().registerPipeline(newPath);
            journalStep(journalFile, oldId, newId, "registered " + newId, journal);

            // Step 9 (S10 stays untouched — history keeps recording what was true then).
            api.service().eventLog().emit(Event.builder(EventType.PIPELINE_RENAMED)
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
            if (Files.exists(srcPath)) api.service().registerPipeline(srcPath);
            log.warn("[PIPELINE-RENAME] '{}' -> '{}' failed after {}", oldId, newId, journal, ex);
            throw new ApiException(500, "rename of '" + oldId + "' to '" + newId + "' failed after "
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
        for (String line : Files.readAllLines(journalFile, StandardCharsets.UTF_8)) {
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
            throw new ApiException(404, "no incomplete rename found in rename.journal"
                    + (selOld != null || selNew != null ? " matching the given oldId/newId" : ""));
        if (pending.size() > 1)
            throw new ApiException(409, "several incomplete renames — specify {oldId, newId}: "
                    + pending.stream().map(p -> p.oldId() + " -> " + p.newId()).toList());
        PendingRename p = pending.get(0);
        String oldId = p.oldId(), newId = p.newId();

        Path srcPath = WriteGates.jail(writeRoot, writeRoot.resolve(p.srcFileName()), "source path");
        String newFileName = WriteGates.safeName(newId, "pipeline id") + "_pipeline.toon";
        Path newPath = WriteGates.jail(writeRoot, writeRoot.resolve(newFileName), "resolved path");
        boolean srcExists = Files.exists(srcPath);
        boolean newExists = Files.exists(newPath);
        if (!srcExists && !newExists)
            throw new ApiException(409, "cannot resume '" + oldId + "' -> '" + newId + "': neither "
                    + p.srcFileName() + " nor " + newFileName + " exists — manual reconciliation needed");

        // Fail-closed identity checks before touching anything: each surviving file must still be the
        // migration's own — the operator may have replaced either since the failed attempt.
        if (newExists) {
            Map<String, Object> chk = ConfigLoader.filesystem().decode(newPath.toString());
            if (!newId.equals(chk.get("id")))
                throw new ApiException(409, newFileName + " exists but is not this rename's product "
                        + "(id: " + chk.get("id") + ") — manual reconciliation needed");
        }
        PipelineConfig srcCfg = null;
        if (srcExists) {
            srcCfg = PipelineConfig.load(srcPath.toString());
            if (!oldId.equals(srcCfg.identity().pipelineName()))
                throw new ApiException(409, p.srcFileName() + " no longer carries id '" + oldId
                        + "' (now '" + srcCfg.identity().pipelineName() + "') — manual reconciliation needed");
            // The failed attempt's recovery re-registers the source, and it may have been reactivated or
            // started since — the same lifecycle gates a fresh rename runs.
            WriteGates.conflictIf(srcCfg.active(), "pipeline '" + oldId
                    + "' is active; deactivate (active: false) before resuming the rename");
            WriteGates.conflictIf(api.service().isRunning(oldId), "pipeline '" + oldId
                    + "' is currently running; wait for it to finish before resuming the rename");
        }
        Optional<Path> registeredNew = api.service().pathFor(newId);
        if (registeredNew.isPresent()
                && !registeredNew.get().toAbsolutePath().normalize().equals(newPath.toAbsolutePath().normalize()))
            throw new ApiException(409, "pipeline id '" + newId + "' is registered to a different config ("
                    + registeredNew.get().getFileName() + ") — manual reconciliation needed");

        List<String> journal = new ArrayList<>();
        journalStep(journalFile, oldId, newId, "resume", journal);
        if (srcExists) api.service().unregisterPipeline(srcPath);
        try {
            int ledgerRows = com.gamma.acquire.AcquisitionLedgers.shared().renameSource(oldId, newId);
            journalStep(journalFile, oldId, newId, "ledger rows moved: " + ledgerRows, journal);

            // dirs.* are identical on both sides (rename never relocates them), so whichever config file
            // survives supplies the audit-file locations; renameAuditFiles derives file NAMES from oldId.
            PipelineConfig cfgForDirs = srcCfg != null ? srcCfg : PipelineConfig.load(newPath.toString());
            int auditFiles = renameAuditFiles(cfgForDirs, oldId, newId);
            journalStep(journalFile, oldId, newId, "audit files renamed: " + auditFiles, journal);

            if (api.service().statusStore() instanceof DbStatusStore db) {
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
                Map<String, Object> written = ConfigLoader.filesystem().decode(newPath.toString());
                label = String.valueOf(written.getOrDefault("name", newId));
            }

            int dependents = p.rewriteDependents() ? rewriteDependents(writeRoot, oldId, newId) : 0;
            if (p.rewriteDependents())
                journalStep(journalFile, oldId, newId, "dependents rewritten: " + dependents, journal);

            api.service().registerPipeline(newPath);
            journalStep(journalFile, oldId, newId, "registered " + newId, journal);

            api.service().eventLog().emit(Event.builder(EventType.PIPELINE_RENAMED)
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
            if (Files.exists(srcPath)) api.service().registerPipeline(srcPath);
            log.warn("[PIPELINE-RENAME] resume '{}' -> '{}' failed after {}", oldId, newId, journal, ex);
            throw new ApiException(500, "resume of '" + oldId + "' to '" + newId + "' failed after "
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
        String label = (newNameRaw == null || newNameRaw.isBlank())
                ? String.valueOf(src.getOrDefault("name", oldId)) : newNameRaw.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", label);
        out.put("id", newId);
        src.forEach((k, v) -> {
            if (!"name".equals(k) && !"id".equals(k)) out.put(k, v);
        });

        List<Finding> findings = new ArrayList<>(ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), out));
        findings.addAll(ConfigSafetyValidator.check("pipeline", out, SafetyPolicy.defaultPolicy()));
        Set<String> preExisting = new HashSet<>();
        ConfigLoader.filesystem().validate(ConfigSpecs.pipeline(), src).forEach(f -> preExisting.add(PipelineSupport.findingKey(f)));
        ConfigSafetyValidator.check("pipeline", src, SafetyPolicy.defaultPolicy())
                .forEach(f -> preExisting.add(PipelineSupport.findingKey(f)));
        List<Finding> introduced = findings.stream()
                .filter(f -> f.severity() == Severity.ERROR)
                .filter(f -> !preExisting.contains(PipelineSupport.findingKey(f)))
                .toList();
        if (!introduced.isEmpty()) {
            journalStep(journalFile, oldId, newId,
                    "refused: renamed config introduces ERROR findings — source restored", journal);
            api.service().registerPipeline(srcPath);   // the config write never happened — restore visibility
            return new ConfigWrite(null, null, ApiContext.respondJson(e, 422, Map.of("written", false,
                    "error", "the renamed config introduces ERROR-level findings; not written",
                    "findings", introduced, "journal", journal)));
        }
        byte[] bytes = ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8);
        AtomicFiles.write(newPath, bytes, ".cfg-");
        Files.deleteIfExists(srcPath);
        journalStep(journalFile, oldId, newId, "wrote " + newFileName + "; removed source config", journal);
        return new ConfigWrite(label, findings, null);
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
     * Rewrite every dependent config's reference to {@code oldId} into {@code newId} (plan §1's dependent
     * table): {@code *_enrich.toon} triggers, {@code *_job.toon} triggers, {@code expectation}/
     * {@code decision-rule} pipeline targets, and {@code dataset} store references. Best-effort per file —
     * one malformed sibling must never abort a rename whose state-moving steps already committed. Returns
     * the total count of files rewritten.
     */
    private int rewriteDependents(Path writeRoot, String oldId, String newId) {
        int count = rewriteEnrichTriggers(writeRoot, oldId, newId);
        count += rewriteJobTriggers(writeRoot, oldId, newId);
        count += rewriteComponentTargets(writeRoot, "expectation", oldId, newId);
        count += rewriteComponentTargets(writeRoot, "decision-rule", oldId, newId);
        count += rewriteDatasetRefs(writeRoot, oldId, newId);
        return count;
    }

    /** {@code triggers.on_pipeline} in every {@code *_enrich.toon} directly under the write root. */
    private int rewriteEnrichTriggers(Path writeRoot, String oldId, String newId) {
        if (!Files.isDirectory(writeRoot)) return 0;
        int count = 0;
        try (Stream<Path> files = Files.list(writeRoot)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("_enrich.toon")).toList()) {
                try {
                    Map<String, Object> raw = ConfigLoader.filesystem().decode(p.toString());
                    if (!(raw.get("triggers") instanceof Map<?, ?> t)
                            || !oldId.equalsIgnoreCase(String.valueOf(t.get("on_pipeline")))) continue;
                    Map<String, Object> triggers = new LinkedHashMap<>(mapAt(raw, "triggers"));
                    triggers.put("on_pipeline", newId);
                    Map<String, Object> out = new LinkedHashMap<>(raw);
                    out.put("triggers", triggers);
                    AtomicFiles.write(p, ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8), ".enr-");
                    count++;
                } catch (Exception ex) {
                    log.warn("[PIPELINE-RENAME] skipping unreadable enrichment {}: {}", p, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("[PIPELINE-RENAME] could not list enrichments under {}: {}", writeRoot, ex.getMessage());
        }
        return count;
    }

    /** Top-level {@code on_pipeline} in every {@code jobs/*_job.toon} under the write root. */
    private int rewriteJobTriggers(Path writeRoot, String oldId, String newId) {
        Path jobsDir = writeRoot.resolve("jobs");
        if (!Files.isDirectory(jobsDir)) return 0;
        int count = 0;
        try (Stream<Path> files = Files.list(jobsDir)) {
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith("_job.toon")).toList()) {
                try {
                    Map<String, Object> raw = ConfigLoader.filesystem().decode(p.toString());
                    if (!oldId.equalsIgnoreCase(String.valueOf(raw.get("on_pipeline")))) continue;
                    Map<String, Object> out = new LinkedHashMap<>(raw);
                    out.put("on_pipeline", newId);
                    AtomicFiles.write(p, ConfigCodec.toToon(out).getBytes(StandardCharsets.UTF_8), ".job-");
                    count++;
                } catch (Exception ex) {
                    log.warn("[PIPELINE-RENAME] skipping unreadable job {}: {}", p, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.warn("[PIPELINE-RENAME] could not list jobs under {}: {}", jobsDir, ex.getMessage());
        }
        return count;
    }

    /**
     * {@code target} on every {@code type} component (expectation / decision-rule) whose {@code targetType}
     * is {@code pipeline} (the default when absent) and whose {@code target} names {@code oldId} —
     * mirrors {@code DataSourceBundleResolver.ruleTargets}'s matching rule.
     */
    private int rewriteComponentTargets(Path writeRoot, String type, String oldId, String newId) {
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        int count = 0;
        for (ComponentRegistry.Component c : store.list(type)) {
            Map<String, Object> content = c.content();
            String targetType = String.valueOf(content.getOrDefault("targetType", "pipeline"));
            if (!"pipeline".equalsIgnoreCase(targetType)) continue;
            String target = content.get("target") == null ? "" : String.valueOf(content.get("target")).trim();
            if (!oldId.equalsIgnoreCase(target)) continue;
            Map<String, Object> updated = new LinkedHashMap<>(content);
            updated.put("target", newId);
            try {
                store.write(type, c.name(), updated);
                count++;
            } catch (IOException ex) {
                log.warn("[PIPELINE-RENAME] could not rewrite {} '{}': {}", type, c.name(), ex.getMessage());
            }
        }
        return count;
    }

    /**
     * {@code sourceName} and/or the first path segment of {@code physicalRef} on every {@code dataset}
     * component that reads {@code oldId}'s store — mirrors
     * {@code DataSourceBundleResolver.datasetReadsStore}'s {@code physicalRef} rule, widened to
     * {@code sourceName} (a {@code kind: virtual} dataset's direct store reference, which that resolver
     * does not need to check but a rename does — an unrewritten one would silently start reading nothing).
     */
    private int rewriteDatasetRefs(Path writeRoot, String oldId, String newId) {
        ComponentStore store = new ComponentStore(writeRoot.resolve("registry"));
        int count = 0;
        for (ComponentRegistry.Component c : store.list("dataset")) {
            Map<String, Object> content = c.content();
            Map<String, Object> updated = new LinkedHashMap<>(content);
            boolean changed = false;

            Object sn = content.get("sourceName");
            if (sn != null && oldId.equalsIgnoreCase(String.valueOf(sn).trim())) {
                updated.put("sourceName", newId);
                changed = true;
            }

            Object ref = content.get("physicalRef");
            String refStr = ref == null ? "" : String.valueOf(ref).trim();
            if (!refStr.isEmpty() && !"null".equals(refStr)) {
                int slash = refStr.indexOf('/');
                String head = slash < 0 ? refStr : refStr.substring(0, slash);
                if (head.equalsIgnoreCase(oldId)) {
                    updated.put("physicalRef", newId + (slash < 0 ? "" : refStr.substring(slash)));
                    changed = true;
                }
            }

            if (!changed) continue;
            try {
                store.write("dataset", c.name(), updated);
                count++;
            } catch (IOException ex) {
                log.warn("[PIPELINE-RENAME] could not rewrite dataset '{}': {}", c.name(), ex.getMessage());
            }
        }
        return count;
    }
}
