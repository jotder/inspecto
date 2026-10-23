import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ComponentsService } from '@inspecto/core/api';
import { LinkAnalysisService, LinkAnalysisView, caseListIds } from './link-analysis.service';

function service(): LinkAnalysisService {
    TestBed.configureTestingModule({ providers: [{ provide: ComponentsService, useValue: {} }] });
    return TestBed.inject(LinkAnalysisService);
}

const VIEW: LinkAnalysisView = {
    id: 'ring-one',
    name: 'Ring one',
    sourceId: 'entity-projection',
    query: { projection: { datasetId: 'transfers', sourceCol: 'from', targetCol: 'to' } },
    lists: { start: 'suspects', exclusions: ['noisy', 'merchants'] },
    start: { datasetId: 'transfers', listId: 'suspects', values: ['A-1'] },
};

describe('LinkAnalysisService - a case is a view plus the Entity Lists it uses (LA-ONBOARD-1 phase 7, A-1b)', () => {
    it('a case round-trips its lists and its start through the stored content', () => {
        const s = service();
        const content = s.toContent(VIEW);
        expect(content['lists']).toEqual({ start: 'suspects', exclusions: ['noisy', 'merchants'] });
        expect(content['start']).toEqual({ datasetId: 'transfers', listId: 'suspects', values: ['A-1'] });
        expect(s.fromContent('ring-one', content)).toEqual(VIEW);
        expect(caseListIds(VIEW)).toEqual(['suspects', 'noisy', 'merchants']);
    });

    it('an older view (no lists, no start) reads as one that uses none; a wrong shape is dropped, not trusted', () => {
        const s = service();
        const old = s.fromContent('old', { name: 'Old', sourceId: 'entity-projection', query: {} });
        expect(old.lists).toBeUndefined();
        expect(old.start).toBeUndefined();
        expect(caseListIds(old)).toEqual([]);
        const odd = s.fromContent('odd', {
            name: 'Odd',
            lists: { start: 7, exclusions: ['ok', 3, ''] },
            start: { datasetId: 'transfers', values: [] },
        });
        expect(odd.lists).toEqual({ exclusions: ['ok'] });
        expect(odd.start).toBeUndefined(); // a start with nothing to draw is no start
    });

    it('LA-ONBOARD-DEFECTS-1 (2): a case keeps the Entity Type its start named; a wrong shape is dropped', () => {
        const s = service();
        const typed: LinkAnalysisView = {
            ...VIEW,
            start: { datasetId: 'transfers', entityType: 'wallet', values: ['W-1'] },
        };
        expect(s.fromContent('ring-one', s.toContent(typed)).start).toEqual(typed.start);
        const odd = s.fromContent('odd', {
            name: 'Odd',
            start: { datasetId: 'transfers', entityType: 7, values: ['W-1'] },
        });
        expect(odd.start).toEqual({ datasetId: 'transfers', values: ['W-1'] });
    });
});

describe('LinkAnalysisService - a case keeps its own name (LA-ONBOARD-1 phase 8)', () => {
    it('reads the name back after the registry stamps `name` with the id, as ComponentStore.write does', () => {
        const s = service();
        const stored = { ...s.toContent(VIEW), name: 'ring-one' }; // the server's write: in-file identity == URL id
        expect(s.fromContent('ring-one', stored).name).toBe('Ring one');
    });

    it('an older view saved without a title still reads its stored name, else its id', () => {
        const s = service();
        expect(s.fromContent('v1', { name: 'v1' }).name).toBe('v1');
        expect(s.fromContent('v2', { title: '  ', name: 'v2' }).name).toBe('v2');
        expect(s.fromContent('v3', {}).name).toBe('v3');
    });
});
