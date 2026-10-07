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
        assertTrue(roots.stream().anyMatch(r -> r.toString().replace('\\', '/').contains("/inspecto-config/")),
                "the scan does not see this module's own sources");
    }

    /** Every {@code <module>/src/main/java} under the checkout root, wherever the module sits (repo root or a group directory). */
    private static List<Path> mainRoots() {
        List<Path> roots = new ArrayList<>();
        try {
            Files.walkFileTree(repoRoot(), new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes a) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (name.equals("target") || name.equals("node_modules") || name.startsWith(".") || name.equals("inspecto-ui")
                            || name.equals("spaces") || name.equals("data"))
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    if (name.equals("java") && dir.endsWith(Path.of("src", "main", "java"))) {
                        roots.add(dir);
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new AssertionError("cannot list " + repoRoot(), e);
        }
        return roots;
    }

    /** The checkout root - the OUTERMOST ancestor of the working directory whose pom.xml declares the reactor modules. */
    private static Path repoRoot() {
        Path found = null;
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom) && Files.readString(pom).contains("<modules>")) found = dir;
            } catch (IOException e) {
                throw new AssertionError("cannot read " + pom, e);
            }
        }
        if (found == null) throw new AssertionError("cannot locate the checkout root from " + Path.of("").toAbsolutePath());
        return found;
    }
}
