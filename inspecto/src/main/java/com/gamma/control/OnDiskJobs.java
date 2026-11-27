package com.gamma.control;

import com.gamma.event.EventLog;
import com.gamma.job.AttachApprovals;
import com.gamma.job.JobConfig;
import com.gamma.job.JobService;
import com.gamma.job.JobTemplate;
import com.gamma.job.PublicationApproval;
import com.gamma.service.CollectorService;
import com.gamma.service.SpaceContext;
import com.gamma.service.SpaceId;
import com.gamma.service.SpaceLock;
import com.gamma.service.SpaceManager;
import com.gamma.spi.http.ApiContext;
import com.gamma.util.ToonHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * SB-2c of {@code SIGNAL-BOARD-1} — a Job file added, changed or removed on disk (a hand edit, a {@code git pull}) takes
 * effect with no restart. The Space lock finds the change ({@code CONFIG_CHANGED_OUTSIDE}) and hands it to this
 * {@link SpaceLock.OutsideApplier}, which applies it through the API save path: the same parse and checks as
 * {@code PUT /jobs/{name}} ({@link JobRoutes#parseJob}, {@link JobRoutes#refuseBeforeRegister}), then
 * {@link JobService#upsertJob} / {@link JobService#removeJob}. A file that fails a check is not applied — the running
 * Job stays as it was — and its {@code CONFIG_CHANGED_OUTSIDE} event says why ({@code applied} attribute, WARN).
 *
 * <p>The lock computes only when asked (boot, a server write, a read of its report), so a light poll asks it when a
 * {@code *_job.toon} file's time or size changed: a walk of the config tree that stats each Job file, every
 * {@value #DEFAULT_POLL_SECONDS} s,
 * {@code -Djobs.ondisk.poll.seconds=0} turns the poll off. The lock's own kill switch ({@code -Dspace.integrity=off})
 * turns all of this off.
 */
final class OnDiskJobs implements SpaceLock.OutsideApplier, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OnDiskJobs.class);

    static final String POLL_PROPERTY = "jobs.ondisk.poll.seconds";
    static final long DEFAULT_POLL_SECONDS = 5;

    private final SpaceManager spaces;
    private final ApiContext api;
    private final ScheduledExecutorService poll;
    /** config root → the last stamp of its Job files. */
    private final Map<Path, String> stamps = new ConcurrentHashMap<>();
    /** absolute Job file → the name of the Job it last ran ("" = none known). */
    private final Map<String, String> ran = new ConcurrentHashMap<>();

    OnDiskJobs(SpaceManager spaces, ApiContext api) {
        this.spaces = spaces;
        this.api = api;
        long seconds = Long.getLong(POLL_PROPERTY, DEFAULT_POLL_SECONDS);
        if (seconds <= 0) {
            this.poll = null;
            return;
        }
        this.poll = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jobs-ondisk-poll");
            t.setDaemon(true);
            return t;
        });
        pollOnce();   // the baseline now, so an edit made before the first tick is still seen
        poll.scheduleWithFixedDelay(this::pollOnce, seconds, seconds, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        if (poll != null) poll.shutdownNow();
    }

    // ── the poll: ask the lock to look when a Job file's time or size changed ──────────────────

    void pollOnce() {
        try {
            java.util.Set<Path> live = new java.util.HashSet<>();
            for (SpaceContext sc : spaces.all()) {
                Path root = sc.root().config();
                if (root == null) continue;
                live.add(root);
                String now = stamp(root);
                String before = stamps.put(root, now);
                if (before != null && !before.equals(now)) SpaceLock.refresh(root);
            }
            stamps.keySet().retainAll(live);   // a closed or deleted Space is forgotten
        } catch (RuntimeException ex) {
            log.warn("[JOBS] the on-disk Job poll failed: {}", ex.toString());
        }
    }

    /** Path, time and size of every live {@code *_job.toon} under {@code root}, in path order. */
    static String stamp(Path root) {
        if (!Files.isDirectory(root)) return "";
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.forEach(p -> {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (!SpacePromotion.liveJob(rel)) return;
                try {
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    if (a.isRegularFile()) out.put(rel, a.lastModifiedTime().toMillis() + ":" + a.size());
                } catch (IOException gone) {
                    // removed while walking: the next stamp sees it gone
                }
            });
        } catch (IOException | RuntimeException ex) {
            return "unreadable";
        }
        return out.toString();
    }

    // ── the applier ──────────────────────────────────────────────────────────────────────────

    @Override
    public Map<String, String> apply(String spaceId, Path configRoot, List<SpaceLock.OutsideChange> changes) {
        Map<String, String> out = new LinkedHashMap<>();
        Optional<SpaceContext> sc = spaces.space(SpaceId.of(spaceId));
        if (sc.isEmpty()) return out;   // closed or deleted meanwhile: nothing to apply to
        String prior = MDC.get(EventLog.SPACE_MDC_KEY);
        // the lock's thread carries no Space: bind this one, so the save checks read this Space's roots and service
        if (EventLog.DEFAULT_SPACE_ID.equals(spaceId)) MDC.remove(EventLog.SPACE_MDC_KEY);
        else MDC.put(EventLog.SPACE_MDC_KEY, spaceId);
        try {
            for (SpaceLock.OutsideChange c : changes) {
                if (!"job".equals(c.kind()) || !SpacePromotion.liveJob(c.path())) continue;
                String outcome;
                try {
                    outcome = "removed".equals(c.change()) ? remove(sc.get().service(), configRoot, c)
                            : upsert(sc.get().service(), configRoot, c);
                } catch (IOException | RuntimeException refused) {   // ApiException included: a save check said no
                    outcome = "not applied: " + refused.getMessage();
                }
                if (outcome.startsWith("not applied"))
                    log.warn("[JOBS] {} in Space '{}' changed on disk ({}); {} - the running Job is unchanged",
                            c.path(), spaceId, c.change(), outcome);
                else log.info("[JOBS] {} in Space '{}' changed on disk ({}); {}", c.path(), spaceId, c.change(), outcome);
                out.put(c.path(), outcome);
            }
        } finally {
            if (prior == null) MDC.remove(EventLog.SPACE_MDC_KEY);
            else MDC.put(EventLog.SPACE_MDC_KEY, prior);
        }
        return out;
    }

    /** Apply one added, changed or moved Job file: loaded as the boot loader loads it, checked as the API save checks. */
    private String upsert(CollectorService service, Path configRoot, SpaceLock.OutsideChange c) throws IOException {
        Map<String, Object> job = ToonHelper.requireSection(ToonHelper.load(configRoot.resolve(c.path()).toString()), "job");
        Object ref = job.get("template");
        if (ref != null) {   // as ServiceBootstrap.loadJobs: an unknown template refuses the file
            JobTemplate t = AttachApprovals.templates(configRoot).get(String.valueOf(ref).trim());
            if (t == null) throw new IllegalArgumentException("references unknown job template '" + ref + "'");
            job = t.instantiate(job);
        }
        JobService js = service.jobServiceOrCreate();
        String previous = runningFrom(js, configRoot, c);
        // judged as boot loads it first: a Job the boot already runs is not re-judged by the stricter save parse
        JobConfig loaded = JobConfig.fromMap(Map.of("job", job));
        Optional<JobConfig> running = js.jobConfig(loaded.name());
        if (running.isPresent() && running.get().equals(loaded)) {
            ran.put(key(configRoot, c.path()), loaded.name());
            return "unchanged: Job '" + loaded.name() + "' already runs this";
        }
        JobConfig cfg = JobRoutes.parseJob(job);
        JobRoutes.refuseSystemJob(api, cfg.name());
        if (running.isPresent() && !cfg.name().equals(previous))   // as POST /jobs: one file per name
            throw new IllegalArgumentException("a Job named '" + cfg.name() + "' already runs from another file; "
                    + "give this one another name");
        JobRoutes.refuseBeforeRegister(api, cfg, () -> running.map(r -> Map.<String, Object>of("job", r.toMap())).orElse(null));
        js.upsertJob(cfg);
        ran.put(key(configRoot, c.path()), cfg.name());
        if (previous != null && !previous.equals(cfg.name()) && !js.isSystemJob(previous)) {
            js.removeJob(previous);
            PublicationApproval.forget(configRoot, previous);
            return "applied: Job '" + previous + "' is now '" + cfg.name() + "'";
        }
        return "applied: Job '" + cfg.name() + "' is live";
    }

    /**
     * The Job this file last ran, or null — what this applier last applied from it, else (first time it sees the file)
     * the id the lock's previous version declared. Remembered per file, so a refused version never becomes "previous":
     * the lock records every version it saw, applied or not.
     */
    private String runningFrom(JobService js, Path configRoot, SpaceLock.OutsideChange c) {
        String name = ran.computeIfAbsent(key(configRoot, c.path()), k -> c.previousId() == null ? "" : c.previousId());
        return name.isEmpty() || js.jobConfig(name).isEmpty() ? null : name;
    }

    private static String key(Path configRoot, String path) {
        return configRoot.toAbsolutePath().normalize().resolve(path).toString();
    }

    /** Apply one removed Job file, as {@code DELETE /jobs/{name}} does after it deleted the file. */
    private String remove(CollectorService service, Path configRoot, SpaceLock.OutsideChange c) {
        String last = ran.remove(key(configRoot, c.path()));
        String name = last != null && !last.isEmpty() ? last : c.id();
        JobService js = service.jobService().orElse(null);
        if (name == null || js == null || js.jobConfig(name).isEmpty()) return "unchanged: no Job '" + name + "' runs";
        JobRoutes.refuseSystemJob(api, name);
        js.removeJob(name);
        PublicationApproval.forget(configRoot, name);   // a re-created publication needs a new approval
        return "applied: Job '" + name + "' removed";
    }
}
