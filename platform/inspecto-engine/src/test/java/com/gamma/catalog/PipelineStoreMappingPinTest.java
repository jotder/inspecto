package com.gamma.catalog;

import com.gamma.etl.PipelineConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-COLLECTOR-COVERAGE-1 slice S1: pins the Pipeline-schema-to-store mapping over EVERY shipped config
 * ({@code spaces/**} - templates, default, demo, ucc - and the {@code inspecto/examples} tree). A batch's
 * {@code output_table} is the selector table of the schema that read it ({@code ConsignmentPlanner}: blank table
 * becomes {@code "default"} then {@code null}, written as ""), so the store a Pipeline's batches name is
 * derivable from its schemas alone. The roster idea needs this to be UNIQUE: each store claimed by exactly one
 * Pipeline within a Space, and every Pipeline claiming at least one non-blank store.
 */
class PipelineStoreMappingPinTest {

    private static final Path REPO = Path.of("..", "..");

    /** The stores a Pipeline's batches can name: its non-blank selector tables ("" for single / segmented). */
    static Set<String> stores(PipelineConfig cfg) {
        Set<String> out = new TreeSet<>();
        for (PipelineSchemas.Entry e : PipelineSchemas.entries(cfg))
            if (e.table() != null && !e.table().isBlank() && !"default".equals(e.table()))
                out.add(e.table().toLowerCase(Locale.ROOT));
        return out;
    }

    @Test
    void surveyEveryShippedConfig() throws Exception {
        List<Path> files = new ArrayList<>();
        for (String root : List.of("spaces", "inspecto/examples"))
            try (Stream<Path> w = Files.walk(REPO.resolve(root))) {
                w.filter(f -> f.getFileName().toString().endsWith("_pipeline.toon")).sorted().forEach(files::add);
            }
        assertTrue(files.size() > 50, "shipped Pipeline configs found: " + files.size());
        StringBuilder report = new StringBuilder();
        Set<String> withStores = new TreeSet<>();
        assertTrue(!files.isEmpty());
        for (Path f : files) {
            PipelineConfig cfg;
            try {
                cfg = PipelineConfig.loadForValidation(f.toString());
            } catch (Exception e) {
                report.append("UNLOADABLE ").append(f).append('\n');
                continue;
            }
            if (!stores(cfg).isEmpty()) withStores.add(cfg.identity().pipelineName());
            report.append(f.toString().replace('\\', '/')).append(" | ").append(cfg.identity().pipelineName())
                    .append(" | collector=").append(cfg.collector().id()).append(" | stores=").append(stores(cfg)).append('\n');
        }
        // FINDING (2026-10-12): the schema-to-store mapping is NOT derivable for the shipped configs. Single-schema and
        // segmented Pipelines write output_table "" (ConsignmentPlanner), so only a selector Pipeline names a store.
        // A derived roster would list ~every Pipeline as expected yet the batches join could never see it deliver:
        // false "never delivered" alarms. Do not build the roster until this set grows on purpose.
        assertEquals(Set.of("voucher_unknown_etl"), withStores, report.toString());
    }
}
