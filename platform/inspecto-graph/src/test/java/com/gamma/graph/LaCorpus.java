package com.gamma.graph;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The synthetic corpus of a Link Analysis Space Template, generated for a test rather than read from the repository
 * ({@code SPACES-CONSOLIDATION-1} P2.4, D-SC4): the repository keeps a Template's configuration, not its data, so a
 * test that needs the data asks the Template's own deterministic generator for it ({@code node tools/seed-space-data.mjs}).
 *
 * <p>Written once per JVM under {@code <module>/target/la-corpora/<template>/data} (disposable build output, never
 * {@code spaces/}). A missing Node or Python, or a generator that fails, is a named failure — never a skipped test.
 *
 * <p>⚠ A copy of {@code com.gamma.etl.LaCorpus#data} (the etl module's test tree), on purpose: this module is
 * dependency-free (the enforcer plan, {@code tools/check-module-deps.mjs}) and the etl module reaches it through
 * {@code inspecto-analytics}, so it cannot be this module's test dependency. Keep the two in step.
 */
final class LaCorpus {

    private static final Map<String, Path> GENERATED = new HashMap<>();

    private LaCorpus() {}

    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath().normalize();
        while (p != null && !Files.isRegularFile(p.resolve("tools/seed-space-data.mjs"))) p = p.getParent();
        if (p == null) throw new IllegalStateException("tools/seed-space-data.mjs was not found above " + Path.of("").toAbsolutePath());
        return p;
    }

    /** The generated data folder (it holds {@code inbox/} and {@code staged/}) of the named Template, generating it on first use. */
    static synchronized Path data(String template) {
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
}
