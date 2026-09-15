import { describe, expect, it } from 'vitest';
import {
    buildReconciliation,
    CompareColumn,
    ageBucketOf,
    breakId,
    breakAgeDays,
    mergeBreaks,
    matchedKeyCount,
    ReconBreak,
    resolveBreak,
    runReconciliation,
    summarize,
    withinTolerance,
} from './reconciliation-types';

const KEYS = ['id'];
const COST_ABS: CompareColumn = { column: 'cost_usd', toleranceType: 'absolute', tolerance: 0.02 };

const LEFT = [
    { id: 1, cost_usd: 1.8 },
    { id: 2, cost_usd: 0.3 },
    { id: 3, cost_usd: 2.6 },
    { id: 4, cost_usd: 0.1 },
];
const RIGHT = [
    { id: 1, cost_usd: 1.8 }, // clean
    { id: 2, cost_usd: 0.31 }, // within 0.02 tolerance ⇒ clean
    { id: 3, cost_usd: 2.1 }, // value break
    { id: 5, cost_usd: 0.5 }, // missing on left
];
// id 4 missing on right.

describe('withinTolerance', () => {
    it('absolute: passes inside, fails outside', () => {
        expect(withinTolerance(0.3, 0.31, COST_ABS)).toBe(true);
        expect(withinTolerance(2.6, 2.1, COST_ABS)).toBe(false);
    });
    it('exact: string-equal only', () => {
        const c: CompareColumn = { column: 'x', toleranceType: 'exact', tolerance: 0 };
        expect(withinTolerance('A', 'A', c)).toBe(true);
        expect(withinTolerance('A', 'B', c)).toBe(false);
    });
    it('percent: relative to left', () => {
        const c: CompareColumn = { column: 'x', toleranceType: 'percent', tolerance: 10 };
        expect(withinTolerance(100, 105, c)).toBe(true); // 5%
        expect(withinTolerance(100, 120, c)).toBe(false); // 20%
        expect(withinTolerance(0, 1, c)).toBe(false); // left 0 ⇒ exact required
    });
});

describe('runReconciliation', () => {
    it('produces value/missing breaks and respects tolerance', () => {
        const breaks = runReconciliation({ keyColumns: KEYS, compareColumns: [COST_ABS] }, LEFT, RIGHT);
        const byType = breaks.reduce<Record<string, number>>((m, b) => ((m[b.type] = (m[b.type] ?? 0) + 1), m), {});
        expect(byType['value_break']).toBe(1); // id 3
        expect(byType['missing_right']).toBe(1); // id 4
        expect(byType['missing_left']).toBe(1); // id 5
        expect(breaks.every((b) => b.status === 'open')).toBe(true);
        const vb = breaks.find((b) => b.type === 'value_break')!;
        expect(vb.key).toBe('3');
        expect(vb.column).toBe('cost_usd');
        expect(vb.diff).toBeCloseTo(0.5, 5);
    });

    it('matchedKeyCount counts keys on both sides', () => {
        expect(matchedKeyCount(KEYS, LEFT, RIGHT)).toBe(3); // ids 1,2,3
    });
});

describe('mergeBreaks (lifecycle)', () => {
    it('auto-closes a previous break that is gone this run', () => {
        const prev: ReconBreak[] = [{ key: '4', type: 'missing_right', status: 'open' }];
        const fresh: ReconBreak[] = []; // key 4 now matches
        const merged = mergeBreaks(prev, fresh);
        expect(merged).toHaveLength(1);
        expect(merged[0].status).toBe('auto_closed');
    });

    it('preserves a manual resolution when the break still exists', () => {
        const prev: ReconBreak[] = [
            { key: '3', type: 'value_break', column: 'cost_usd', status: 'resolved', note: 'known FX gap' },
        ];
        const fresh: ReconBreak[] = [{ key: '3', type: 'value_break', column: 'cost_usd', status: 'open' }];
        const merged = mergeBreaks(prev, fresh);
        expect(merged[0].status).toBe('resolved');
        expect(merged[0].note).toBe('known FX gap');
    });

    it('drops previously auto-closed breaks that are still gone (bounded history)', () => {
        const prev: ReconBreak[] = [{ key: '9', type: 'missing_left', status: 'auto_closed' }];
        expect(mergeBreaks(prev, [])).toHaveLength(0);
    });
});

