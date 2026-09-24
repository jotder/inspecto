package com.gamma.pipeline;

import com.gamma.config.io.ConfigCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ViewStore}: persist / read / delete / list {@code sink.view} definitions (T32 Phase C).
 *
 * <p>The class Javadoc has said "unit-tests directly" since it was written, and nothing did — found while
 * grounding the PATH-2 sweep. The two contracts worth pinning are not the file I/O: they are
 * {@link ViewDefinition}'s <b>dual-emit / dual-read</b> vocabulary bridge (a definition written before the
 * Tier-3 rename says {@code flow:}, one written now says both) and {@code list()} being <b>skip-on-bad</b>
 * rather than fail-on-bad, since a job writes into this directory while an API reads it.
 */
class ViewStoreTest {

    private static ViewDefinition def(String store) {
        return new ViewDefinition(store, "orders_pipeline", List.of("raw_orders", "customers"),
                "SELECT * FROM raw_orders", "2026-08-14T10:00:00Z");
    }

    @Test
    void writeReadDeleteRoundTrip(@TempDir Path root) throws Exception {
        ViewStore store = new ViewStore(root);
        assertFalse(store.exists("premium"));
        assertTrue(store.get("premium").isEmpty(), "absent is empty, not an exception");

        store.write(def("premium"));
        assertTrue(Files.isRegularFile(root.resolve("premium_view.toon")), "named <store>_view.toon");
        assertTrue(store.exists("premium"));

        ViewDefinition back = store.get("premium").orElseThrow();
        assertEquals("premium", back.store());
        assertEquals("orders_pipeline", back.pipeline());
        assertEquals(List.of("raw_orders", "customers"), back.sourceStores());
        assertEquals("SELECT * FROM raw_orders", back.derivedSql());
        assertEquals("2026-08-14T10:00:00Z", back.definedAt());

        assertTrue(store.delete("premium"));
        assertFalse(store.exists("premium"));
        assertFalse(store.delete("premium"), "a second delete removed nothing and says so");
    }

    /**
     * The Tier-3 cutover (4.0.0): the writer emits only the canonical {@code pipeline} key, and the reader no
     * longer falls back to the pre-rename {@code flow} key — a {@code flow}-only file resolves no producing
     * pipeline. The dual-read was dropped because no definition on disk carried {@code flow} alone.
     */
    @Test
    void aDefinitionWritesAndReadsOnlyTheCanonicalPipelineKey(@TempDir Path root) throws Exception {
        ViewStore store = new ViewStore(root);
        store.write(def("canonical"));

        Map<String, Object> written = ConfigCodec.toMap(
                Files.readString(root.resolve("canonical_view.toon"), StandardCharsets.UTF_8));
        assertEquals("orders_pipeline", written.get("pipeline"), () -> "canonical key missing: " + written);
        assertFalse(written.containsKey("flow"), () -> "the pre-rename key is no longer emitted: " + written);

        // A file as written before the rename: 'flow' only, no 'pipeline' at all.
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("store", "legacy");
        legacy.put("flow", "old_flow_id");
        legacy.put("source_store", List.of("raw"));
        legacy.put("defined_at", "2026-01-01T00:00:00Z");
        Files.writeString(root.resolve("legacy_view.toon"), ConfigCodec.toToon(legacy), StandardCharsets.UTF_8);

        assertNull(store.get("legacy").orElseThrow().pipeline(),
                "the pre-rename key is no longer read — there is no silent fallback");
    }

    @Test
    void anAbsentDerivedSqlIsOmittedRatherThanWrittenAsNull(@TempDir Path root) throws Exception {
        ViewStore store = new ViewStore(root);
        store.write(new ViewDefinition("chained", "p", List.of("a"), null, "2026-08-14T10:00:00Z"));

        assertFalse(Files.readString(root.resolve("chained_view.toon")).contains("derived_sql"));
        assertNull(store.get("chained").orElseThrow().derivedSql(),
                "a multi-statement transform chain has no single derivable SELECT; it re-runs the pipeline");
    }

