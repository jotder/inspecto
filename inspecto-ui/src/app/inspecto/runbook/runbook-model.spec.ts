import { describe, expect, it } from 'vitest';
import { runbookFromContent, runbookLinkTarget, runbookToContent } from './runbook-model';

describe('runbook-model', () => {
    it('reads ordered steps, keeps only a valid link, and falls back to the id for a missing title', () => {
        const r = runbookFromContent('irsf', {
            steps: [
                { text: 'Open the evidence', link: { kind: 'dataset', id: 'fraud_irsf' } },
                { text: 'Bad link', link: { kind: 'pipeline', id: 'p' } },
                { text: ' ' },
            ],
            tags: ['fraud', {}],
        });
        expect(r.title).toBe('irsf');
        expect(r.steps.map((s) => s.text)).toEqual(['Open the evidence', 'Bad link']);
        expect(r.steps[0].link).toEqual({ kind: 'dataset', id: 'fraud_irsf' });
        expect(r.steps[1].link).toBeNull();
        expect(r.tags).toEqual(['fraud']);
    });

    it('writes the edited keys over every key it does not model, and drops cleared optionals', () => {
        const out = runbookToContent(
            {
                id: 'irsf',
                title: ' T ',
                summary: '',
                ownerRole: '',
                tags: [' ', 'a'],
                steps: [{ text: 'x', link: { kind: 'case', id: ' ' } }, { text: '' }],
            },
            { 'x-note': 'kept', owner: 'alice', summary: 'old' },
        );
        expect(out).toEqual({
            'x-note': 'kept',
            owner: 'alice',
            id: 'irsf',
            title: 'T',
            steps: [{ text: 'x' }],
            tags: ['a'],
        });
    });

    it('routes each link kind to its page', () => {
        expect(runbookLinkTarget({ kind: 'dataset', id: 'd' }).commands).toEqual(['/catalog/datasets', 'd']);
        expect(runbookLinkTarget({ kind: 'case', id: 'c' }).commands).toEqual(['/cases', 'c']);
        expect(runbookLinkTarget({ kind: 'query', id: 'q' })).toEqual({
            commands: ['/studio/queries'],
            queryParams: { id: 'q' },
        });
    });
});
