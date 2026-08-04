package com.gamma.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvTest {

    @Test
    void readIntoMapsHeaderToValuesInOrder(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "a,b,c\n1,2,3\n4,5,6\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertEquals(2, out.size());
        assertEquals(List.of("a", "b", "c"), List.copyOf(out.get(0).keySet()));   // ordered
        assertEquals("6", out.get(1).get("c"));
    }

    @Test
    void shortRowsPadWithEmptyStrings(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "a,b,c\n1,2\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertEquals("", out.get(0).get("c"));
    }

    @Test
    void backslashesAreLiteralNotEscapes(@TempDir Path dir) throws Exception {
        // The reason this class exists: opencsv's default parser strips '\' from Windows paths.
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "path\n\"C:\\db\\out.csv\"\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertEquals("C:\\db\\out.csv", out.get(0).get("path"));
    }

    @Test
    void emptyOrHeaderlessFileYieldsNoRows(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);
        assertTrue(out.isEmpty());
    }

    // ── accept-both-on-read for the batch_id → consignment_id rename (plan §11.3, decision 2) ──

    /** A ledger written before the rename must read back under the canonical name. */
    @Test
    void canonicalisesTheLegacyBatchIdHeader(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("legacy_lineage.csv");
        Files.writeString(f, "batch_id,src_id,row_count\nB1,0,7\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);

        assertEquals("B1", out.get(0).get("consignment_id"), "the legacy header must read under the new name");
        assertFalse(out.get(0).containsKey("batch_id"),
                "and NOT under both — two spellings in one row would re-introduce the ambiguity and would "
                        + "surface to OperationalTables' drift warning as an un-queryable column");
        assertEquals(List.of("consignment_id", "src_id", "row_count"), List.copyOf(out.get(0).keySet()),
                "column order is untouched — CommitLog and friends still parse positionally");
    }

    /**
     * The case that forces canonicalisation into the reader rather than into each consumer:
     * {@code FileStatusStore.readRuns} globs many run-timestamped ledgers into ONE row list, and
     * {@code CsvLedger} only writes a header when a file is created — so a pre-rename file and a post-rename
     * file legitimately coexist. Both must land under one key, or half the rows silently vanish from any
     * lookup by id.
     */
    @Test
    void readsPreAndPostRenameLedgersIntoOneConsistentKey(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("p_lineage_001.csv");
        Path recent = dir.resolve("p_lineage_002.csv");
        Files.writeString(old, "batch_id,row_count\nB1,7\n", StandardCharsets.UTF_8);
        Files.writeString(recent, "consignment_id,row_count\nC2,9\n", StandardCharsets.UTF_8);

        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(old, out);
        Csv.readInto(recent, out);

        assertEquals(2, out.size());
        assertEquals(List.of("B1", "C2"), out.stream().map(r -> r.get("consignment_id")).toList(),
                "both generations of ledger must be reachable by the same key");
    }

    /** The alias is column-name-scoped: it must not touch values, nor a column merely containing the text. */
    @Test
    void doesNotRewriteValuesOrUnrelatedColumns(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("t.csv");
        Files.writeString(f, "note,__batch_id\nsee batch_id column,X1\n", StandardCharsets.UTF_8);
        List<Map<String, String>> out = new ArrayList<>();
        Csv.readInto(f, out);

        assertEquals("see batch_id column", out.get(0).get("note"), "values are never rewritten");
        assertEquals("X1", out.get(0).get("__batch_id"),
                "__batch_id is the data-plane system column and is explicitly out of scope");
    }
}
