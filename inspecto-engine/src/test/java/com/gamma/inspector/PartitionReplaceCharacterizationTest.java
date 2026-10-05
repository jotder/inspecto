package com.gamma.inspector;

import com.gamma.etl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LA-DAILY-INGEST-1 T4 / M03: CHARACTERIZATION of what the real ingest path does to a Hive partition when a
 * day file is re-run, corrected, late, or shares an event date with another file. These pin OBSERVED
 * behaviour (the output file is named {@code <inbox-file-stem>_out.csv}, so the file NAME decides replace vs
 * accumulate), not a desired contract; the defects they expose are listed in the roadmap T4 findings.
 */
class PartitionReplaceCharacterizationTest {

    private static final String SCHEMA = """
            partitionKey: EVENT_DATE
            raw:
              name: ev
              format: CSV
              fields[3]{name,selector,type}:
                ACCOUNT_NUMBER,"account",VARCHAR
                EVENT_DATE,"event_date",DATE
                AMOUNT,"amount",DOUBLE
            mapping:
              canonicalName: ev
              rawName: ev
              rules[3]{targetColumn,sourceExpression,transformType}:
                ACCOUNT_NUMBER,ACCOUNT_NUMBER,DIRECT
                EVENT_DATE,EVENT_DATE,DIRECT
                AMOUNT,AMOUNT,DIRECT
            """;

    private static String row(String acct, String date) {
        return "{\"account\":\"" + acct + "\",\"event_date\":\"" + date + "\",\"amount\":1.5}\n";
    }

    @Test
    void sameDayFileRerunReplacesTheSingleOutputFileWithIdenticalRowCount(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        ingest(cfg, "xdr_20200403.jsonl", row("AA1", "2020-04-03") + row("AA2", "2020-04-03"), 1);
        ingest(cfg, "xdr_20200403.jsonl", row("AA1", "2020-04-03") + row("AA2", "2020-04-03"), 2);

        assertEquals(1, outFiles(cfg, "day=03").size(), "re-run lands on the SAME file name: replaced, no stale file");
        assertEquals(2, countAcct(cfg, "day=03", "AA"), "row count identical after re-run (AC-02)");
        assertNoStagingLeftovers(cfg);
    }

    @Test
    void correctedDayFileWithSameNameAndSameDatesReplacesTheOldRows(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        ingest(cfg, "xdr_20200403.jsonl", row("OLD1", "2020-04-03") + row("OLD2", "2020-04-03"), 1);
        ingest(cfg, "xdr_20200403.jsonl", row("NEW1", "2020-04-03"), 2);

        assertEquals(1, outFiles(cfg, "day=03").size());
        assertEquals(0, countAcct(cfg, "day=03", "OLD"), "old rows are gone");
        assertEquals(1, countAcct(cfg, "day=03", "NEW"));
    }

    @Test
    void correctedSameNameFileThatNoLongerHasADateLeavesTheStaleOldPartitionFile(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        // v1 spans two event dates (a midnight-straddling day file); the corrected v2 holds only 04-03.
        ingest(cfg, "xdr_20200403.jsonl", row("AA1", "2020-04-03") + row("BB1", "2020-04-04"), 1);
        ingest(cfg, "xdr_20200403.jsonl", row("AA1", "2020-04-03"), 2);

        // DEFECT PIN: replace is per-written-partition-file, not per-input-file. The 04-04 file of v1 survives.
        assertEquals(1, outFiles(cfg, "day=04").size(), "stale partition file from the previous version remains");
        assertEquals(1, countAcct(cfg, "day=04", "BB"), "row only in the superseded version is still served");
    }

    @Test
    void correctedFileRedeliveredUnderADifferentNameDuplicatesTheDay(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        ingest(cfg, "xdr_20200403.jsonl", row("AA1", "2020-04-03") + row("AA2", "2020-04-03"), 1);
        ingest(cfg, "xdr_20200403_v2.jsonl", row("AA1", "2020-04-03") + row("AA2", "2020-04-03"), 2);

        // DEFECT PIN: nothing links the two names; the partition now holds two files and every row twice.
        assertEquals(2, outFiles(cfg, "day=03").size());
        assertEquals(4, countAcct(cfg, "day=03", "AA"), "rows duplicated");
    }

