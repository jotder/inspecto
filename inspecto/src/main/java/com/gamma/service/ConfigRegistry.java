package com.gamma.service;

import com.gamma.etl.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An in-memory, thread-safe index of loaded pipeline configs keyed by their <b>in-file identity</b>
 * ({@code config.identity().pipelineName()}) — the fix for the O(n) re-parse scans that
 * {@code CollectorService.pathFor}/{@code configFor}/{@code activeRegistry} performed on every call.
 *
 * <p>{@link #rebuild} parses each path <em>once</em> and snapshots the result, so subsequent lookups
 * are O(1) map reads with no disk I/O. It is also <b>mtime-cached</b>: a rebuild re-parses a config only
 * when its pipeline file or one of its referenced schema/grammar/segment files has changed on disk,
 * reusing the prior parse otherwise. This makes it safe to call every poll cycle — the cycle re-reads
 * nothing in steady state, yet still picks up edits on the next tick. It also resolves the "discovery-suffix vs in-file
 * identity" mismatch the old code never reconciled: discovery finds files by the {@code _pipeline.toon}
 * suffix, but lookups address pipelines by the {@code name} declared <em>inside</em> the file — so
 * keying the index by that in-file identity makes a name lookup correct regardless of filename, and
 * two files claiming the same identity are flagged rather than silently shadowing each other.
 *
 * <p>This index backs both the <b>read</b> surface (the Control API, the catalog's {@code ConfigSource},
 * status sync) and, since v4.7.0, the ingest run path: the poll cycle pulls the cached configs and
 * re-stamps each with a fresh run timestamp per cycle via {@link PipelineConfig#forNewRun()} (a cheap
 * copy, no re-parse), so every cycle still gets its own status/batch/lineage CSVs while schemas are read
 * only when they change. The audit reads resolve by the stable status directory + pipeline name +
 * persistent commit log, never by that per-cycle timestamp.
 */
public final class ConfigRegistry {

    private static final Logger log = LoggerFactory.getLogger(ConfigRegistry.class);
    private static final String PIPELINE_SUFFIX = "_pipeline.toon";

    /** One indexed pipeline: its stable id, source path, and the loaded config. */
    public record Entry(String id, Path path, PipelineConfig config) {}

    /**
     * A registered pipeline file that did not load (PIPELINE-LOAD-FAILURE-INVISIBLE-1): kept so the read
     * surface can list it as broken, but deliberately <b>outside</b> the index — every lookup, {@link #all},
     * {@link #configs} and {@link #configForPath} still see only loaded configs, so nothing schedules, runs
     * or counts it.
     *
     * @param path    the registered pipeline file
     * @param name    the file-name stem ({@code orders_pipeline.toon} → {@code orders}); the in-file identity
     *                is unknown because the file never parsed
     * @param file    the file the loader refused — a referenced schema/segment/grammar when the loader's
     *                message names one, else {@code path}
     * @param line    the 1-based line the message names, or {@code null}
     * @param message the loader's own message, verbatim
     */
    public record LoadFailure(Path path, String name, String file, Integer line, String message) {}

    /**
     * {@code <file>.toon: line N: } — the prefix {@code PipelineConfigParser.readToon} + {@code ConfigCodec} put
     * on a decode refusal (the line part is optional). The file part admits a drive letter but no other colon,
     * so a message that wraps the prefix ({@code "…: C:\x\y.toon: line 7: …"}) still yields just the path.
     */
    private static final Pattern FILE_AND_LINE = Pattern.compile("((?:[A-Za-z]:)?[^:\\n]*?\\.toon): (?:line (\\d+): )?");

    /**
     * A cached parse keyed by pipeline file path: the indexed {@link Entry} plus the modification-time
     * fingerprint of every file that contributed to it (the pipeline {@code .toon} and each referenced
     * schema/grammar/segment file). A subsequent {@link #rebuild} reuses this entry verbatim while the
     * fingerprint is unchanged — so a pipeline (and its schemas) is parsed once and re-read only when
     * something on disk actually changes.
     */
    private record Cached(Entry entry, Map<Path, Long> fingerprint) {}

    /** Snapshot map (id → entry); replaced wholesale on {@link #rebuild} for lock-free reads. */
    private final AtomicReference<Map<String, Entry>> index = new AtomicReference<>(Map.of());
    /** Parse cache keyed by pipeline file path (rebuild-only; not read concurrently with mutation). */
    private final AtomicReference<Map<Path, Cached>> cache = new AtomicReference<>(Map.of());
    /** Paths that failed to load at the last {@link #rebuild}, in registration order; replaced wholesale. */
    private final AtomicReference<List<LoadFailure>> failures = new AtomicReference<>(List.of());
    private final Runnable onRebuild;

    public ConfigRegistry() {
        this(null);
    }

    /**
     * @param onRebuild a callback fired after every successful {@link #rebuild} (e.g. to invalidate a
     *                  derived catalog when configs change); {@code null} for none
     */
    public ConfigRegistry(Runnable onRebuild) {
        this.onRebuild = onRebuild;
    }

    /**
     * Re-index every path, <b>parsing only what changed on disk</b>. For each path whose pipeline file
     * and every referenced schema/grammar/segment file are unchanged since the last rebuild (by
     * modification time), the previously-parsed config is reused with no disk read; otherwise the file
     * is re-parsed and its fingerprint refreshed. Unloadable configs are warned, kept out of the index and
     * recorded in {@link #failures} (PIPELINE-LOAD-FAILURE-INVISIBLE-1); a duplicate in-file identity warns
     * and the later path wins. Fires the rebuild callback on completion.
     *
     * <p>This is what lets the poll cycle call {@code rebuild} every tick cheaply: a steady-state cycle
     * re-reads nothing and emits no "Loaded N schema(s)" churn, yet an edit to a pipeline or any of its
     * schema files is still picked up on the next cycle.
     */
    public void rebuild(List<Path> paths) {
        Map<Path, Cached> prevCache = cache.get();
        Map<String, Entry> next = new LinkedHashMap<>();
        Map<Path, Cached> nextCache = new LinkedHashMap<>();
        List<LoadFailure> nextFailures = new ArrayList<>();
        for (Path p : paths) {
            try {
                Cached cached = prevCache.get(p);
                Cached fresh = (cached != null && fingerprintMatches(cached.fingerprint()))
                        ? cached                                   // unchanged on disk → reuse the parse
                        : parse(p);                                // new or changed → re-read + log
                nextCache.put(p, fresh);
                Entry e = fresh.entry();
                Entry prev = next.put(e.id(), e);
                if (prev != null) {
                    log.warn("Duplicate pipeline id '{}' from {} and {}; keeping the latter",
                            e.id(), prev.path(), p);
                }
            } catch (Exception e) {
                log.warn("Could not load config {}: {}", p, e.getMessage());
                nextFailures.add(failure(p, e));
            }
        }
        index.set(Map.copyOf(next));
        cache.set(Map.copyOf(nextCache));
        failures.set(List.copyOf(nextFailures));
        if (onRebuild != null) {
            onRebuild.run();
        }
    }

    /** Describe a load refusal: the file + line the loader's message names, else the pipeline file itself. */
    private static LoadFailure failure(Path p, Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String file = p.toString();
        Integer line = null;
        Matcher m = FILE_AND_LINE.matcher(message);
        if (m.find()) {
            file = m.group(1).strip();
            if (m.group(2) != null) line = Integer.valueOf(m.group(2));
        }
        String stem = p.getFileName().toString();
        if (stem.endsWith(PIPELINE_SUFFIX)) stem = stem.substring(0, stem.length() - PIPELINE_SUFFIX.length());
        else if (stem.endsWith(".toon")) stem = stem.substring(0, stem.length() - ".toon".length());
        return new LoadFailure(p, stem, file, line, message);
    }

    /** Parse a pipeline file fresh and snapshot the mtime fingerprint of it + its referenced files. */
    private Cached parse(Path p) throws IOException {
        PipelineConfig cfg = PipelineConfig.load(p.toString());
        String id = cfg.identity().pipelineName();
        noteSuffixDivergence(p, id);
        Map<Path, Long> fp = new LinkedHashMap<>();
        fp.put(p, mtime(p));
        for (Path ref : cfg.referencedFiles()) fp.put(ref, mtime(ref));
        log.debug("Loaded config {} (id '{}', {} referenced file(s))", p, id, cfg.referencedFiles().size());
        return new Cached(new Entry(id, p, cfg), fp);
    }

    /** Whether every fingerprinted file still has the modification time recorded at the last parse. */
    private static boolean fingerprintMatches(Map<Path, Long> fingerprint) {
        for (Map.Entry<Path, Long> e : fingerprint.entrySet()) {
            if (mtime(e.getKey()) != e.getValue()) return false;
        }
        return true;
    }

    /** Last-modified epoch-millis for a file, or {@code -1} when it is missing/unreadable (forces a
     *  re-parse, which then surfaces the real error through the normal load path). */
    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * The cached config for a specific pipeline <em>file path</em>, if loaded. Unlike {@link #get(String)}
     * (keyed by in-file identity, which dedupes collisions), this is keyed by the source path, so two
     * distinct files are both reachable even if they declare the same {@code name}. Used by the run path,
     * which executes per registered path. Empty when the path is unregistered or failed to load.
     */
    public Optional<PipelineConfig> configForPath(Path path) {
        Cached c = cache.get().get(path);
        return c == null ? Optional.empty() : Optional.of(c.entry().config());
    }

    /** The config for a pipeline id, if indexed. */
    public Optional<PipelineConfig> get(String id) {
        Entry e = index.get().get(id);
        return e == null ? Optional.empty() : Optional.of(e.config());
    }

    /** The source path for a pipeline id, if indexed. */
    public Optional<Path> getPath(String id) {
        Entry e = index.get().get(id);
        return e == null ? Optional.empty() : Optional.of(e.path());
    }

    /** The pipeline id registered for {@code path}, if any (reverse lookup; O(n) but n is small). */
    public Optional<String> idForPath(Path path) {
        return index.get().values().stream()
                .filter(e -> e.path().equals(path))
                .map(Entry::id)
                .findFirst();
    }

    /** All loaded configs in registration order. */
    public List<PipelineConfig> configs() {
        return index.get().values().stream().map(Entry::config).toList();
    }

    /** All indexed entries in registration order. */
    public List<Entry> all() {
        return List.copyOf(index.get().values());
    }

    /** The registered pipeline files that did not load at the last {@link #rebuild}, in registration order. */
    public List<LoadFailure> failures() {
        return failures.get();
    }

    public int size() {
        return index.get().size();
    }

    /**
     * Log (at INFO, not WARN) when a file's {@code _pipeline.toon} prefix differs from the in-file
     * identity. A differing prefix is a common, legitimate pattern (the file name need not equal the
     * pipeline name), so this is visibility — not an alarm — confirming which identity won.
     */
    private static void noteSuffixDivergence(Path p, String id) {
        String file = p.getFileName().toString();
        if (!file.endsWith(PIPELINE_SUFFIX)) {
            return;
        }
        String prefix = file.substring(0, file.length() - PIPELINE_SUFFIX.length());
        if (!prefix.equalsIgnoreCase(id)) {
            log.info("Config {} registered under in-file id '{}' (filename prefix is '{}')", p, id, prefix);
        }
    }
}
