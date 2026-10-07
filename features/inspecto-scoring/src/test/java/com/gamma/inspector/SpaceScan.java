package com.gamma.inspector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Never store values (2026-10-03): scan EVERY file under a Space byte-wise for planted values. The only files allowed
 * to hold one are byte-identical copies of the planted SOURCE file (its inbox / backup / quarantine copy — what replay
 * re-reads); any other file holding a value is a copy the platform made.
 */
public final class SpaceScan {

    private SpaceScan() {}

    public static void assertNoValueOutsideSources(Path space, byte[] source, String... needles) throws Exception {
        List<Path> files;
        try (Stream<Path> w = Files.walk(space)) { files = w.filter(Files::isRegularFile).toList(); }
        List<String> offending = new ArrayList<>();
        int sourceCopies = 0;
        for (Path f : files) {
            byte[] b = Files.readAllBytes(f);
            if (Arrays.equals(b, source)) { sourceCopies++; continue; }
            for (String n : needles)
                if (indexOf(b, n.getBytes(StandardCharsets.UTF_8)) >= 0) { offending.add(f + " holds '" + n + "'"); break; }
        }
        assertTrue(offending.isEmpty(), "a value is stored outside the source file: " + offending);
        assertTrue(sourceCopies > 0, "the probe would otherwise succeed: the source itself is still on disk");
    }

    private static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= hay.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
}
