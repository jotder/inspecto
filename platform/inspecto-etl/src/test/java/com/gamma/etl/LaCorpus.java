package com.gamma.etl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The synthetic corpus of a Link Analysis Space Template, generated for a test rather than read from the repository
 * ({@code SPACES-CONSOLIDATION-1} P2.4, D-SC4): the repository keeps a Template's configuration, not its data, so a
 * test that needs the data asks the Template's own deterministic generator for it ({@code node tools/seed-space-data.mjs}).
 *
 * <p>The corpus is written once per JVM under {@code <module>/target/la-corpora/<template>/data} (disposable build output,
 * never {@code spaces/}); the generators leave a file alone when its bytes are unchanged, so a second class or run only
 * re-checks. A missing Node or Python, or a generator that fails, is a named failure — never a skipped test.
 *
 * <p>⚠ {@code com.gamma.graph.LaCorpus} in {@code inspecto-graph} is a copy of {@link #data} on purpose: that module is
 * dependency-free (the enforcer plan) and the engine, which reaches it through {@code inspecto-analytics}, cannot be
 * its test dependency. Keep the two in step.
 */
public final class LaCorpus {

    private static final Map<String, Path> GENERATED = new HashMap<>();

    private LaCorpus() {}

    /** The repository root: the nearest folder above the working directory that holds {@code tools/seed-space-data.mjs}. */
    public static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath().normalize();
        while (p != null && !Files.isRegularFile(p.resolve("tools/seed-space-data.mjs"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("tools/seed-space-data.mjs was not found above " + Path.of("").toAbsolutePath());
        return p;
    }

    /** The generated data folder (it holds {@code inbox/} and {@code staged/}) of the Template whose folder is {@code template}. */
    public static Path data(Path template) {
        return data(template.getFileName().toString());
    }

    /** The generated data folder (it holds {@code inbox/} and {@code staged/}) of the named Template, generating it on first use. */
    public static synchronized Path data(String template) {
        Path cached = GENERATED.get(template);
        if (cached != null) return cached;
        Path root = repoRoot();
        Path data = Path.of("target", "la-corpora", template, "data").toAbsolutePath().normalize();
        try {
            Files.createDirectories(data.getParent());
            Process run = new ProcessBuilder("node", root.resolve("tools/seed-space-data.mjs").toString(), template, "--data", data.toString())
                    .directory(root.toFile()).redirectErrorStream(true).start();
            String out = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (run.waitFor() != 0)
                throw new IllegalStateException("generating the '" + template + "' corpus failed (node tools/seed-space-data.mjs): " + out);
        } catch (IOException e) {
            throw new IllegalStateException("generating the '" + template + "' corpus needs Node (and Python) on the PATH: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while generating the '" + template + "' corpus", e);
        }
        GENERATED.put(template, data);
        return data;
    }

    /**
     * Make {@code space} a copy of the Template: its folder without {@code data/}, then the generated corpus as
     * {@code space/data}. What a Space created from the Template receives, minus a corpus the repository no longer commits.
     */
    public static void install(Path template, Path space) throws IOException {
        Path templateData = template.resolve("data");
        try (Stream<Path> w = Files.walk(template)) {
            for (Path p : w.toList()) {
                if (p.startsWith(templateData)) continue;
                Path to = space.resolve(template.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
        Path generated = data(template);
        Files.createDirectories(space.resolve("data"));
        try (Stream<Path> w = Files.walk(generated)) {
            for (Path p : w.toList()) {
                Path to = space.resolve("data").resolve(generated.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(to);
                else Files.copy(p, to);
            }
        }
    }
}
