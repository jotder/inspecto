package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-exact characterization of {@link TransformCompiler}. These assertions pin the
 * SQL string each transform/partition type emits, so the extraction of expression
 * generation out of {@link DataTransformer} stays behaviour-preserving forever — any
 * future change that alters the emitted SQL must update these intentionally.
 *
 * <p>Formats are fixed to a single date pattern ({@code %Y-%m-%d}) and a single
 * timestamp pattern ({@code %Y-%m-%d %H:%M:%S}) so the COALESCE chains are deterministic.
 */
class TransformCompilerTest {

    private static PipelineConfig.CsvSettings cfg(Path dir) throws Exception {
        return TestConfigs.csv(dir, PipelineConfigBatchTest.miniSchema())
                .dateFormats("\"%Y-%m-%d\"")
                .tsFormats("\"%Y-%m-%d %H:%M:%S\"")
                .load()
                .csv();
    }

    private static final Map<String, String> TYPES = Map.of(
            "X", "VARCHAR", "D", "DATE", "T", "TIMESTAMP", "A", "DOUBLE");

    // ── data columns ────────────────────────────────────────────────────────────

    @Test
    void partitionVarchar(@TempDir Path dir) throws Exception {
        assertEquals("\"raw_input\".\"C\"",
                TransformCompiler.partitionColumn(new PartitionDef("c", "C", PartitionDef.Type.VARCHAR),
                        "raw_input", TYPES, cfg(dir)));
    }

    @Test
    void partitionDoubleAndInteger(@TempDir Path dir) throws Exception {
        PipelineConfig.CsvSettings cfg = cfg(dir);
        assertEquals("TRY_CAST(\"raw_input\".\"N\" AS DOUBLE)",
                TransformCompiler.partitionColumn(new PartitionDef("n", "N", PartitionDef.Type.DOUBLE),
                        "raw_input", TYPES, cfg));
        assertEquals("TRY_CAST(\"raw_input\".\"N\" AS INTEGER)",
                TransformCompiler.partitionColumn(new PartitionDef("n", "N", PartitionDef.Type.INTEGER),
                        "raw_input", TYPES, cfg));
    }

    @Test
    void partitionDateComponents(@TempDir Path dir) throws Exception {
        // A non-TIMESTAMP source (DT is untyped → VARCHAR) parses via date_formats.
        PipelineConfig.CsvSettings cfg = cfg(dir);
        String dateExpr = "COALESCE(TRY_STRPTIME(CAST(\"raw_input\".\"DT\" AS VARCHAR), '%Y-%m-%d'))::DATE";
        assertEquals("YEAR(" + dateExpr + ")::VARCHAR",
                TransformCompiler.partitionColumn(new PartitionDef("year", "DT", PartitionDef.Type.DATE_YEAR),
                        "raw_input", TYPES, cfg));
        assertEquals("LPAD(MONTH(" + dateExpr + ")::VARCHAR, 2, '0')",
                TransformCompiler.partitionColumn(new PartitionDef("month", "DT", PartitionDef.Type.DATE_MONTH),
                        "raw_input", TYPES, cfg));
        assertEquals("LPAD(DAY(" + dateExpr + ")::VARCHAR, 2, '0')",
                TransformCompiler.partitionColumn(new PartitionDef("day", "DT", PartitionDef.Type.DATE_DAY),
                        "raw_input", TYPES, cfg));
    }

    @Test
    void partitionTimestampSourceUsesTimestampFormats(@TempDir Path dir) throws Exception {
        // A TIMESTAMP-typed source (T) must parse via timestamp_formats, not date_formats — otherwise
        // a value like "2018-04-09-00.00.00" fails a date-only parse and lands in the 1900 sentinel.
        PipelineConfig.CsvSettings cfg = cfg(dir);
        String tsExpr = "COALESCE(TRY_STRPTIME(CAST(\"raw_input\".\"T\" AS VARCHAR), "
                + "'%Y-%m-%d %H:%M:%S'))::TIMESTAMP";
        assertEquals("YEAR(" + tsExpr + ")::VARCHAR",
                TransformCompiler.partitionColumn(new PartitionDef("year", "T", PartitionDef.Type.DATE_YEAR),
                        "raw_input", TYPES, cfg));
        assertEquals("LPAD(MONTH(" + tsExpr + ")::VARCHAR, 2, '0')",
                TransformCompiler.partitionColumn(new PartitionDef("month", "T", PartitionDef.Type.DATE_MONTH),
                        "raw_input", TYPES, cfg));
    }
}
