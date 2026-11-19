package com.gamma.control;

import com.gamma.acquire.CollectorConnectors;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.inspector.RunFiles;
import com.gamma.pipeline.ComponentRegistry;
import com.gamma.pipeline.ComponentStore;
import com.gamma.service.CollectorService;
import com.gamma.service.CollectorService.PreRun;
import com.gamma.service.FileStatusStore;
import com.gamma.service.SpaceContext;
import com.gamma.spi.auth.ApiException;
import com.gamma.spi.http.ApiContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>The steps before an import Pipeline reads its inbox</b> ({@code BASIC-ELT-PROFILE-1} BE-1b; LA-ARRIVAL-MODES-1,
 * LA-ARRIVAL-NEW-COLUMNS-1): judge every waiting file by the Dataset's columns (a refused one is set aside in
 * {@code refused/} with its reason), then keep the new columns every file the run takes carries - in that order, ONE
 * implementation for both ways an import's Pipeline runs:
 * <ul>
 *   <li><b>Inside Add data's run</b> (Add a file, Run now): the run claims the Dataset in {@link OnboardImport#IN_FLIGHT}
 *       and calls {@link #runAll} before its run; what was refused and kept lands in the person's answer.</li>
 *   <li><b>A run that did not pass through Add data</b> (a scheduled poll, a {@code POST /pipelines/{id}/run}): the
 *       engine's {@link CollectorService#preRunStep pre-run step} {@value #STEP} claims the Dataset, runs the same list,
 *       and holds the claim through the run, so the commit steps ({@link ImportCommitSteps}) run as its holder and Add data
 *       cannot judge the inbox under it. A scheduled run of a Dataset someone else holds does not start (the next tick
 *       looks again); a person's run of one goes ahead untouched - Add data's own run is such a run. A kept column is
 *       undone when the run fails. A scheduled run of a local folder with a stability window judges only the files
 *       unmodified for that window and takes only those: a file still being written is never judged half-written.
 *       After the run the step writes its arrivals sidecar lines, as Run now does (BASIC-WATCH-ARRIVALS-1).
 *       <i>Watch this folder</i> ({@link InboxWatch}, B1) is such a scheduled run: it also waits while Add data is off in
 *       the Space, and keeps what Data status says after a run that took a file ({@code watchError}: the run failed, or
 *       set a file aside).</li>
 * </ul>
 */
final class ImportPreRunSteps {

    private ImportPreRunSteps() {}

    private static final Logger log = LoggerFactory.getLogger(ImportPreRunSteps.class);

    /** The engine pre-run step's name. One engine step runs the whole list: the second step reads what the first judged. */
    static final String STEP = "import.pre_run";

    /** What one run's pre-run steps share: the import, and what each step found. */
    static final class Run {
        final OnboardImport.Space space;
        final String id, type;
        final Map<String, Object> content;
        final OnboardImport.Job job;
        /** The inbox files to judge (absolute, normalised); null: every file waiting. */
        final Set<Path> only;
        /** False: the person chose, on Add a file, to leave new columns out. */
        final boolean keepNewColumns;
        /** Add a file's upload, already judged by its caller: taken with the accepted waiting files. */
        DatasetNewColumns.Taken upload;
        /** Out: the waiting files set aside, {@code [{file, reason, setAside}]}. */
        List<Map<String, Object>> refused = List.of();
        /** Out: the files the run takes, each with its column check. */
        final List<DatasetNewColumns.Taken> taken = new ArrayList<>();

        Run(OnboardImport.Space space, String id, Map<String, Object> content, OnboardImport.Job job, Set<Path> only,
            boolean keepNewColumns) {
            this.space = space;
            this.id = id;
            this.content = content;
            this.type = String.valueOf(content.get("importFormat"));
            this.job = job;
            this.only = only;
            this.keepNewColumns = keepNewColumns;
        }

        String pipeline() {
            return String.valueOf(content.get("importPipeline"));
        }
    }

    interface Work {
        void run(Run run) throws IOException;
    }

    record Step(String name, int order, Work work) {}

    /** The steps, sorted by {@code order}: the order both ways run them. */
    static final List<Step> STEPS = java.util.stream.Stream.of(
            new Step("import.judge_inbox", 100, r -> {
                r.refused = OnboardImport.judgeInbox(r.space.dataRoot(), r.pipeline(), r.id, r.type,
                        r.space.writeRoot().resolve(OnboardImport.CONFIG_DIR).resolve(r.pipeline() + "_schema.toon"),
                        r.content, r.taken, r.only);
                if (r.upload != null) r.taken.add(r.upload);
            }),
            new Step("import.settle_new_columns", 200, r -> OnboardImport.settleNewColumns(r.job, r.id, r.type, r.taken, r.keepNewColumns)))
            .sorted(java.util.Comparator.comparingInt(Step::order)).toList();

    /** Every step, in order: Add data's in-run path, and the engine step's. */
    static void runAll(Run run) throws IOException {
        for (Step step : STEPS) step.work().run(run);
    }

    // ── a run outside Add data ─────────────────────────────────────────────────────────────────────────────────

    /** The control planes whose Spaces' runs are looked at. */
    private static final Set<ApiContext> ATTACHED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * The {@link OnboardImport#IN_FLIGHT} claims an engine run holds from its pre-run steps to its end, each with what the
     * run's commit steps answered ({@link #outcome}): its arrivals line reads the follow-on's index outcome from it.
     */
    private static final Map<String, Map<String, Object>> HELD_BY_RUN = new java.util.concurrent.ConcurrentHashMap<>();

    /** Called once per ControlApi (from {@link OnboardRoutes#register}); registering again replaces the step. */
    static void register(ApiContext api) {
        ATTACHED.add(api);
        api.onClose(() -> ATTACHED.remove(api));
        CollectorService.preRunStep(STEP, 100, ImportPreRunSteps::beforeRun);
    }

    /** Whether {@code claim} ({@code writeRoot|id}) is held by an engine run's pre-run step: its commit steps act for it. */
    static boolean heldByRun(String claim) {
        return HELD_BY_RUN.containsKey(claim);
    }

    /** A commit step's outcome ({@link ImportCommitSteps}) for the run that holds {@code claim}; nothing when no run holds it. */
    static void outcome(String claim, Map<String, Object> out) {
        Map<String, Object> summary = HELD_BY_RUN.get(claim);
        if (summary != null) summary.putAll(out);
    }

    private static PreRun beforeRun(CollectorService service, String pipeline, boolean scheduled) {
        for (ApiContext api : ATTACHED)
            for (SpaceContext ctx : HostContext.of(api).spaces().all())
                if (ctx.service() == service) return beforeRun(api, ctx.id().value(), service, pipeline, scheduled);
        return PreRun.GO;
    }

    private static PreRun beforeRun(ApiContext api, String space, CollectorService service, String pipeline, boolean scheduled) {
        String prev = MDC.get(EventLog.SPACE_MDC_KEY);
        MDC.put(EventLog.SPACE_MDC_KEY, space);   // the Space's roots, as a request of that Space reads them
        try {
            Path writeRoot = api.writeRoot(), dataRoot = api.dataRoot();
            if (writeRoot == null || dataRoot == null) return PreRun.GO;
            String id = pipeline;   // an import's Pipeline is named as its Dataset
            Map<String, Object> content = new ComponentStore(writeRoot.resolve("registry")).get("dataset", id)
                    .map(ComponentRegistry.Component::content).orElse(null);
            if (content == null || !OnboardImport.isOurs(writeRoot, id, content, service)) return PreRun.GO;
            PipelineConfig cfg = service.configFor(id).orElse(null);
            if (cfg == null) return PreRun.GO;
            // B1: Watch this folder is a scheduled run of the import; while Add data is off in the Space it waits (as before)
            boolean watched = scheduled && InboxWatch.watched(content, cfg);
            if (watched && !DataSettings.forRoot(writeRoot).selfImportInForce()) return PreRun.SKIP;
            String claim = writeRoot + "|" + id;
            if (!OnboardImport.IN_FLIGHT.add(claim)) return scheduled ? PreRun.SKIP : PreRun.GO;   // Add data, a rewrite or a delete holds it
            Map<String, Object> summary = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
            HELD_BY_RUN.put(claim, summary);
            OnboardImport.Space s = new OnboardImport.Space(writeRoot, dataRoot, service, "scheduler");
            OnboardImport.Job job = new OnboardImport.Job(s, id, String.valueOf(content.get("kind")), null, null, content, false);
            RunFiles.Scope narrowed = null;
            boolean handedOff = false;   // the claim and the kept column now belong to the run's after-hook
            try {
                Set<Path> only = scheduled ? settled(OnboardImport.inbox(dataRoot, id), cfg, content) : null;
                Run run = new Run(s, id, content, job, only, true);
                runAll(run);
                if (only != null) narrowed = RunFiles.only(OnboardImport.inbox(dataRoot, id), only);
                if (!run.refused.isEmpty() || !job.keptColumns.isEmpty())
                    log.info("Dataset '{}' before its run: refused {}, kept columns {}", id, run.refused, job.keptColumns);
                boolean kept = !job.keptColumns.isEmpty();
                // BASIC-WATCH-ARRIVALS-1: what the run adds to the folder's own records - the files it takes and the ones it
                // sets aside - read on disk, so under either status store; nothing to read when no file waits to be taken
                boolean takes = only == null || !only.isEmpty();
                Set<String> quarantined = takes ? quarantined(cfg) : Set.of();
                Set<String> filesBefore = takes ? fileKeys(cfg) : Set.of();
                final RunFiles.Scope scope = narrowed;
                java.util.function.Consumer<Boolean> after = ok -> {
                    boolean good = false;
                    List<Map<String, String>> setAside = List.of();
                    try {
                        setAside = takes ? setAsideSince(cfg, quarantined) : List.of();
                        // a run that sets a file aside after a column was kept for it did not take what the column was kept for
                        good = ok && (!kept || setAside.isEmpty());
                        if (scope != null) scope.close();
                        if (!good) job.rollback();   // a column kept for a run that failed is not kept
                        if (takes) recordArrivals(job, cfg, filesBefore, good, summary);
                    } finally {
                        release(claim);
                    }
                    // Data status (watchError): kept until a run that takes a file says otherwise - not cleared by the 30 s
                    // runs that find nothing to take
                    if (watched && takes) InboxWatch.ran(dataRoot, id, !ok ? InboxWatch.SERVER_FAULT
                            : setAside.isEmpty() ? null : InboxWatch.setAside(setAside, dataRoot, writeRoot));
                };
                handedOff = true;
                // a kept column grew the schema: the run reads the Pipeline's config again, or it would read the file
                // with the old one and set it aside (field_mismatch)
                return kept ? PreRun.reloadThen(after) : PreRun.then(after);
            } catch (IOException | RuntimeException failed) {
                log.warn("Dataset '{}' before its run failed: {}", id, failed.toString());
                if (watched) InboxWatch.ran(dataRoot, id, failed instanceof ApiException a && a.status < 500
                        ? ServerFaults.withoutServerPaths(a.getMessage(), dataRoot, writeRoot) : InboxWatch.SERVER_FAULT);
                return scheduled ? PreRun.SKIP : PreRun.GO;   // a person's run goes ahead and answers for itself
            } finally {
                if (!handedOff) {   // a failure, an Error included: nothing is kept, the claim is not leaked
                    try {
                        if (narrowed != null) narrowed.close();
                        job.rollback();
                    } finally {
                        release(claim);
                    }
                }
            }
        } finally {
            if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY);
            else MDC.put(EventLog.SPACE_MDC_KEY, prev);
        }
    }

    /**
     * The files {@code cfg}'s quarantine folder holds, by path: read from the folder itself ({@link FileStatusStore}), not
     * from the service's status store. The default store ({@code status.backend=db}) is a projection that catches up only
     * after the run (the poll's {@code syncStatus}), so it would not yet hold a file this run set aside.
     */
    private static Set<String> quarantined(PipelineConfig cfg) {
        Set<String> out = new HashSet<>();
        for (Map<String, String> q : ON_DISK.quarantine(cfg)) out.add(String.valueOf(q.get("path")));
        return out;
    }

    private static final FileStatusStore ON_DISK = new FileStatusStore();

    /**
     * The files set aside since {@code before} (quarantine paths): the quarantine entries {@code cfg}'s folder holds now that
     * it did not hold then, read on disk, less each one's companion rejected-rows file ({@code <stem>_errors.csv}, moved in
     * beside it), which is not a file the run was given.
     */
    private static List<Map<String, String>> setAsideSince(PipelineConfig cfg, Set<String> before) {
        List<Map<String, String>> fresh = new ArrayList<>();
        for (Map<String, String> q : ON_DISK.quarantine(cfg)) if (!before.contains(String.valueOf(q.get("path")))) fresh.add(q);
        Set<String> companions = new HashSet<>();
        for (Map<String, String> q : fresh) companions.add(com.gamma.etl.FileNames.errorsFileName(cfg, String.valueOf(q.get("file"))));
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> q : fresh) if (!companions.contains(String.valueOf(q.get("file")))) out.add(q);
        return out;
    }

    /** The per-file ledger rows {@code cfg} holds, as {@code filename|consignment_id}, read on disk as {@link #quarantined}. */
    private static Set<String> fileKeys(PipelineConfig cfg) {
        Set<String> out = new HashSet<>();
        for (Map<String, String> f : ON_DISK.files(cfg)) out.add(f.get("filename") + "|" + f.get("consignment_id"));
        return out;
    }

    /**
     * BASIC-WATCH-ARRIVALS-1: the arrivals sidecar lines of an engine run of the import, as Run now writes them
     * ({@link OnboardImport.Job#recordArrival}): one per file the run added to the ledger, with its index outcome (the
     * follow-on's answer in {@code summary}) and the columns the run kept - none when they were taken back ({@code good}
     * false). Best effort, as every sidecar line.
     */
    private static void recordArrivals(OnboardImport.Job job, PipelineConfig cfg, Set<String> before, boolean good,
                                       Map<String, Object> summary) {
        try {
            List<Map<String, String>> took = new ArrayList<>();
            for (Map<String, String> f : ON_DISK.files(cfg))
                if (!before.contains(f.get("filename") + "|" + f.get("consignment_id"))) took.add(f);
            if (took.isEmpty()) return;
            job.taken = took;
            if (!good) job.keptColumns = List.of();
            Map<String, Object> outcome;
            synchronized (summary) {
                outcome = new java.util.LinkedHashMap<>(summary);
            }
            job.recordArrival(outcome);
        } catch (RuntimeException lost) {
            log.warn("Dataset '{}': the arrivals of its run were not recorded: {}", job.id, lost.toString());
        }
    }

    private static void release(String claim) {
        HELD_BY_RUN.remove(claim);
        OnboardImport.IN_FLIGHT.remove(claim);
    }

    /**
     * The waiting files a scheduled run of a local folder judges and takes: each unmodified for the Pipeline's stability
     * window. Null (every file, the run unnarrowed) when the Collector is remote (its files land during the run) or has no
     * stability window.
     */
    private static Set<Path> settled(Path inbox, PipelineConfig cfg, Map<String, Object> content) throws IOException {
        PipelineConfig.Stability st = cfg.collector().stability();
        if (CollectorConnectors.isRemote(cfg) || !st.enabled() || st.windowMillis() <= 0) return null;
        long now = System.currentTimeMillis();
        Set<Path> out = new HashSet<>();
        for (Path p : DatasetFeed.waiting(inbox, "." + OnboardImport.extension(String.valueOf(content.get("importFormat"))))) {
            try {
                if (now - Files.getLastModifiedTime(p).toMillis() >= st.windowMillis()) out.add(p.toAbsolutePath().normalize());
            } catch (IOException gone) {
                // gone since it was listed: not judged, not taken
            }
        }
        return out;
    }
}
