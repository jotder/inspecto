package com.gamma.etl.unpack;

import com.gamma.util.Csv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The run-level unpack ledger (unpack-stage plan §2.2, unblocked by the operator's §6 Q1 sign-off):
 * the archive verdict vocabulary, the single-declaration column contract, and the accumulate→flush
 * lifecycle.
 */
class UnpackLedgerTest {

    // ── the vocabulary (§6 Q1a) ────────────────────────────────────────────────

    @Test
    void everyEntryIngestedIsUNPACKED() {
        assertEquals(UnpackStatus.UNPACKED, UnpackStatus.verdict(3, 3, 0, 0, false));
    }

    /**
     * 🔴 The WIDENED definition. The plan's original wording was "≥1 ingested, ≥1 quarantined" — a
     * skipped (encrypted) entry is never quarantined, so an archive with one locked member and four
     * good ones matched NO status at all. Both shapes must be PARTIAL.
     */
    @Test
    void partialCoversQuarantinedAndSkippedAlike() {
        assertEquals(UnpackStatus.UNPACKED_PARTIAL, UnpackStatus.verdict(4, 3, 1, 0, false),
                "one quarantined entry");
        assertEquals(UnpackStatus.UNPACKED_PARTIAL, UnpackStatus.verdict(5, 4, 0, 1, false),
                "one SKIPPED entry — the case the original wording missed");
        assertEquals(UnpackStatus.UNPACKED_PARTIAL, UnpackStatus.verdict(5, 3, 1, 1, false),
                "both at once");
    }

    /**
     * 🔴 EMPTY and UNREADABLE are ONE code path in the expansion (the same throw) but stay DISTINCT
     * statuses by operator decision — separated only by how many entries the walk SAW.
     */
    @Test
    void emptyAndUnreadableAreDistinguishedByEntriesFound() {
        assertEquals(UnpackStatus.EMPTY, UnpackStatus.verdict(0, 0, 0, 0, false),
                "archive opened, zero entries");
        assertEquals(UnpackStatus.UNREADABLE, UnpackStatus.verdict(2, 0, 0, 2, false),
                "entries existed, all locked");
        assertEquals(UnpackStatus.UNREADABLE, UnpackStatus.verdict(0, 0, 0, 0, true),
                "expansion itself threw — corrupt or cap breach, never EMPTY");
    }

    /** An entry that was planned but never accounted for still reads as partial, not clean. */
    @Test
    void ingestedShortOfFoundIsPartialEvenWithNoFailures() {
        assertEquals(UnpackStatus.UNPACKED_PARTIAL, UnpackStatus.verdict(4, 2, 0, 0, false));
    }

    // ── the single-declaration contract (⛔ the batches ledger's five mirrors) ──

    /**
     * ⛔ The header must be DERIVED from the one column list, never restated. This is the guard
     * against repeating the batches ledger's five-mirror drift — where a column added to one mirror
     * and missed in another is silently HIDDEN, because reads are by header name.
     */
    @Test
    void headerIsDerivedFromTheOneColumnDeclaration() {
        assertEquals(String.join(",", UnpackLedger.COLUMNS), UnpackLedger.HEADER);
        assertEquals(12, UnpackLedger.COLUMNS.size());
        assertEquals("run_id", UnpackLedger.COLUMNS.get(0));
        assertEquals(List.copyOf(UnpackLedger.COLUMNS), UnpackLedger.COLUMNS,
                "COLUMNS must be immutable — it is the single source of column order");
    }

    // ── accumulate → flush ─────────────────────────────────────────────────────

    /** A full archive lifecycle lands one row, readable BY HEADER NAME, with the right verdict. */
    @Test
    void archiveRowRoundTripsByHeaderName(@TempDir Path dir) throws Exception {
        String run = "20260826_120000_roundtrip";
        Path poll = Files.createDirectories(dir.resolve("in"));
        File archive = Files.writeString(poll.resolve("bundle.zip"), "x").toFile();
        Path ledger = dir.resolve("p_unpack_" + run + ".csv");

        UnpackLedger.expanded(run, archive, "zip", 3, 1, 900L, 2400L, false, "");
        UnpackLedger.entryOutcome(run, archive, "B1", true);
        UnpackLedger.entryOutcome(run, archive, "B2", false);
        UnpackLedger.flush(run, ledger.toString(), poll);

        List<Map<String, String>> rows = read(ledger);
        assertEquals(1, rows.size(), "one row per archive per run");
        Map<String, String> r = rows.get(0);
        assertEquals(run, r.get("run_id"));
        assertEquals("bundle.zip", r.get("archive_relpath"));
        assertEquals("zip", r.get("format"));
        assertEquals("3", r.get("entries_found"));
        assertEquals("1", r.get("entries_ingested"));
        assertEquals("1", r.get("entries_failed"));
        assertEquals("1", r.get("entries_skipped"));
        assertEquals("900", r.get("bytes_in"));
        assertEquals("2400", r.get("bytes_out"));
        assertEquals("UNPACKED_PARTIAL", r.get("status"));
        assertEquals("B1 B2", r.get("consignment_ids"), "every consignment the entries landed in");
    }

    /**
     * 🔴 Two archives sharing a BASENAME in different inbox subdirectories are TWO archives. Keying
     * the roll-up on the filename (as the audit's display `origin` column does) would silently sum
     * them into one row.
     */
    @Test
    void sameNamedArchivesInDifferentDirsAreSeparateRows(@TempDir Path dir) throws Exception {
        String run = "20260826_120000_collide";
        Path poll = dir.resolve("in");
        File east = Files.writeString(
                Files.createDirectories(poll.resolve("east")).resolve("data.zip"), "x").toFile();
        File west = Files.writeString(
                Files.createDirectories(poll.resolve("west")).resolve("data.zip"), "x").toFile();
        Path ledger = dir.resolve("p_unpack_" + run + ".csv");

        UnpackLedger.expanded(run, east, "zip", 1, 0, 10L, 20L, false, "");
        UnpackLedger.entryOutcome(run, east, "B1", true);
        UnpackLedger.expanded(run, west, "zip", 1, 0, 10L, 20L, false, "");
        UnpackLedger.entryOutcome(run, west, "B2", true);
        UnpackLedger.flush(run, ledger.toString(), poll.toAbsolutePath().normalize());

        List<Map<String, String>> rows = read(ledger);
        assertEquals(2, rows.size(), "two archives, two rows — not one summed row");
        assertEquals(java.util.Set.of("east/data.zip", "west/data.zip"),
                rows.stream().map(r -> r.get("archive_relpath")).collect(java.util.stream.Collectors.toSet()),
                "the relpath disambiguates them, and uses forward slashes on every platform");
    }

    /** Flush is idempotent — a second call writes nothing, so it is safe on two exit paths. */
    @Test
    void flushIsIdempotent(@TempDir Path dir) throws Exception {
        String run = "20260826_120000_idem";
        File archive = Files.writeString(dir.resolve("a.zip"), "x").toFile();
        Path ledger = dir.resolve("u.csv");
        UnpackLedger.expanded(run, archive, "zip", 1, 0, 1L, 1L, false, "");
        UnpackLedger.entryOutcome(run, archive, "B1", true);

        UnpackLedger.flush(run, ledger.toString(), dir);
        UnpackLedger.flush(run, ledger.toString(), dir);

        assertEquals(1, read(ledger).size(), "the second flush must not double-report");
    }

    /** A run with no archives writes no file at all — an empty ledger is noise, not information. */
    @Test
    void runWithNoArchivesWritesNothing(@TempDir Path dir) {
        Path ledger = dir.resolve("none.csv");
        assertTrue(UnpackLedger.isEmpty("20260826_120000_none"));
        UnpackLedger.flush("20260826_120000_none", ledger.toString(), dir);
        assertFalse(Files.exists(ledger));
    }

    /** An error message carrying a comma must not shift every column after it. */
    @Test
    void errorTextIsQuotedSoColumnsStayAligned(@TempDir Path dir) throws Exception {
        String run = "20260826_120000_quote";
        File archive = Files.writeString(dir.resolve("bad.zip"), "x").toFile();
        Path ledger = dir.resolve("u.csv");
        UnpackLedger.expanded(run, archive, "zip", 0, 0, 5L, 0L, true,
                "zip: archive exceeds max_entries (2), giving up");
        UnpackLedger.flush(run, ledger.toString(), dir);

        Map<String, String> r = read(ledger).get(0);
        assertEquals("UNREADABLE", r.get("status"));
        assertTrue(r.get("error").contains("max_entries"), r.get("error"));
        assertEquals("", r.get("consignment_ids"), "the column after the comma-bearing one is intact");
    }

    /** {@code Csv.readInto} fills an out-list; this is the read-by-header-name idiom it provides. */
    private static List<Map<String, String>> read(Path ledger) throws Exception {
        List<Map<String, String>> out = new java.util.ArrayList<>();
        Csv.readInto(ledger, out);
        return out;
    }
}