    @Test
    void listIsFilenameOrderedAndIgnoresEverythingThatIsNotAViewDefinition(@TempDir Path root) throws Exception {
        ViewStore store = new ViewStore(root);
        store.write(def("beta"));
        store.write(def("alpha"));
        Files.writeString(root.resolve("notes.txt"), "not a view");
        Files.createDirectory(root.resolve("sub_view.toon"));   // right suffix, but a directory

        assertEquals(List.of("alpha", "beta"), store.list().stream().map(ViewDefinition::store).toList());
    }

    /**
     * ⚠ Skip-on-bad, deliberately: a pipeline job writes into this directory while an API lists it, so one
     * unparseable or half-written file must not make every other view undiscoverable.
     *
     * <p>⚠ <b>And parseability is not the test.</b> That is what the sibling case below caught: a file that
     * <em>parses</em> fine but is not a definition used to become a <b>phantom view with a null store</b> —
     * listed by {@code list()} and served by {@code GET /views} to the UI, because {@code fromMap} is a
     * lossless mapper that fills absent keys with {@code null}.
     */
    @Test
    void anUnparseableFileIsSkippedRatherThanFailingTheWholeScan(@TempDir Path root) throws Exception {
        ViewStore store = new ViewStore(root);
        store.write(def("good"));
        Files.writeString(root.resolve("broken_view.toon"), "\u0000: [unparseable", StandardCharsets.UTF_8);

        assertEquals(List.of("good"), store.list().stream().map(ViewDefinition::store).toList());
        assertTrue(store.get("broken").isEmpty(), "the same tolerance on the single-get path");
    }

    /** The parseable-but-not-a-definition half — the defect this test class found. */
    @Test
    void aFileThatParsesButIsNotAViewDefinitionIsSkippedToo(@TempDir Path root) throws Exception {
        ViewStore store = new ViewStore(root);
        store.write(def("good"));
        Files.writeString(root.resolve("stray_view.toon"), "something_else: 1\n", StandardCharsets.UTF_8);

        List<ViewDefinition> listed = store.list();
        assertEquals(List.of("good"), listed.stream().map(ViewDefinition::store).toList());
        assertTrue(listed.stream().noneMatch(d -> d.store() == null), "no null-identity phantom view");
        assertTrue(store.get("stray").isEmpty(), "the same verdict on the single-get path");
    }

    @Test
    void listOnAMissingDirectoryIsEmptyNotAnError(@TempDir Path root) {
        assertEquals(List.of(), new ViewStore(root.resolve("never-created")).list());
    }

    @Test
    void aWriteReplacesTheDefinitionAndLeavesNoTempFileBehind(@TempDir Path root) throws Exception {
        ViewStore store = new ViewStore(root);
        store.write(def("orders"));
        store.write(new ViewDefinition("orders", "new_pipeline", List.of("z"), null, "2026-08-14T11:00:00Z"));

        assertEquals("new_pipeline", store.get("orders").orElseThrow().pipeline());
        try (var files = Files.list(root)) {
            assertEquals(List.of("orders_view.toon"),
                    files.map(p -> p.getFileName().toString()).sorted().toList(),
                    "the atomic temp+move must not leave a .view-* scratch file");
        }
    }

    /**
     * The name is a single path segment: the character class admits no {@code /} and no {@code \}, so a
     * traversal or absolute name cannot be spelled — the same structural argument as
     * {@code com.gamma.config.safety.DataRef}, reached by a different rule.
     */
    @Test
    void anUnsafeStoreNameIsRefusedBeforeItReachesTheFilesystem(@TempDir Path root) {
        ViewStore store = new ViewStore(root);
        for (String bad : new String[]{"../escape", "a/b", "a\\b", "/abs", "C:/abs", "", "-lead", ".lead", ".."})
            assertThrows(IllegalArgumentException.class, () -> store.exists(bad), () -> "accepted: " + bad);
        assertThrows(IllegalArgumentException.class, () -> store.exists(null));
    }

    @Test
    void theRootIsNormalizedOnceSoCallersSeeOneFrame(@TempDir Path root) {
        assertEquals(root.resolve("views"), new ViewStore(root.resolve("x/../views")).root());
        assertThrows(NullPointerException.class, () -> new ViewStore(null));
    }
}
