import { describe, expect, it } from 'vitest';
import { Arrival, Arrivals } from '@inspecto/link-analysis/add-data/add-data.model';
import { Dataset } from './dataset-types';
import {
    FeedContext,
    LA_WIDGETS_FALLBACK,
    NO_AUTHOR_RIGHT,
    NO_LINK_ANALYSIS,
    NO_WRITE_ROOT,
    factsOf,
    factsQuery,
    feedOffers,
    importDatasetOf,
    rowsWords,
    setAsideLines,
    spanWords,
    starterColumns,
} from './dataset-page';

/** An Add data Dataset of calls: a link caller → callee at started, an amount, as the import writes it. */
function calls(over: Partial<Dataset> = {}): Dataset {
    return {
        id: 'calls',
        name: 'calls',
        kind: 'stream' as never,
        sourceName: 'calls/database',
        columns: [
            { name: 'caller', type: 'string', role: 'entity', classification: 'msisdn' },
            { name: 'callee', type: 'string', role: 'entity', classification: 'msisdn' },
            { name: 'started', type: 'date', role: 'temporal' },
            { name: 'call_id', type: 'number', role: 'dimension' },
            { name: 'charge', type: 'number', role: 'dimension' },
        ],
        measures: [],
        calculated: [],
        primaryEntityType: 'msisdn',
        defaultLinkMapping: { from: 'caller', to: 'callee', primaryTime: 'started' },
        extra: { origin: 'import', importPipeline: 'calls', recordType: 'voice_call' },
        ...over,
    };
}

/** Basic, no sign-in: Link Analysis is the explorer; its dashboard widgets are Standard. */
const BASIC: FeedContext = {
    flags: { geoLink: true, laWidgets: false, authoring: true },
    reasons: { laWidgets: 'Link Analysis widgets on dashboards need Standard.' },
    signIn: false,
    canAuthor: true,
};
const STANDARD: FeedContext = { ...BASIC, flags: { geoLink: true, laWidgets: true, authoring: true }, reasons: {} };

function arrival(over: Partial<Arrival>): Arrival {
    return {
        file: 'a.csv',
        sender: 'upload',
        received: '2026-11-05T10:00:00',
        status: 'SUCCESS',
        rowsRead: 10,
        rowsRejected: 0,
        mode: 'append',
        sha256: null,
        index: null,
        consignment: 'c1',
        kept: true,
        coversFrom: null,
        coversTo: null,
        ...over,
    };
}