describe('break aging (BREAK-AGING-1)', () => {
    const DAY = 86_400_000;
    const at = (iso: string) => new Date(iso);

    it('stamps a genuinely new break with the run instant', () => {
        const merged = mergeBreaks(
            [],
            [{ key: '4', type: 'missing_right', status: 'open' }],
            '2026-09-01T00:00:00.000Z',
        );
        expect(merged[0].firstSeenAt).toBe('2026-09-01T00:00:00.000Z');
    });

    /**
     * The row's own acceptance, and the defect the implementation must avoid: a fresh break arrives from
     * the engine with NO stamp on every run, so re-stamping a carried one would reset its age to zero
     * every run and the aging view would permanently read "everything is new".
     */
    it('a break carried across three runs keeps its FIRST first-seen', () => {
        const fresh: ReconBreak[] = [{ key: '3', type: 'value_break', column: 'cost_usd', status: 'open' }];
        const run1 = mergeBreaks([], fresh, '2026-07-01T00:00:00.000Z');
        const run2 = mergeBreaks(run1, fresh, '2026-08-01T00:00:00.000Z');
        const run3 = mergeBreaks(run2, fresh, '2026-09-01T00:00:00.000Z');

        expect(run3).toHaveLength(1);
        expect(run3[0].firstSeenAt).toBe('2026-07-01T00:00:00.000Z');
        expect(breakAgeDays(run3[0], at('2026-09-01T00:00:00.000Z'))).toBe(62);
    });

    it('keeps the stamp across a manual resolution, so aging survives triage', () => {
        const fresh: ReconBreak[] = [{ key: '3', type: 'value_break', status: 'open' }];
        const run1 = mergeBreaks([], fresh, '2026-07-01T00:00:00.000Z');
        const resolved = resolveBreak(run1, run1[0], true, 'known FX gap');
        const run2 = mergeBreaks(resolved, fresh, '2026-09-01T00:00:00.000Z');

        expect(run2[0].status).toBe('resolved');
        expect(run2[0].firstSeenAt).toBe('2026-07-01T00:00:00.000Z');
    });

    it('stamps a break persisted before the field existed, rather than leaving it ageless forever', () => {
        const legacy: ReconBreak[] = [{ key: '3', type: 'value_break', status: 'open' }]; // no firstSeenAt
        const merged = mergeBreaks(legacy, legacy, '2026-09-01T00:00:00.000Z');
        expect(merged[0].firstSeenAt).toBe('2026-09-01T00:00:00.000Z');
    });

    it('reports no age at all for a break with no stamp — never zero', () => {
        // ⛔ A missing stamp is "unknown", not "new". Reporting it as 0 days would be the opposite of
        // what an aging view is for: the oldest untracked breaks would look freshest.
        expect(breakAgeDays({ key: '3', type: 'value_break', status: 'open' })).toBeNull();
        expect(ageBucketOf({ key: '3', type: 'value_break', status: 'open' })).toBe('unknown');
        expect(breakAgeDays({ key: '3', type: 'value_break', status: 'open', firstSeenAt: 'not a date' })).toBeNull();
    });

    it('buckets on upper-exclusive boundaries so a day lands in exactly one', () => {
        const now = at('2026-09-01T00:00:00.000Z');
        const aged = (days: number): ReconBreak => ({
            key: 'k',
            type: 'value_break',
            status: 'open',
            firstSeenAt: new Date(now.getTime() - days * DAY).toISOString(),
        });
        expect(ageBucketOf(aged(0), now)).toBe('0-30');
        expect(ageBucketOf(aged(29), now)).toBe('0-30');
        expect(ageBucketOf(aged(30), now)).toBe('30-60');
        expect(ageBucketOf(aged(59), now)).toBe('30-60');
        expect(ageBucketOf(aged(60), now)).toBe('60-90');
        expect(ageBucketOf(aged(89), now)).toBe('60-90');
        expect(ageBucketOf(aged(90), now)).toBe('90+');
        expect(ageBucketOf(aged(400), now)).toBe('90+');
    });

    it('summarize ages the OPEN breaks only', () => {
        const now = at('2026-09-01T00:00:00.000Z');
        const old = new Date(now.getTime() - 100 * DAY).toISOString();
        const breaks: ReconBreak[] = [
            { key: '1', type: 'value_break', status: 'open', firstSeenAt: old },
            // settled work must not age the backlog — clearing breaks would otherwise make it look older
            { key: '2', type: 'missing_right', status: 'resolved', firstSeenAt: old },
            { key: '3', type: 'missing_left', status: 'auto_closed', firstSeenAt: old },
            { key: '4', type: 'value_break', status: 'open' }, // no stamp
        ];
        const s = summarize(breaks, 4, 4, 3, now);
        expect(s.byAge['90+']).toBe(1);
        expect(s.byAge.unknown).toBe(1);
        expect(s.byAge['0-30']).toBe(0);
        expect(Object.values(s.byAge).reduce((a, b) => a + b, 0)).toBe(s.open);
    });
});

