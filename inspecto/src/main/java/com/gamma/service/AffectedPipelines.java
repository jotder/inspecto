package com.gamma.service;

import com.gamma.config.io.ConfigCodec;
import com.gamma.etl.PipelineConfig;
import com.gamma.pipeline.ComponentRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Which Pipelines does a config change reach (duckle C7, {@code DUCKLE-C7-AFFECTED-CONTRACTS-1}, first
 * slice) — a <b>CI-shaped check over a diff</b>, not an in-app git feature. Given the files a change
 * touched (added / modified / deleted, with the pre-change content where git has it), report every
 * Pipeline the change reaches, each with the chain that reached it, plus what could not be decided.
 *
 * <p><b>No second lineage model.</b> Every edge is one the product already reads:
 * <ul>
 *   <li><b>child → parent</b>: a file a Pipeline's parser read ({@link PipelineConfig#referencedFiles()},
 *       loaded through {@link ConfigRegistry}) — a schema, mapping, grammar edit reaches that Pipeline;</li>
 *   <li><b>producer → Dataset</b>: {@link PipelineDependents#scan} (a Dataset's {@code sourceName} /
 *       {@code physicalRef} head names its producing Pipeline);</li>
 *   <li><b>Dataset → consumer</b>: {@code collector.dataset}, the id {@code connector: dataset} resolves.</li>
 * </ul>
 * Propagation is breadth-first, so each Pipeline carries its shortest chain.
 *
 * <p><b>Deleting a producer is a change</b>: a deleted Pipeline is reported, and the Datasets still naming
 * it lead on to their consumers. A deleted file a surviving Pipeline read makes that Pipeline fail to load,
 * which is reported as reached (the load failure names the file) rather than dropped.
 *
 * <p><b>Nothing is guessed.</b> A modified {@code .toon} whose decoded content is unchanged (formatting,
 * key order) reaches nothing. Canvas geometry is not in config at all (the editor keeps node positions in
 * browser storage), so no on-disk change can be layout-only beyond that. A Pipeline that does not load, a
 * {@code collector.dataset} that is not a bare Dataset id (templated or path-shaped), or a deleted Pipeline
 * whose pre-change content is unavailable is listed as <b>UNCERTAIN</b>, never resolved.
 *
 * <p>⚠ Contract verdicts (breaking / possibly breaking / revalidate) are NOT in this slice.
 */
public final class AffectedPipelines {

    private static final String PIPELINE_SUFFIX = "_pipeline.toon";

    private AffectedPipelines() {
    }

    public enum Status { ADDED, MODIFIED, DELETED }

    /**
     * One changed file.
     *
     * @param file   absolute path of the file (it may no longer exist when {@code DELETED})
     * @param status what the change did to it
     * @param before the pre-change content, or {@code null} when unknown / the file is new
     */
    public record Change(Path file, Status status, String before) {
        public Change {
            file = file.toAbsolutePath().normalize();
            Objects.requireNonNull(status, "status");
        }
    }

    /** A reached Pipeline and the chain from the changed file to it ({@code file:… → dataset:… → pipeline:…}). */
    public record Hit(String pipeline, List<String> chain) {
    }

    /** Something the check could not decide — a subject and why. */
    public record Uncertain(String subject, String reason) {
    }

    /** A changed file that reaches nothing, and why. */
    public record Ignored(String file, String reason) {
    }

    public record Report(List<Hit> affected, List<Uncertain> uncertain, List<Ignored> ignored) {
    }

