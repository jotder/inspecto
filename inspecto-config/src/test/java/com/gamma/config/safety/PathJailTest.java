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

    /** A {@code *.secrets} directory under an allowed root is still no job path — any spelling, or via its real path. */
    @Test
    void aJobPathIntoASecretsDirectoryIsRefused(@TempDir Path root) throws Exception {
        Path secrets = Files.createDirectories(root.resolve("config.secrets"));
        java.util.List<Path> roots = java.util.List.of(root);
        for (String v : new String[]{secrets.toString(), secrets.resolve(".pending-changes.key").toString(), secrets.resolve(".risk-score-mask.key").toString(),
                root.resolve("CONFIG.SECRETS").resolve("x").toString(), root + "/config.secrets./x", "config.secrets/x",
                root.resolve("data").resolve("..").resolve("config.secrets").toString()}) {
            PathJail.Escape ex = assertThrows(PathJail.Escape.class,
                    () -> PathJail.requireJobPathUnderAny(roots, root, v, "target_dir"), v);
            assertTrue(ex.getMessage().contains("secrets directory"), ex.getMessage());
        }
        Path link = root.resolve("innocent");
        try {
            Files.createSymbolicLink(link, secrets);
        } catch (Exception | Error e) {
            link = null;   // no symlink privilege on this host: the spelling cases above still run
        }
        if (link != null) {
            Path l = link;
            assertThrows(PathJail.Escape.class, () -> PathJail.requireJobPathUnderAny(roots, root, l.resolve("x").toString(), "dir"));
        }
        assertDoesNotThrow(() -> PathJail.requireJobPathUnderAny(roots, root, root.resolve("data").toString(), "dir"),
                "an ordinary path under the root still resolves");
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

    /**
     * 🔴 An object-store URI must be refused on EVERY platform, and this test exists because
     * {@code Paths.get} does not do that on its own.
     *
     * <p>⚠ Measured 2026-09-14 on {@code "s3://bucket/data"}: Windows throws
     * {@code InvalidPathException}, so the jail refused it by accident; **Linux — the shipped
     * {@code linux_amd64} target — does not throw at all** and produces {@code /s3:/bucket/data},
     * a real local directory named {@code s3:} under the working directory, which {@code contains}
     * then judges confidently and wrongly. ⛔ So this assertion is NOT redundant with the
     * "unparseable value" case: on the platform that matters the value parses fine.
     *
     * <p>⚠ This is a REFUSAL, not a statement that object stores will never be supported. When
     * scale-out §5.4 bullets 1 and 6 land, dispatch on {@code isUri} — a bucket URI cannot be
     * contained by {@link Path} comparison, so it needs its own containment rule, not this one.
     */
    @Test
    void objectStoreUriIsRefusedOnEveryPlatform(@TempDir Path root) {
        for (String uri : new String[] {"s3://bucket/data", "gs://bucket/data",
                                        "abfss://c@acct.dfs.core.windows.net/data", "file:///etc"}) {
            PathJail.Escape ex = assertThrows(PathJail.Escape.class,
                    () -> PathJail.require(root, uri, "dirs.database"), uri);
            assertEquals("dirs.database", ex.field());
            assertTrue(ex.getMessage().contains("is a URI"), ex.getMessage());
        }
    }

    /**
     * ⚠ The refusal must not swallow a Windows drive letter or an ordinary POSIX path that merely
     * contains a colon. A one-character scheme is a drive, and a colon with no {@code //} after it is
     * just a filename byte — both stay legal, so the guard cannot be "fixed" into rejecting them.
     */
    @Test
    void driveLettersAndColonsInNamesAreNotMistakenForUris() {
        assertFalse(PathJail.isUri("C:/data"), "a Windows drive letter is not a URI scheme");
        assertFalse(PathJail.isUri("C:\\data"), "a Windows drive letter is not a URI scheme");
        assertFalse(PathJail.isUri("out/my:file"), "a colon inside a name is not a URI scheme");
        // ⚠ Keep this one: it is the case that fails if the `//` requirement is ever dropped from the
        // pattern. The two above cannot catch that, because their colon is not at the start.
        assertFalse(PathJail.isUri("my:file"), "a leading name with a colon is not a URI scheme");
        assertFalse(PathJail.isUri("relative/dir"), "an ordinary relative path is not a URI");
        assertTrue(PathJail.isUri("s3://bucket"), "s3:// is a URI");
    }

    // ── SEC-INGEST-EXPR-EXTERNAL-ACCESS-1 round 3: canonical, not lexical ──────────────────

    /** A Space dir with its config/ tree and a real Pipeline file in it. */
    private static Path space(Path root) throws IOException {
        Path s = root.resolve("space");
        Files.createDirectories(s.resolve("config/orders"));
        Files.writeString(s.resolve("config/orders/p.toon"), "name: p\n");
        return s;
    }

    /** The run-time layer on its own — the gate is bypassed, so the filter is the only thing refusing. */
    @Test
    void theAllowlistFilterRefusesTheConfigTreeDirectly(@TempDir Path root) throws IOException {
        Path s = space(root);
        assertTrue(PathJail.readAllowlistRefusal(s.resolve("config")) != null, "config/ itself");
        assertTrue(PathJail.readAllowlistRefusal(s.resolve("config/orders")) != null, "under config/");
        assertTrue(PathJail.readAllowlistRefusal(s) != null, "the Space root (it holds config/)");
        assertTrue(PathJail.readAllowlistRefusal(s.resolve("data/orders")) == null, "a data dir passes");
    }

    /** Windows strips a trailing dot or space, so `config.` IS `config`; refused on every platform (fail closed). */
    @Test
    void aTrailingDotOrSpaceDoesNotWalkAroundTheConfigCheck(@TempDir Path root) throws IOException {
        Path s = space(root);
        for (String v : new String[]{"config.", "config./orders", "config../orders"})
            assertTrue(PathJail.readAllowlistRefusal(Path.of(s + "/" + v)) != null, v);
        Path spaced = Path.of(s + "/data/x.secrets./y");
        assertTrue(PathJail.readAllowlistRefusal(spaced) != null, "a trailing-dot .secrets segment");
    }

    @Test
    void caseVariantsOfConfigAreRefusedOnACaseInsensitiveFilesystem(@TempDir Path root) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows"), "case-insensitive filesystem only");
        Path s = space(root);
        for (String v : new String[]{"CONFIG", "Config/orders", "cOnFiG./orders"})
            assertTrue(PathJail.readAllowlistRefusal(s.resolve(v)) != null, v);
        assertTrue(PathJail.readAllowlistRefusal(Path.of(s.toString().toUpperCase(java.util.Locale.ROOT))) != null,
                "the Space root, spelled in upper case");
    }

    @Test
    void refuseDataHomeComparesCanonicalPaths(@TempDir Path root) throws IOException {
        Path s = space(root);
        assertThrows(PathJail.Escape.class, () -> PathJail.refuseDataHome(Path.of(s + "/config./orders"), s,
                java.util.List.of(), "config./orders", "dirs.poll"));
        assertThrows(PathJail.Escape.class, () -> PathJail.refuseDataHome(Path.of(s + "/."), s,
                java.util.List.of(), ".", "dirs.poll"));
    }

    // ── round 4: the remaining spellings, at the run-time filter ────────────────────────

    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

    /** Refused, or not even a constructible path (the JDK refuses it before any filesystem call). */
    private static void refusedOrUnconstructible(String spelled) {
        Path p;
        try {
            p = Path.of(spelled);
        } catch (java.nio.file.InvalidPathException unconstructible) {
            return;
        }
        assertTrue(PathJail.readAllowlistRefusal(p) != null, spelled + " (" + PathJail.canonical(p) + ")");
    }

    @Test
    void everyRemainingSpellingOfConfigIsRefusedByTheFilter(@TempDir Path root) throws IOException {
        Path s = space(root);
        String abs = s.toString();
        for (String v : new String[]{"config..", "config. .", "config/new/child", "config/orders/not-yet/there"})
            refusedOrUnconstructible(abs + "/" + v);
        refusedOrUnconstructible(abs.replace('\\', '/') + "\\config/orders");   // mixed separators
        refusedOrUnconstructible(abs + "/config\\orders");
        if (WINDOWS) {
            refusedOrUnconstructible(abs + "\\config::$DATA");
            refusedOrUnconstructible(abs + "\\config:x");
            refusedOrUnconstructible("\\\\?\\" + abs + "\\config");
            refusedOrUnconstructible("\\\\?\\" + abs + "\\config\\orders");
        }
    }

    /** A Cyrillic 'с' is a DIFFERENT name: its own directory, never the real config/ tree. */
    @Test
    void aUnicodeLookalikeIsItsOwnDirectoryAndCannotReachConfig(@TempDir Path root) throws IOException {
        Path s = space(root);
        Path look = s.resolve("\u0441onfig/orders");
        Files.createDirectories(look);
        assertEquals(null, PathJail.readAllowlistRefusal(look), "a lookalike is not config/");
        assertFalse(PathJail.canonical(look).startsWith(PathJail.canonical(s.resolve("config"))),
                "the lookalike resolves to its own directory");
        assertEquals(null, PathJail.spaceDirOf(look), "and is not a Space's config dir");
    }

    /** The behaviour change of round 3: spaceDirOf reads a segment as the filesystem does. */
    @Test
    void spaceDirOfReadsConfigAsTheFilesystemDoes(@TempDir Path root) throws IOException {
        Path s = space(root);
        Path cs = PathJail.canonical(s);
        assertEquals(cs, PathJail.canonical(PathJail.spaceDirOf(s.resolve("config/orders"))));
        assertEquals(cs, PathJail.canonical(PathJail.spaceDirOf(Path.of(s + "/config./orders"))), "config. IS config");
        assertEquals(cs, PathJail.canonical(PathJail.spaceDirOf(Path.of(s + "/config../orders"))));
        if (WINDOWS)
            assertEquals(cs, PathJail.canonical(PathJail.spaceDirOf(s.resolve("Config/orders"))), "case-insensitive");
        else
            assertEquals(null, PathJail.spaceDirOf(s.resolve("Config/orders")), "case-sensitive filesystem");
        assertEquals(null, PathJail.spaceDirOf(s.resolve("data/orders")));
    }


    /** processing.refusal round 6: the data root itself is never allowlistable, even before any restricted store exists. */
    @Test
    void readAllowlistRefusalRefusesTheDataRootAndRestrictedStores(@TempDir Path tmp) throws Exception {
        Path data = Files.createDirectories(tmp.resolve("space/data"));
        Path pipe = Files.createDirectories(data.resolve("p/database"));
        assertEquals(null, PathJail.readAllowlistRefusal(pipe), "the premise: an ordinary data dir is allowed");
        PathJail.registerDataRoot(data);
        assertFalse(Files.exists(data.resolve(".restricted")), "no restricted store yet");
        assertEquals("is the Space data root", PathJail.readAllowlistRefusal(data));
        Path d = Files.createDirectories(tmp.resolve("other"));
        Files.createDirectories(d.resolve(".restricted/x"));
        assertEquals("contains a restricted store", PathJail.readAllowlistRefusal(d));
        assertEquals("is inside a restricted store", PathJail.readAllowlistRefusal(d.resolve(".restricted/x")));
        // A nested data/ fallback: <d2>/data/.restricted is refused from <d2>, one level up.
        Path d2 = Files.createDirectories(tmp.resolve("nested/p"));
        Files.createDirectories(d2.resolve("data/.restricted/q"));
        assertEquals("contains a restricted store", PathJail.readAllowlistRefusal(d2));
        // And the write gate refuses a data home that IS a Space's data root.
        Path space = tmp.resolve("space");
        assertThrows(PathJail.Escape.class, () -> PathJail.refuseDataHome(data, space, java.util.List.of(space), "data", "dirs.database"));
    }
}
