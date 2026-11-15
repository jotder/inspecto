import { describe, expect, it } from 'vitest';
import {
    ARRIVALS_MIN_WIDTH,
    Arrival,
    ColumnState,
    OnboardDraft,
    adoptRedraft,
    arrivalColumns,
    arrivalRow,
    choiceKey,
    choiceStep,
    confirmAllLabel,
    initialColumns,
    initialRoles,
    lowChoices,
    proposalValue,
    summaryLines,
} from './add-data.model';
import { lowDraft, summary, transfersDraft } from './add-data.testing';
import { coverageLines } from './reference-coverage.component';
import { pick, plural } from './words';

/** The honest, forgiving import (LA-FULL-CYCLE-TEST-1 phase 3): findings 4, 5, 6 and 10 as pure rules. */
describe('the one plural helper (finding 10)', () => {
    it('agrees in number, with thousands separators', () => {
        expect(plural(1, 'row was', 'rows were')).toBe('1 row was');
        expect(plural(0, 'row was', 'rows were')).toBe('0 rows were');
        expect(plural(1200, 'row', 'rows')).toBe('1,200 rows');
        expect(pick(1, 'does', 'do')).toBe('does');
        expect(pick(2, 'does', 'do')).toBe('do');
    });

    it('the coverage of a details file: "137 appear in transfers; 1 does not"', () => {
        const coverage = {
            dataset: 'accounts',
            entityType: 'account',
            entityTypeLabel: 'Account',
            identifyingColumn: 'account_id',
            described: 138,
            datasets: [{ dataset: 'transfers', columns: ['from_account', 'to_account'], appear: 137, absent: 1 }],
            truncated: false,
        };
        expect(coverageLines(coverage)).toEqual([
            '138 Account entities described.',
            '137 appear in transfers; 1 does not.',
        ]);
        expect(
            coverageLines({ ...coverage, described: 1, datasets: [{ ...coverage.datasets[0], appear: 1, absent: 0 }] }),
        ).toEqual(['1 Account entity described.', '1 appears in transfers; 0 do not.']);
    });
});

describe('the result accounts for every row and value (finding 5)', () => {
    const dirty = summary({
        rowsIn: 461,
        rowsAfter: 461,
        rowsChanged: 461,
        index: { result: 'BUILT', edges: 460, nodes: 137 },
        blanked: [
            { column: 'booked_at', type: 'timestamp', values: 1, example: 'not-a-date' },
            { column: 'amount', type: 'number', values: 3, example: 'abc' },
        ],
        unlinked: { rows: 1, noFrom: 1, noTo: 0, from: 'from_account', to: 'to_account' },
        respelled: [{ column: 'from_account', values: 1, example: ' acc-1003 ', readAs: 'ACC-1003' }],
    });

    it('names the four dirty rows of the fixture, each with its reason, in the singular or the plural', () => {
        const lines = summaryLines(dirty).map((l) => l.text);
        expect(lines[0]).toBe('461 rows were read from transfers.csv; 461 rows were kept.');
        expect(lines).toContain(
            "1 value in booked_at was not a date with a time and was left blank (e.g. 'not-a-date').",
        );
        expect(lines).toContain("3 values in amount were not numbers and were left blank (e.g. 'abc').");
        expect(lines).toContain('1 row has no From (from_account), so it draws no link.');
        expect(lines).toContain(
            "1 value in from_account is a second spelling of an entity already there, differing only by spaces or letter case (e.g. ' acc-1003 ' is ACC-1003).",
        );
        // 461 rows kept, 1 without a From: the 460 links of the index line are now explained
        expect(lines).toContain('The link index was built: 460 links between 137 entities.');
    });

    it('a blank end on either side, and several rows', () => {
        const both = summaryLines({
            ...dirty,
            unlinked: { rows: 5, noFrom: 2, noTo: 4, from: 'payer', to: 'payee' },
        }).map((l) => l.text);
        expect(both).toContain('5 rows have no From (payer) or no To (payee), so they draw no link.');
    });

    it('a clean import says nothing about dirt', () => {
        const clean = summaryLines(
            summary({ blanked: [], respelled: [], unlinked: { rows: 0, noFrom: 0, noTo: 0, from: 'a', to: 'b' } }),
        );
        expect(clean.map((l) => l.tone)).not.toContain('warning');
        expect(clean.map((l) => l.text).join(' ')).not.toMatch(/left blank|draws no link|letter case/);
    });
});

