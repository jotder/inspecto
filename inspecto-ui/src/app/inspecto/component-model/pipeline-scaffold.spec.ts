import { describe, expect, it } from 'vitest';
import { derivedPipelineId, pipelineId, pipelineScaffold } from './pipeline-scaffold';

const SPEC_PATTERN = /^[a-z0-9][a-z0-9_]*$/;

describe('pipelineScaffold', () => {
    it('stamps an explicit id equal to what the parser would derive', () => {
        const config = pipelineScaffold('My Pipeline');
        expect(config['name']).toBe('My Pipeline');
        expect(config['id']).toBe('my_pipeline');
        expect(config['id']).toBe(derivedPipelineId('My Pipeline'));
    });

    // An omitted id left the pipeline deriving one the rename gate refuses forever. The slug is now
    // narrowed into the pattern's alphabet instead, so every name the create form accepts gets an id.
    it('always stamps an id that satisfies the spec pattern', () => {
        for (const name of ['my-pipe', '9lives', '_leading', 'has.dot', 'My Pipeline 2!', 'café']) {
            const id = pipelineScaffold(name)['id'] as string;
            expect(id, name).toMatch(SPEC_PATTERN);
        }
        expect(pipelineScaffold('my-pipe')['id']).toBe('my_pipe');
        expect(pipelineScaffold('9lives')['id']).toBe('9lives');
        expect(pipelineScaffold('_leading')['id']).toBe('p__leading');
        expect(pipelineScaffold('has.dot')['id']).toBe('has_dot');
    });

    // DATA-DIRS-RESOLVE-AGAINST-CWD-1 (2026-09-23): the server resolves relative data paths under the
    // SPACE directory, so every derived dir is Space-relative `data/...`. A `spaces/<id>/` prefix would
    // now resolve to `spaces/<id>/spaces/<id>/data/...` — pin that none is emitted.
    it('derives every dir Space-relative under data/', () => {
        const dirs = pipelineScaffold('orders', {})['dirs'] as Record<string, string>;
        const keys = ['poll', 'database', 'backup', 'temp', 'errors', 'quarantine', 'markers', 'status_dir', 'log_dir'];
        expect(Object.keys(dirs).sort()).toEqual([...keys].sort());
        for (const k of keys) {
            expect(dirs[k], k).toMatch(/^data\//);
        }
        expect(dirs['poll']).toBe('data/inbox/orders');
        expect(dirs['database']).toBe('data/orders/database');
        expect(dirs['status_dir']).toBe('data/orders/status');
    });

    // Decision 2026-09-06 (NAME-DIRS-1): dirs follow the slug id, so a display name with spaces or
    // punctuation never reaches the filesystem — one identity for id, file and every path.
    it('derives every dir from the slug id, never the raw display name', () => {
        const cfg = pipelineScaffold('My Order Feed', {});
        expect(cfg['name']).toBe('My Order Feed');
        expect(cfg['id']).toBe('my_order_feed');
        const dirs = cfg['dirs'] as Record<string, string>;
        expect(dirs['poll']).toBe('data/inbox/my_order_feed');
        expect(dirs['database']).toBe('data/my_order_feed/database');
        expect(dirs['quarantine']).toBe('data/my_order_feed/quarantine');
        for (const v of Object.values(dirs)) expect(v).not.toMatch(/[ .-]/);
    });

    // An explicit database (Onboarding lets the operator type one) still governs the siblings.
    it('derives the siblings off an explicit database', () => {
        const dirs = pipelineScaffold('orders', {
            database: 'data/custom/database',
        })['dirs'] as Record<string, string>;
        expect(dirs['database']).toBe('data/custom/database');
        // `pipelineHome` strips only the conventional `/database` leaf, so the siblings hang off it.
        expect(dirs['backup']).toBe('data/custom/backup');
        expect(dirs['status_dir']).toBe('data/custom/status');
    });

    it('leaves the parser fallback derivation unnarrowed', () => {
        // Narrowing THIS would silently re-key every on-disk pipeline that carries no `id:`.
        expect(derivedPipelineId('my-pipe')).toBe('my-pipe');
        expect(pipelineId('my-pipe')).toBe('my_pipe');
    });

    it('keeps the rest of the draft shape unchanged', () => {
        const config = pipelineScaffold('cdr', { poll: '/in', database: '/db/database', description: ' notes ' });
        expect(config['active']).toBe(false);
        expect(config['description']).toBe('notes');
        expect(config['dirs']).toMatchObject({ poll: '/in', database: '/db/database', status_dir: '/db/status' });
        expect(config['processing']).toMatchObject({ threads: 1 });
        // `retention_days` is deliberately absent so the engine's own default of 90 governs.
        expect(config).not.toHaveProperty('retention_days');
    });

    /**
     * Source-file lineage ON by default for NEW pipelines (operator ask 2026-08-22): file_name,
     * optional — clearing the Files & metadata field removes it. Existing pipelines are untouched
     * because this is a SCAFFOLD default, never an engine one.
     */
    it('seeds output.filename_column as file_name for new pipelines', () => {
        expect(pipelineScaffold('cdr')['output']).toEqual({ filename_column: 'file_name' });
    });

    /**
     * 🔴 Decision D3 / pipeline spec gap 2. The lift retypes a parse node to its per-format subtype ONLY
     * when `parsing.frontend` names one explicitly, so a scaffold without it produced the generic Step
     * an author then had to convert through a custody dialog.
     */
    it('writes parsing.frontend so the lift types the Parse Step immediately', () => {
        expect(pipelineScaffold('cdr', { frontend: 'delimited' })['parsing']).toEqual({ frontend: 'delimited' });
        expect(pipelineScaffold('cdr', { frontend: 'fixedwidth' })['parsing']).toEqual({ frontend: 'fixedwidth' });
    });

    /**
     * ⚠ Absent, never defaulted. Guessing `delimited` would author a format nobody chose and re-create
     * the generic Step by another route — D3 weighed that and chose to ask.
     */
    it('omits parsing entirely when no format was chosen', () => {
        expect(pipelineScaffold('cdr')).not.toHaveProperty('parsing');
        expect(pipelineScaffold('cdr', { frontend: '  ' })).not.toHaveProperty('parsing');
    });
});
