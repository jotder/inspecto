package com.gamma.etl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code TOON-UNQUOTED-DECIMAL-SKIPS-PIPELINE-1}: an unquoted {@code DECIMAL(18,2)} in a schema's tabular
 * {@code raw.fields} row. The comma is also the row delimiter, so the row has one value more than its header
 * declares — genuinely ambiguous, so the load must refuse it (it always did), but it must say WHERE and WHY.
 *
 * <p>🔴 Before the fix the load failed with JToon's bare {@code "Tabular row value count (4) does not match
 * header field count (3)"} — no file, no line, no key, no hint. {@code ConfigRegistry} logged that as a WARN
 * against the <em>pipeline</em> path and skipped the Pipeline, while the offending line was in the schema file.
 */
class SchemaTabularRowWidthTest {

    @Test
    void anUnquotedDecimalNamesTheSchemaFileLineAndTheFix(@TempDir Path dir) throws Exception {
        Path pipeline = PipelineConfigBatchTest.writePipeline(dir, "");
        Path schema = dir.resolve("mini_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema()
                .replace("AMT,\"1\",DOUBLE", "AMT,\"1\",DECIMAL(18,2)"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.load(pipeline.toString()));
        String m = e.getMessage();
        assertTrue(m.contains("mini_schema.toon"), "names the file holding the bad row: " + m);
        assertTrue(m.contains("line 7"), "names the line: " + m);
        assertTrue(m.contains("fields"), "names the table: " + m);
        assertTrue(m.contains("4 values") && m.contains("3 columns"), "expected vs actual: " + m);
        assertTrue(m.contains("\"DECIMAL(18,2)\""), "shows the quoted form of the offending value: " + m);
    }

    @Test
    void theQuotedDecimalLoads(@TempDir Path dir) throws Exception {
        Path pipeline = PipelineConfigBatchTest.writePipeline(dir, "");
        Files.writeString(dir.resolve("mini_schema.toon"), PipelineConfigBatchTest.miniSchema()
                .replace("AMT,\"1\",DOUBLE", "AMT,\"1\",\"DECIMAL(18,2)\""));
        assertNotNull(PipelineConfig.load(pipeline.toString()), "a quoted DECIMAL(18,2) is one value");
    }
}
