package com.gamma.job;

import com.gamma.audit.EventLevel;
import com.gamma.audit.EventType;
import com.gamma.consignment.ConsignmentOutputStores;
import com.gamma.consignment.DbConsignmentOutputStore;
import com.gamma.consignment.DbConsignmentOutputStore.DayPendingSummary;
import com.gamma.consignment.DbConsignmentOutputStore.JobDayStatus;
import com.gamma.enrich.DatedReferences;
import com.gamma.etl.ConsignmentEvent;
import com.gamma.etl.PipelineConfig;
import com.gamma.event.EventLog;
import com.gamma.pipeline.PipelineGraph;
import com.gamma.pipeline.PipelineNode;
import com.gamma.pipeline.PipelineStore;
import com.gamma.pipeline.exec.RowShaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Level-triggered reconcile loop for {@code schedule.mode: by_day} Jobs
 * ({@code DATED-REFERENCE-RUNS-1} P2/P3/P4).
 *
 * <p>Reads {@code pending(Job, D)} from {@link DbConsignmentOutputStore}, checks Reference
 * availability and input conditions, respects {@code quiet} / {@code max_hold}, handles
 * {@code deadline} outcomes ({@code previous} / {@code fail} / {@code skip}), and submits
 * up to {@code max_parallel} ready days per Job according to {@code order}.
 */
public final class DayReconciler {

    private static final Logger log = LoggerFactory.getLogger(DayReconciler.class);

    public static final String PARAM_DAY = "__by_day_record_day";
    public static final String PARAM_INPUT_PATHS = "__by_day_input_paths";
    public static final String PARAM_IF_VERSION_MISSING = "__by_day_if_version_missing";
    public static final String PARAM_STALE = "__by_day_stale";
    public static final String PARAM_STALE_REASON = "__by_day_stale_reason";

    /** Per-day detail for the Backlog API / UI (P4). */
    public record DayBacklog(
            String day,
            String status,
            long pendingFiles,
            long pendingRows,
            String waitingReason,
            boolean stale,
            String staleReason,
            String referenceVersions,
            String firstArrivedAt,
            String updatedAt) {}

    /** Per-Job summary for the Backlog API / UI (P4). */
    public record JobBacklog(
            String jobName,
            String inputStream,
            boolean paused,
            int readyCount,
            int waitingCount,
            int runningCount,
            int staleCount,
            long oldestWaitingAgeSeconds,
            long estimatedDrainSeconds,
            List<DayBacklog> days) {}

    public interface RunSubmitter {
        boolean submitDay(JobConfig cfg, String day, List<String> inputPaths,
                          String ifVersionMissingOverride, boolean stale, String staleReason);
        int activeCount(String jobName);
    }

    private final Supplier<List<JobConfig>> jobsSupplier;
    private final Supplier<List<PipelineConfig>> pipelinesSupplier;
    private final PipelineStore pipelineStore;
    private final ZoneId zone;
    private final EventLog eventLog;
    private final RunSubmitter submitter;

    private final Set<String> pausedJobs = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, DayBacklog>> lastSnapshot = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSuccessDuration = new ConcurrentHashMap<>();
    private final Map<String, Long> avgDayDurationSeconds = new ConcurrentHashMap<>();
    private final Set<String> runningJobDays = ConcurrentHashMap.newKeySet();

    public DayReconciler(Supplier<List<JobConfig>> jobsSupplier,
                         Supplier<List<PipelineConfig>> pipelinesSupplier,
                         PipelineStore pipelineStore,
                         ZoneId zone,
                         EventLog eventLog,
                         RunSubmitter submitter) {
        this.jobsSupplier = jobsSupplier;
        this.pipelinesSupplier = pipelinesSupplier;
        this.pipelineStore = pipelineStore;
        this.zone = zone == null ? ZoneId.of("UTC") : zone;
        this.eventLog = eventLog;
        this.submitter = submitter;
    }

    public boolean pauseJob(String jobName) {
        if (jobName == null) return false;
        return pausedJobs.add(jobName);
    }

    public boolean resumeJob(String jobName) {
        if (jobName == null) return false;
        return pausedJobs.remove(jobName);
    }

    public boolean isPaused(String jobName) {
        return jobName != null && pausedJobs.contains(jobName);
    }

    public void markDayRunning(String jobName, String day) {
        if (jobName != null && day != null) runningJobDays.add(jobName + "|" + day);
    }

