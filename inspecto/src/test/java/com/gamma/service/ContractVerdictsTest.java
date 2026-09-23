package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.AffectedPipelines.Change;
import com.gamma.service.AffectedPipelines.Report;
import com.gamma.service.AffectedPipelines.Status;
import com.gamma.service.AffectedPipelines.Tier;
import com.gamma.service.AffectedPipelines.Verdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DUCKLE-C7 second half: the reader-dependent contract verdicts, over real config trees. Fixture: {@code prod}
 * (CSV, the 3-column mini schema ID / AMT / EVENT_DATE) produces Dataset {@code prod_ds}; {@code cons} reads it
 * through {@code connector: dataset} on the parquet frontend, selecting the columns its test names.
 */
class ContractVerdictsTest {

    private static String dirs(Path dir) {
        String d = dir.toString().replace('\\', '/');
        return """
            dirs:
              poll: %1$s/inbox
              database: %1$s/db
              backup: %1$s/backup
              temp: %1$s/temp
              errors: %1$s/errors
              quarantine: %1$s/quarantine
              markers: %1$s/markers
              status_dir: %1$s/status
              log_dir: %1$s/logs
            """.formatted(d);
    }

    /** The producer: CSV over the mini schema, plus any extra top-level block (e.g. a {@code steps:} chain). */
    private static Path producer(Path root, String extra) throws Exception {
        Path dir = Files.createDirectories(root.resolve("prod"));
        Path schema = dir.resolve("prod_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        Files.writeString(dir.resolve("prod_pipeline.toon"), """
            name: prod
            active: false
            %s
            output:
              format: CSV
            processing:
              threads: 1
              file_pattern: "glob:**/*.csv"
              schema_file: "%s"
              csv_settings:
                delimiter: ","
                skip_header_lines: 0
                skip_junk_lines: 0
                skip_tail_lines: 0
                date_formats[1]: "%%Y-%%m-%%d"
                timestamp_formats[1]: "%%Y-%%m-%%d"
            %s
            """.formatted(dirs(dir), schema.toString().replace('\\', '/'), extra));
        return schema;
    }

    /** A parquet consumer of {@code dataset} selecting exactly {@code columns} (lower-case parquet names). */
    private static void consumer(Path root, String name, String dataset, String... columns) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        StringBuilder fields = new StringBuilder();
        StringBuilder rules = new StringBuilder();
        for (String c : columns) {
            fields.append("    ").append(c.toUpperCase()).append(",\"").append(c).append("\",VARCHAR\n");
            rules.append("    ").append(c.toUpperCase()).append(',').append(c.toUpperCase()).append(",DIRECT\n");
        }
        Path schema = dir.resolve(name + "_schema.toon");
        Files.writeString(schema, """
            raw:
              name: %1$s
              format: PARQUET
              fields[%2$d]{name,selector,type}:
            %3$smapping:
              canonicalName: %1$s
              rawName: %1$s
              rules[%2$d]{targetColumn,sourceExpression,transformType}:
            %4$s""".formatted(name, columns.length, fields, rules));
        Files.writeString(dir.resolve(name + "_pipeline.toon"), """
            name: %s
            active: false
            collector:
              connector: dataset
              dataset: %s
            %s
            output:
              format: PARQUET
            processing:
              threads: 1
              file_pattern: "glob:**/*.parquet"
              schema_file: "%s"
            parsing:
              frontend: parquet
            """.formatted(name, dataset, dirs(dir), schema.toString().replace('\\', '/')));
    }

    private static void dataset(Path root, String name, String physicalRef) throws Exception {
        Path ds = Files.createDirectories(root.resolve("registry/datasets"));
        Files.writeString(ds.resolve(name + ".toon"), "name: " + name + "\nphysicalRef: " + physicalRef + "\n");
    }

    /** Edit the producer's schema (replace {@code from} with {@code to}) and analyse that one change. */
    private static Report editSchema(Path root, Path schema, String from, String to) throws Exception {
        String before = Files.readString(schema);
        assertTrue(before.contains(from), "fixture drift: " + from);
        Files.writeString(schema, before.replace(from, to));
        return AffectedPipelines.analyze(root, List.of(new Change(schema, Status.MODIFIED, before)));
    }

    /** Drop AMT from the mini schema — its raw field and its mapping rule — and fix both counts. */
    private static Report removeAmt(Path root, Path schema) throws Exception {
        String before = Files.readString(schema);
        String after = before.replace("fields[3]", "fields[2]").replace("rules[3]", "rules[2]")
                .replace("    AMT,\"1\",DOUBLE\n", "").replace("    AMT,AMT,DIRECT\n", "")
                .replace("EVENT_DATE,\"2\",DATE", "EVENT_DATE,\"1\",DATE");
        Files.writeString(schema, after);
        return AffectedPipelines.analyze(root, List.of(new Change(schema, Status.MODIFIED, before)));
    }

    private static List<Verdict> of(Report r, Tier tier) {
        return r.verdicts().stream().filter(v -> v.tier() == tier).toList();
    }

    @Test
    void removingAColumnAConsumerReadsIsBreaking(@TempDir Path root) throws Exception {
        Path schema = producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "id", "amt");

        Report r = removeAmt(root, schema);

        List<Verdict> breaking = of(r, Tier.BREAKING);
        assertEquals(1, breaking.size(), AffectedPipelines.render(r));
        assertEquals("pipeline:prod", breaking.get(0).subject());
        assertEquals("amt", breaking.get(0).column());
        assertEquals("pipeline:cons", breaking.get(0).reader());
        assertTrue(breaking.get(0).reason().contains("removed"), breaking.get(0).reason());
        assertTrue(of(r, Tier.POSSIBLY_BREAKING).isEmpty(), "a read column is never merely possibly breaking");
        assertTrue(r.breaking());
        assertTrue(AffectedPipelines.render(r).contains("BREAKING          pipeline:prod.amt  reader pipeline:cons"),
                AffectedPipelines.render(r));
    }

    @Test
    void removingAColumnNobodyReadsIsPossiblyBreakingNeverCompatible(@TempDir Path root) throws Exception {
        Path schema = producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "id");

        Report r = removeAmt(root, schema);

        assertTrue(of(r, Tier.BREAKING).isEmpty(), AffectedPipelines.render(r));
        List<Verdict> possibly = of(r, Tier.POSSIBLY_BREAKING);
        assertEquals(1, possibly.size(), AffectedPipelines.render(r));
        assertEquals("amt", possibly.get(0).column());
        assertNull(possibly.get(0).reader());
        assertFalse(r.breaking());
    }

    @Test
    void retypingAColumnAConsumerReadsIsBreaking(@TempDir Path root) throws Exception {
        Path schema = producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "amt");

        Report r = editSchema(root, schema, "AMT,\"1\",DOUBLE", "AMT,\"1\",BIGINT");

        List<Verdict> breaking = of(r, Tier.BREAKING);
        assertEquals(1, breaking.size(), AffectedPipelines.render(r));
        assertEquals("amt", breaking.get(0).column());
        assertTrue(breaking.get(0).reason().contains("retyped DOUBLE -> BIGINT"), breaking.get(0).reason());
    }

    @Test
    void anAddedColumnIsAdditiveAndNothingElse(@TempDir Path root) throws Exception {
        Path schema = producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "id", "amt");
        String before = Files.readString(schema);
        Files.writeString(schema, before.replace("fields[3]", "fields[4]").replace("rules[3]", "rules[4]")
                .replace("EVENT_DATE,\"2\",DATE\n", "EVENT_DATE,\"2\",DATE\n    NOTE,\"3\",VARCHAR\n")
                .replace("EVENT_DATE,EVENT_DATE,DIRECT\n", "EVENT_DATE,EVENT_DATE,DIRECT\n    NOTE,NOTE,DIRECT\n"));

        Report r = AffectedPipelines.analyze(root, List.of(new Change(schema, Status.MODIFIED, before)));

        assertEquals(List.of("prod", "cons"), r.affected().stream().map(AffectedPipelines.Hit::pipeline).toList(),
                "the change still REACHES the consumer — additive is a verdict, not an omission");
        assertEquals(1, r.verdicts().size(), AffectedPipelines.render(r));
        assertEquals(Tier.ADDITIVE, r.verdicts().get(0).tier());
        assertEquals("note", r.verdicts().get(0).column());
        assertTrue(r.verdicts().get(0).reason().contains("not a contract break"));
    }

    @Test
    void aConsumerBehindATransformStepIsRevalidate(@TempDir Path root) throws Exception {
        Path schema = producer(root, """
            steps[1]:
              - sql:
                  sql: SELECT ID, AMT * 2 AS AMT, EVENT_DATE FROM input
            """);
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "id", "amt");

        Report r = removeAmt(root, schema);

        assertTrue(of(r, Tier.BREAKING).isEmpty(), "no column lineage through sql — not a guess: "
                + AffectedPipelines.render(r));
        assertTrue(of(r, Tier.POSSIBLY_BREAKING).isEmpty(), AffectedPipelines.render(r));
        List<String> readers = of(r, Tier.REVALIDATE).stream().map(Verdict::reader).toList();
        assertTrue(readers.contains("pipeline:cons"), AffectedPipelines.render(r));
        assertTrue(of(r, Tier.REVALIDATE).get(0).reason().contains("'sql' step"), AffectedPipelines.render(r));
    }

    @Test
    void deletingTheProducerIsBreakingForEveryReader(@TempDir Path root) throws Exception {
        producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "id");
        Path widgets = Files.createDirectories(root.resolve("registry/widgets"));
        Files.writeString(widgets.resolve("kpi.toon"), """
            vizType: kpi
            datasetId: prod_ds
            controls:
              value[1]{field,agg}:
                ID,count
            """);
        Path prod = root.resolve("prod/prod_pipeline.toon");
        String before = Files.readString(prod);
        Files.delete(prod);

        Report r = AffectedPipelines.analyze(root, List.of(new Change(prod, Status.DELETED, before)));

        List<String> readers = of(r, Tier.BREAKING).stream().map(Verdict::reader).toList();
        assertEquals(List.of("dataset:prod_ds", "pipeline:cons", "widget:kpi"), readers, AffectedPipelines.render(r));
        assertTrue(of(r, Tier.BREAKING).stream().allMatch(v -> v.reason().contains("deleted")));
    }

    @Test
    void aWidgetReadingTheRemovedColumnIsBreakingAndOneReadingASavedQueryIsRevalidate(@TempDir Path root)
            throws Exception {
        Path schema = producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        Path widgets = Files.createDirectories(root.resolve("registry/widgets"));
        Files.writeString(widgets.resolve("sum_amt.toon"), """
            vizType: bar
            datasetId: prod_ds
            controls:
              x[1]{field}:
                EVENT_DATE
              y[1]{field,agg}:
                AMT,sum
            """);
        Files.writeString(widgets.resolve("by_query.toon"), "vizType: table\ndatasetId: prod_ds\nqueryId: q1\n");

        Report r = removeAmt(root, schema);

        assertEquals(List.of("widget:sum_amt"), of(r, Tier.BREAKING).stream().map(Verdict::reader).toList(),
                AffectedPipelines.render(r));
        assertEquals(List.of("widget:by_query"), of(r, Tier.REVALIDATE).stream().map(Verdict::reader).toList(),
                AffectedPipelines.render(r));
    }

    @Test
    void theConsumerOfABrokenConsumerIsRevalidate(@TempDir Path root) throws Exception {
        Path schema = producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "amt");
        dataset(root, "cons_ds", "cons/db");
        consumer(root, "leaf", "cons_ds", "amt");

        Report r = removeAmt(root, schema);

        assertEquals(List.of("pipeline:cons"), of(r, Tier.BREAKING).stream().map(Verdict::reader).toList());
        List<Verdict> revalidate = of(r, Tier.REVALIDATE);
        assertEquals(List.of("dataset:cons_ds", "pipeline:leaf"), revalidate.stream().map(Verdict::reader).toList(),
                AffectedPipelines.render(r));
        assertTrue(revalidate.get(1).reason().contains("downstream of pipeline:cons"), revalidate.get(1).reason());
    }

    @Test
    void aPipelineFileEditThatLeavesTheOutputAloneHasNoVerdict(@TempDir Path root) throws Exception {
        producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "id", "amt");
        Path prod = root.resolve("prod/prod_pipeline.toon");
        String before = Files.readString(prod);
        Files.writeString(prod, before.replace("threads: 1", "threads: 2"));

        Report r = AffectedPipelines.analyze(root, List.of(new Change(prod, Status.MODIFIED, before)));

        assertEquals(List.of("prod", "cons"), r.affected().stream().map(AffectedPipelines.Hit::pipeline).toList());
        assertTrue(r.verdicts().isEmpty(), "the pre-change config is re-derived, not assumed: "
                + AffectedPipelines.render(r));
    }

    @Test
    void deletingADatasetIsBreakingForItsConsumers(@TempDir Path root) throws Exception {
        producer(root, "");
        dataset(root, "prod_ds", "prod/db");
        consumer(root, "cons", "prod_ds", "id");
        Path ds = root.resolve("registry/datasets/prod_ds.toon");
        String before = Files.readString(ds);
        Files.delete(ds);

        Report r = AffectedPipelines.analyze(root, List.of(new Change(ds, Status.DELETED, before)));

        assertEquals(List.of("pipeline:cons"), of(r, Tier.BREAKING).stream().map(Verdict::reader).toList(),
                AffectedPipelines.render(r));
        assertEquals("dataset:prod_ds", of(r, Tier.BREAKING).get(0).subject());
    }
}
