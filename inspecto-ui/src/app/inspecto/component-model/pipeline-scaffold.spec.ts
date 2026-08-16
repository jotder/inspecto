import { describe, expect, it } from 'vitest';
import { derivedPipelineId, pipelineScaffold } from './pipeline-scaffold';

describe('pipelineScaffold', () => {
    it('stamps an explicit id equal to what the parser would derive', () => {
        const config = pipelineScaffold('My Pipeline');
        expect(config['name']).toBe('My Pipeline');
        expect(config['id']).toBe('my_pipeline');
        expect(config['id']).toBe(derivedPipelineId('My Pipeline'));
    });

    // The pattern is enforced ONLY on an explicit id, so stamping one for a name the create form
    // accepts today would newly reject it. Omitting keeps the old derivation and the old behaviour.
    it('omits the id when the slug would violate the spec pattern', () => {
        expect(pipelineScaffold('my-pipe')['id']).toBeUndefined();
        expect(pipelineScaffold('9lives')['id']).toBe('9lives');
        expect(pipelineScaffold('_leading')['id']).toBeUndefined();
        expect(pipelineScaffold('has.dot')['id']).toBeUndefined();
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
