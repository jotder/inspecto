import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';
import type { InvestigationStepResult, WorkingSet, WorkingSetEntity } from '@inspecto/link-analysis/api/inv.service';
import { domainProfile } from '@inspecto/link-analysis/graph/domain-profile';
import {
    DegreeOutcome,
    MAX_EXPAND_FRONTIER,
    degreeOutcome,
    degreeOutcomeMessage,
    degreeState,
    investigableEntityOf,
    investigateBinding,
    investigateQueryParams,
    msisdnError,
    normaliseMsisdn,
    parseInvestigateParams,
    latestTimeOf,
    latestTimeSql,
    missingMappedColumns,
    presetRung,
    presetsSummary,
    sameInvestigateRequest,
    seedError,
    windowLabel,
} from './investigate-number';

const entity = (id: string, hop: number, hidden = false): WorkingSetEntity => ({
    id,
    type: 'msisdn',
    hop,
    seed: 's',
    admittedBy: 1,
    hidden,
    kept: false,
});
const ws = (...entities: WorkingSetEntity[]): WorkingSet => ({ entities, links: [], excluded: [], hash: '' });

describe('investigate-number - deep link parameters', () => {
    it('reads seed, entityType and dataset; entityType defaults to msisdn', () => {
        expect(
            parseInvestigateParams(convertToParamMap({ seed: ' 966501 ', entityType: 'msisdn', dataset: 'cdr' })),
        ).toEqual({ seed: '966501', entityType: 'msisdn', dataset: 'cdr' });
        expect(parseInvestigateParams(convertToParamMap({ seed: '966501' }))).toEqual({
            seed: '966501',
            entityType: 'msisdn',
        });
    });

    it('is null without a usable seed (absent, blank, or not an id)', () => {
        expect(parseInvestigateParams(convertToParamMap({}))).toBeNull();
        expect(parseInvestigateParams(convertToParamMap({ seed: '  ', entityType: 'msisdn' }))).toBeNull();
        expect(parseInvestigateParams(convertToParamMap({ seed: 'x'.repeat(201) }))).toBeNull();
        expect(parseInvestigateParams(convertToParamMap({ case: 'C-1' }))).toBeNull();
    });

    it('compares requests by value, so an unrelated param change is not a new request', () => {
        const a = parseInvestigateParams(convertToParamMap({ seed: '1', case: 'C-1' }));
        const b = parseInvestigateParams(convertToParamMap({ seed: '1', case: 'C-2' }));
        expect(sameInvestigateRequest(a, b)).toBe(true);
        expect(sameInvestigateRequest(a, parseInvestigateParams(convertToParamMap({ seed: '2' })))).toBe(false);
        expect(sameInvestigateRequest(null, null)).toBe(true);
    });

    it('builds the query params the host pages link with', () => {
        expect(investigateQueryParams('966501', 'msisdn')).toEqual({ seed: '966501', entityType: 'msisdn' });
        expect(investigateQueryParams('966501', 'msisdn', 'cdr')).toEqual({
            seed: '966501',
            entityType: 'msisdn',
            dataset: 'cdr',
        });
    });
});

describe('investigate-number - MSISDN validation', () => {
    it.each(['966501234567', '+966501234567', '+966 50 123-4567', '(050) 123.4567', '123456'])('accepts %s', (v) =>
        expect(msisdnError(v)).toBeNull(),
    );
    it.each([
        ['', 'Enter a number.'],
        ['   ', 'Enter a number.'],
        ['12345', 'A number is 6 to 15 digits, optionally starting with +.'],
        ['1234567890123456', 'A number is 6 to 15 digits, optionally starting with +.'],
        ['96650abc', 'A number is 6 to 15 digits, optionally starting with +.'],
        ['96+6501234', 'A number is 6 to 15 digits, optionally starting with +.'],
    ])('refuses %j', (v, message) => expect(msisdnError(v)).toBe(message));

    it('normalises separators away and keeps the +', () => {
        expect(normaliseMsisdn(' +966 50-123.45(67) ')).toBe('+966501234567');
    });
});