describe('the Arrivals grid (finding 5)', () => {
    const arrival: Arrival = {
        file: 'transfers.csv',
        sender: 'upload',
        received: '2026-11-15T10:00:00',
        status: 'DONE',
        rowsRead: 461,
        rowsRejected: 0,
        mode: 'append',
        sha256: null,
        index: 'BUILT',
        consignment: 'b1',
        kept: true,
        coversFrom: '2026-01-01 10:58:10',
        coversTo: '2026-04-30 21:14:02',
        rowsLeftOut: 0,
    };

    it('Left out says 0 when nothing was left out, and Covers the period of the Time column', () => {
        const row = arrivalRow(arrival);
        expect(row.leftOut).toBe(0);
        expect(row.covers).toBe('2026-01-01 – 2026-04-30');
        expect(arrivalRow({ ...arrival, rowsLeftOut: 12, rowsRejected: 2, rowsFilteredOut: 10 }).leftOut).toBe(12);
        // not known (an older server, or a filter whose rows were not counted): blank, never a made-up 0
        expect(arrivalRow({ ...arrival, rowsLeftOut: null }).leftOut).toBeNull();
        const leftOut = arrivalColumns().find((c) => c.field === 'leftOut')!;
        const format = leftOut.valueFormatter as (p: { value: unknown }) => string;
        expect(format({ value: 0 })).toBe('0');
    });

    it('fits the Dataset card at 1440 px: the columns need no more than 770 px before the grid scrolls sideways', () => {
        const need = arrivalColumns().reduce((sum, c) => sum + (c.minWidth ?? 110), 0);
        expect(need).toBe(ARRIVALS_MIN_WIDTH);
        // the la-app page is max-w-4xl (896 px) less its padding and the card's: about 810 px for the grid
        expect(need).toBeLessThanOrEqual(810);
    });
});

describe('Confirm all (finding 6)', () => {
    it('a choice belongs to the step it is shown in, and the button always says how many', () => {
        expect(choiceStep(choiceKey.format('header'))).toBe('columns');
        expect(choiceStep(choiceKey.type('amount'))).toBe('columns');
        for (const k of [
            choiceKey.entityType('a'),
            choiceKey.role('a'),
            choiceKey.link('from'),
            choiceKey.primary,
            choiceKey.identifying,
        ])
            expect(choiceStep(k)).toBe('hold');
        expect(lowChoices(lowDraft(), true).map((p) => choiceStep(p.key))).toEqual(['columns', 'columns', 'hold']);
        expect(confirmAllLabel(16)).toBe('Confirm all 16 guesses');
        expect(confirmAllLabel(2)).toBe('Confirm all 2 guesses');
    });
});

describe('adoptRedraft - the server proposed the draft again (finding 4)', () => {
    /** `ts` read as text by the first draft: no Time, so the link has no When and says why. */
    function textTime(): OnboardDraft {
        const d = transfersDraft();
        d.columns[5] = {
            ...d.columns[5],
            type: { value: 'VARCHAR', evidence: 'the values are text', confidence: 'high' },
        };
        d.entityTypes.columns[5] = {
            column: 'ts',
            entityType: { value: null, evidence: 'neither the name nor the values', confidence: 'high' },
            role: { value: 'detail', evidence: 'ts is shown on the link', confidence: 'high' },
        };
        d.entityTypes.link!.primaryTime = {
            value: null,
            evidence: 'no column holds dates or times',
            confidence: 'low',
        };
        return d;
    }
    const state = (d: OnboardDraft): ColumnState[] => initialColumns(d);

    it('one edit: the stated type brings its role and the link time with it', () => {
        const old = textTime();
        const next = transfersDraft(); // the server's answer once ts is stated a Date and time
        const columns = state(old).map((c) => (c.name === 'ts' ? { ...c, type: 'TIMESTAMP' } : c));
        const out = adoptRedraft(old, next, columns, initialRoles(old), new Set(), new Set());
        expect(out.columns.find((c) => c.name === 'ts')).toMatchObject({ type: 'TIMESTAMP', role: 'temporal' });
        expect(out.roles.primaryTime).toBe('ts');
        expect(out.roles.from).toBe('from_account');
    });

    it('what the person changed stays: their own Entity Type, role and link choices are not proposed over', () => {
        const old = textTime();
        const next = transfersDraft();
        const columns = state(old).map((c) =>
            c.name === 'channel'
                ? { ...c, role: 'detail' as const }
                : c.name === 'txn_id'
                  ? { ...c, entityType: 'msisdn' }
                  : c,
        );
        const roles = { ...initialRoles(old), kind: '' };
        const out = adoptRedraft(old, next, columns, roles, new Set(['channel']), new Set());
        expect(out.columns.find((c) => c.name === 'channel')!.role).toBe('detail');
        expect(out.columns.find((c) => c.name === 'txn_id')!.entityType).toBe('msisdn');
        expect(out.roles.kind).toBe('');
        expect(out.roles.primaryTime).toBe('ts');
    });

    it('a confirmation stands only while what it confirmed is still proposed', () => {
        const old = lowDraft();
        const next = lowDraft();
        next.entityTypes.link = {
            ...next.entityTypes.link!,
            from: { ...next.entityTypes.link!.from, value: 'to_account' },
            to: { ...next.entityTypes.link!.to, value: 'from_account' },
        };
        const confirmed = new Set([choiceKey.format('header'), choiceKey.type('amount'), choiceKey.link('from')]);
        const out = adoptRedraft(old, next, state(old), initialRoles(old), new Set(), confirmed);
        expect([...out.confirmed]).toEqual([choiceKey.format('header'), choiceKey.type('amount')]);
        expect(proposalValue(old, choiceKey.link('from'))).not.toBe(proposalValue(next, choiceKey.link('from')));
    });
});
