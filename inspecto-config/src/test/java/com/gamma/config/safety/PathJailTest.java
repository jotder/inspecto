package com.gamma.config.safety;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial coverage for the shared containment primitive.
 *
 * <p>⚠ Every escape case here was <b>falsification-probed</b>: with {@code require}'s containment
 * check replaced by a plain {@code root.resolve(value)}, each one must go red. A safety test that
 * cannot fail is not a safety test — the pipeline test-run work found a case that passed only by
 * coincidence of iteration order, and this suite is written to be immune to that.
 */
class PathJailTest {

    @Test
    void containedRelativePathIsReturnedAbsolute(@TempDir Path root) {
        Path got = PathJail.require(root, root.resolve("a/b.toon").toString(), "schema_file");
        assertEquals(root.resolve("a/b.toon").toAbsolutePath().normalize(), got);
    }

    @Test
    void dotDotEscapeIsRefused(@TempDir Path root) {
        PathJail.Escape ex = assertThrows(PathJail.Escape.class,
                () -> PathJail.require(root, root.resolve("../secret.toon").toString(), "schema_file"));
        assertEquals("schema_file", ex.field());
        assertTrue(ex.getMessage().contains("outside the root"), ex.getMessage());
    }

    @Test
    void absolutePathOutsideRootIsRefused(@TempDir Path root, @TempDir Path elsewhere) {
        assertThrows(PathJail.Escape.class,
                () -> PathJail.require(root, elsewhere.resolve("secret.toon").toString(), "grammar"));
    }

    @Test
    void uncPathIsRefusedBeforeAnyResolution(@TempDir Path root) {
        assertThrows(PathJail.Escape.class, () -> PathJail.require(root, "\\\\server\\share\\x", "dirs.poll"));
        assertThrows(PathJail.Escape.class, () -> PathJail.require(root, "//server/share/x", "dirs.poll"));
    }

    @Test
    void blankValueIsRefused(@TempDir Path root) {
        assertThrows(PathJail.Escape.class, () -> PathJail.require(root, "   ", "dirs.poll"));
        assertThrows(PathJail.Escape.class, () -> PathJail.require(root, null, "dirs.poll"));
    }

    /**
     * The sibling-prefix trap: a naive {@code startsWith} on the STRING form would accept
     * {@code /tmp/rootX} as living under {@code /tmp/root}. {@link Path#startsWith} is component-wise
     * so this already holds — pinned here so a future string-based rewrite cannot silently regress it.
     */
    @Test
    void siblingDirectoryWithRootAsNamePrefixIsRefused(@TempDir Path parent) throws IOException {
        Path root    = Files.createDirectory(parent.resolve("root"));
        Path sibling = Files.createDirectory(parent.resolve("rootX"));
        assertTrue(sibling.toString().startsWith(root.toString()), "precondition: the string form really does share a prefix");

        assertThrows(PathJail.Escape.class,
                () -> PathJail.require(root, sibling.resolve("secret.toon").toString(), "schema_file"));
        assertFalse(PathJail.contains(root, sibling));
    }

    @Test
    void theRootItselfIsContained(@TempDir Path root) {
        assertDoesNotThrow(() -> PathJail.require(root, root.toString(), "dirs.database"));
        assertTrue(PathJail.contains(root, root));
    }

    /**
     * A path that does not exist yet must still be jailable — the common case for an output dir.
     * This is why the symlink check walks up to the nearest EXISTING ancestor rather than demanding
     * the full path resolve.
     */
    @Test
    void notYetExistingPathUnderRootIsAllowed(@TempDir Path root) {
        assertDoesNotThrow(() -> PathJail.require(root, root.resolve("does/not/exist/yet").toString(), "dirs.temp"));
    }

