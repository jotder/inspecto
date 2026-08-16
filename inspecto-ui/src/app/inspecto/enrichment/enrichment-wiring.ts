/**
 * The **derived** half of an enrichment's wiring — the conventions a host should never ask an author
 * to retype: the transform reads this pipeline's Stage-1 output, writes under the space's `enriched/`
 * directory, and runs after every committed batch of the pipeline it hangs off.
 *
 * <p>One statement of it, two hosts (definition-surface P6-c): the Onboarding Enrichment stage, which
 * derives everything and renders no wiring form at all, and the Pipelines `enrichment` node dialog,
 * which still ASKS through {@link ENRICHMENT_WIRING_ATTRIBUTES} but seeds those fields from here when
 * authoring a fresh companion. ⛔ It lives in shared `inspecto/` rather than the onboarding feature
 * precisely because the wizard shell is being retired (P6-e) and the convention must outlive it.
 *
 * <p>Pure: no injection, no HTTP. The caller resolves where the Stage-1 output actually is — the two
 * hosts read that from genuinely different places (the draft's `dirs`/`output` blocks vs. the lifted
 * graph's sink node), which is why THAT is a parameter and not something this file guesses.
 */

/** Hive grain of both Stage-1 output and the enriched store — the pipeline scaffold's own default. */
export const ENRICHMENT_DEFAULT_PARTITIONS: readonly string[] = ['year', 'month', 'day'];

export interface EnrichmentWiringSeed {
    /** Companion identity (`<pipeline>_enrich`) — names the output directory. */
    enrichName: string;
    /** The engine's normalized pipeline id — what `BatchEvent.pipeline()` carries. */
    pipelineId: string;
    /** Space-relative root: `spaces/<id>`, or `.` when single-tenant. */
    base: string;
    /** The Stage-1 output store the transform reads as the `input` view; blank when unresolvable. */
    inputDatabase?: string;
    /** Its format; `EnrichmentConfig.fromMap` defaults to PARQUET, and so does the pipeline scaffold. */
    inputFormat?: string;
    /** Its Hive partition columns, when the host knows them. */
    inputPartitions?: readonly string[];
}

/** The `input`/`output`/`triggers` blocks of an `EnrichmentConfig`, in the config file's vocabulary. */
export interface EnrichmentWiring {
    input: Record<string, unknown>;
    output: Record<string, unknown>;
    triggers: Record<string, unknown>;
}

/**
 * The wiring blocks a host can derive without asking. `input.database` stays BLANK when the caller
 * could not resolve the Stage-1 output — an empty required field the author must fill is honest; an
 * invented store path reads zero rows everywhere and looks like it worked.
 */
export function enrichmentWiringDefaults(seed: EnrichmentWiringSeed): EnrichmentWiring {
    return {
        input: {
            database: seed.inputDatabase ?? '',
            format: (seed.inputFormat || 'PARQUET').toUpperCase(),
            partitions: [...(seed.inputPartitions ?? ENRICHMENT_DEFAULT_PARTITIONS)],
        },
        output: {
            database: `${seed.base}/data/enriched/${seed.enrichName}`,
            format: 'PARQUET',
            partitions: [...ENRICHMENT_DEFAULT_PARTITIONS],
        },
        triggers: { on_pipeline: seed.pipelineId },
    };
}
