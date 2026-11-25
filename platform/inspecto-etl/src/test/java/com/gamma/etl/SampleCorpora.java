package com.gamma.etl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A Space Template copied for a test WITHOUT its committed {@code data/samples}, the samples built instead from the
 * Template's fixed-seed Java generator ({@code SPACES-CONSOLIDATION-1} P2.3, D-SC3 as corrected: {@code aml},
 * {@code payment-fraud}, {@code mobile-money}, {@code telco-ra} and {@code telco-fraud} are generated, not hand-written).
 * The repository keeps a Template's configuration; a test that needs its samples takes them from the generator, so the
 * committed copies can be untracked later.
 */
public final class SampleCorpora {

    private SampleCorpora() {}

    /**
     * Copy the Template folder {@code template} to {@code to}, leaving out its {@code data/samples}, then write
     * {@code samples} (relative path under {@code data/samples} to content; none for a Template that ships no samples).
     */
    public static void copyTemplate(Path template, Path to, Map<String, String> samples) throws IOException {
        Path committed = template.resolve("data").resolve("samples");
        try (Stream<Path> w = Files.walk(template)) {
            for (Path p : w.toList()) {
                if (p.startsWith(committed)) continue;
                Path target = to.resolve(template.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target);
            }
        }
        writeSamples(to, samples);
    }

    /** Write {@code samples} under {@code root/data/samples}, as UTF-8. */
    public static void writeSamples(Path root, Map<String, String> samples) throws IOException {
        Path dir = root.resolve("data").resolve("samples");
        Files.createDirectories(dir);
        for (Map.Entry<String, String> f : samples.entrySet()) {
            Path file = dir.resolve(f.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, f.getValue(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The drift check ({@code SPACES-CONSOLIDATION-1} D-SC13): the samples a Template still commits under
     * {@code data/samples} must equal what its generator produces now, so a copy a tool or a demo reads cannot go stale.
     * Skipped when the Template commits none, so the golden tests stay independent of the committed copy.
     * {@code -Dsamples.regenerate=true} rewrites the committed folder from the generator first.
     */
    public static void assertCommittedMatches(Path template, Map<String, String> samples) throws IOException {
        Path dir = template.resolve("data").resolve("samples");
        assumeTrue(Files.isDirectory(dir), "no samples are committed under " + dir);
        if (Boolean.getBoolean("samples.regenerate")) {
            try (Stream<Path> w = Files.walk(dir)) {
                for (Path p : w.sorted(Comparator.reverseOrder()).toList()) if (!p.equals(dir)) Files.delete(p);
            }
            writeSamples(template, samples);
        }
        TreeSet<String> committed = new TreeSet<>();
        try (Stream<Path> w = Files.walk(dir)) {
            w.filter(Files::isRegularFile).forEach(p -> committed.add(dir.relativize(p).toString().replace('\\', '/')));
        }
        assertEquals(new TreeSet<>(samples.keySet()), committed, "the committed sample files are the generator's, no more (regenerate with -Dsamples.regenerate=true)");
        for (Map.Entry<String, String> f : samples.entrySet())
            assertEquals(f.getValue(), Files.readString(dir.resolve(f.getKey()), StandardCharsets.UTF_8).replace("\r\n", "\n"),
                    f.getKey() + " drifted from its generator (regenerate with -Dsamples.regenerate=true)");
    }
}
