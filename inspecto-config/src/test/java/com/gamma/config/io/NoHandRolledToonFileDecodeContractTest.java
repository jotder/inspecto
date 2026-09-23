package com.gamma.config.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code CONFIGCODEC-CALLERS-NO-FILE-NAME-1} — no production code decodes a {@code .toon} FILE by hand.
 *
 * <p>A reader that writes {@code ConfigCodec.toMap(Files.readString(p))} gets a decode refusal that names the
 * line but not the file, so an author is left to guess which of a Space's configs is broken. The one file
 * decode is {@code ToonHelper.load} ({@code inspecto-util}), which prefixes the refusal with the path.
 * Decoding a STRING (a request body, a bundle entry, bytes already in hand) stays {@code ConfigCodec.toMap}.
 *
 * <p>It matches raw TEXT across every module's {@code src/main/java}, so the pattern in a comment trips it
 * too — a loud, one-line fix, which is the safe direction. {@link #theScanSeesTheModules()} keeps a green
 * run meaningful: a scan that resolves no roots would otherwise report "no offenders".
 */
class NoHandRolledToonFileDecodeContractTest {

    /** The hand-rolled file decodes: {@code toMap(Files.readString(…))} and {@code JToon.decode(Files.…)}. */
    private static final List<String> BANNED = List.of("toMap(Files.read", "JToon.decode(Files.read");

    @Test
    void noMainSourceDecodesAToonFileWithoutNamingIt() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path root : mainRoots()) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String src = Files.readString(p);
                    if (BANNED.stream().anyMatch(src::contains)) offenders.add(repoRoot().relativize(p).toString());
                }
            }
        }
        assertTrue(offenders.isEmpty(), "these decode a .toon file by hand, so a refusal names the line but not"
                + " the file — use ToonHelper.load(path) instead: " + offenders);
    }

    @Test
    void theScanSeesTheModules() {
        List<Path> roots = mainRoots();
        assertTrue(roots.size() >= 10, "only " + roots.size() + " module source roots resolved — the scan is"
                + " measuring almost nothing; fix the root lookup, do not trust the green");
        assertTrue(roots.stream().anyMatch(r -> r.startsWith(repoRoot().resolve("inspecto-config"))),
                "the scan does not see this module's own sources");
    }

    /** Every {@code <module>/src/main/java} directly under the checkout root. */
    private static List<Path> mainRoots() {
        try (Stream<Path> modules = Files.list(repoRoot())) {
            return modules.map(m -> m.resolve("src/main/java")).filter(Files::isDirectory).toList();
        } catch (IOException e) {
            throw new AssertionError("cannot list " + repoRoot(), e);
        }
    }

    /** The checkout root — the nearest ancestor of the working directory that holds the reactor pom. */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 4 && dir != null; up++, dir = dir.getParent())
            if (Files.exists(dir.resolve("pom.xml")) && Files.isDirectory(dir.resolve("inspecto-config"))) return dir;
        throw new AssertionError("cannot locate the checkout root from " + Path.of("").toAbsolutePath());
    }
}