describe('resolveBreak + summarize', () => {
    it('resolves a break by identity', () => {
        const breaks: ReconBreak[] = [{ key: '3', type: 'value_break', column: 'cost_usd', status: 'open' }];
        const out = resolveBreak(breaks, breaks[0], true, 'accepted variance');
        expect(out[0].status).toBe('resolved');
        expect(out[0].note).toBe('accepted variance');
    });

    it('summarize counts by status/type, excluding auto-closed from type tallies', () => {
        const breaks: ReconBreak[] = [
            { key: '3', type: 'value_break', status: 'open' },
            { key: '4', type: 'missing_right', status: 'resolved' },
            { key: '9', type: 'missing_left', status: 'auto_closed' },
        ];
        const s = summarize(breaks, 4, 4, 3);
        expect(s.open).toBe(1);
        expect(s.resolved).toBe(1);
        expect(s.autoClosed).toBe(1);
        expect(s.byType.value_break).toBe(1);
        expect(s.byType.missing_left).toBe(0); // the only missing_left is auto-closed
    });
});

describe('buildReconciliation', () => {
    it('slugs the name into an id and starts empty', () => {
        const r = buildReconciliation('Switch vs Billing', 'switch_cdr', 'billing_cdr', ['id'], [COST_ABS]);
        expect(r.id).toMatch(/^switch_vs_billing_[a-z0-9]{4}$/);
        expect(r.breaks).toEqual([]);
        expect(r.lastRunAt).toBeNull();
    });
});

describe('breakId — the server-shared Break identity', () => {
    const b = (type: ReconBreak['type'], key: string, column?: string): ReconBreak =>
        ({ type, key, column, status: 'open' }) as ReconBreak;

    /**
     * ⛔ A published contract, not an internal detail: `ReconRoutes.breakIdentity()` renders the
     * byte-identical string into the `breakId` Incident attribute it dedupes on and keys
     * `GET /recon/promoted` by (`BREAK-DEDUPE-GRAIN-1`). These literals are duplicated verbatim in
     * `ControlApiReconPromoteTest`; if one side changes spelling, one of the two suites must go red.
     */
    it('renders (type, key, column) in the spelling the backend pins', () => {
        expect(breakId(b('value_break', 'EU|voice', 'amount'))).toBe('value_break|EU\\|voice|amount');
        expect(breakId(b('break' as ReconBreak['type'], 'EU|voice'))).toBe('break|EU\\|voice|');
        expect(breakId(b('break' as ReconBreak['type'], 'NA|data'))).toBe('break|NA\\|data|');
    });

    it('is injective when a value contains the separator', () => {
        // Without escaping both render `value_break|EU|voice|amount` and two different Breaks would share
        // one Incident — the defect BREAK-DEDUPE-GRAIN-1 removes, reintroduced one level down.
        expect(breakId(b('value_break', 'EU|voice', 'amount'))).not.toBe(
            breakId(b('value_break', 'EU', 'voice|amount')),
        );
    });

    it('is injective when a value contains a backslash', () => {
        // `\` is the escape character itself, so it must be escaped FIRST or `a\` + `|b` collides with
        // `a` + `\|b`. (Each literal below is a single backslash.)
        expect(breakId(b('value_break', 'a\\', 'b'))).not.toBe(breakId(b('value_break', 'a', '\\b')));
    });

    it('distinguishes a missing column from an empty one only by content, not by position', () => {
        expect(breakId(b('value_break', 'k'))).toBe(breakId(b('value_break', 'k', '')));
    });

    it('separates the three identity components', () => {
        expect(breakId(b('value_break', 'k', 'c'))).not.toBe(breakId(b('missing_left', 'k', 'c')));
        expect(breakId(b('value_break', 'k', 'c'))).not.toBe(breakId(b('value_break', 'k2', 'c')));
        expect(breakId(b('value_break', 'k', 'c'))).not.toBe(breakId(b('value_break', 'k', 'c2')));
    });
});