    /**
     * The check the weakest of the five superseded implementations lacked entirely: a link INSIDE
     * the root pointing OUT of it. Normalisation alone cannot see this — only the real-path re-check can.
     */
    @Test
    void symlinkEscapingTheRootIsRefused(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path secretDir = Files.createDirectory(outside.resolve("stash"));
        Files.writeString(secretDir.resolve("secret.toon"), "stolen");
        Path link = TestLinks.linkDirectory(root.resolve("innocent"), secretDir);

        Path reached = link.resolve("secret.toon");
        assertTrue(Files.exists(reached), "precondition: the link resolves, so a naive check would accept it");
        assertTrue(reached.normalize().startsWith(root), "precondition: it LOOKS contained before the real-path check");

        assertThrows(PathJail.Escape.class, () -> PathJail.require(root, reached.toString(), "schema_file"),
                "a link pointing outside the root must be refused");
        assertFalse(PathJail.contains(root, reached));
    }

    @Test
    void symlinkStayingInsideTheRootIsAllowed(@TempDir Path root) throws IOException {
        Path realDir = Files.createDirectory(root.resolve("real"));
        Files.writeString(realDir.resolve("fine.toon"), "fine");
        Path link = TestLinks.linkDirectory(root.resolve("alias"), realDir);

        assertDoesNotThrow(() -> PathJail.require(root, link.resolve("fine.toon").toString(), "schema_file"));
        assertTrue(PathJail.contains(root, link.resolve("fine.toon")));
    }

    /**
     * ⚠ A root that is ITSELF a link. Resolving only the candidate's real path and comparing it to an
     * unresolved base rejects every legitimate path under such a root — the shape of {@code /tmp} →
     * {@code /private/tmp} and of any linked deploy directory. The comparison must be real-to-real.
     */
    @Test
    void rootThatIsItselfALinkStillContainsItsOwnFiles(@TempDir Path parent) throws IOException {
        Path realRoot = Files.createDirectory(parent.resolve("real-root"));
        Files.writeString(realRoot.resolve("inside.toon"), "fine");
        Path linkedRoot = TestLinks.linkDirectory(parent.resolve("linked-root"), realRoot);

        Path viaLink = linkedRoot.resolve("inside.toon");
        assertTrue(PathJail.contains(linkedRoot, viaLink),
                "a file under a linked root must be contained by that root");
        assertDoesNotThrow(() -> PathJail.require(linkedRoot, viaLink.toString(), "schema_file"));
    }

    /**
     * The shared-truth property: {@code require} succeeding and {@code contains} returning true must
     * never disagree. Drift between the enforcing and advisory surfaces is exactly the failure this
     * class was introduced to end, so it is pinned rather than assumed.
     */
    @Test
    void requireAndContainsAgree(@TempDir Path root, @TempDir Path outside) {
        String[] values = {
                root.resolve("ok.toon").toString(),
                root.resolve("nested/deep/ok.toon").toString(),
                root.toString(),
                root.resolve("../escape.toon").toString(),
                outside.resolve("secret.toon").toString(),
        };
        for (String v : values) {
            boolean required;
            try {
                PathJail.require(root, v, "field");
                required = true;
            } catch (PathJail.Escape e) {
                required = false;
            }
            assertEquals(required, PathJail.contains(root, Path.of(v)),
                    "require and contains disagreed about " + v);
        }
    }

    /** The root may itself be relative; it must be absolutised before comparison, not compared raw. */
    @Test
    void relativeRootIsAbsolutisedBeforeComparison() {
        Path relativeRoot = Path.of("");
        assertDoesNotThrow(() -> PathJail.require(relativeRoot, "some/nested/file.toon", "schema_file"));
        assertThrows(PathJail.Escape.class,
                () -> PathJail.require(relativeRoot, Path.of("").toAbsolutePath().getParent().resolve("x").toString(), "schema_file"));
    }

    /**
     * ⚠ The shape every config this product ships actually uses: a ref authored relative to the
     * SERVER ROOT, not to the config's own directory. Jailing these against their {@code configDir}
     * would break every space — see the plan's §2. They must pass against the spaces root.
     */
    @Test
    void serverRootRelativeRefAsShippedConfigsAuthorThemIsAllowed(@TempDir Path cwdRoot) throws IOException {
        Path spaces = Files.createDirectories(cwdRoot.resolve("spaces/default/config/events"));
        Path schema = Files.writeString(spaces.resolve("events_schema.toon"), "x");
        assertDoesNotThrow(() -> PathJail.require(cwdRoot, schema.toString(), "schema_file"));
    }
}
