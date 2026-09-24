package com.gamma.pipeline;

import com.gamma.etl.ExcelExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.gamma.util.Values.strOrEmpty;

/**
 * The <b>Pipeline Document</b> as a workbook (ELT amendment D-8: <i>"XLSX export = fast-follow"</i>).
 *
 * <p>⛔ <b>No new dependency.</b> The workbook is written by DuckDB's {@code excel} extension —
 * {@code COPY … TO … (FORMAT xlsx)} — which is already in the bundle and already staged for air-gapped
 * installs, because it is the same extension the {@code xlsx} PARSER reads with. Apache POI was the
 * obvious answer and was measured against this one: writing works on the bundled DuckDB (verified
 * 2026-09-16 on 1.5.2), so POI would have added the reactor's first spreadsheet dependency tree, and grown
 * every edition bundle, to do something the runtime could already do.
 *
 * <p>⛔ <b>Every value here comes from {@link PipelineDocumentModel}</b> — including the masking. This
 * class decides sheet shape and nothing else. A workbook is emailed far more readily than a Markdown file,
 * so a mask that drifted here would leak further than one that drifted anywhere else.
 *
 * <p>⚠ One sheet per document section, in reading order, because a reviewer signs off section by section.
 * A section with several tables stacks them with a blank row between, since a sheet cannot hold two headers.
 */
public final class PipelineDocumentXlsx {

    private PipelineDocumentXlsx() {}

    /** Excel's own limit: 31 chars, and none of {@code []:*?/\}. A clash gets a numeric suffix. */
    static String sheetName(String raw, Set<String> taken) {
        String cleaned = strOrEmpty(raw).replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (cleaned.isEmpty()) cleaned = "Sheet";
        if (cleaned.length() > 31) cleaned = cleaned.substring(0, 31).trim();
        String candidate = cleaned;
        for (int n = 2; !taken.add(candidate); n++) {
            String suffix = " (" + n + ")";
            String head = cleaned.length() + suffix.length() > 31
                    ? cleaned.substring(0, 31 - suffix.length()).trim() : cleaned;
            candidate = head + suffix;
        }
        return candidate;
    }

    /**
     * Write {@code doc} to {@code target} as a workbook.
     *
     * <p>⚠ Each sheet is materialised as a DuckDB relation of TEXT columns and copied out. Everything is
     * text on purpose: a Pipeline Document is a configuration record, and letting a spreadsheet re-type
     * {@code 0012} as the number 12, or a version as a date, would corrupt the very thing being signed off.
     */
    public static void write(PipelineDocumentModel.Doc doc, Path target) throws Exception {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:");
             Statement st = conn.createStatement()) {
            ExcelExtension.ensureLoaded(conn);

            Set<String> taken = new java.util.LinkedHashSet<>();
            List<String> bodies = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (PipelineDocumentModel.Section section : doc.sections()) {
                List<List<String>> grid = grid(section);
                if (grid.isEmpty()) continue;
                bodies.add(relation(grid));
                names.add(sheetName(section.heading(), taken));
            }
            if (bodies.isEmpty()) {
                bodies.add(relation(List.of(List.of("(no content)"))));
                names.add("Pipeline");
            }

            // 🔴 One WORKBOOK per sheet, then merged (XLSX-EXPORT-LAST-SECTION-ONLY-1): the 1.5.2 writer
            // accepts APPEND true but rewrites the file, so one COPY per sheet into the same file kept only
            // the last section. (HEADER false) - the grid already carries its own header rows.
            Path parts = Files.createTempDirectory(parent, ".xlsx-parts-");
            try {
                List<Path> files = new ArrayList<>();
                for (int i = 0; i < bodies.size(); i++) {
                    Path part = parts.resolve("part" + i + ".xlsx");
                    st.execute("COPY (" + bodies.get(i) + ") TO " + literal(part.toString().replace('\\', '/'))
                            + " (FORMAT xlsx, SHEET " + literal(names.get(i)) + ", HEADER false)");
                    files.add(part);
                }
                XlsxSheetMerger.merge(files, names, target);
            } finally {
                try (var walk = Files.list(parts)) {
                    for (Path p : walk.toList()) Files.deleteIfExists(p);
                }
                Files.deleteIfExists(parts);
            }
        }
    }

    /** A section as a plain grid: its prose, then each table's header row and rows, blank-separated. */
    static List<List<String>> grid(PipelineDocumentModel.Section section) {
        List<List<String>> out = new ArrayList<>();
        if (!strOrEmpty(section.heading()).isEmpty()) out.add(List.of(section.heading()));
        for (String p : section.prose()) out.add(List.of(p));
        for (PipelineDocumentModel.Table t : section.tables()) {
            if (!out.isEmpty()) out.add(List.of(""));
            out.add(t.headers());
            out.addAll(t.rows());
        }
        return out;
    }

    /** {@code SELECT … UNION ALL …} over the grid, padded to a rectangle of TEXT columns. */
    private static String relation(List<List<String>> grid) {
        int width = grid.stream().mapToInt(List::size).max().orElse(1);
        List<String> rows = new ArrayList<>();
        for (List<String> row : grid) {
            List<String> cells = new ArrayList<>();
            for (int i = 0; i < width; i++)
                cells.add(literal(i < row.size() ? row.get(i) : "") + "::VARCHAR AS c" + i);
            rows.add("SELECT " + String.join(", ", cells));
        }
        return String.join(" UNION ALL ", rows);
    }

    private static String literal(String s) {
        return "'" + strOrEmpty(s).replace("'", "''") + "'";
    }

    /** Convenience: build the model for a recipe and write it, the shape a route calls. */
    public static void write(String id, Map<String, Object> recipe,
                             Map<String, Map<String, Object>> components, String fingerprint,
                             Path target) throws Exception {
        write(PipelineDocument.model(id, recipe, components, fingerprint), target);
    }
}