describe('investigate-number - the telecom binding', () => {
    it('binds an msisdn to the telecom profile default mapping and presets', () => {
        const b = investigateBinding({ seed: '966501', entityType: 'msisdn' })!;
        expect(b.profileId).toBe('telecom');
        expect(b.projection).toEqual({
            datasetId: 'telecom_links',
            sourceCol: 'a_msisdn',
            targetCol: 'b_msisdn',
            linkKindCol: 'link_kind',
            entityType: 'msisdn',
        });
        expect(b.timeCol).toBe('last_seen');
        expect(b.eventsCol).toBe('events');
        expect(b.presets).toEqual({ windowDays: 30, minEvents: 2, maxFanOut: 50, budget: 2000 });
        expect(b.maxDegree).toBe(4);
        expect(b.presets).toBe(domainProfile('telecom').investigate!.expand); // ONE home for the presets
    });

    it('lets the deep link name another Dataset; an unknown entity type binds nothing', () => {
        expect(investigateBinding({ seed: '1', entityType: 'MSISDN', dataset: 'cdr' })!.projection.datasetId).toBe(
            'cdr',
        );
        expect(investigateBinding({ seed: '1', entityType: 'iban' })).toBeNull();
    });

    it('finds the investigable entity on a record only in a keyed Alert Rule key attribute', () => {
        expect(investigableEntityOf({ rule: 'r', 'key.a_msisdn': '966501', key: 'a_msisdn=966501' })).toEqual({
            seed: '966501',
            entityType: 'msisdn',
        });
        expect(investigableEntityOf({ msisdn: '966501' })).toBeNull(); // not a key attribute
        expect(investigableEntityOf({ 'key.entity_key': 'E-1' })).toBeNull(); // no profile names that type
        expect(investigableEntityOf({ 'key.msisdn': 'NULL' })).toBeNull();
        expect(investigableEntityOf(undefined)).toBeNull();
    });
});