    public void markDayFinished(String jobName, String day, long durationMs) {
        if (jobName != null && day != null) runningJobDays.remove(jobName + "|" + day);
        if (jobName != null && durationMs > 0) {
            long secs = Math.max(1L, durationMs / 1000L);
            avgDayDurationSeconds.merge(jobName, secs, (oldVal, v) -> (oldVal + v) / 2);
        }
    }

    /** React to a committed Consignment: mark stale days when a Reference updates, then reconcile. */
    public void onConsignmentCommitted(ConsignmentEvent event) {
        if (event == null || !"SUCCESS".equalsIgnoreCase(event.status()) || event.dryRun()) return;
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return;

        List<PipelineConfig> pipelines = pipelinesSupplier == null ? List.of() : pipelinesSupplier.get();
        PipelineConfig producer = pipelines.stream()
                .filter(p -> p.identity().pipelineName().equals(event.pipeline()))
                .findFirst().orElse(null);

        if (producer != null && producer.producesReference() && producer.reference().isDaily()) {
            Set<String> updatedDays = extractDaysFromPartitions(event.partitions());
            if (!updatedDays.isEmpty()) {
                for (JobConfig jc : jobsSupplier.get()) {
                    ByDaySchedule sched = ByDaySchedule.parse(jc.name(), jc.params());
                    if (!sched.isByDay()) continue;
                    JobInputs deps = resolveJobInputs(jc);
                    if (!deps.references.contains(event.pipeline())) continue;
                    LocalDate today = LocalDate.now(zone);
                    for (String d : updatedDays) {
                        boolean withinWindow = false;
                        if (sched.rerunOnReferenceChange()) {
                            try {
                                LocalDate dayDate = LocalDate.parse(d);
                                withinWindow = !dayDate.isBefore(today.minusDays(sched.rerunWithinDays()));
                            } catch (Exception ignored) { }
                        }
                        store.markReferenceChanged(jc.name(), d,
                                "reference " + event.pipeline() + " updated for " + d,
                                withinWindow);
                    }
                }
            }
        }
        reconcileAll(Instant.now(), false);
    }

    /** Run one reconcile pass across all enabled {@code by_day} Jobs. */
    public synchronized void reconcileAll(Instant now, boolean ignoreQuiet) {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return;
        for (JobConfig jc : jobsSupplier.get()) {
            if (!jc.enabled()) continue;
            ByDaySchedule sched = ByDaySchedule.parse(jc.name(), jc.params());
            if (!sched.isByDay()) continue;
            reconcileJob(jc, sched, store, now, ignoreQuiet, true);
        }
    }

    /** Drain a specific Job immediately (used by P4 Backlog Drain / Run now). */
    public synchronized int drainJob(String jobName, boolean ignoreQuiet) {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return 0;
        JobConfig jc = jobsSupplier.get().stream()
                .filter(j -> j.name().equals(jobName)).findFirst().orElse(null);
        if (jc == null) return 0;
        ByDaySchedule sched = ByDaySchedule.parse(jc.name(), jc.params());
        if (!sched.isByDay()) return 0;
        return reconcileJob(jc, sched, store, Instant.now(), ignoreQuiet, true);
    }

    /** Run one specific day immediately for {@code jobName} (P4 Backlog Run now). */
    public synchronized boolean runDayNow(String jobName, String day, boolean allowPreviousFallback) {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return false;
        JobConfig jc = jobsSupplier.get().stream()
                .filter(j -> j.name().equals(jobName)).findFirst().orElse(null);
        if (jc == null) return false;
        JobInputs deps = resolveJobInputs(jc);
        if (deps.inputStream == null) return false;
        List<String> paths = store.pendingPathsForDay(jobName, deps.inputStream, day);
        if (paths.isEmpty()) return false;
        boolean forcePrev = allowPreviousFallback && !allReferencesReady(jc, deps, day, null);
        return submitter.submitDay(jc, day, paths,
                forcePrev ? "previous" : null,
                forcePrev, forcePrev ? "manual run with previous reference fallback" : null);
    }

    /** Mark a waiting day as skipped (P4 Backlog Give up waiting). */
    public boolean giveUpWaiting(String jobName, String day) {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        if (store == null) return false;
        JobConfig jc = jobsSupplier.get().stream()
                .filter(j -> j.name().equals(jobName)).findFirst().orElse(null);
        if (jc == null) return false;
        JobInputs deps = resolveJobInputs(jc);
        if (deps.inputStream == null) return false;
        List<String> paths = store.pendingPathsForDay(jobName, deps.inputStream, day);
        if (paths.isEmpty()) return false;
        store.recordJobDayProgress(jobName, day, paths, null, null, "SKIPPED",
                false, "operator gave up waiting", null);
        reconcileJob(jc, ByDaySchedule.parse(jc.name(), jc.params()), store, Instant.now(), false, false);
        return true;
    }

