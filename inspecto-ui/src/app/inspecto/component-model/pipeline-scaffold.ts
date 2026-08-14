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
export function pipelineScaffold(
    name: string,
    opts: { poll?: string; database?: string; description?: string; reference?: boolean } = {},
): Record<string, unknown> {
    const home = (opts.database || `data/${name}/database`).replace(/\/database$/, '');
    const config: Record<string, unknown> = {
        name,
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
