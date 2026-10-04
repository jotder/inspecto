package com.gamma.catalog;

import com.gamma.etl.PipelineConfig;
import com.gamma.etl.SchemaSelector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The ONE answer to "which pipeline schemas does a store (an origin name) read from": the schema entries a
 * pipeline declares ({@link #entries}, also what {@link MetadataGraphBuilder} lays out as RAW_SCHEMA / TABLE
 * nodes) and the pipelines of a Space that an origin name denotes ({@link #forStore}).
 *
 * <p>The origin rule is the Catalog's: a Dataset's {@code physicalRef} head (or {@code sourceName}) names a
 * Stream (a pipeline's {@code stream}, default its own name) or a {@code produces: reference} pipeline,
 * compared case-insensitively. ⚠ Still mirrored, not shared, in {@code MetadataGraphBuilder.originNode},
 * {@code PipelineDependents.datasets}, {@code DataSourceBundleResolver.datasetReadsStore} and
 * {@code PipelineRenameRoutes.rewriteDatasetRefs} (other modules / node-set based) — change them together.
 */
public final class PipelineSchemas {

    private PipelineSchemas() {}

    /** One schema a pipeline declares: its Catalog key, the table it is bound to (selector only) and its content. */
    public record Entry(String key, String table, Map<String, Object> schema) {}

    /**
     * What an origin resolves to.
     *
     * @param entries    the schemas of every matching pipeline
     * @param reshaped   a matching pipeline has a step that rewrites the column set (summarize, sql, lookup, join, route)
     * @param unreadable a pipeline that may be this origin's could not be loaded, so its schema is unknown
     */
    public record Found(List<Entry> entries, boolean reshaped, boolean unreadable) {
        public boolean matched() { return !entries.isEmpty() || unreadable; }
    }

    /** Step kinds that change which columns the output has; any of them makes column lineage untraceable here. */
    private static final List<String> RESHAPING = List.of(PipelineConfig.Step.SUMMARIZE, PipelineConfig.Step.JOIN,
            PipelineConfig.Step.SQL, PipelineConfig.Step.LOOKUP, PipelineConfig.Step.ROUTE);

    /** The schemas a pipeline declares, in the order the Catalog lays them out. */
    public static List<Entry> entries(PipelineConfig cfg) {
        List<Entry> out = new ArrayList<>();
        PipelineConfig.Schemas s = cfg.schemas();
        if (s.segments() != null && !s.segments().isEmpty()) {
            for (Map.Entry<String, Map<String, Object>> e : s.segments().entrySet())
                out.add(new Entry(e.getKey(), null, e.getValue()));
        } else if (s.selector() != null && s.selector().hasSchemas()) {
            int i = 0;
            for (SchemaSelector.Selection sel : s.selector().entries()) {
                String key = firstNonBlank(sel.table(), SchemaProjection.canonicalName(sel.schema()), "schema_" + i);
                out.add(new Entry(key, sel.table(), sel.schema()));
                i++;
            }
        } else if (s.single() != null) {
            out.add(new Entry(firstNonBlank(SchemaProjection.canonicalName(s.single()), "main"), null, s.single()));
        }
        return out;
    }

    /**
     * The pipelines under {@code configRoot} ({@code *_pipeline.toon}, outside {@code registry}) whose Stream or
     * own name is {@code origin}. A file that fails to load and whose name contains the origin counts as
     * {@code unreadable}: fail closed rather than read it as "no schema".
     */
    public static Found forStore(Path configRoot, String origin) {
        List<Entry> entries = new ArrayList<>();
        boolean reshaped = false, unreadable = false;
        if (origin == null || origin.isBlank() || configRoot == null || !Files.isDirectory(configRoot))
            return new Found(entries, false, false);
        String o = origin.trim().toLowerCase(Locale.ROOT);
        List<Path> files;
        try (Stream<Path> w = Files.walk(configRoot, 4)) {
            files = w.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith("_pipeline.toon"))
                    .filter(f -> !configRoot.relativize(f).startsWith("registry"))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (Path f : files) {
            PipelineConfig cfg;
            try {
                cfg = PipelineConfig.loadForValidation(f.toString());
            } catch (Exception e) {
                if (f.getFileName().toString().toLowerCase(Locale.ROOT).contains(o)) unreadable = true;
                continue;
            }
            if (!o.equalsIgnoreCase(cfg.identity().pipelineName()) && !o.equalsIgnoreCase(cfg.stream())) continue;
            entries.addAll(entries(cfg));
            for (PipelineConfig.Step st : cfg.steps())
                if (RESHAPING.contains(st.kind())) reshaped = true;
        }
        return new Found(entries, reshaped, unreadable);
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
