/**
 * The space-convention pipeline draft scaffold — the ONE shape both creation surfaces write
 * (Onboarding's create dialog and the Pipelines editor's New pipeline, W5). `dirs.poll` and
 * `dirs.database` are hard-required by the parser AND the write spec even for an inactive draft,
 * and the rest of the dir set is derived silently: without `dirs.status_dir` the run audit never
 * lands, and without `processing.duplicate_check` the LOCAL poll path re-ingests the same file
 * every cycle (both found by the P3 live walk). `retention_days` is deliberately OMITTED (2026-08-14
 * correction) — it used to hardcode 30, silently overriding `PipelineConfigParser`'s own default of
 * 90 with no operator decision behind the difference; omitting the key lets that default govern.
 */
/**
 * The identity `PipelineConfigParser` derives from a name when no explicit `id:` is present
 * (`PipelineConfigParser.java:81` — lower-case, spaces underscored). Mirrored here so a stamped id is
 * byte-identical to the derived one; if that derivation ever changes, this must change with it.
 */
export function derivedPipelineId(name: string): string {
    return name.trim().toLowerCase().replaceAll(' ', '_');
}

/** The `id:` FieldSpec's pattern (`ConfigSpecs.pipeline()`) — enforced only on an EXPLICIT id. */
const PIPELINE_ID_PATTERN = /^[a-z0-9][a-z0-9_]*$/;

/**
 * The **home directory** a pipeline's dirs hang off: its `database` less the conventional `/database`
 * leaf, or `data/<name>` when nothing declares one. The one place that knows the convention — a second
 * copy would let a branch store land outside the pipeline home, and that path is load-bearing (a route
 * branch's `database` is its identity in the lowered config).
 */
export function pipelineHome(name: string, database?: string): string {
    return (database || `data/${name}/database`).replace(/\/database$/, '');
}

/**
 * The three store dirs a persistent sink declares, derived off a {@link pipelineHome}. `pipelineScaffold`
 * derives seven more siblings (`errors`/`quarantine`/`markers`/`status`/`logs`) off the same home; a sink
 * NODE carries only these three.
 */
export function storeDirs(home: string): { database: string; backup: string; temp: string } {
    return { database: `${home}/database`, backup: `${home}/backup`, temp: `${home}/temp` };
}

export function pipelineScaffold(
    name: string,
    opts: { poll?: string; database?: string; description?: string; reference?: boolean } = {},
): Record<string, unknown> {
    const home = pipelineHome(name, opts.database);
    const id = derivedPipelineId(name);
    const config: Record<string, unknown> = {
        name,
        // Stamp identity at CREATION so `name` is a display label from day one: a later relabel is then
        // a one-field edit with zero migration, instead of `PipelineRoutes.relabel` having to stamp the
        // derived id first. The value is exactly what the parser would have derived, so this changes
        // nothing about how the pipeline is keyed — it only stops the id moving when the name does.
        //
        // ⚠ Omitted when the slug would not satisfy the spec's `id` pattern. That pattern is enforced
        // ONLY on an explicit id, so writing one for e.g. "my-pipe" would newly REJECT a name the
        // create form accepts today. Such a pipeline keeps deriving (and stays un-renameable) until
        // someone decides whether the derivation or the pattern is wrong — see BACKLOG.
        ...(PIPELINE_ID_PATTERN.test(id) ? { id } : {}),
        active: false,
        dirs: {
            poll: opts.poll || `data/inbox/${name}`,
            database: opts.database || `data/${name}/database`,
            backup: `${home}/backup`,
            temp: `${home}/temp`,
            errors: `${home}/errors`,
            quarantine: `${home}/quarantine`,
            markers: `${home}/markers`,
            status_dir: `${home}/status`,
            log_dir: `${home}/logs`,
        },
        processing: {
            threads: 1,
            duplicate_check: { enabled: true, marker_extension: '.processed' },
        },
    };
    if (opts.description?.trim()) config['description'] = opts.description.trim();
    if (opts.reference) config['produces'] = 'reference';
    return config;
}
