package com.gamma.service;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SE-0 corpus equivalence gate ({@code docs/superpower/2026-11-11-space-evolution-plan.md} §3): every tracked
 * Space under {@code spaces/} and every Space Template under {@code spaces/_templates/} is loaded through the
 * real boot path ({@link SpaceLoadSnapshot}) — twice to prove the load is deterministic, then once with each
 * registered capability off and once on. A capability that changes what loads fails here, naming the Space and
 * the differing entries.
 *
 * <p><b>Adding a capability:</b> append one {@link Capability#flag} line to {@link #CAPABILITIES} with its
 * {@code -D} kill switch. Nothing else changes.
 *
 * <p>Never loads in place: each Space's {@code config/} and {@code space.toon} are copied under a temp dir.
 * A Space whose boot throws fails here; a Pipeline or Job that does not load is recorded in the snapshot (its
 * load warning) and must merely be the same on every load. The test runs on the Basic classpath, so Alert Rules
 * are compared through their registry components and their "Not arming" warnings, not as armed rules.
 */
class SpaceCorpusEquivalenceTest {

    /** One capability whose kill switch must not change what a Space loads. */
    record Capability(String name, Runnable on, Runnable off, Consumer<Path> configMutation) {
        /** A capability gated by a {@code -D} system property ("true" = on). */
        static Capability flag(String property) {
            return new Capability(property, () -> System.setProperty(property, "true"),
                    () -> System.clearProperty(property), null);
        }
    }

    /** The registered capabilities. Empty today; each SE lane adds its {@code -D} flag here. */
    static final List<Capability> CAPABILITIES = List.of();

    /**
     * The corpus roots as repo paths. ⚠ These literals ARE the wiring: {@code tools/affected-modules.mjs} discovers
     * the test modules that read a repo path from string literals in {@code src/test}, so a change under any of these
     * folders schedules this module's tests. {@link #everyCorpusFolderIsWiredToTheAffectedPlan} keeps it complete.
     */
    static final List<String> WIRED_CORPUS_ROOTS = List.of(
            "../spaces/default/", "../spaces/demo/", "../spaces/ucc/", "../spaces/_templates/");

    /** Generated or runtime folders that are never part of the shipped corpus. */
    private static final Set<String> NOT_CORPUS = Set.of("uat");

    private static Path spacesRoot() {
        Path p = Path.of("..", "spaces").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(p), "found NO spaces/ tree at " + p + " — the gate would prove nothing");
        return p;
    }

    /** A corpus entry: a Space or Space Template folder and the id it loads as. */
    record Entry(String label, Path dir, String id, boolean template) {}

    static List<Entry> corpus() throws IOException {
        Path root = spacesRoot();
        Set<String> tracked = trackedTopFolders(root);
        List<Entry> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                String name = d.getFileName().toString();
                if (Files.isSymbolicLink(d) || NOT_CORPUS.contains(name)) continue;
                if (tracked != null && !tracked.contains(name)) continue;   // a local, untracked Space
                if (SpaceManager.notASpaceReason(d) != null) continue;
                out.add(new Entry(name, d, name, false));
            }
        }
        Path templates = root.resolve("_templates");
        if (Files.isDirectory(templates)) try (Stream<Path> dirs = Files.list(templates)) {
            for (Path d : dirs.filter(Files::isDirectory).sorted().toList()) {
                String name = d.getFileName().toString();
                if (name.startsWith(".") || Files.isSymbolicLink(d)) continue;
                if (!Files.exists(d.resolve("template.toon")) || !Files.isDirectory(d.resolve("config"))) continue;
                out.add(new Entry("_templates/" + name, d, "tpl-" + name, true));
            }
        }
        assertFalse(out.isEmpty(), "the corpus is empty — the enumeration is broken, not the corpus");
        return out;
    }

    /** Top-level folders under spaces/ that git tracks, or null when git is unavailable (then: all on disk). */
    private static Set<String> trackedTopFolders(Path root) {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "--", ".").directory(root.toFile())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) return null;
            Set<String> s = new TreeSet<>();
            for (String line : out.split("\\R")) if (line.contains("/")) s.add(line.substring(0, line.indexOf('/')));
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @TestFactory
    Stream<DynamicTest> everyShippedSpaceLoadsTheSameWhateverTheCapabilityFlags(@TempDir Path tmp) throws IOException {
        return corpus().stream()
                .map(e -> DynamicTest.dynamicTest(e.label(), () -> assertEquivalent(e, CAPABILITIES, tmp)));
    }

    static void assertEquivalent(Entry e, List<Capability> caps, Path tmp) throws IOException {
        String key = e.id() + "-" + System.nanoTime();
        SpaceLoadSnapshot.Snapshot first = SpaceLoadSnapshot.load(e.dir(), e.id(), e.template(), tmp.resolve(key + "-a"), null);
        SpaceLoadSnapshot.Snapshot second = SpaceLoadSnapshot.load(e.dir(), e.id(), e.template(), tmp.resolve(key + "-b"), null);
        String det = first.diff(e.label(), second, "load 1", "load 2");
        assertEquals("", det, "the load is not deterministic:\n" + det);
        // what loaded, one line per Space: the findings baseline (pipeline load failures and load warnings)
        System.out.println("[SE-0] " + e.label() + " " + first.summary());
        for (Capability c : caps) {
            SpaceLoadSnapshot.Snapshot off, on;
            c.off().run();
            try {
                off = SpaceLoadSnapshot.load(e.dir(), e.id(), e.template(), tmp.resolve(key + "-" + c.name() + "-off"), null);
                c.on().run();
                on = SpaceLoadSnapshot.load(e.dir(), e.id(), e.template(), tmp.resolve(key + "-" + c.name() + "-on"),
                        c.configMutation());
            } finally {
                c.off().run();
            }
            String d = off.diff(e.label(), on, c.name() + "=off", c.name() + "=on");
            assertEquals("", d, "capability '" + c.name() + "' changes what loads:\n" + d);
        }
    }

    /** A new shipped Space must be named in {@link #WIRED_CORPUS_ROOTS}, or a change to it would not run this gate. */
    @Test
    void everyCorpusFolderIsWiredToTheAffectedPlan() throws IOException {
        Set<String> wired = new TreeSet<>();
        for (String r : WIRED_CORPUS_ROOTS) wired.add(r.substring("../spaces/".length(), r.length() - 1));
        Set<String> needed = new TreeSet<>();
        for (Entry e : corpus()) needed.add(e.template() ? "_templates" : e.label());
        needed.removeAll(wired);
        assertTrue(needed.isEmpty(), "add these to WIRED_CORPUS_ROOTS so tools/affected-modules.mjs runs this gate "
                + "when they change: " + needed);
    }

    /** The gate bites: a test-only toggle that adds a Connection must fail the comparison, naming Space and entry. */
    @Test
    void theGateFailsWithAReadableDiffWhenACapabilityChangesWhatLoads(@TempDir Path tmp) throws IOException {
        Entry demo = corpus().stream().filter(e -> e.label().equals("demo")).findFirst()
                .orElseThrow(() -> new AssertionError("spaces/demo is missing from the corpus"));
        Capability changesWhatLoads = new Capability("self-test.extra-connection", () -> {}, () -> {}, config -> {
            try {
                Files.writeString(config.resolve("se0_selftest_connection.toon"),
                        "connection:\n  id: se0_selftest\n  connector: local\n");
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        });
        AssertionError bit = assertThrows(AssertionError.class,
                () -> assertEquivalent(demo, List.of(changesWhatLoads), tmp));
        String msg = bit.getMessage();
        assertTrue(msg.contains("Space 'demo'"), "the diff names the Space: " + msg);
        assertTrue(msg.contains("self-test.extra-connection"), "the diff names the capability: " + msg);
        assertTrue(msg.contains("connections: only self-test.extra-connection=on: se0_selftest"),
                "the diff names the differing entry: " + msg);
    }
}
