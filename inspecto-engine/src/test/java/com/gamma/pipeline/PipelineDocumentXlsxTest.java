package com.gamma.pipeline;

import com.gamma.etl.ExcelExtension;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Pipeline Document as a workbook (D-8's fast-follow).
 *
 * <p>⛔ The load-bearing test here is the masking one: a workbook travels further than a Markdown file, and
 * the whole reason this renderer reads a shared model rather than projecting the recipe itself is that a
 * duplicated masking rule would eventually drift and put a credential in a spreadsheet.
 *
 * <p>⚠ <b>Only {@link #writesARealWorkbookFromTheStagedExtension} is assumption-gated</b>, and only on a
 * stageable {@code excel.duckdb_extension} being on this box. The others (model, masking, sheet naming, and
 * the missing-binary failure) must stay ungated. ⛔ Do not widen the gate to the class.
 *
 * <p>🔴 <b>What the gate still costs.</b> On a clean CI runner there is no binary to stage (CI populates no
 * cache), so the real write SKIPS there. Where it runs, it runs in STAGED mode — the file loaded by path, as
 * a bundle's launcher does — not "whatever loads". The shipped-bundle proof itself (no network, no
 * {@code ~/.duckdb}) is recorded in {@code docs/okf/capabilities/pipeline-authoring/pipeline-authoring.md} §3.0.
 */
class PipelineDocumentXlsxTest {

    /**
     * The directory holding the {@code excel.duckdb_extension} a bundle would ship for THIS DuckDB and platform,
     * or skip — never pass — when there is none.
     *
     * <p>D-8 (2026-09-24): the test no longer asks "can {@code excel} load somehow" (a warm {@code ~/.duckdb}
     * made that green on every dev box and proved nothing about a bundle). It finds the binary the way
     * {@code package.ps1} stages it — an explicit {@code -Dduckdb.extension.dir}, else a built bundle's
     * {@code inspecto-deploy/duckdb-extensions/<platform>/}, else DuckDB's own
     * {@code <cache>/v<version>/<platform>/} layout package.ps1 copies from — and then runs the export in
     * STAGED mode, where {@code DuckDbExtension} loads that file by path and nothing else.
     */
    private static Path stagedExcelDir() throws Exception {
        String version;
        String platform;
        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("xlsxdoc_"));
             Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT library_version FROM pragma_version()");
            rs.next();
            version = rs.getString(1);
            rs = st.executeQuery("PRAGMA platform");
            rs.next();
            platform = rs.getString(1);
        }
        List<Path> candidates = new ArrayList<>();
        String flag = System.getProperty(ExcelExtension.DIR_PROPERTY);
        if (flag != null && !flag.isBlank()) candidates.add(Path.of(flag));
        Path repo = Path.of("").toAbsolutePath().getParent();
        candidates.add(repo.resolve("inspecto-deploy/duckdb-extensions").resolve(platform));
        for (String cache : new String[]{System.getenv("DUCKDB_EXTENSION_CACHE"),
                repo.resolve(".duckdb-extension-cache").toString(),
                Path.of(System.getProperty("user.home"), ".duckdb", "extensions").toString()})
            if (cache != null) candidates.add(Path.of(cache, version, platform));
        for (Path c : candidates)
            if (Files.isRegularFile(c.resolve("excel.duckdb_extension"))) return c;
        Assumptions.abort("no excel.duckdb_extension for DuckDB " + version + "/" + platform + " in " + candidates
                + " — build a bundle (package.ps1) or fetch it (node tools/fetch-duckdb-extensions.mjs)");
        return null;
    }

    /** Run {@code body} in STAGED mode against {@code dir}, restoring the flag afterwards. */
    private static void staged(Path dir, ThrowingRunnable body) throws Exception {
        String previous = System.getProperty(ExcelExtension.DIR_PROPERTY);
        System.setProperty(ExcelExtension.DIR_PROPERTY, dir.toString());
        try {
            body.run();
        } finally {
            if (previous == null) System.clearProperty(ExcelExtension.DIR_PROPERTY);
            else System.setProperty(ExcelExtension.DIR_PROPERTY, previous);
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }

    private static Map<String, Object> recipe() {
        return Map.of(
                "name", "cdr_ingest",
                "active", true,
                "steps", List.of(
                        Map.of("collect", Map.of("connection", "connections/src", "password", "hunter2")),
                        Map.of("sink", Map.of("database", "warehouse", "format", "PARQUET"))),
                "guarantees", Map.of("backup", true));
    }

    @Test
    void writesARealWorkbookFromTheStagedExtension(@TempDir Path dir) throws Exception {
        Path extDir = stagedExcelDir();
        Path out = dir.resolve("doc.xlsx");

        staged(extDir, () -> PipelineDocumentXlsx.write("cdr_ingest", recipe(), Map.of(), "abc123", out));

        assertTrue(Files.exists(out), "the workbook was written");
        assertTrue(Files.size(out) > 1000, "…and it is a real xlsx, not an empty shell: " + Files.size(out));
        byte[] head = Files.readAllBytes(out);
        assertEquals('P', head[0], "xlsx is a zip container");
        assertEquals('K', head[1]);

        // XLSX-EXPORT-LAST-SECTION-ONLY-1: open the zip — every section is its own sheet, in reading order,
        // and each sheet holds its own section (its first cell is the heading), not the last one's.
        PipelineDocumentModel.Doc doc = PipelineDocument.model("cdr_ingest", recipe(), Map.of(), "abc123");
        Set<String> taken = new java.util.LinkedHashSet<>();
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        for (PipelineDocumentModel.Section s : doc.sections()) {
            List<List<String>> grid = PipelineDocumentXlsx.grid(s);
            if (!grid.isEmpty()) expected.put(PipelineDocumentXlsx.sheetName(s.heading(), taken), grid.get(0).get(0));
        }
        assertTrue(expected.size() >= 4, "the fixture must exercise several sections: " + expected.keySet());

        Map<String, List<String>> sheets = XlsxSheetMergerTest.sheets(out);
        assertEquals(List.copyOf(expected.keySet()), List.copyOf(sheets.keySet()),
                "one sheet per section, in order");
        expected.forEach((sheet, firstCell) -> assertEquals(firstCell, sheets.get(sheet).get(0),
                "sheet '" + sheet + "' must hold its own section: " + sheets.get(sheet)));
        assertEquals(List.of(), Files.list(dir).filter(p -> !p.equals(out)).toList(), "temp parts cleaned up");
    }

    /**
     * ⛔ UNGATED on purpose: a bundle whose {@code duckdb-extensions/<platform>/} lacks the binary must fail
     * the export with the exact missing path — not reach for the network. Real DuckDB; it cannot egress,
     * because staged mode issues no statement before the file check.
     */
    @Test
    void aBundleMissingTheBinaryFailsLoudlyNamingTheFile(@TempDir Path dir) throws Exception {
        Path empty = Files.createDirectory(dir.resolve("duckdb-extensions"));
        Path out = dir.resolve("doc.xlsx");

        Exception thrown = assertThrows(Exception.class, () ->
                staged(empty, () -> PipelineDocumentXlsx.write("cdr_ingest", recipe(), Map.of(), "abc123", out)));

        assertTrue(thrown.getMessage().contains(empty.resolve("excel.duckdb_extension").toAbsolutePath().toString()),
                "the failure must name the missing file: " + thrown.getMessage());
        assertFalse(Files.exists(out), "no partial workbook");
    }

    /** ⛔ A secret-shaped key must never reach the workbook, exactly as it never reaches the Markdown. */
    @Test
    void aSecretIsMaskedInTheWorkbookToo(@TempDir Path dir) throws Exception {
        PipelineDocumentModel.Doc doc = PipelineDocument.model("cdr_ingest", recipe(), Map.of(), "abc123");

        String everyCell = doc.sections().stream()
                .flatMap(s -> s.tables().stream())
                .flatMap(t -> t.rows().stream())
                .flatMap(List::stream)
                .reduce("", (a, b) -> a + " | " + b);

        assertFalse(everyCell.contains("hunter2"), "the password reached the model: " + everyCell);
        assertTrue(everyCell.contains(PipelineDocumentModel.MASK), "…and it was masked, not dropped");
        assertFalse(PipelineDocument.render("cdr_ingest", recipe(), Map.of(), "abc123").contains("hunter2"),
                "and the Markdown agrees — one rule, both renderings");
    }

    @Test
    void theModelCarriesTheSameStepsTheMarkdownDoes() {
        PipelineDocumentModel.Doc doc = PipelineDocument.model("cdr_ingest", recipe(), Map.of(), "abc123");

        List<String> headings = doc.sections().stream().map(PipelineDocumentModel.Section::heading).toList();
        assertTrue(headings.contains("Steps"), headings.toString());
        assertTrue(headings.contains("1. Collect"), headings.toString());
        assertTrue(headings.contains("2. Sink"), headings.toString());
        assertTrue(headings.contains("Guarantees"), headings.toString());
    }

    /** Excel's own constraints: 31 chars, no []:*?/\ — and a clash must not silently overwrite a sheet. */
    @Test
    void sheetNamesAreLegalAndUnique() {
        Set<String> taken = new java.util.LinkedHashSet<>();

        assertEquals("Pipeline cdr", PipelineDocumentXlsx.sheetName("Pipeline/cdr", taken));
        String truncated = PipelineDocumentXlsx.sheetName(
                "A very long heading that goes on and on past the limit", taken);
        assertEquals(31, truncated.length(), "Excel's own cap: " + truncated);
        assertTrue(truncated.startsWith("A very long heading that goes"), truncated);
        assertEquals("Sheet", PipelineDocumentXlsx.sheetName("", taken));

        Set<String> fresh = new java.util.LinkedHashSet<>();
        assertEquals("Steps", PipelineDocumentXlsx.sheetName("Steps", fresh));
        assertEquals("Steps (2)", PipelineDocumentXlsx.sheetName("Steps", fresh),
                "a clashing section must get its own sheet, not overwrite the first");
    }
}
