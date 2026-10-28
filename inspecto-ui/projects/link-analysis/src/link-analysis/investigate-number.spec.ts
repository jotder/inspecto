import { convertToParamMap } from '@angular/router';
import { describe, expect, it } from 'vitest';
import type { InvestigationStepResult, WorkingSet, WorkingSetEntity } from '@inspecto/link-analysis/api/inv.service';
import { domainProfile } from '@inspecto/link-analysis/graph/domain-profile';
import { installTelecomFixture } from '@inspecto/link-analysis/graph/domain-profile.testing';
import {
    expandApproval,
    thresholdWords,
    DegreeOutcome,
    admittedHop,
    degreeOutcome,
    degreeOutcomesFromLog,
    isOneHop,
    isAwaiting,
    mergeRung,
    reconcileApprovals,
    degreeOutcomeMessage,
    degreeState,
    hubBadgeText,
    hubContactsFromRows,
    overriddenHubs,
    sealedHubThreshold,
    investigationTitle,
    overrideSteps,
    profileForDataset,
    requestedDegree,
    suspectTitle,
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
import { configureLaLimits, laLimit, resetLaLimits } from '@inspecto/link-analysis/link-analysis/la-limits';

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


// The SPA holds no industry profile of its own (LA-EDITIONS-1): the telecom pack arrives from the server.
installTelecomFixture();

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
            datasetId: 'telecom_links_dataset',
            sourceCol: 'a_msisdn',
            targetCol: 'b_msisdn',
            linkKindCol: 'link_kind',
            entityType: 'msisdn',
        });
        expect(b.timeCol).toBe('last_seen');
        expect(b.eventsCol).toBe('events');
        expect(b.presets).toEqual({ windowDays: 30, minEvents: 1, maxFanOut: 500, budget: 10000 });
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

    it('leaves high-connectivity entities out of the frontier unless the analyst opts in (telecom S3)', () => {
        const hub = { ...entity('h', 1), highConnectivity: true };
        const state = degreeState(ws(entity('s', 0), entity('a', 1), hub), 4);
        expect(state).toMatchObject({ current: 1, frontier: ['a'], heldHubs: ['h'], blocked: null });
        expect(degreeState(ws(entity('s', 0), entity('a', 1), hub), 4, [], true).frontier).toEqual(['a', 'h']);
        // Every outer entity flagged: nothing to name, and the way out is stated.
        expect(degreeState(ws(entity('s', 0), hub), 4).blocked).toContain('Hold supernodes');
        expect(degreeState(ws(entity('s', 0), hub), 4, [], true).blocked).toBeNull();
    });

    it('a degree held for its fan-out says what to do; one held for its budget does not offer a fan-out', () => {
        const held = { degree: 1, admitted: 0, linksAdded: 0, truncated: false, fanOutCapped: 0, awaitingApproval: 'p1' };
        const fan = degreeOutcomeMessage({ ...held, heldFor: ['maxFanOut unbounded (threshold 2000)'] });
        expect(fan).toContain('a second person approves request p1');
        expect(fan).toContain('four_eyes_fan_out_above');
        expect(fan).toContain('Ask an approver, or use Expand… with a fan-out at or below the threshold.');
        expect(degreeOutcomeMessage({ ...held, heldFor: ['budget 500 > 100'] })).not.toContain('Ask an approver');
    });

    it('words the hub badge with the threshold when known, never a guessed contact count', () => {
        expect(hubBadgeText(500)).toBe('High connectivity — more than 500 contacts, not expanded');
        expect(hubBadgeText(null)).toBe('High connectivity — not expanded');
        // The entity's own sealed count, when served; null (old logs) falls back to the threshold wording.
        expect(hubBadgeText(500, 650)).toBe('High connectivity — 650 contacts (threshold 500), not expanded');
        expect(hubBadgeText(null, 1200)).toBe('High connectivity — 1,200 contacts, not expanded');
        expect(hubBadgeText(500, null)).toBe('High connectivity — more than 500 contacts, not expanded');
        const base = { degree: 2, admitted: 1, linksAdded: 1, truncated: false, fanOutCapped: 0, hubsFlagged: 1 };
        expect(degreeOutcomeMessage({ ...base, hubDegreeSource: 'index' })).toContain(
            '1 high-connectivity number shown but not expanded (contacts counted from the link index).',
        );
        expect(degreeOutcomeMessage({ ...base, hubDegreeSource: 'flat' })).toContain(
            '(contacts counted from the Dataset)',
        );
        expect(degreeOutcomeMessage(base)).toContain('1 high-connectivity number shown but not expanded.');
        expect(
            degreeOutcomeMessage({
                degree: 2,
                admitted: 1,
                linksAdded: 1,
                truncated: false,
                fanOutCapped: 0,
                hubsOverridden: 2,
            }),
        ).toContain('Analyst override: expanded through 2 high-connectivity numbers — recorded in the log.');
    });

    it('is blocked above the server frontier cap', () => {
        const many = Array.from({ length: laLimit('investigation.max_frontier') + 1 }, (_, i) => entity(`n${i}`, 2));
        expect(degreeState(ws(entity('s', 0), ...many), 4).blocked).toContain(`one expand names at most ${laLimit('investigation.max_frontier').toLocaleString('en-US')}`);
    });

    it('follows the investigation.max_frontier / expand_budget_default limits in force (LA-CONFIG-LIMITS-1)', () => {
        configureLaLimits({ 'investigation.max_frontier': 2, 'investigation.expand_budget_default': 300 });
        try {
            const three = [entity('n1', 2), entity('n2', 2), entity('n3', 2)];
            expect(degreeState(ws(entity('s', 0), ...three), 4).blocked).toContain('one expand names at most 2.');
            expect(expandApproval({}, { budgetAbove: 299, fanOutAbove: null }).budget).toBe(300);
        } finally {
            resetLaLimits();
        }
    });

    it('anchors the window at the Dataset latest event time, never today; no end = no window', () => {
        const p = domainProfile('telecom').investigate!.expand;
        expect(presetRung(p, true, '2026-09-29T18:30:00.000Z')).toEqual({
            budget: 10000,
            minEvents: 1,
            maxFanOut: 500,
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
        expect(latestTimeSql('tele"links', 'last_seen')).toBe(
            `SELECT strftime(MAX("last_seen"), '%Y-%m-%dT%H:%M:%S') AS latest FROM "tele""links"`,
        );
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

describe('investigate-number - live-run fixes', () => {
    it('the Advanced rung wins over the preset rung field by field; the hub opt-in never rides along', () => {
        expect(
            mergeRung(
                { minEvents: 2, maxFanOut: 50, budget: 2000 },
                { minEvents: 1, includeHubs: true, expandHubs: ['h'] },
            ),
        ).toEqual({ minEvents: 1, maxFanOut: 50, budget: 2000 });
        expect(mergeRung({ budget: 5 }, null)).toEqual({ budget: 5 });
    });

    it('a degree is the outermost hop its step admitted, not the count of steps', () => {
        const set = ws(entity('s', 0), { ...entity('x', 3), admittedBy: 9 });
        expect(admittedHop(set, 9)).toBe(3);
        expect(admittedHop(set, 4)).toBeNull();
        const r = { step: 9, op: 'expand', delta: { admitted: ['x'], linksAdded: 1 }, truncated: false } as never;
        expect(degreeOutcome(2, r, {}, null, set).degree).toBe(3);
        expect(degreeOutcome(2, r, {}).degree).toBe(2);
    });

    it('reconciles a held degree with the log: unchanged while pending, then approved or denied', () => {
        const held: DegreeOutcome = {
            degree: 1,
            admitted: 0,
            linksAdded: 0,
            truncated: false,
            fanOutCapped: 0,
            awaitingApproval: 'p2',
        };
        const req = (status: 'pending' | 'approved' | 'denied', step?: number) => [{ id: 'p2', status, step }] as never;
        const outcomes = [held];
        expect(reconcileApprovals(outcomes, req('pending'), ws())).toBe(outcomes);
        expect(isAwaiting(held)).toBe(true);
        const approved = reconcileApprovals(outcomes, req('approved', 4), ws({ ...entity('a', 1), admittedBy: 4 }));
        expect(approved[0]).toMatchObject({ decision: 'approved', admitted: 1, degree: 1 });
        expect(isAwaiting(approved[0])).toBe(false);
        expect(reconcileApprovals(outcomes, req('denied'), ws())[0].decision).toBe('denied');
    });
});

describe('investigate-number - third live run fixes', () => {
    const at = (id: string, hop: number, admittedBy: number, flagged = false): WorkingSetEntity => ({
        ...entity(id, hop),
        admittedBy,
        ...(flagged ? { highConnectivity: true } : {}),
    });

    it("reads the flagged entities' contact counts from the Working Set rows", () => {
        const m = hubContactsFromRows([
            { entityId: 'h', highConnectivity: true, contacts: 650 },
            { entityId: 'a', highConnectivity: false, contacts: null },
            { entityId: 7, contacts: 3 },
        ]);
        expect([...m]).toEqual([['h', 650]]);
        expect(hubBadgeText(500, m.get('h'))).toBe('High connectivity — 650 contacts (threshold 500), not expanded');
    });

    it('labels a held and an approved "Expand anyway" by the same hop: one past the hub', () => {
        const set = ws(at('s', 0, 1), at('h', 2, 2, true), at('r', 3, 3));
        const pending = [{ id: 'p4', status: 'pending', params: { ids: ['h'], expandHubs: ['h'] } }] as never;
        const held = reconcileApprovals([], pending, set);
        expect(held[0]).toMatchObject({ degree: 3, awaitingApproval: 'p4' });
        expect(degreeOutcomeMessage(held[0])).toMatch(/^Expand anyway through h \(admits at degree 3\): waiting/);
        const approved = reconcileApprovals(
            held,
            [{ id: 'p4', status: 'approved', step: 5 }] as never,
            ws(...set.entities, at('n', 3, 5)),
        );
        expect(degreeOutcomeMessage(approved[0])).toMatch(
            /^Expand anyway through h \(admits at degree 3\): approved \(request p4\)/,
        );
        expect(requestedDegree(set, ['h'])).toBe(3);
        expect(requestedDegree(set, ['nobody'])).toBeNull();
    });

    it("keeps a hub override's admitted contacts out of the next degree's frontier", () => {
        const entries = [
            { step: 3, kind: 'op', op: 'expand', params: { ids: ['a'] }, undoneBy: null },
            { step: 4, kind: 'op', op: 'expand', params: { ids: ['h'], expandHubs: ['h'] }, undoneBy: null },
            { step: 5, kind: 'op', op: 'expand', params: { ids: ['x'], expandHubs: ['x'] }, undoneBy: 6 },
        ] as never;
        const overrides = overrideSteps(entries);
        expect([...overrides]).toEqual([4]);
        const set = ws(at('s', 0, 1), at('h', 1, 2, true), at('r', 2, 3), at('bg1', 2, 4), at('bg2', 2, 4));
        expect(degreeState(set, 4).frontier).toEqual(['r', 'bg1', 'bg2']);
        expect(degreeState(set, 4, [], false, overrides)).toMatchObject({ current: 2, frontier: ['r'] });
        // only override contacts left: they are the frontier rather than nothing
        expect(degreeState(ws(at('bg', 1, 4)), 4, [], false, overrides).frontier).toEqual(['bg']);
    });

    it('never puts the raw number in a default title unless masking is known to be off', () => {
        expect(suspectTitle('99979100001')).toBe('Suspect number');
        expect(suspectTitle('99979100001', 'typed')).toBe('Suspect number');
        expect(suspectTitle('99979100001', 'none')).toBe('Suspect 99979100001');
    });

    it('names the Investigation by the typed title when the server masked it, never by the pseudonym', () => {
        expect(investigationTitle('Case A', 'Case A', 'inv-1')).toBe('Case A');
        expect(investigationTitle('masked:0123abcd', 'Suspect 99979100001', 'inv-1')).toBe('Suspect 99979100001');
        expect(investigationTitle('masked:0123abcd', undefined, 'inv-1')).toBe('(title masked)');
        expect(investigationTitle('masked:0123abcd', 'masked:0123abcd', 'inv-1')).toBe('(title masked)');
        expect(investigationTitle(null, null, 'inv-1')).toBe('inv-1');
    });

    it('finds the profile whose default mapping binds a Dataset', () => {
        expect(profileForDataset('telecom_links_dataset')).toBe('telecom');
        expect(profileForDataset('something_else')).toBeNull();
        expect(profileForDataset(null)).toBeNull();
    });
});

describe('investigate-number - fourth live run fixes', () => {
    it('D1: the window end is read as a zone-free string and parsed as UTC, whatever the host zone', () => {
        // the seed corpus' latest last_seen; strftime answers it without any zone, so no host offset can enter
        const end = latestTimeOf('2026-09-29T23:57:59');
        expect(end).toBe('2026-09-29T23:57:59.000Z');
        const rung = presetRung(domainProfile('telecom').investigate!.expand, true, end);
        // inclusive end: `to` is exclusive, so it sits one second after the latest event
        expect(rung.window).toEqual({ from: '2026-08-30T23:57:59.000Z', to: '2026-09-29T23:58:00.000Z' });
        const lastLink = Date.parse('2026-09-29T23:57:59Z');
        const w = rung.window as { from: string; to: string };
        expect(lastLink >= Date.parse(w.from) && lastLink < Date.parse(w.to)).toBe(true);
        expect(latestTimeSql('telecom_links', 'last_seen')).not.toContain('SELECT MAX("last_seen") AS');
    });

    it('D4: the telecom preset reaches the documented ladder without four-eyes', () => {
        const p = domainProfile('telecom').investigate!.expand;
        // the suspect's 403 candidate rows all fit, so the 1-event shared_device link to WANGIRI-B survives degree 1
        expect(p.maxFanOut).toBeGreaterThanOrEqual(403);
        // the busiest degree of the hub-held ladder reads ~4 700 rows
        expect(p.budget).toBeGreaterThanOrEqual(5000);
        // the showcase's four_eyes_fan_out_above
        expect(p.maxFanOut).toBeLessThanOrEqual(2000);
    });

    it('pluralises the events floor', () => {
        expect(presetsSummary({ minEvents: 1 })).toBe('at least 1 event per link');
        expect(presetsSummary({ minEvents: 3 })).toBe('at least 3 events per link');
    });

    it('D3: a hub named by an applied or approved override is expanded through, read from the log', () => {
        const entries = [
            { step: 1, kind: 'op', op: 'expand', undoneBy: null, params: { ids: ['s'], hubThreshold: 500 } },
            { step: 2, kind: 'op', op: 'expand', undoneBy: null, params: { ids: ['h'], expandHubs: ['h'] } },
            { step: 3, kind: 'op', op: 'expand', undoneBy: 4, params: { ids: ['g'], expandHubs: ['g'] } },
            { step: 5, kind: 'op', op: 'expand', undoneBy: null, params: { ids: ['k', 'x'], includeHubs: true } },
        ] as never;
        expect([...overriddenHubs(entries, new Set(['h', 'g', 'k']))].sort()).toEqual(['h', 'k']);
        expect(sealedHubThreshold(entries)).toBe(500);
        expect(sealedHubThreshold([] as never)).toBeNull();
        expect(hubBadgeText(500, 650, true)).toBe(
            'High connectivity — 650 contacts (threshold 500), expanded through override',
        );
    });

    it('D3: reads a contact count served as a string', () => {
        expect(
            hubContactsFromRows([
                { entityId: 'h', contacts: '650' },
                { entityId: 'y', contacts: null },
            ]).get('h'),
        ).toBe(650);
    });

    it('names the hub and the degree an override admits at, held and approved alike', () => {
        const o = {
            degree: 3,
            admitted: 0,
            linksAdded: 0,
            truncated: false,
            fanOutCapped: 0,
            awaitingApproval: 'r1',
            override: 'h',
        };
        expect(degreeOutcomeMessage(o)).toMatch(
            /^Expand anyway through h \(admits at degree 3\): waiting for approval/,
        );
        expect(degreeOutcomeMessage({ ...o, decision: 'approved', admitted: 2 })).toMatch(
            /^Expand anyway through h \(admits at degree 3\): approved \(request r1\)/,
        );
        const r = reconcileApprovals(
            [],
            [{ id: 'r2', status: 'pending', params: { ids: ['h'], expandHubs: ['h'] } }] as never,
            ws(entity('s', 0), entity('h', 2)),
        );
        expect(r[0]).toMatchObject({ override: 'h', degree: 3, awaitingApproval: 'r2' });
    });
});

describe('investigate-number - walk-through 6 fixes', () => {
    const at = (id: string, hop: number, step: number): WorkingSetEntity => ({ ...entity(id, hop), admittedBy: step });

    it('D3: rebuilds the per-degree lines from the sealed log after a reopen', () => {
        const set: WorkingSet = {
            entities: [at('s', 0, 1), at('a', 1, 2), at('b', 1, 2), at('c', 2, 3)],
            links: [
                { admittedBy: 2 } as never,
                { admittedBy: 2 } as never,
                { admittedBy: 3 } as never,
            ],
            excluded: [],
            hash: '',
        };
        const window = { from: '2026-08-30T23:57:59.000Z', to: '2026-09-29T23:58:00.000Z' };
        const entries = [
            { step: 1, kind: 'op', op: 'seed', undoneBy: null, params: { ids: ['s'] } },
            { step: 2, kind: 'op', op: 'expand', undoneBy: null, params: { ids: ['s'], window, budget: 5000 } },
            { step: 3, kind: 'op', op: 'expand', undoneBy: null, params: { ids: ['a', 'b'], window: 'full' }, read: { truncated: true } },
            { step: 4, kind: 'op', op: 'expand', undoneBy: 5, params: { ids: ['c'] } },
        ] as never;
        const out = degreeOutcomesFromLog(entries, set);
        expect(out.map(degreeOutcomeMessage)).toEqual([
            'Degree 1 (30 days to 29 Sep 2026): 2 new entities, 2 links added.',
            'Degree 2 (all available data): 1 new entity, 1 link added. The read stopped at its budget — some neighbours are missing.',
        ]);
        expect(out[0].budget).toBe(5000);
    });

    it('rebuilds the fan-out and hub counts from the sealed read, worded like the live line', () => {
        const hub = { ...at('h', 1, 2), highConnectivity: true };
        const set: WorkingSet = { entities: [at('s', 0, 1), hub, at('x', 1, 2)], links: [], excluded: [], hash: '' };
        const entries = [
            { step: 1, kind: 'op', op: 'seed', undoneBy: null, params: { ids: ['s'] } },
            {
                step: 2,
                kind: 'op',
                op: 'expand',
                undoneBy: null,
                params: { ids: ['s'], maxFanOut: 10 },
                read: {
                    truncated: false,
                    fanOutCapped: 4,
                    hubs: [{ id: 'h', degree: 900 }, { id: 'gone', degree: 800 }],
                    hubDegreeSource: 'index',
                },
            },
        ] as never;
        const [o] = degreeOutcomesFromLog(entries, set);
        expect(o.fanOutCapped).toBe(4);
        expect(o.hubsFlagged).toBe(1);
        const live = degreeOutcomeMessage({ ...o, hubsFlagged: 1, hubDegreeSource: 'index', fanOutCapped: 4 });
        expect(degreeOutcomeMessage(o)).toBe(live);
        expect(degreeOutcomeMessage(o)).toContain('weaker links were');
        expect(degreeOutcomeMessage(o)).toContain('high-connectivity number');
    });

    it('rebuilds the held-back frontier hub count from the log view, worded like the live line', () => {
        const set: WorkingSet = { entities: [at('s', 0, 1), at('x', 1, 2)], links: [], excluded: [], hash: '' };
        const entries = [
            { step: 1, kind: 'op', op: 'seed', undoneBy: null, params: { ids: ['s'] } },
            { step: 2, kind: 'op', op: 'expand', undoneBy: null, params: { ids: ['s'] }, read: { truncated: false, hubsHeld: 2 } },
        ] as never;
        const [o] = degreeOutcomesFromLog(entries, set);
        expect(o.hubsHeld).toBe(2);
        expect(degreeOutcomeMessage(o)).toBe(degreeOutcomeMessage({ ...o, hubsHeld: 2 }));
        expect(degreeOutcomeMessage(o)).toContain('2 high-connectivity numbers of the frontier not expanded from.');
        const [none] = degreeOutcomesFromLog([{ step: 1, kind: 'seed' }, { step: 2, kind: 'op', op: 'expand', undoneBy: null, params: { ids: ['s'] }, read: { truncated: false } }] as never, set);
        expect(none.hubsHeld).toBeUndefined();
    });

    it('labels a held one-hop expand by what it expands from, never as the ladder degree', () => {
        const set = ws(entity('s', 0), entity('a', 1), entity('b', 2));
        expect(isOneHop(set, ['s'])).toBe(true);
        expect(isOneHop(set, ['b'])).toBe(false);
        expect(isOneHop(set, undefined)).toBe(true);
        const r = reconcileApprovals(
            [],
            [{ id: 'r9', status: 'pending', params: { ids: ['s'] } }] as never,
            set,
        );
        expect(degreeOutcomeMessage(r[0])).toMatch(/^Expand one hop from s: waiting for approval/);
        const approved: DegreeOutcome = { ...r[0], decision: 'approved', admitted: 3 };
        expect(degreeOutcomeMessage(approved)).toMatch(/^Expand one hop from s: approved \(request r9\)/);
        // the ladder keeps its own line
        const ladder = reconcileApprovals([], [{ id: 'r8', status: 'pending', params: { ids: ['b'] } }] as never, set);
        expect(degreeOutcomeMessage(ladder[0])).toMatch(/^Degree 3: waiting for approval/);
    });
});

describe('investigate-number - four-eyes clarity (LA-EXPAND-FOUREYES-CLARITY-1)', () => {
    const none = { budgetAbove: null, fanOutAbove: null };
    // spaces/_templates/la-showcase/config/link-analysis.toon: four_eyes_fan_out_above 2000, no budget threshold
    const showcase = { budgetAbove: null, fanOutAbove: 2000 };

    it('a default one-hop expand is never held under the shipped defaults (no threshold set)', () => {
        const a = expandApproval({}, none);
        expect(a).toEqual({ budget: laLimit('investigation.expand_budget_default'), fanOut: null, exceeded: [] });
        expect(laLimit('investigation.expand_budget_default')).toBe(4000); // CL-10: doubled
    });

    it("the telecom profile's presets stay under la-showcase's fan-out threshold", () => {
        const p = domainProfile('telecom').investigate!.expand;
        expect(expandApproval({ budget: p.budget, maxFanOut: p.maxFanOut }, showcase).exceeded).toEqual([]);
    });

    it('a blank fan-out cap or a larger one is held where a fan-out threshold is set, and says which limit', () => {
        expect(expandApproval({}, showcase).exceeded).toEqual([
            'fan-out is unbounded, above 2,000 (four_eyes_fan_out_above)',
        ]);
        expect(expandApproval({ maxFanOut: 2000 }, showcase).exceeded).toEqual([]);
        expect(expandApproval({ maxFanOut: 2001 }, showcase).exceeded[0]).toContain('four_eyes_fan_out_above');
        const b = expandApproval({ budget: 5000, maxFanOut: 10 }, { budgetAbove: 1000, fanOutAbove: 2000 });
        expect(b.exceeded).toEqual(['row budget 5,000 is above 1,000 (four_eyes_budget_above)']);
    });

    it('a held expand names the threshold crossed and the Settings key', () => {
        expect(thresholdWords(['budget 500 > 100', 'maxFanOut unbounded (threshold 2000)'])).toBe(
            'budget 500 > 100, set by four_eyes_budget_above; maxFanOut unbounded (threshold 2000), set by four_eyes_fan_out_above - Settings > Link Analysis',
        );
    });

    it('the waiting text from a held request carries its reasons', () => {
        const r = reconcileApprovals(
            [],
            [{ id: 'r1', status: 'pending', params: { ids: ['s'] }, sensitivity: { exceeded: ['budget 500 > 100'] } }] as never,
            ws(entity('s', 0)),
        );
        const text = degreeOutcomeMessage(r[0]);
        expect(text).toContain('budget 500 > 100, set by four_eyes_budget_above');
        expect(text).toContain('Settings > Link Analysis');
    });
});
