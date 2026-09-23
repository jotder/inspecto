package com.gamma.config.io;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test over the CORE module's shipped {@code examples/} tree: every committed sample
 * config round-trips through {@code decode → encode → decode} with an equal map, and the re-encoded
 * form is comment-free — the G5 guarantee that anything the codec produces decodes back through the
 * one (strict) {@code ConfigCodec.toMap}.
 *
 * <p>Lives in the core module (not fp-config, where the rest of {@code ConfigCodecTest} moved in
 * the S5 split) because the fixture it walks is the core's own {@code examples/} directory —
 * surefire's working directory is the module root, so the walk only resolves here.
 */
class ShippedExamplesRoundTripTest {

    // The shipped, committed sample configs live under the module's examples/ tree (runnable feature
    // examples). The old target — config/ — was a gitignored scratch dir: present on a dev box but
    // absent in CI, so the walk threw NoSuchFileException there (and round-tripped scratch files locally).
    private static final Path CONFIG = Path.of("examples");

    @Test
    void everyShippedConfigRoundTripsToStrictCanonicalForm() throws Exception {
        List<Path> toons;
        try (Stream<Path> w = Files.walk(CONFIG)) {
            toons = w.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".toon"))
                    .sorted()
                    .toList();
        }
        assertFalse(toons.isEmpty(), "expected shipped .toon samples under " + CONFIG.toAbsolutePath());

        for (Path p : toons) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            Map<String, Object> map1 = ConfigCodec.toMap(text);
            String canonical = ConfigCodec.toToon(map1);                        // canonical encode
            assertFalse(canonical.contains("\n#"), "canonical form of " + p + " must carry no comments");

            Map<String, Object> map2 = ConfigCodec.toMap(canonical);            // re-decode
            assertEquals(map1, map2, "round-trip map mismatch for " + p);
        }
    }
}