    private int reconcileJob(JobConfig jc, ByDaySchedule sched, DbConsignmentOutputStore store,
                             Instant now, boolean ignoreQuiet, boolean allowSubmit) {
        JobInputs deps = resolveJobInputs(jc);
        if (deps.inputStream == null) return 0;

        List<DayPendingSummary> pending = store.pendingDaysForJob(jc.name(), deps.inputStream);
        Map<String, JobDayStatus> recordedStatuses = new LinkedHashMap<>();
        for (JobDayStatus st : store.jobDayStatuses(jc.name())) {
            recordedStatuses.put(st.recordDay(), st);
        }

        Map<String, DayBacklog> dayMap = new LinkedHashMap<>();
        List<String> readyDays = new ArrayList<>();
        Map<String, Boolean> readyUsesFallback = new LinkedHashMap<>();
        Map<String, String> readyStaleReason = new LinkedHashMap<>();

        for (DayPendingSummary p : pending) {
            String day = p.recordDay();
            boolean unknownDay = DbConsignmentOutputStore.UNKNOWN_DAY.equals(day);
            String runningKey = jc.name() + "|" + day;
            if (runningJobDays.contains(runningKey)) {
                dayMap.put(day, new DayBacklog(day, "RUNNING", p.pendingFiles(), p.pendingRows(),
                        null, false, null, null, p.firstArrivedAt(), p.lastArrivedAt()));
                continue;
            }

            StringBuilder whyNotReady = new StringBuilder();
            boolean refsReady = unknownDay || allReferencesReady(jc, deps, day, whyNotReady);
            boolean settled = unknownDay || ignoreQuiet || isSettled(p, sched, now, whyNotReady);
            Instant deadlineAt = unknownDay ? null : sched.deadlineForDay(day, zone);
            boolean overdue = deadlineAt != null && now.isAfter(deadlineAt);

            JobDayStatus prevSt = recordedStatuses.get(day);
            boolean wasStale = prevSt != null && prevSt.stale();
            String staleReason = prevSt != null ? prevSt.staleReason() : null;

            if (refsReady && settled) {
                readyDays.add(day);
                readyUsesFallback.put(day, false);
                readyStaleReason.put(day, staleReason);
                dayMap.put(day, new DayBacklog(day, "READY", p.pendingFiles(), p.pendingRows(),
                        null, wasStale, staleReason, prevSt == null ? null : prevSt.referenceVersions(),
                        p.firstArrivedAt(), p.lastArrivedAt()));
            } else if (overdue && !refsReady) {
                switch (sched.onDeadline()) {
                    case PREVIOUS -> {
                        readyDays.add(day);
                        readyUsesFallback.put(day, true);
                        readyStaleReason.put(day, "deadline exceeded: used previous reference");
                        dayMap.put(day, new DayBacklog(day, "READY", p.pendingFiles(), p.pendingRows(),
                                "deadline exceeded (previous fallback)", true,
                                "deadline exceeded: used previous reference", null,
                                p.firstArrivedAt(), p.lastArrivedAt()));
                    }
                    case FAIL -> {
                        List<String> paths = store.pendingPathsForDay(jc.name(), deps.inputStream, day);
                        store.recordJobDayProgress(jc.name(), day, paths, null, null, "FAILED",
                                true, "deadline exceeded: " + whyNotReady, null);
                        emitDeadlineAlert(jc.name(), day, whyNotReady.toString());
                        dayMap.put(day, new DayBacklog(day, "FAILED", p.pendingFiles(), p.pendingRows(),
                                whyNotReady.toString(), true, "deadline exceeded", null,
                                p.firstArrivedAt(), p.lastArrivedAt()));
                    }
                    case SKIP -> {
                        List<String> paths = store.pendingPathsForDay(jc.name(), deps.inputStream, day);
                        store.recordJobDayProgress(jc.name(), day, paths, null, null, "SKIPPED",
                                false, "deadline exceeded (skipped)", null);
                        dayMap.put(day, new DayBacklog(day, "SKIPPED", p.pendingFiles(), p.pendingRows(),
                                whyNotReady.toString(), false, "deadline exceeded (skipped)", null,
                                p.firstArrivedAt(), p.lastArrivedAt()));
                    }
                }
            } else {
                String reason = whyNotReady.toString();
                List<String> paths = store.pendingPathsForDay(jc.name(), deps.inputStream, day);
                store.recordJobDayProgress(jc.name(), day, paths, null, null, "WAITING",
                        wasStale, reason, null);
                dayMap.put(day, new DayBacklog(day, "WAITING", p.pendingFiles(), p.pendingRows(),
                        reason, wasStale, staleReason, prevSt == null ? null : prevSt.referenceVersions(),
                        p.firstArrivedAt(), p.lastArrivedAt()));
            }
        }

        // Also include STALE / FAILED enriched days from job_day_progress that weren't in pending
        for (JobDayStatus st : recordedStatuses.values()) {
            if (!dayMap.containsKey(st.recordDay()) && (st.stale() || "FAILED".equals(st.status()))) {
                dayMap.put(st.recordDay(), new DayBacklog(st.recordDay(), st.status(), st.files(), st.rows(),
                        null, st.stale(), st.staleReason(), st.referenceVersions(), null, st.updatedAt()));
            }
        }

        lastSnapshot.put(jc.name(), dayMap);

        if (!allowSubmit || isPaused(jc.name()) || readyDays.isEmpty()) return 0;

        String todayStr = LocalDate.now(zone).toString();
        List<String> sortedReady = sched.sortDays(readyDays, todayStr);
        int active = submitter.activeCount(jc.name());
        int capacity = Math.max(0, sched.maxParallel() - active);
        int submitted = 0;
        for (String day : sortedReady) {
            if (submitted >= capacity) break;
            List<String> paths = store.pendingPathsForDay(jc.name(), deps.inputStream, day);
            if (paths.isEmpty()) continue;
            boolean fallback = Boolean.TRUE.equals(readyUsesFallback.get(day));
            String sReason = readyStaleReason.get(day);
            markDayRunning(jc.name(), day);
            if (submitter.submitDay(jc, day, paths, fallback ? "previous" : null, fallback, sReason)) {
                submitted++;
            } else {
                markDayFinished(jc.name(), day, 0);
            }
        }
        return submitted;
    }

