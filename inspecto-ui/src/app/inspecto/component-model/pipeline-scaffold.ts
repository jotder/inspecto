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
 * (`PipelineConfigParser.java:81` — lower-case, spaces underscored). That derivation is NOT constrained
 * to the `id` pattern, so it can emit an id the spec rejects; {@link pipelineId} narrows it. Kept as the
 * separate, faithful mirror because it is what a config with no `id:` is keyed by TODAY.
 */
export function derivedPipelineId(name: string): string {
    return name.trim().toLowerCase().replaceAll(' ', '_');
}

/**
 * The id stamped on a NEW pipeline: {@link derivedPipelineId} narrowed into the spec's alphabet, so a
 * name the create form accepts can always carry an explicit identity. Anything outside `[a-z0-9_]`
 * becomes `_`, and a leading non-alphanumeric is prefixed — `"My-Pipe!"` → `my_pipe_`, `"café"` → `caf_`.
 *
 * ⚠ Only ever applied at CREATION. The parser's unconstrained derivation stays the fallback for a
 * config with no `id:`, because narrowing THAT would silently re-key every such pipeline already on
 * disk — its config filename, `<id>_commits.log` audit trail, ledger `sourceId` and Catalog Stream.
 */
export function pipelineId(name: string): string {
    const slug = derivedPipelineId(name).replace(/[^a-z0-9_]/g, '_');
    return /^[a-z0-9]/.test(slug) ? slug : `p_${slug}`;
}

/**
 * The identity a config is REGISTERED under: its explicit `id:`, else {@link derivedPipelineId} of its
 * `name`. Mirrors `PipelineConfigParser` and `ConfigRoutes.identityFields`, and it is the key every
 * route addresses the pipeline by — so a create surface must navigate by THIS, never by the display
 * name it just collected from the operator.
 */
export function configPipelineId(config: Record<string, unknown>, fallback: string): string {
    const explicit = String(config['id'] ?? '').trim();
    if (explicit) return explicit;
    const name = String(config['name'] ?? '').trim();
    return name ? derivedPipelineId(name) : fallback;
}

/**
 * The path base a space's data directories hang off: `spaces/<id>` for a named space, `.` for the
 * un-prefixed default namespace.
 *
 * ⚠ Load-bearing, not cosmetic. `ConfigSafetyValidator` resolves every `dirs.*` value **CWD-relative**
 * (its own words: "config values are CWD-relative") and jails the result under the allowed roots, which
 * are the per-space directories. So a bare `data/<name>` resolves next to the server's working directory
 * — outside every root — and the write is refused with nine ERROR findings before a byte is written.
 * Every committed pipeline on disk is space-qualified for exactly this reason.
 *
 * The one place that knows the convention: it was hand-copied into the Onboarding dialog, and the
 * Pipelines editor's "New pipeline" had no copy at all, which is why that surface could never create
 * a pipeline (2026-08-25).
 */
export function spaceBase(spaceId: string | null | undefined): string {
    return spaceId ? `spaces/${spaceId}` : '.';
}

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
    opts: {
        poll?: string;
        database?: string;
        description?: string;
        reference?: boolean;
        /** Active space id; scopes the derived dirs so the write clears the path jail. See {@link spaceBase}. */
        space?: string | null;
    } = {},
): Record<string, unknown> {
    const base = spaceBase(opts.space);
    const home = pipelineHome(name, opts.database || `${base}/data/${name}/database`);
    const config: Record<string, unknown> = {
        name,
        // Stamp identity at CREATION so `name` is a display label from day one: a later relabel is then
        // a one-field edit with zero migration, instead of `PipelineRoutes.relabel` having to stamp the
        // derived id first.
        //
        // ALWAYS stamped, because {@link pipelineId} narrows into the `id` pattern's alphabet. It used
        // to be omitted whenever the raw slug failed that pattern (e.g. "my-pipe"), which left the
        // pipeline deriving its id — and `PipelineRoutes.rename` enforces the same pattern, so such a
        // pipeline could never be renamed for the rest of its life.
        id: pipelineId(name),
        active: false,
        dirs: {
            poll: opts.poll || `${base}/data/inbox/${name}`,
            database: opts.database || `${base}/data/${name}/database`,
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
        // Source-file lineage ON by default for NEW pipelines (operator ask 2026-08-22): every
        // output row carries which file it came from, as `file_name`. Optional — clearing the field
        // on the Files & metadata tab removes it. Existing pipelines are untouched (an engine-side
        // default would silently grow every existing store's output by a column). The engine fails
        // the load if a declared schema column collides with this name, so the default can never
        // silently shadow real data.
        output: { filename_column: 'file_name' },
    };
    if (opts.description?.trim()) config['description'] = opts.description.trim();
    if (opts.reference) config['produces'] = 'reference';
    return config;
}
