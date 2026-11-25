package com.gamma.etl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

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
}