describe('investigate-number - next degree', () => {
    it('expands from the visible entities at the outermost hop', () => {
        const s = degreeState(ws(entity('s', 0), entity('a', 1), entity('b', 1), entity('c', 1, true)), 4);
        expect(s.current).toBe(1);
        expect(s.frontier).toEqual(['a', 'b']);
        expect(s.blocked).toBeNull();
    });

    it('a seed alone is degree 0, its frontier the seed', () => {
        expect(degreeState(ws(entity('s', 0)))).toMatchObject({ current: 0, frontier: ['s'], blocked: null });
    });

    it('is blocked on an empty Working Set and at the maximum degree', () => {
        expect(degreeState(ws(), 4).blocked).toBe('Seed an entity first.');
        expect(degreeState(null).blocked).toBe('Seed an entity first.');
        expect(degreeState(ws(entity('s', 0), entity('d', 4)), 4)).toMatchObject({
            current: 4,
            blocked: 'Degree 4 reached.',
        });
    });

    it('is blocked when the last expand of the next degree found nobody new', () => {
        const none: DegreeOutcome = { degree: 2, admitted: 0, linksAdded: 0, truncated: false, fanOutCapped: 0 };
        expect(degreeState(ws(entity('s', 0), entity('a', 1)), 4, [none]).blocked).toBe(
            'Degree 2 found no new entities — there is nothing further to expand.',
        );
        // an earlier empty degree does not block a later one
        expect(degreeState(ws(entity('s', 0), entity('a', 1), entity('b', 2)), 4, [none]).blocked).toBeNull();
    });

    it('is blocked above the server frontier cap', () => {
        const many = Array.from({ length: MAX_EXPAND_FRONTIER + 1 }, (_, i) => entity(`n${i}`, 2));
        expect(degreeState(ws(entity('s', 0), ...many), 4).blocked).toContain('one expand names at most 1,000');
    });

    it('anchors the window at the Dataset latest event time, never today; no end = no window', () => {
        const p = domainProfile('telecom').investigate!.expand;
        expect(presetRung(p, true, '2026-09-29T18:30:00.000Z')).toEqual({
            budget: 2000,
            minEvents: 2,
            maxFanOut: 50,
            window: { from: '2026-08-30T18:30:00.000Z', to: '2026-09-29T18:30:01.000Z' },
        });
        expect(presetRung(p, false, '2026-09-29T18:30:00.000Z')).not.toHaveProperty('window');
        expect(presetRung(p, true, null)).not.toHaveProperty('window');
        expect(presetRung({}, true, '2026-09-29T18:30:00.000Z')).toEqual({});
    });

    it('says what the window covers honestly', () => {
        const p = { windowDays: 30 };
        expect(windowLabel(p, '2026-09-29T18:30:00.000Z')).toBe('30 days to 29 Sep 2026');
        expect(windowLabel(p, null)).toBe('all available data');
        expect(windowLabel({}, '2026-09-29T18:30:00.000Z')).toBeNull();
        expect(presetsSummary({ windowDays: 30, budget: 2000 }, null)).toBe(
            'all available data, 2,000 rows per degree',
        );
    });

    it('reads the latest event time with quoted identifiers and parses DuckDB timestamps', () => {
        expect(latestTimeSql('tele"links', 'last_seen')).toBe('SELECT MAX("last_seen") AS latest FROM "tele""links"');
        expect(latestTimeOf('2026-09-29 18:30:00')).toBe('2026-09-29T18:30:00.000Z');
        expect(latestTimeOf(Date.parse('2026-09-29T00:00:00Z'))).toBe('2026-09-29T00:00:00.000Z');
        expect(latestTimeOf(null)).toBeNull();
        expect(latestTimeOf('not a time')).toBeNull();
    });

    it('checks a named Dataset for the mapped columns and a deep-link seed for the MSISDN shape', () => {
        const b = investigateBinding({ seed: '966501', entityType: 'msisdn', dataset: 'x' })!;
        expect(missingMappedColumns(b, ['A_MSISDN', 'b_msisdn', 'link_kind', 'last_seen', 'EVENTS'])).toEqual([]);
        expect(missingMappedColumns(b, ['a_msisdn', 'b_msisdn'])).toEqual(['link_kind', 'last_seen', 'events']);
        expect(seedError({ seed: '12ab', entityType: 'msisdn' })).toBe(
            'A number is 6 to 15 digits, optionally starting with +.',
        );
        expect(seedError({ seed: '+966 50 123 4567', entityType: 'msisdn' })).toBeNull();
        expect(seedError({ seed: 'ACC-1', entityType: 'account' })).toBeNull();
    });

    it('notes hub suppression and the anchored range on the degree line', () => {
        const step = {
            step: 2,
            op: 'expand',
            delta: { admitted: ['a'], removed: [], linksAdded: 1, linksRemoved: 0, hidden: [], kept: [], excluded: [] },
            truncated: false,
            read: { rowCount: 1, fingerprint: '', readAt: '', hubsFlagged: 1, rung: { hubsHeld: ['h1', 'h2'] } },
            workingSet: { entities: 2, links: 1, excluded: 0, hash: '' },
        } as InvestigationStepResult;
        expect(degreeOutcomeMessage(degreeOutcome(1, step, {}, '30 days to 29 Sep 2026'))).toBe(
            'Degree 1 (30 days to 29 Sep 2026): 1 new entity, 1 link added. 1 high-connectivity number shown but not ' +
                'expanded. 2 high-connectivity numbers of the frontier not expanded from.',
        );
    });

    it('states a degree in plain words, truncation and the fan-out cap included', () => {
        const step = {
            step: 3,
            op: 'expand',
            delta: {
                admitted: ['a', 'b'],
                removed: [],
                linksAdded: 5,
                linksRemoved: 0,
                hidden: [],
                kept: [],
                excluded: [],
            },
            truncated: true,
            read: { rowCount: 2000, fingerprint: '', readAt: '', fanOutCapped: 12 },
            workingSet: { entities: 3, links: 5, excluded: 0, hash: '' },
        } as InvestigationStepResult;
        const o = degreeOutcome(2, step, { budget: 2000, maxFanOut: 50 });
        expect(o).toEqual({
            degree: 2,
            admitted: 2,
            linksAdded: 5,
            truncated: true,
            fanOutCapped: 12,
            budget: 2000,
            maxFanOut: 50,
            hubsFlagged: 0,
            hubsHeld: 0,
        });
        expect(degreeOutcomeMessage(o)).toBe(
            'Degree 2: 2 new entities, 5 links added. The read stopped at its budget of 2,000 rows — some ' +
                'neighbours are missing. 12 weaker links were left out: each entity keeps its 50 strongest links.',
        );
        expect(degreeOutcomeMessage({ ...o, admitted: 1, linksAdded: 1, truncated: false, fanOutCapped: 0 })).toBe(
            'Degree 2: 1 new entity, 1 link added.',
        );
    });
});