    @Test
    void lateFileForAnEarlierDateAddsOnlyItsOwnPartitionAndLeavesIngestedDaysUntouched(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        ingest(cfg, "xdr_20200405.jsonl", row("D5", "2020-04-05"), 1);
        Path day5 = outFiles(cfg, "day=05").get(0);
        String before = Files.readString(day5);
        long modified = Files.getLastModifiedTime(day5).toMillis();

        ingest(cfg, "xdr_20200401.jsonl", row("D1", "2020-04-01"), 2);

        assertEquals(1, outFiles(cfg, "day=01").size(), "late day lands in its own event-date partition");
        assertEquals(before, Files.readString(day5), "already-ingested later day is byte-identical");
        assertEquals(modified, Files.getLastModifiedTime(day5).toMillis(), "and was not rewritten");
    }

    @Test
    void twoFilesOfTheSameEventDateAccumulateAsTwoSiblingFiles(@TempDir Path dir) throws Exception {
        PipelineConfig cfg = cfg(dir);
        ingest(cfg, "xdr_a.jsonl", row("P1", "2020-04-03"), 1);
        ingest(cfg, "xdr_b.jsonl", row("Q1", "2020-04-03"), 2);

        assertEquals(2, outFiles(cfg, "day=03").size(), "append semantics: a second file adds a sibling, no replace");
        assertEquals(1, countAcct(cfg, "day=03", "P"));
        assertEquals(1, countAcct(cfg, "day=03", "Q"));
    }

    // ---- helpers ----

    private static void ingest(PipelineConfig cfg, String name, String body, int seq) throws Exception {
        Path inbox = Path.of(cfg.dirs().poll());
        Files.createDirectories(inbox);
        Path f = inbox.resolve(name);
        Files.writeString(f, body, StandardCharsets.UTF_8);
        SchemaSelector.Selection sel = new SchemaSelector.Selection(cfg.schemas().single(), null);
        Consignment batch = new Consignment(cfg.identity().runTimestamp() + "_pr_" + String.format("%04d", seq), "pr", null,
                List.of(new Consignment.Member(f.toFile(), 0, f.toFile().length(), sel)));
        ConsignmentIngestor.process(batch, cfg, new ConsignmentAuditWriter(
                cfg.dirs().statusFilePath(), cfg.dirs().batchesFilePath(), cfg.dirs().lineageFilePath()));
    }

    private static List<Path> outFiles(PipelineConfig cfg, String dayDir) throws Exception {
        Path root = Path.of(cfg.dirs().database());
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> w = Files.walk(root)) {
            return w.filter(p -> p.getFileName().toString().endsWith("_out.csv"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/" + dayDir + "/"))
                    .sorted().toList();
        }
    }

    private static long countAcct(PipelineConfig cfg, String dayDir, String prefix) throws Exception {
        long n = 0;
        for (Path p : outFiles(cfg, dayDir))
            n += Files.readAllLines(p).stream().filter(l -> l.contains(prefix)).count();
        return n;
    }

    private static void assertNoStagingLeftovers(PipelineConfig cfg) throws Exception {
        try (Stream<Path> w = Files.walk(Path.of(cfg.dirs().database()))) {
            assertTrue(w.noneMatch(p -> p.toString().endsWith(".tmp")
                    || (p.toString().replace('\\', '/').contains("/.staging/") && Files.isRegularFile(p))));
        }
    }

    private static PipelineConfig cfg(Path dir) throws Exception {
        String d = dir.toString().replace('\\', '/');
        Path schema = dir.resolve("ev_schema.toon");
        Files.writeString(schema, SCHEMA, StandardCharsets.UTF_8);
        Path pipe = dir.resolve("ev_pipeline.toon");
        Files.writeString(pipe, ("""
                name: PR_PIPE
                version: 1
                dirs:
                  poll: %s/inbox
                  database: %s/db
                  backup: %s/backup
                  temp: %s/temp
                  errors: %s/errors
                  quarantine: %s/quarantine
                  markers: %s/markers
                  status_dir: %s/status
                output:
                  format: CSV
                processing:
                  threads: 1
                  file_pattern: "glob:**/*.jsonl"
                  schema_file: %s
                  csv_settings:
                    date_formats[1]: "%%Y-%%m-%%d"
                    timestamp_formats[1]: "%%Y-%%m-%%d"
                parsing:
                  frontend: json
                  json:
                    format: newline
                """).formatted(d, d, d, d, d, d, d, d,
                schema.toString().replace('\\', '/')), StandardCharsets.UTF_8);
        return PipelineConfig.load(pipe.toString());
    }
}