    private boolean isSettled(DayPendingSummary p, ByDaySchedule sched, Instant now, StringBuilder why) {
        try {
            Instant last = p.lastArrivedAt() == null ? null : Instant.parse(p.lastArrivedAt());
            Instant first = p.firstArrivedAt() == null ? null : Instant.parse(p.firstArrivedAt());
            boolean quietPassed = last == null || !now.isBefore(last.plus(sched.quiet()));
            boolean maxHoldPassed = first != null && !now.isBefore(first.plus(sched.maxHold()));
            if (quietPassed || maxHoldPassed) return true;
            long remSecs = Duration.between(now, last.plus(sched.quiet())).getSeconds();
            if (!why.isEmpty()) why.append("; ");
            why.append("settling (quiet ").append(Math.max(1, remSecs)).append("s remaining)");
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private boolean allReferencesReady(JobConfig jc, JobInputs deps, String day, StringBuilder why) {
        List<PipelineConfig> pipelines = pipelinesSupplier == null ? List.of() : pipelinesSupplier.get();
        ByDaySchedule sched = ByDaySchedule.parse(jc.name(), jc.params());

        Set<String> allRefs = new LinkedHashSet<>(deps.references);
        allRefs.addAll(sched.inputs().keySet());

        for (String refName : allRefs) {
            PipelineConfig refCfg = pipelines.stream()
                    .filter(p -> p.identity().pipelineName().equals(refName) && p.producesReference())
                    .findFirst().orElse(null);
            if (refCfg == null) continue;
            if (refCfg.reference().isDaily()) {
                String fmt = (refCfg.output() == null || refCfg.output().format() == null)
                        ? "PARQUET" : refCfg.output().format();
                List<String> avail = DatedReferences.availableDays(
                        Path.of(refCfg.dirs().database()), com.gamma.sql.SqlViews.ext(fmt));
                String policy = deps.refMissingPolicy.getOrDefault(refName, "wait");
                boolean hasVersion = avail.contains(day)
                        || ("previous".equals(policy) && avail.stream().anyMatch(d -> d.compareTo(day) <= 0));
                if (!hasVersion) {
                    if (why != null) {
                        if (!why.isEmpty()) why.append("; ");
                        why.append("waiting for reference ").append(refName).append(" (").append(day).append(")");
                    }
                    return false;
                }
            }
            ByDaySchedule.InputCondition cond = sched.inputs().get(refName);
            if (cond != null && cond.notEmpty()) {
                DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
                if (store != null) {
                    long rows = store.dailyVolume(refName, day, day).stream()
                            .mapToLong(DbConsignmentOutputStore.DailyVolume::rows).sum();
                    if (rows <= 0) {
                        if (why != null) {
                            if (!why.isEmpty()) why.append("; ");
                            why.append("reference ").append(refName).append(" is empty for ").append(day);
                        }
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void emitDeadlineAlert(String jobName, String day, String reason) {
        if (eventLog == null) return;
        eventLog.emit(com.gamma.audit.Event.builder(EventType.JOB_FAILED)
                .source("job:" + jobName)
                .level(EventLevel.ERROR)
                .message("Deadline exceeded for job '" + jobName + "' day " + day + ": " + reason)
                .attr("job", jobName)
                .attr("day", day));
    }

    private static Set<String> extractDaysFromPartitions(List<String> partitions) {
        Set<String> days = new LinkedHashSet<>();
        if (partitions == null) return days;
        for (String p : partitions) {
            if (p == null) continue;
            for (String seg : p.split("/")) {
                int eq = seg.indexOf('=');
                if (eq > 0) {
                    String val = seg.substring(eq + 1);
                    DatedReferences.normalizeDay(val).ifPresent(days::add);
                }
            }
        }
        return days;
    }

    private record JobInputs(String inputStream, List<String> references, Map<String, String> refMissingPolicy) {}

    private JobInputs resolveJobInputs(JobConfig jc) {
        String inputStream = jc.opt("input_stream", null);
        List<String> refs = new ArrayList<>();
        Map<String, String> policies = new LinkedHashMap<>();

        String pipelineId = jc.opt("pipeline", jc.opt("flow", null));
        if (pipelineId != null && pipelineStore != null) {
            PipelineGraph g = pipelineStore.get(pipelineId).orElse(null);
            if (g != null) {
                for (PipelineNode n : g.nodes()) {
                    if ("source_store".equals(n.type()) && inputStream == null) {
                        Object storeName = n.cfg("store");
                        if (storeName != null && !storeName.toString().isBlank()) {
                            inputStream = storeName.toString();
                        }
                    } else if ("transform.join".equals(n.type())) {
                        Object refObj = n.cfg("reference");
                        if (refObj != null) {
                            String name = com.gamma.etl.ReferenceBinding.name(refObj.toString());
                            if (name != null) {
                                refs.add(name);
                                Object pol = n.cfg("if_version_missing");
                                if (pol != null) policies.put(name, pol.toString());
                            }
                        }
                    }
                }
            }
        }
        if (inputStream == null && !jc.onPipelines().isEmpty()) {
            inputStream = jc.onPipelines().get(0);
        }
        return new JobInputs(inputStream, refs, policies);
    }

    /** Current backlog snapshot across all {@code by_day} Jobs (P4). */
    public List<JobBacklog> backlogSnapshot() {
        DbConsignmentOutputStore store = ConsignmentOutputStores.shared();
        Instant now = Instant.now();
        List<JobBacklog> result = new ArrayList<>();
        for (JobConfig jc : jobsSupplier.get()) {
            ByDaySchedule sched = ByDaySchedule.parse(jc.name(), jc.params());
            if (!sched.isByDay()) continue;
            JobInputs deps = resolveJobInputs(jc);
            if (store != null) {
                reconcileJob(jc, sched, store, now, false, false);
            }
            Map<String, DayBacklog> dayMap = lastSnapshot.getOrDefault(jc.name(), Map.of());
            List<DayBacklog> days = new ArrayList<>(dayMap.values());
            int ready = 0, waiting = 0, running = 0, stale = 0;
            long oldestAge = 0;
            for (DayBacklog d : days) {
                switch (d.status()) {
                    case "READY" -> ready++;
                    case "WAITING" -> {
                        waiting++;
                        if (d.firstArrivedAt() != null) {
                            try {
                                long age = Duration.between(Instant.parse(d.firstArrivedAt()), now).getSeconds();
                                if (age > oldestAge) oldestAge = age;
                            } catch (Exception ignored) { }
                        }
                    }
                    case "RUNNING" -> running++;
                }
                if (d.stale()) stale++;
            }
            long perDaySecs = avgDayDurationSeconds.getOrDefault(jc.name(), 30L);
            long drainSecs = ((long) (ready + waiting) * perDaySecs) / Math.max(1, sched.maxParallel());
            result.add(new JobBacklog(jc.name(), deps.inputStream(), isPaused(jc.name()),
                    ready, waiting, running, stale, oldestAge, drainSecs, days));
        }
        return result;
    }
}