describe('dataset-page (ONBOARDING-FIRST-RUN-1 C5)', () => {
    it('reads only an Add data Dataset as an import card, with its kind and Pipeline', () => {
        expect(importDatasetOf(calls())).toEqual({
            id: 'calls',
            kind: 'stream',
            description: undefined,
            primaryEntityType: 'msisdn',
            importPipeline: 'calls',
            importSource: undefined,
        });
        expect(importDatasetOf(calls({ kind: 'reference' as never }))?.kind).toBe('reference');
        expect(importDatasetOf(calls({ extra: {} }))).toBeNull();
    });

    it('call records offer the network; on Basic the dashboard network view is locked with its reason', () => {
        const offers = feedOffers(calls(), BASIC);
        expect(offers.map((o) => o.id)).toEqual(['network', 'network-widget', 'starter-dashboard']);
        expect(offers[0]).toMatchObject({
            action: 'Explore as a network in Link Analysis',
            because: 'Looks like voice call records: caller → callee, started.',
            lock: null,
        });
        expect(offers[1].lock).toBe('Link Analysis widgets on dashboards need Standard.');
        expect(feedOffers(calls(), { ...BASIC, reasons: {} })[1].lock).toBe(LA_WIDGETS_FALLBACK);
        expect(feedOffers(calls(), STANDARD)[1].lock).toBeNull();
    });

    it('without Link Analysis both network offers are locked, never hidden', () => {
        const offers = feedOffers(calls(), { ...STANDARD, flags: { geoLink: false, laWidgets: false } });
        expect(offers.slice(0, 2).map((o) => [o.id, o.lock])).toEqual([
            ['network', NO_LINK_ANALYSIS],
            ['network-widget', NO_LINK_ANALYSIS],
        ]);
    });

    it('a date and an amount offer a starter dashboard over the Dataset own columns; an id is no amount', () => {
        const sales = calls({
            defaultLinkMapping: undefined,
            columns: [
                { name: 'store_id', type: 'number', role: 'dimension' },
                { name: 'sold_on', type: 'date', role: 'dimension' },
                { name: 'total', type: 'number', role: 'dimension' },
            ],
        });
        expect(starterColumns(sales)).toEqual({ event_date: 'sold_on', amount: 'total' });
        const [o] = feedOffers(sales, BASIC);
        expect(o).toMatchObject({ id: 'starter-dashboard', action: 'Make a starter dashboard', lock: null });
        expect(o.because).toContain('Has a date (sold_on) and an amount (total)');
        // a Measure role wins over the first number; the link's measure over both
        expect(
            starterColumns({ ...sales, columns: [...sales.columns, { name: 'qty', type: 'number', role: 'measure' }] }),
        ).toEqual({ event_date: 'sold_on', amount: 'qty' });
        // only ids: no amount, no offer
        expect(starterColumns({ ...sales, columns: sales.columns.slice(0, 2) })).toBeNull();
        expect(feedOffers({ ...sales, columns: sales.columns.slice(0, 2) }, BASIC)).toEqual([]);
    });

    it('the starter is locked without a write root, or for a signed-in person without the right to author', () => {
        const sales = calls({
            defaultLinkMapping: undefined,
            columns: [
                { name: 'sold_on', type: 'date', role: 'temporal' },
                { name: 'total', type: 'number', role: 'measure' },
            ],
        });
        expect(feedOffers(sales, { ...BASIC, flags: { authoring: false } })[0].lock).toBe(NO_WRITE_ROOT);
        expect(feedOffers(sales, { ...BASIC, signIn: true, canAuthor: false })[0].lock).toBe(NO_AUTHOR_RIGHT);
        expect(feedOffers(sales, { ...BASIC, signIn: true, canAuthor: true })[0].lock).toBeNull();
    });

    it('counts rows and spans the time column in one /bi/query, read back by position', () => {
        expect(factsQuery(calls())).toEqual({
            dataset: 'calls',
            measures: [{ agg: 'count' }, { agg: 'min', field: 'started' }, { agg: 'max', field: 'started' }],
        });
        expect(factsQuery(calls({ defaultLinkMapping: undefined, columns: [] })).measures).toEqual([{ agg: 'count' }]);
        const f = factsOf({ count: 1234, min_started: '2026-09-01T00:00:00', max_started: '2026-09-28' }, [
            'count',
            'min_started',
            'max_started',
        ]);
        expect(f).toEqual({ rows: 1234, from: '2026-09-01', to: '2026-09-28' });
        expect(rowsWords(f)).toBe('1,234');
        expect(spanWords(f, true)).toBe('2026-09-01 – 2026-09-28');
        expect(spanWords(f, false)).toBe('No date column');
        expect(rowsWords(null)).toBe('Not counted');
        expect(spanWords(factsOf(undefined, []), true)).toBe('Not known yet');
    });

    it('Set aside: the kept files rejected rows and every refused file, each with why', () => {
        const a: Arrivals = {
            dataset: 'calls',
            eventDateColumn: null,
            truncated: false,
            reason: null,
            arrivals: [
                arrival({ file: 'b.csv', rowsRejected: 3 }),
                arrival({ file: 'old.csv', rowsRejected: 9, kept: false }), // replaced: its rows are gone
                arrival({ file: 'c.csv', rowsRejected: 1 }),
                arrival({
                    file: 'later.csv',
                    status: 'REFUSED',
                    reason: "it has no column 'charge'",
                    rowsRejected: null,
                }),
            ],
        };
        expect(setAsideLines(a)).toEqual([
            '4 rows were set aside: they did not match the column types.',
            "later.csv was refused: it has no column 'charge'.",
        ]);
        expect(setAsideLines({ ...a, arrivals: [arrival({})] })).toEqual([]);
        expect(setAsideLines(null)).toEqual([]);
    });
});
