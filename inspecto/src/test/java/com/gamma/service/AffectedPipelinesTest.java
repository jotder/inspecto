package com.gamma.service;

import com.gamma.etl.PipelineConfigBatchTest;
import com.gamma.service.AffectedPipelines.Change;
import com.gamma.service.AffectedPipelines.Report;
import com.gamma.service.AffectedPipelines.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DUCKLE-C7 first slice: which Pipelines a change reaches, over real config trees. Fixture: {@code prod}
 * produces Dataset {@code prod_ds} ({@code physicalRef: prod/...}), which {@code cons} reads through
 * {@code connector: dataset}.
 */
class AffectedPipelinesTest {

    private static Path pipeline(Path root, String name, String collector) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Path schema = dir.resolve(name + "_schema.toon");
        Files.writeString(schema, PipelineConfigBatchTest.miniSchema());
        String d = dir.toString().replace('\\', '/');
        String toon = """
            name: %s
            active: false
            %s
            dirs:
              poll: %s/inbox
              database: %s/db
              backup: %s/backup
              temp: %s/temp
              errors: %s/errors
              quarantine: %s/quarantine
              markers: %s/markers
              status_dir: %s/status
              log_dir: %s/logs
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
            """.formatted(name, collector, d, d, d, d, d, d, d, d, d, schema.toString().replace('\\', '/'));
        Path p = dir.resolve(name + "_pipeline.toon");
        Files.writeString(p, toon);
        return p;
    }

    private static String consumerOf(String dataset) {
        return "collector:\n  connector: dataset\n  dataset: " + dataset + "\n";
    }

    private static Path fixture(Path root) throws Exception {
        pipeline(root, "prod", "");
        pipeline(root, "cons", consumerOf("prod_ds"));
        pipeline(root, "other", "");
        Path ds = Files.createDirectories(root.resolve("registry/datasets"));
        Files.writeString(ds.resolve("prod_ds.toon"), "name: prod_ds\nphysicalRef: prod/db\n");
        return root;
    }

    private static List<String> ids(Report r) {
        return r.affected().stream().map(AffectedPipelines.Hit::pipeline).toList();
    }

    @Test
    void aChangedSchemaReachesItsPipelineAndOnThroughItsDataset(@TempDir Path root) throws Exception {
        fixture(root);
        Path schema = root.resolve("prod/prod_schema.toon");
        String before = Files.readString(schema);
        Files.writeString(schema, before.replace("AMT,\"1\",DOUBLE", "AMT,\"1\",BIGINT"));

        Report r = AffectedPipelines.analyze(root, List.of(new Change(schema, Status.MODIFIED, before)));

        assertEquals(List.of("prod", "cons"), ids(r), AffectedPipelines.render(r));
        assertEquals(List.of("file:prod/prod_schema.toon", "pipeline:prod"), r.affected().get(0).chain());
        assertEquals(List.of("file:prod/prod_schema.toon", "pipeline:prod", "dataset:prod_ds", "pipeline:cons"),
                r.affected().get(1).chain());
        assertTrue(r.uncertain().isEmpty(), r.uncertain().toString());
    }

    @Test
    void aChangedDatasetReachesItsConsumerPipeline(@TempDir Path root) throws Exception {
        fixture(root);
        Path ds = root.resolve("registry/datasets/prod_ds.toon");
        String before = Files.readString(ds);
        Files.writeString(ds, before + "description: moved\n");

        Report r = AffectedPipelines.analyze(root, List.of(new Change(ds, Status.MODIFIED, before)));

        assertEquals(List.of("cons"), ids(r), AffectedPipelines.render(r));
        assertEquals(List.of("file:registry/datasets/prod_ds.toon", "dataset:prod_ds", "pipeline:cons"),
                r.affected().get(0).chain());
    }

    @Test
    void deletingAProducerIsAChange(@TempDir Path root) throws Exception {
        fixture(root);
        Path prod = root.resolve("prod/prod_pipeline.toon");
        String before = Files.readString(prod);
        Files.delete(prod);

        Report r = AffectedPipelines.analyze(root, List.of(new Change(prod, Status.DELETED, before)));

        assertEquals(List.of("prod", "cons"), ids(r), AffectedPipelines.render(r));
        assertEquals(List.of("file:prod/prod_pipeline.toon (deleted)", "pipeline:prod", "dataset:prod_ds",
                "pipeline:cons"), r.affected().get(1).chain());
    }

    @Test
    void aFormattingOnlyChangeReachesNothing(@TempDir Path root) throws Exception {
        fixture(root);
        Path p = root.resolve("other/other_pipeline.toon");
        String before = Files.readString(p);
        Files.writeString(p, before + "\n\n");           // same decoded content

        Report r = AffectedPipelines.analyze(root, List.of(new Change(p, Status.MODIFIED, before)));

        assertTrue(r.affected().isEmpty(), AffectedPipelines.render(r));
        assertEquals(1, r.ignored().size());
        assertTrue(r.ignored().get(0).reason().contains("unchanged"));
    }

    @Test
    void aDynamicDatasetReferenceIsUncertainNotResolved(@TempDir Path root) throws Exception {
        fixture(root);
        pipeline(root, "dyn", consumerOf("\"${DATASET}\""));
        Path ds = root.resolve("registry/datasets/prod_ds.toon");
        String before = Files.readString(ds);
        Files.writeString(ds, before + "description: moved\n");

        Report r = AffectedPipelines.analyze(root, List.of(new Change(ds, Status.MODIFIED, before)));

        assertEquals(List.of("cons"), ids(r), "the dynamic consumer is NOT claimed as reached");
        assertEquals(1, r.uncertain().size(), r.uncertain().toString());
        assertEquals("pipeline:dyn", r.uncertain().get(0).subject());
    }

    @Test
    void aDatasetsPrefixedReferenceIsTheDatasetLinkNotUncertain(@TempDir Path root) throws Exception {
        fixture(root);
        pipeline(root, "pref", consumerOf("datasets/prod_ds"));   // the trigger-ref spelling the parser accepts
        Path ds = root.resolve("registry/datasets/prod_ds.toon");
        String before = Files.readString(ds);
        Files.writeString(ds, before + "description: moved\n");

        Report r = AffectedPipelines.analyze(root, List.of(new Change(ds, Status.MODIFIED, before)));

        assertEquals(List.of("cons", "pref"), ids(r).stream().sorted().toList(), AffectedPipelines.render(r));
        assertTrue(r.uncertain().isEmpty(), r.uncertain().toString());
    }

    @Test
    void aDeletedSchemaReachesThePipelineThatNoLongerLoads(@TempDir Path root) throws Exception {
        fixture(root);
        Path schema = root.resolve("other/other_schema.toon");
        String before = Files.readString(schema);
        Files.delete(schema);

        Report r = AffectedPipelines.analyze(root, List.of(new Change(schema, Status.DELETED, before)));

        assertEquals(List.of("other"), ids(r), AffectedPipelines.render(r));
        assertTrue(r.uncertain().stream().anyMatch(u -> u.subject().equals("pipeline:other")),
                "and it is listed as uncertain: what it reads can no longer be known");
    }

    @Test
    void aFileNoPipelineReadsIsIgnored(@TempDir Path root) throws Exception {
        fixture(root);
        Path readme = Files.writeString(root.resolve("README.md"), "x");

        Report r = AffectedPipelines.analyze(root, List.of(new Change(readme, Status.ADDED, null)));

        assertTrue(r.affected().isEmpty());
        assertEquals("no Pipeline reads this file", r.ignored().get(0).reason());
    }

    @Test
    void enrichmentAndJobLinksOfEveryReachedPipelineAreListedWithTheirChains(@TempDir Path root) throws Exception {
        fixture(root);
        Files.writeString(root.resolve("prod_enrich.toon"),
                "name: prod_enrich\nreferences:\n  base:\n    ref: prod\n");
        Files.writeString(root.resolve("trig_enrich.toon"), "name: trig_enrich\ntriggers:\n  on_pipeline: cons\n");
        Path jobs = Files.createDirectories(root.resolve("jobs"));
        Files.writeString(jobs.resolve("after_cons_job.toon"), "type: report\non_pipeline: cons\n");
        Files.writeString(jobs.resolve("after_other_job.toon"), "type: report\non_pipeline: other\n");
        Path schema = root.resolve("prod/prod_schema.toon");
        String before = Files.readString(schema);
        Files.writeString(schema, before.replace("AMT,\"1\",DOUBLE", "AMT,\"1\",BIGINT"));

        Report r = AffectedPipelines.analyze(root, List.of(new Change(schema, Status.MODIFIED, before)));

        java.util.Map<String, List<String>> chains = new java.util.LinkedHashMap<>();
        for (AffectedPipelines.Dependent d : r.dependents()) chains.put(d.kind() + ":" + d.name(), d.chain());
        assertEquals(List.of("file:prod/prod_schema.toon", "pipeline:prod", "enrichment:prod_enrich"),
                chains.get("enrichment:prod_enrich"), AffectedPipelines.render(r));
        assertEquals(List.of("file:prod/prod_schema.toon", "pipeline:prod", "dataset:prod_ds", "pipeline:cons",
                "job:after_cons_job"), chains.get("job:after_cons_job"));
        assertEquals(List.of("file:prod/prod_schema.toon", "pipeline:prod", "dataset:prod_ds", "pipeline:cons",
                "enrichment:trig_enrich"), chains.get("enrichment:trig_enrich"));
        assertFalse(chains.containsKey("job:after_other_job"), "a job on an unreached Pipeline is not listed");
        assertTrue(AffectedPipelines.render(r).contains("DEPENDENT job:after_cons_job  via on_pipeline"),
                AffectedPipelines.render(r));
    }

    @Test
    void aChangedDatasetListsItsWidgetsAndDashboardsEvenWithNoConsumer(@TempDir Path root) throws Exception {
        pipeline(root, "prod", "");
        Path dsDir = Files.createDirectories(root.resolve("registry/datasets"));
        Path ds = dsDir.resolve("lone_ds.toon");
        Files.writeString(ds, "name: lone_ds\nphysicalRef: prod/db\n");
        Path widgets = Files.createDirectories(root.resolve("registry/widgets"));
        Files.writeString(widgets.resolve("lone_kpi.toon"), "vizType: kpi\ndatasetId: lone_ds\n");
        Path boards = Files.createDirectories(root.resolve("registry/dashboards"));
        Files.writeString(boards.resolve("board.toon"), "tiles[1]{widgetId}:\n  lone_kpi\n");
        String before = Files.readString(ds);
        Files.writeString(ds, before + "sourceName: prod\n");

        Report r = AffectedPipelines.analyze(root, List.of(new Change(ds, Status.MODIFIED, before)));

        assertTrue(r.affected().isEmpty(), AffectedPipelines.render(r));
        assertEquals(List.of("widget:lone_kpi", "dashboard:board"),
                r.dependents().stream().map(d -> d.kind() + ":" + d.name()).toList(), AffectedPipelines.render(r));
        assertEquals(List.of("file:registry/datasets/lone_ds.toon", "dataset:lone_ds", "widget:lone_kpi",
                "dashboard:board"), r.dependents().get(1).chain());
        assertTrue(r.ignored().isEmpty(), "a Dataset a Widget shows is not ignored: " + r.ignored());
    }

    @Test
    void alertRulesOnAReachedPipelineOrDatasetAreListed(@TempDir Path root) throws Exception {
        fixture(root);
        Path alerts = Files.createDirectories(root.resolve("registry/alert-rules"));
        Files.writeString(alerts.resolve("on_cons.toon"), "name: on_cons\nonPipeline: cons\n");
        Files.writeString(alerts.resolve("on_ds.toon"), "name: on_ds\ndataset: prod_ds\n");
        Files.writeString(alerts.resolve("on_other.toon"), "name: on_other\nonPipeline: other\n");
        Path schema = root.resolve("prod/prod_schema.toon");
        String before = Files.readString(schema);
        Files.writeString(schema, before.replace("AMT,\"1\",DOUBLE", "AMT,\"1\",BIGINT"));

        Report r = AffectedPipelines.analyze(root, List.of(new Change(schema, Status.MODIFIED, before)));

        java.util.Map<String, List<String>> chains = new java.util.LinkedHashMap<>();
        for (AffectedPipelines.Dependent d : r.dependents()) chains.put(d.kind() + ":" + d.name(), d.chain());
        assertEquals(List.of("file:prod/prod_schema.toon", "pipeline:prod", "alert-rule:on_ds"),
                chains.get("alert-rule:on_ds"), AffectedPipelines.render(r));
        assertEquals(List.of("file:prod/prod_schema.toon", "pipeline:prod", "dataset:prod_ds", "pipeline:cons",
                "alert-rule:on_cons"), chains.get("alert-rule:on_cons"), AffectedPipelines.render(r));
        assertFalse(chains.containsKey("alert-rule:on_other"), "an alert on an unreached Pipeline is not listed");
    }
}