    /** Analyse {@code changes} against the config tree as it stands under {@code writeRoot} (the post-change state). */
    public static Report analyze(Path writeRoot, List<Change> changes) {
        Path root = writeRoot.toAbsolutePath().normalize();
        ConfigRegistry registry = new ConfigRegistry();
        registry.rebuild(pipelineFiles(root));

        Map<Path, List<String>> readers = new LinkedHashMap<>();       // file → Pipelines that read it
        Map<String, List<String>> consumers = new LinkedHashMap<>();   // dataset id → consuming Pipelines
        List<Uncertain> uncertain = new ArrayList<>();
        for (ConfigRegistry.Entry e : registry.all()) {
            readers.computeIfAbsent(e.path().toAbsolutePath().normalize(), k -> new ArrayList<>()).add(e.id());
            for (Path f : e.config().referencedFiles()) {
                if (f == null) continue;
                List<String> ids = readers.computeIfAbsent(f.toAbsolutePath().normalize(), k -> new ArrayList<>());
                if (!ids.contains(e.id())) ids.add(e.id());
            }
            PipelineConfig.Collector c = e.config().collector();
            String ds = c == null ? null : c.dataset();
            if (ds == null || ds.isBlank()) continue;
            ds = ds.trim();
            if (ds.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                consumers.computeIfAbsent(ds, k -> new ArrayList<>()).add(e.id());
            } else {
                uncertain.add(new Uncertain("pipeline:" + e.id(),
                        "collector.dataset '" + ds + "' is not a bare Dataset id (templated or path-shaped); "
                                + "which Dataset it reads is not resolved"));
            }
        }

        Map<Path, ComponentRegistry.Component> datasetByFile = new LinkedHashMap<>();
        for (ComponentRegistry.Component c : ComponentRegistry.scan(root.resolve("registry")).ofType("dataset")) {
            datasetByFile.put(c.path().toAbsolutePath().normalize(), c);
        }
        Path datasetDir = root.resolve("registry").resolve("datasets");

        Map<String, Hit> hits = new LinkedHashMap<>();
        Deque<Hit> queue = new ArrayDeque<>();
        List<Ignored> ignored = new ArrayList<>();

        for (Change ch : changes) {
            String rel = rel(root, ch.file());
            List<String> start = List.of("file:" + rel + (ch.status() == Status.DELETED ? " (deleted)" : ""));
            if (semanticallyUnchanged(ch)) {
                ignored.add(new Ignored(rel, "decoded content unchanged (formatting only)"));
                continue;
            }
            boolean reached = false;

            for (String id : readers.getOrDefault(ch.file(), List.of())) {
                reached |= offer(hits, queue, id, start);
            }
            for (ConfigRegistry.LoadFailure f : registry.failures()) {
                boolean names = f.path().toAbsolutePath().normalize().equals(ch.file())
                        || (f.file() != null && Path.of(f.file()).toAbsolutePath().normalize().equals(ch.file()))
                        // e.g. "Schema file not found: C:/…/x_schema.toon" — no line prefix, so no parsed file
                        || (f.message() != null && f.message().replace('\\', '/')
                                .contains(ch.file().toString().replace('\\', '/')));
                if (names) {
                    reached |= offer(hits, queue, f.name(), start);
                }
            }
            if (ch.status() == Status.DELETED && ch.file().getFileName().toString().endsWith(PIPELINE_SUFFIX)) {
                String id = idFromBefore(ch.before());
                if (id == null) {
                    id = stem(ch.file(), PIPELINE_SUFFIX);
                    uncertain.add(new Uncertain("pipeline:" + id, "deleted without its pre-change content; "
                            + "id taken from the file name, the in-file identity may differ"));
                }
                reached |= offer(hits, queue, id, start);
            }
            if (ch.file().getParent() != null && ch.file().getParent().equals(datasetDir)) {
                ComponentRegistry.Component c = datasetByFile.get(ch.file());
                String id = c != null ? c.name() : idFromBefore(ch.before());
                if (id == null) id = stem(ch.file(), ".toon");
                List<String> chain = append(start, "dataset:" + id);
                for (String consumer : consumers.getOrDefault(id, List.of())) {
                    reached |= offer(hits, queue, consumer, chain);
                }
                if (consumers.getOrDefault(id, List.of()).isEmpty() && !reached) {
                    ignored.add(new Ignored(rel, "Dataset '" + id + "' has no consuming Pipeline"));
                }
                reached = true;
            }
            if (!reached) ignored.add(new Ignored(rel, "no Pipeline reads this file"));
        }

        // producer → Dataset → consumer, breadth-first so each Pipeline keeps its shortest chain
        while (!queue.isEmpty()) {
            Hit h = queue.poll();
            for (PipelineDependents.Dependent d : PipelineDependents.scan(root, h.pipeline()).dependents()) {
                if (!"dataset".equals(d.kind())) continue;
                List<String> chain = append(h.chain(), "dataset:" + d.name());
                for (String consumer : consumers.getOrDefault(d.name(), List.of())) {
                    offer(hits, queue, consumer, chain);
                }
            }
        }

        for (ConfigRegistry.LoadFailure f : registry.failures()) {
            uncertain.add(new Uncertain("pipeline:" + f.name(),
                    "does not load, so what it reads is unknown: " + f.message()));
        }
        return new Report(List.copyOf(hits.values()), List.copyOf(uncertain), List.copyOf(ignored));
    }

