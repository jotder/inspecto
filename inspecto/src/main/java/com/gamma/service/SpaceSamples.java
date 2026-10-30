package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import com.gamma.inspector.MultiCollectorProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A Space Template's <b>sample data</b>, loaded and run from one call ({@code LA-ONBOARD-1} OB-12, console UC-1):
 * {@code POST /spaces {template, loadSamples: true}}.
 *
 * <p>A template ships samples in either of two places, both copied verbatim into the new Space by
 * {@link SpaceManager#createFromTemplate}:
 * <ul>
 *   <li>{@code data/samples/<pipeline>/} — pristine files the user would otherwise copy into the Pipeline's inbox by
 *       hand ({@code orders-starter}, {@code la-sample}); {@link #load} copies them into that Pipeline's
 *       {@code dirs.poll};</li>
 *   <li>{@code data/inbox/<dir>/} — files the template lands straight in an inbox ({@code la-showcase}).</li>
 * </ul>
 * Each Pipeline that then has files waiting is <b>run once, explicitly</b> — a trigger-less Pipeline is not
 * guaranteed to poll (DATA-TIERS-1 DT-17 makes Basic manual-only), so loading never waits for a poller. The explicit
 * run takes the same per-Pipeline claim as the poller, so a poll cycle already under way is waited for, never
 * overlapped, and the duplicate markers keep a file from loading twice.
 */
public final class SpaceSamples {

    private static final Logger log = LoggerFactory.getLogger(SpaceSamples.class);

    private SpaceSamples() {}

    /** One Pipeline's outcome: {@code status} is {@code loaded} or {@code failed} (then {@code error} says why). */
    public record PipelineLoad(String pipeline, int sampleFiles, String status, int filesLoaded, int filesQuarantined,
                               String error) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pipeline", pipeline);
            m.put("sampleFiles", sampleFiles);
            m.put("status", status);
            m.put("filesLoaded", filesLoaded);
            m.put("filesQuarantined", filesQuarantined);
            if (error != null) m.put("error", error);
            return m;
        }
    }

    /** True when the template directory carries any sample file ({@code data/samples/**} or {@code data/inbox/**}). */
    public static boolean shipped(Path templateDir) throws IOException {
        return hasFile(templateDir.resolve("data").resolve("samples")) || hasFile(templateDir.resolve("data").resolve("inbox"));
    }

    /**
     * Copy the Space's {@code data/samples/<pipeline>/} files into each named Pipeline's inbox, then run every Pipeline
     * whose inbox holds files, once. A sample folder that names no Pipeline of the Space is reported as failed.
     */
    public static List<PipelineLoad> load(SpaceContext ctx) throws IOException {
        CollectorService service = ctx.service();
        Path base = ctx.root().base();
        Map<String, Integer> copied = new LinkedHashMap<>();
        List<PipelineLoad> out = new ArrayList<>();
        Path samples = ctx.root().dataRoot().resolve("data").resolve("samples");
        if (Files.isDirectory(samples)) {
            try (Stream<Path> dirs = Files.list(samples)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    String name = dir.getFileName().toString();
                    Optional<PipelineConfig> cfg = service.configFor(name);
                    if (cfg.isEmpty()) {
                        log.warn("Space '{}': sample folder data/samples/{} names no Pipeline; skipped", ctx.id().value(), name);
                        out.add(new PipelineLoad(name, countFiles(dir), "failed", 0, 0,
                                "the sample folder data/samples/" + name + " names no Pipeline of this Space"));
                        continue;
                    }
                    copied.put(name, copyFiles(dir, inbox(base, cfg.get())));
                }
            }
        }
        Set<String> toRun = new LinkedHashSet<>(copied.keySet());
        for (PipelineView p : service.pipelines()) {
            Optional<PipelineConfig> cfg = service.configFor(p.name());
            if (cfg.isPresent() && !cfg.get().template() && hasFile(inbox(base, cfg.get()))) toRun.add(p.name());
        }
        for (String name : toRun) {
            PipelineConfig cfg = service.configFor(name).orElseThrow();
            int files = copied.containsKey(name) ? copied.get(name) : countFiles(inbox(base, cfg));
            try {
                MultiCollectorProcessor.RunResult r = service.runPipelineOffThread(name)
                        .orElse(new MultiCollectorProcessor.RunResult(0, 0));
                int loaded = service.statusStore().files(cfg).size();
                int quarantined = service.statusStore().quarantine(cfg).size();
                String why = r.failed() > 0 || r.cancelled() > 0 ? "the run did not finish"
                        : quarantined > 0 ? quarantined + " file(s) were quarantined"
                        : files > 0 && loaded == 0 ? "the run loaded no file" : null;
                out.add(new PipelineLoad(name, files, why == null ? "loaded" : "failed", loaded, quarantined, why));
            } catch (RuntimeException failed) {
                log.warn("Space '{}': sample run of Pipeline '{}' failed: {}", ctx.id().value(), name, failed.toString());
                out.add(new PipelineLoad(name, files, "failed", 0, 0, String.valueOf(failed.getMessage())));
            }
        }
        return out;
    }

    /** The Pipeline's {@code dirs.poll}, resolved against the Space when relative. */
    private static Path inbox(Path base, PipelineConfig cfg) {
        Path poll = Path.of(cfg.dirs().poll());
        return com.gamma.config.safety.PathJail.onDataRoot(poll.isAbsolute() ? poll : base.resolve(poll).normalize());
    }

    private static int copyFiles(Path from, Path to) throws IOException {
        int n = 0;
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                Path target = to.resolve(from.relativize(f).toString());
                Files.createDirectories(target.getParent());
                Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
                n++;
            }
        }
        return n;
    }

    private static boolean hasFile(Path dir) throws IOException {
        return countFiles(dir) > 0;
    }

    private static int countFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            return (int) walk.filter(Files::isRegularFile).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
