package com.gamma.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gamma.acquire.AcquisitionLedgers;
import com.gamma.config.safety.DiscoveredRoots;
import com.gamma.event.EventLog;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * SE-0 harness (space evolution plan §3): loads ONE Space config root through the real boot entry point
 * ({@link SpaceBootstrap#load}, bound to the Space exactly as {@code SpaceManager.bootQuietly} binds it) and
 * returns a canonical, sorted, path-independent snapshot of what loaded.
 *
 * <p>The Space is never loaded in place: its {@code config/} and {@code space.toon} are copied to
 * {@code workDir/<id>/}, so data, audit and DuckDB stores land under {@code workDir} and the tracked folder is
 * never written. A Space Template is copied the way {@code SpaceManager.createFromTemplate} copies it: every
 * {@code ${SPACE}} token in a {@code .toon} is rewritten to the id.
 */
final class SpaceLoadSnapshot {

    private SpaceLoadSnapshot() {}

    /** What loaded, by section; each section is a sorted set of normalised entries. */
    record Snapshot(Map<String, TreeSet<String>> sections) {

        /** Entry counts per section, plus every load failure and warning verbatim (normalised). */
        String summary() {
            StringBuilder b = new StringBuilder();
            sections.forEach((k, v) -> b.append(k).append('=').append(v.size()).append(' '));
            for (String k : List.of("pipeline-load-failures", "load-warnings"))
                for (String e : sections.getOrDefault(k, new TreeSet<>())) b.append("\n    ").append(k).append(": ").append(e);
            return b.toString();
        }

        /** Empty when equal; otherwise one line per differing entry, naming the Space and the section. */
        String diff(String label, Snapshot other, String leftName, String rightName) {
            List<String> out = new ArrayList<>();
            TreeSet<String> keys = new TreeSet<>(sections.keySet());
            keys.addAll(other.sections.keySet());
            for (String k : keys) {
                TreeSet<String> a = sections.getOrDefault(k, new TreeSet<>());
                TreeSet<String> b = other.sections.getOrDefault(k, new TreeSet<>());
                for (String e : a) if (!b.contains(e)) out.add("  " + k + ": only " + leftName + ": " + e);
                for (String e : b) if (!a.contains(e)) out.add("  " + k + ": only " + rightName + ": " + e);
            }
            return out.isEmpty() ? "" : "Space '" + label + "' loaded differently:\n" + String.join("\n", out);
        }
    }

    /**
     * Copy {@code source} (a Space or Space Template folder) to {@code workDir/<id>}, let {@code mutate} touch the
     * copy's {@code config/} (null for none), load it, snapshot it, and close it.
     */
    static Snapshot load(Path source, String id, boolean template, Path workDir, Consumer<Path> mutate)
            throws IOException {
        Path base = workDir.resolve(id);
        copyConfig(source, base, id, template);
        if (mutate != null) mutate.accept(base.resolve("config"));

        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        var rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Thread loader = Thread.currentThread();
        logs.addFilter(new ch.qos.logback.core.filter.Filter<>() {
            @Override public ch.qos.logback.core.spi.FilterReply decide(ILoggingEvent e) {
                return e.getLevel().isGreaterOrEqual(Level.WARN) && e.getThreadName().equals(loader.getName())
                        ? ch.qos.logback.core.spi.FilterReply.NEUTRAL : ch.qos.logback.core.spi.FilterReply.DENY;
            }
        });
        logs.start();
        rootLogger.addAppender(logs);

        SpaceRoot root = SpaceRoot.under(base);
        String prev = MDC.get(EventLog.SPACE_MDC_KEY);
        DiscoveredRoots.register(root.id(), root.base());
        MDC.put(EventLog.SPACE_MDC_KEY, root.id());
        try (SpaceContext ctx = SpaceBootstrap.load(root)) {
            rootLogger.detachAppender(logs);
            return snapshot(ctx, base, logs.list);
        } finally {
            rootLogger.detachAppender(logs);
            AcquisitionLedgers.unregister(root.id());
            com.gamma.acquire.ConnectionRegistry.forget(root.id());
            com.gamma.pipeline.SpaceConfigRoot.forget(root.id());
            DiscoveredRoots.unregister(root.id());
            if (prev == null) MDC.remove(EventLog.SPACE_MDC_KEY); else MDC.put(EventLog.SPACE_MDC_KEY, prev);
        }
    }

    private static Snapshot snapshot(SpaceContext ctx, Path base, List<ILoggingEvent> warnings) {
        CollectorService svc = ctx.service();
        Map<String, TreeSet<String>> s = new TreeMap<>();
        for (PipelineView p : svc.pipelines()) add(s, "pipelines", p.name() + " <- " + p.configPath());
        for (ConfigRegistry.LoadFailure f : svc.pipelineLoadFailures())
            add(s, "pipeline-load-failures", f.file() + ": " + norm(f.message(), base));
        svc.jobService().ifPresent(js -> js.jobs().forEach(j ->
                add(s, "jobs", j.name() + " [" + j.type() + "] hosted=" + j.hosted() + " reason=" + norm(j.reason(), base))));
        svc.enrichmentService().ifPresent(es -> es.configs().forEach(e -> add(s, "enrichments", e.name())));
        svc.connections().forEach((id, c) -> add(s, "connections", id + " [" + c.connector() + "]"));
        for (var c : com.gamma.pipeline.ComponentRegistry.scan(base.resolve("config").resolve("registry")).all())
            add(s, "components", c.type() + "/" + c.name());
        svc.alertService().ifPresent(a -> a.rules().forEach(r -> add(s, "alert-rules", String.valueOf(r.get("name")))));
        svc.configSource().semantics().forEach(m -> add(s, "semantic-models", m.name()));
        svc.rcaTemplates().keySet().forEach(n -> add(s, "rca-templates", n));
        // counted, not de-duplicated: some warnings do not name their Pipeline, so one more or one fewer must still show
        Map<String, Integer> counts = new TreeMap<>();
        for (ILoggingEvent e : warnings)
            counts.merge(e.getLevel() + " " + e.getLoggerName() + ": " + norm(e.getFormattedMessage(), base), 1, Integer::sum);
        counts.forEach((w, n) -> add(s, "load-warnings", w + " (x" + n + ")"));
        return new Snapshot(s);
    }

    private static void add(Map<String, TreeSet<String>> s, String section, String entry) {
        s.computeIfAbsent(section, k -> new TreeSet<>()).add(entry);
    }

    /** No absolute paths, timestamps or temp names: the copy's base becomes {@code <space>}. */
    static String norm(String msg, Path base) {
        if (msg == null) return "";
        String abs = base.toAbsolutePath().normalize().toString();
        String out = msg.replace(abs, "<space>").replace(abs.replace('\\', '/'), "<space>")
                .replace(abs.replace("\\", "\\\\"), "<space>");
        out = out.replaceAll("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?Z?", "<ts>");
        out = out.replaceAll("\\d{10,}", "<n>");
        return out.replace('\\', '/');
    }

    private static void copyConfig(Path src, Path dst, String id, boolean template) throws IOException {
        Files.createDirectories(dst);
        Path manifest = src.resolve("space.toon");
        if (Files.isRegularFile(manifest)) Files.copy(manifest, dst.resolve("space.toon"));
        Path from = src.resolve("config");
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.sorted().toList()) {
                Path target = dst.resolve("config").resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else if (template && p.getFileName().toString().endsWith(".toon"))
                    Files.writeString(target, Files.readString(p).replace("${SPACE}", id));
                else Files.copy(p, target);
            }
        }
    }
}