    private static boolean offer(Map<String, Hit> hits, Deque<Hit> queue, String id, List<String> via) {
        if (id == null || hits.containsKey(id)) return true;
        Hit h = new Hit(id, append(via, "pipeline:" + id));
        hits.put(id, h);
        queue.add(h);
        return true;
    }

    private static List<String> append(List<String> chain, String step) {
        List<String> out = new ArrayList<>(chain);
        out.add(step);
        return List.copyOf(out);
    }

    /** A modified {@code .toon} whose decoded map is equal before and after reaches nothing. */
    private static boolean semanticallyUnchanged(Change ch) {
        if (ch.status() != Status.MODIFIED || ch.before() == null) return false;
        if (!ch.file().getFileName().toString().endsWith(".toon") || !Files.isRegularFile(ch.file())) return false;
        try {
            return ConfigCodec.toMap(ch.before())
                    .equals(ConfigCodec.toMap(Files.readString(ch.file(), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return false;   // undecodable on either side: it is a change
        }
    }

    /** The in-file {@code name} of pre-change content, lower-cased as the pipeline index keys it. */
    private static String idFromBefore(String before) {
        if (before == null) return null;
        try {
            Object n = ConfigCodec.toMap(before).get("name");
            return n == null || String.valueOf(n).isBlank() ? null : String.valueOf(n).trim().toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Path> pipelineFiles(Path root) {
        Path registryDir = root.resolve("registry");
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.getFileName().toString().endsWith(PIPELINE_SUFFIX))
                    .filter(p -> !p.startsWith(registryDir))
                    .filter(Files::isRegularFile)
                    .sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list pipelines under " + root + ": " + e.getMessage(), e);
        }
    }

    private static String stem(Path p, String suffix) {
        String n = p.getFileName().toString();
        return n.endsWith(suffix) ? n.substring(0, n.length() - suffix.length()) : n;
    }

    private static String rel(Path root, Path file) {
        return (file.startsWith(root) ? root.relativize(file) : file).toString().replace('\\', '/');
    }

    /**
     * CI entry: {@code AffectedPipelines <configRoot> <baseRev> [--fail-on-affected]}. Diffs {@code baseRev}
     * against the WORKING TREE (check out the change, pass its merge-base) under {@code configRoot}, reads
     * each modified/deleted file's pre-change content with {@code git show}, and prints {@link #render}.
     * Exit 0; 1 when {@code --fail-on-affected} and something is affected or uncertain; 2 on a usage or git error.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AffectedPipelines <configRoot> <baseRev> [--fail-on-affected]");
            System.exit(2);
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String base = args[1];
        boolean fail = args.length > 2 && "--fail-on-affected".equals(args[2]);
        Path top = Path.of(git(root, "rev-parse", "--show-toplevel").trim());
        List<Change> changes = new ArrayList<>();
        for (String line : git(root, "diff", "--name-status", "--no-renames", base, "--", ".").split("\n")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 2);
            Status st = switch (parts[0].charAt(0)) {
                case 'A' -> Status.ADDED;
                case 'D' -> Status.DELETED;
                default -> Status.MODIFIED;
            };
            String before = st == Status.ADDED ? null : git(root, "show", base + ":" + parts[1]);
            changes.add(new Change(top.resolve(parts[1]), st, before));
        }
        Report r = analyze(root, changes);
        System.out.print(render(r));
        System.exit(fail && !(r.affected().isEmpty() && r.uncertain().isEmpty()) ? 1 : 0);
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            System.err.println("git " + String.join(" ", args) + " failed");
            System.exit(2);
        }
        return out;
    }

    /** Plain-text rendering, one line per item, stable for a CI log. */
    public static String render(Report r) {
        StringBuilder sb = new StringBuilder();
        for (Hit h : r.affected()) sb.append("AFFECTED  ").append(h.pipeline()).append("  ")
                .append(String.join(" -> ", h.chain())).append('\n');
        for (Uncertain u : r.uncertain()) sb.append("UNCERTAIN ").append(u.subject()).append("  ")
                .append(u.reason()).append('\n');
        for (Ignored i : r.ignored()) sb.append("IGNORED   ").append(i.file()).append("  ")
                .append(i.reason()).append('\n');
        return sb.toString();
    }
}
