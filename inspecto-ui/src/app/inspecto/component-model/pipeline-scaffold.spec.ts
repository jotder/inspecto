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
});
