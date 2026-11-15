import { describe, expect, it } from 'vitest';
import type { LinkIndexList, LinkIndexSummary } from '@inspecto/link-analysis/api/graph-runs.service';
import { datasetIndexStatus, indexStateWords, indexStatusSentence, rebuildRequest } from './index-status';

const ix = (over: Partial<LinkIndexSummary> = {}): LinkIndexSummary => ({
    dataset: 'transfers',
    mapping: {
        sourceCol: 'from_account',
        targetCol: 'to_account',
        kindCol: 'channel',
        timeCol: 'booked_at',
        timeColZone: null,
        weightCol: null,
        attrCols: ['channel', 'amount'],
    },
    version: 1,
    stale: false,
    reason: null,
    fingerprint: 'known',
    plan: { recommended: 'none', appendable: false, reasons: [], added: 0, removed: 0, changed: 0 },
    ...over,
});
const list = (...indexes: LinkIndexSummary[]): LinkIndexList => ({ enabled: true, indexes });
const DEFINITION = "the Dataset's definition changed after the index was built";

/** LA-FULL-CYCLE-TEST-1 phase 4: the one status answer the Data page, the data strip and Rebuild share. */
describe('datasetIndexStatus', () => {
    it('says nothing when GET /inv/index has not answered, or the Dataset has no index', () => {
        expect(datasetIndexStatus(null, 'transfers')).toBeNull();
        expect(datasetIndexStatus(list(ix({ dataset: 'other' })), 'transfers')).toBeNull();
    });

    it('is current when the server calls it current over files it could compare, and needs no rebuild', () => {
        expect(datasetIndexStatus(list(ix()), 'transfers')).toEqual({
            state: 'current',
            version: 1,
            why: '',
            rebuild: null,
        });
    });

    it('is never current over files the server could not compare', () => {
        expect(datasetIndexStatus(list(ix({ fingerprint: 'unknown' })), 'transfers')?.state).toBe('unknown');
    });

    it("is out of date when any of the Dataset's indexes is, with the server's plain reason and the advised rebuild", () => {
        const stale = ix({
            version: 2,
            stale: true,
            reason: DEFINITION,
            mapping: { ...ix().mapping, sourceCol: 'payer' },
            plan: { recommended: 'full', appendable: false, reasons: [], added: 0, removed: 0, changed: 0 },
        });
        const s = datasetIndexStatus(list(ix(), stale), 'transfers')!;
        expect(s.state).toBe('out_of_date');
        expect(s.version).toBe(2);
        expect(s.why).toBe(DEFINITION);
        expect(s.rebuild).toEqual({
            dataset: 'transfers',
            sourceCol: 'payer',
            targetCol: 'to_account',
            kindCol: 'channel',
            timeCol: 'booked_at',
            attrCols: ['channel', 'amount'],
            mode: 'full',
        });
    });
});

describe('the words', () => {
    it('one phrase per state, the same on every surface', () => {
        expect(indexStateWords('current')).toBe('up to date');
        expect(indexStateWords('out_of_date')).toBe('out of date');
        expect(indexStateWords('unknown')).toBe('');
    });

    it('a full sentence that says why and what it costs, never "stale"', () => {
        const s = datasetIndexStatus(list(ix({ stale: true, reason: DEFINITION })), 'transfers')!;
        const text = indexStatusSentence(s);
        expect(text).toBe(
            `The link index is out of date: ${DEFINITION}. Until it is rebuilt, the graph is read from the Dataset, which is slower.`,
        );
        expect(text).not.toContain('stale');
    });
});

describe('rebuildRequest', () => {
    it('uses the mode the server advises, and a full build when it advises none', () => {
        const advised = (recommended: 'none' | 'full' | 'append' | 'compact') =>
            rebuildRequest(
                ix({ plan: { recommended, appendable: true, reasons: [], added: 1, removed: 0, changed: 0 } }),
            ).mode;
        expect(advised('append')).toBe('append');
        expect(advised('compact')).toBe('compact');
        expect(advised('none')).toBe('full');
        expect(rebuildRequest(ix({ plan: undefined })).mode).toBe('full');
    });

    it('never sends an empty column', () => {
        const r = rebuildRequest(
            ix({
                mapping: {
                    sourceCol: 's',
                    targetCol: 't',
                    kindCol: null,
                    timeCol: null,
                    weightCol: null,
                    attrCols: [],
                },
            }),
        );
        expect(r).toEqual({ dataset: 'transfers', sourceCol: 's', targetCol: 't', mode: 'full' });
    });
});
