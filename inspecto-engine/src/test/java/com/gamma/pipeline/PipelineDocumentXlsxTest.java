package com.gamma.pipeline;

import com.gamma.etl.ExcelExtension;
import com.gamma.util.DuckDbUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
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
 * <p>⚠ <b>Only {@link #writesARealWorkbook} is assumption-gated</b>, and only on the {@code excel}
 * extension — mirroring {@code XlsxParsingTest#open}. The other three touch no DuckDB at all (model,
 * masking, sheet naming) and must stay ungated: they are what still proves something on a box where the
 * extension cannot load. ⛔ Do not widen the gate to the class.
 *
 * <p>🔴 <b>What this gate COSTS, stated so it is not rediscovered as a surprise.</b> D-8's XLSX export was
 * closed on the premise that no spreadsheet library was needed because DuckDB's {@code excel} extension is
 * *“already bundled and already staged for air-gapped installs”*. On a clean CI runner it is **not
 * loadable**, which is how this test went red the first time the suite ran past the guards above it.
 * Gating makes the build honest — a skip is visible in the reactor's skip count — but it **does not make
 * the premise true**, and it means the writer is now proven only where the extension happens to be warm.
 * ⛔ Do not read a green reactor as evidence that XLSX export works in a shipped bundle; that question is
 * open on `D-8` in BACKLOG §3 and is the operator's, not this test's.
 */
class PipelineDocumentXlsxTest {

    /**
     * Skip — never pass — when DuckDB's {@code excel} extension cannot load here.
     *
     * <p>Probes on a throwaway database rather than trusting a flag, because the three layers
     * {@code DuckDbExtension} tries (cached {@code LOAD} → staged file → networked {@code INSTALL})
     * can each succeed or fail independently of anything this test can see.
     */
    private static void requireExcelExtension() throws Exception {
        boolean loaded;
        try (Connection conn = DuckDbUtil.openConnection(DuckDbUtil.tempDbFile("xlsxdoc_"))) {
            loaded = ExcelExtension.tryLoad(conn);
        }
        Assumptions.assumeTrue(loaded, "DuckDB 'excel' extension unavailable on this box — run once "
                + "with network (caches under ~/.duckdb/extensions) or set -D" + ExcelExtension.DIR_PROPERTY);
    }

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
    void writesARealWorkbook(@TempDir Path dir) throws Exception {
        requireExcelExtension();
        Path out = dir.resolve("doc.xlsx");

        PipelineDocumentXlsx.write("cdr_ingest", recipe(), Map.of(), "abc123", out);

        assertTrue(Files.exists(out), "the workbook was written");
        assertTrue(Files.size(out) > 1000, "…and it is a real xlsx, not an empty shell: " + Files.size(out));
        byte[] head = Files.readAllBytes(out);
        assertEquals('P', head[0], "xlsx is a zip container");
        assertEquals('K', head[1]);
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
