package com.gamma.service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The cohesive set of TOON config files that make up one data source within a space: its
 * {@code *_pipeline.toon}, the {@code *_connection.toon} it binds to ({@code source.connection} —
 * {@code null} for a local-filesystem source), the schema / grammar / segment files it referenced at
 * parse time, and any {@code *_job.toon} whose {@code on_pipeline} targets it.
 *
 * <p>This is the granularity for selective export / import and bulk onboarding (Stage 6). It carries
 * only file paths; packaging the bytes is the export side's concern.
 *
 * <p><b>Metadata note:</b> a {@code *_meta.toon} semantic model declares no pipeline reference, so it
 * cannot be linked to a single data source and is intentionally excluded here — it travels with a
 * whole-space export instead.
 *
 * @param id          the data-source id (the pipeline's in-file {@code name})
 * @param pipeline    the {@code *_pipeline.toon} file
 * @param connection  the bound {@code *_connection.toon}, or {@code null} for a local source
 * @param schemas     schema / grammar / segment files the pipeline read at parse time (may be empty)
 * @param jobs        {@code *_job.toon} files whose {@code on_pipeline} targets this pipeline (may be empty)
 * @param components  {@code config/registry/<type>/<id>.toon} component files bound to this data source —
 *                    Decision Rules that target it and Datasets that read its store (may be empty)
 * @param enrichments {@code *_enrich.toon} companions whose {@code triggers.on_pipeline} names it (may be empty)
 * @param references  the <b>forward</b> closure: each Reference Dataset this data source reads by name,
 *                    carried as the files of the {@code produces: reference} pipeline producing it (may be empty)
 */
public record DataSourceBundle(
        String id,
        Path pipeline,
        Path connection,
        List<Path> schemas,
        List<Path> jobs,
        List<Path> components,
        List<Path> enrichments,
        List<Reference> references) {

    /**
     * One carried Reference Dataset: the id of its producing pipeline and the files that let that pipeline
     * import — its {@code *_pipeline.toon}, its connection and its schema files.
     *
     * @param id    the producing pipeline's data-source id (as {@link DataSourceBundleResolver#dataSourceIds()})
     * @param files the producer's config files, pipeline first
     */
    public record Reference(String id, List<Path> files) {
        public Reference {
            files = List.copyOf(files);
        }
    }

    public DataSourceBundle {
        schemas     = List.copyOf(schemas);
        jobs        = List.copyOf(jobs);
        components  = List.copyOf(components);
        enrichments = List.copyOf(enrichments);
        references  = List.copyOf(references);
    }

    /**
     * Every config file in the bundle, de-duplicated, in a stable order: pipeline, connection, schemas,
     * jobs, registry components, enrichment companions, then each carried Reference's files. A file the
     * forward and reverse closures both reach (a connection a Reference producer shares, say) appears once.
     */
    public List<Path> files() {
        LinkedHashSet<Path> all = new LinkedHashSet<>();
        all.add(pipeline);
        if (connection != null) all.add(connection);
        all.addAll(schemas);
        all.addAll(jobs);
        all.addAll(components);
        all.addAll(enrichments);
        for (Reference r : references) all.addAll(r.files());
        return List.copyOf(all);
    }
}
