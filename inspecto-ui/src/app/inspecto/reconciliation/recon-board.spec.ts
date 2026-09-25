import { describe, expect, it } from 'vitest';
import { ICellRendererParams } from 'ag-grid-community';
import { CompareColumn } from './reconciliation-types';
import {
    aggregateRecon,
    bandCell,
    bandFor,
    buildBoardTree,
    comparedSides,
    decodePath,
    deltaPct,
    encodePath,
    markBreachesExpanded,
    RECON_RECORDS,
    reconBreakSets,
    ReconRunResult,
    breaksFromSets,
    breakImpacts,
    duplicateImpacts,
    oneSides,
    reconCardinality,
    ReconBreakSets,
} from './recon-board';

/**
 * Offline-engine parity with the backend `ReconServiceTest` — the SAME reference fixture
 * (Mediation-vs-Billing): EU/voice matched-equal (A 2×100 vs B 1×200), EU/data matched outside the
 * 0.5% tolerance (118 vs 114 = 3.39%), US/voice matched-equal, MEA/voice only in A, APAC/sms only in B.
 */
const LEFT = [
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'voice', amount: 100 },
    { region: 'EU', product: 'data', amount: 118 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'MEA', product: 'voice', amount: 10 },
];
const RIGHT = [
    { region: 'EU', product: 'voice', amount: 200 },
    { region: 'EU', product: 'data', amount: 114 },
    { region: 'US', product: 'voice', amount: 50 },
    { region: 'APAC', product: 'sms', amount: 7 },
];
const CONFIG = {
    keyColumns: ['region', 'product'],
    compareColumns: [{ column: 'amount', toleranceType: 'percent', tolerance: 0.5 } as CompareColumn],
};

const run = (): ReconRunResult => aggregateRecon(CONFIG, LEFT, RIGHT);

function grain(r: ReconRunResult, region: string, product: string) {
    const row = r.rows.find((x) => x.key['region'] === region && x.key['product'] === product);
    expect(row, `${region}/${product}`).toBeDefined();
    return row!;
}

describe('aggregateRecon (backend ReconService parity)', () => {
    it('computes grain rows, exact totals, and the break summary', () => {
        const r = run();
        expect(r.rows).toHaveLength(5);
        expect(r.measures).toEqual(['amount', RECON_RECORDS]);

        const euData = grain(r, 'EU', 'data');
        expect(euData.a['amount']).toBe(118);
        expect(euData.b['amount']).toBe(114);
        expect(euData.inA && euData.inB).toBe(true);

        const euVoice = grain(r, 'EU', 'voice');
        expect(euVoice.a['amount']).toBe(200);
        expect(euVoice.a[RECON_RECORDS]).toBe(2);

        const mea = grain(r, 'MEA', 'voice');
        expect(mea.inB).toBe(false);
        expect(mea.b['amount']).toBeNull();

        expect(r.totals.a['amount']).toBe(378);
        expect(r.totals.b['amount']).toBe(371);
        expect(r.totals.a[RECON_RECORDS]).toBe(5);
        expect(r.totals.b[RECON_RECORDS]).toBe(4);

        expect(r.summary.groups).toBe(5);
        expect(r.summary.matchedKeys).toBe(3);
        expect(r.summary.byType).toEqual({ missing_left: 1, missing_right: 1, value_break: 1 });
    });

    it('ports withinTolerance over aggregates: boundaries are within, zero anchor requires equality', () => {
        const vb = (a: number, b: number, c: Partial<CompareColumn>): number =>
            aggregateRecon(
                {
                    keyColumns: ['k'],
                    compareColumns: [{ column: 'v', toleranceType: 'exact', tolerance: 0, ...c } as CompareColumn],
                },
                [{ k: 'x', v: a }],
                [{ k: 'x', v: b }],
            ).summary.byType.value_break;
        expect(vb(100, 105, { toleranceType: 'absolute', tolerance: 5 })).toBe(0);
        expect(vb(100, 106, { toleranceType: 'absolute', tolerance: 5 })).toBe(1);
        expect(vb(100, 100.5, { toleranceType: 'percent', tolerance: 0.5 })).toBe(0);
        expect(vb(100, 100.6, { toleranceType: 'percent', tolerance: 0.5 })).toBe(1);
        expect(vb(0, 0, { toleranceType: 'percent', tolerance: 50 })).toBe(0);
        expect(vb(0, 1, { toleranceType: 'percent', tolerance: 50 })).toBe(1);
    });

    it('groups NULL key values together (both-null keys match)', () => {
        const r = aggregateRecon(
            { keyColumns: ['k'], compareColumns: [{ column: 'v', toleranceType: 'exact', tolerance: 0 }] },
            [
                { k: null, v: 1 },
                { k: 'x', v: 2 },
            ],
            [{ k: null, v: 1 }],
        );
        expect(r.summary.groups).toBe(2);
        expect(r.summary.matchedKeys).toBe(1);
        expect(r.summary.byType.missing_right).toBe(1);
        expect(r.summary.byType.value_break).toBe(0);
    });
});

describe('aggregateRecon 3-way (anchor model, backend parity)', () => {
    // C: EU/voice 195 (2.5% off A's 200 → value break vs A), EU/data + US equal to A, MEA missing, LATAM only-C.
    const THIRD = [
        { region: 'EU', product: 'voice', amount: 195 },
        { region: 'EU', product: 'data', amount: 118 },
        { region: 'US', product: 'voice', amount: 50 },
        { region: 'LATAM', product: 'voice', amount: 5 },
    ];

    it('compares each non-anchor side against the anchor and reports per-pair summaries', () => {
        const r = aggregateRecon(CONFIG, LEFT, RIGHT, THIRD);
        expect(comparedSides(r)).toEqual(['b', 'c']);
        expect(r.summary.groups).toBe(6); // union: EU/voice, EU/data, US/voice, MEA/voice, APAC/sms, LATAM/voice
        expect(r.totals.c!['amount']).toBe(368); // 195 + 118 + 50 + 5

        const latam = r.rows.find((x) => x.key['region'] === 'LATAM')!;
        expect(latam.inA).toBe(false);
        expect(latam.inC).toBe(true);

        expect(r.summary.pairs).toHaveLength(2);
        const [ab, ac] = r.summary.pairs!;
        expect(ab.side).toBe('b');
        expect(ac.side).toBe('c');
        expect(ac.byType.missing_right).toBe(1); // MEA in A, not C
        expect(ac.byType.missing_left).toBe(1); // LATAM only in C
        expect(ac.byType.value_break).toBe(1); // EU/voice 200 vs 195 outside 0.5%
        // the flat top-level summary mirrors pair A↔B (2-way consumers unchanged)
        expect(r.summary.matchedKeys).toBe(ab.matchedKeys);
    });

    it('breaks scope to the chosen compared side', () => {
        const c = reconBreakSets(CONFIG, LEFT, RIGHT, null, null, 'c', THIRD);
        expect(c.missing_right!.rows.map((r) => r.key['region'])).toEqual(['MEA']);
        expect(c.missing_left!.rows.map((r) => r.key['region'])).toEqual(['LATAM']);
        expect(c.value_break!.rows[0].key['product']).toBe('voice');
        expect(c.value_break!.rows[0].b!['amount']).toBe(195); // compared side rides the 'b' role slot
    });

    it('the Board tree adds c columns and per-side structural marks', () => {
        const nodes = buildBoardTree(aggregateRecon(CONFIG, LEFT, RIGHT, THIRD));
        const eu = nodes.find((n) => n.label === 'EU')!;
        const voice = eu.children!.find((n) => n.label === 'voice')!;
        expect(voice.values!['c_amount']).toBe(195);
        expect(voice.values!['pct_c_amount']).toBeCloseTo(-2.5);
        const latam = nodes.find((n) => n.label === 'LATAM')!;
        expect(latam.values!['__miss_c']).toBe('c'); // present only in C
        expect(latam.values!['__anchorMissing']).toBe(true); // no anchor row under this node
    });
});

describe('reconBreakSets', () => {
    it('splits the three sets, scopes by path, and filters by type', () => {
        const all = reconBreakSets(CONFIG, LEFT, RIGHT);
        expect(all.missing_right!.rows.map((r) => r.key['region'])).toEqual(['MEA']);
        expect(all.missing_right!.rows[0].a?.['amount']).toBe(10);
        expect(all.missing_right!.rows[0].b).toBeUndefined();
        expect(all.missing_left!.rows.map((r) => r.key['region'])).toEqual(['APAC']);
        expect(all.value_break!.rows.map((r) => r.key['product'])).toEqual(['data']);

        const eu = reconBreakSets(CONFIG, LEFT, RIGHT, { region: 'EU' });
        expect(eu.missing_right!.rowCount).toBe(0);
        expect(eu.value_break!.rowCount).toBe(1);

        const one = reconBreakSets(CONFIG, LEFT, RIGHT, null, 'value_break');
        expect(Object.keys(one)).toEqual(['value_break']);
    });
});

describe('buildBoardTree', () => {
    it('rolls parents up from SUMS (never averaged child Δ%s) and marks one-sided nodes structural', () => {
        const nodes = buildBoardTree(run());
        const eu = nodes.find((n) => n.label === 'EU')!;
        expect(eu.values!['a_amount']).toBe(318); // 200 + 118
        expect(eu.values!['b_amount']).toBe(314); // 200 + 114
        // Δ% from rolled sums: (314-318)/318·100 = −1.258… — NOT the child average (0 + −3.39)/2.
        expect(eu.values!['pct_b_amount']).toBeCloseTo(-1.2579, 3);
        expect(eu.values!['__miss_b']).toBeNull();

        // children sorted worst-severity first: BOTH breach — voice on the implicit record count
        // (2 vs 1 rows = −50%) outranks data (amount −3.39%) by |Δ%| within the breach band.
        expect(eu.children!.map((c) => c.label)).toEqual(['voice', 'data']);
        expect(eu.children![0].values!['pct_b___records']).toBeCloseTo(-50);

        const mea = nodes.find((n) => n.label === 'MEA')!;
        expect(mea.values!['__miss_b']).toBe('a');
        expect(mea.values!['b_amount']).toBeNull();

        // drill path ids encode the dimension path in key-column order
        expect(eu.children![1].id).toBe('region:EU|product:data');
    });

    it('markBreachesExpanded expands exactly the ancestors of hot nodes', () => {
        const marked = markBreachesExpanded(buildBoardTree(run()));
        const eu = marked.find((n) => n.label === 'EU')!;
        const us = marked.find((n) => n.label === 'US')!;
        expect(eu.expanded).toBe(true); // contains the EU/data breach
        expect(us.expanded).toBeUndefined(); // all-ok subtree stays collapsed
    });
});

describe('bands + cells + paths', () => {
    it('bandFor honors the locked defaults (<1 ok · 1–2 warn · >2 breach; null = structural)', () => {
        expect(bandFor(0.99)).toBe('ok');
        expect(bandFor(-0.99)).toBe('ok');
        expect(bandFor(1)).toBe('warn');
        expect(bandFor(2)).toBe('warn');
        expect(bandFor(2.01)).toBe('breach');
        expect(bandFor(null)).toBe('structural');
    });

    it('deltaPct is anchor-relative and null for a zero anchor with a non-zero other', () => {
        expect(deltaPct(100, 101)).toBeCloseTo(1);
        expect(deltaPct(0, 0)).toBe(0);
        expect(deltaPct(0, 5)).toBeNull();
        expect(deltaPct(null, 5)).toBeNull();
    });

    it('bandCell renders glyph + text (never color alone) and names the one-sided dataset', () => {
        const cell = bandCell();
        const p = (value: unknown, data?: Record<string, unknown>): ICellRendererParams =>
            ({ value, data }) as unknown as ICellRendererParams;
        expect(cell(p(0.3))).toContain('✓ +0.3%');
        expect(cell(p(-1.6))).toContain('! -1.6%');
        expect(cell(p(3.4))).toContain('✕ +3.4%');
        expect(cell(p(null, { __miss_b: 'a' }))).toContain('⊘ only in A');
        expect(cell(p(null, { __miss_b: 'b' }))).toContain('⊘ only in B');
        expect(cell(p(null, {}))).toContain('✕ new');
        // the 'c' renderer names side C on a one-sided node
        expect(bandCell('c')(p(null, { __miss_c: 'c' }))).toContain('⊘ only in C');
    });

    it('encodePath/decodePath round-trip values with reserved characters', () => {
        const enc = encodePath({ region: 'EU|x', product: 'a:b' }, ['region', 'product']);
        expect(decodePath(enc)).toEqual({ region: 'EU|x', product: 'a:b' });
        expect(decodePath('')).toBeNull();
    });
});

describe('breaksFromSets — cardinality_break reaches the client (RECON-CARDINALITY-1)', () => {
    /**
     * 🔴 This is the mapper the LIVE path uses: `/recon/breaks` → {@link breaksFromSets} → the Break
     * lifecycle. The offline `aggregateRecon`/`reconBreakSets` mirror above has had no caller since the
     * mock backend was removed, so a type that only reached THEM would still be invisible to the app.
     */
    it('maps a cardinality set, carrying the per-side row counts as the evidence', () => {
        const sets: ReconBreakSets = {
            cardinality_break: {
                rows: [
                    {
                        key: { region: 'EU', product: 'voice' },
                        a: { [RECON_RECORDS]: 2 },
                        b: { [RECON_RECORDS]: 1 },
                    },
                ],
                rowCount: 1,
                truncated: false,
            },
        };
        const out = breaksFromSets(CONFIG, sets);
        expect(out).toHaveLength(1);
        expect(out[0].type).toBe('cardinality_break');
        expect(out[0].leftValue).toBe(2);
        expect(out[0].rightValue).toBe(1);
        expect(out[0].status).toBe('open');
        expect(out[0].column).toBeUndefined();
    });

    it('emits one break per KEY, not one per compare column', () => {
        const sets: ReconBreakSets = {
            cardinality_break: {
                rows: [
                    { key: { region: 'EU', product: 'voice' }, a: { [RECON_RECORDS]: 3 }, b: { [RECON_RECORDS]: 1 } },
                    { key: { region: 'US', product: 'voice' }, a: { [RECON_RECORDS]: 1 }, b: { [RECON_RECORDS]: 4 } },
                ],
                rowCount: 2,
                truncated: false,
            },
        };
        expect(breaksFromSets(CONFIG, sets)).toHaveLength(2);
    });

    it('is absent when the server sends no cardinality set', () => {
        expect(breaksFromSets(CONFIG, {})).toEqual([]);
    });

    /** RECON-CARDINALITY-2: a cardinality break keeps the server's key values so its rows can be fetched. */
    it('keeps the server key values on a cardinality break', () => {
        const breaks = breaksFromSets(
            { keyColumns: ['region', 'product'], compareColumns: [] },
            {
                cardinality_break: {
                    rows: [{ key: { region: 'EU', product: 'voice' }, a: { __records: 2 }, b: { __records: 1 } }],
                    rowCount: 1,
                    truncated: false,
                },
            },
        );
        expect(breaks).toHaveLength(1);
        expect(breaks[0].key).toBe('EU · voice');
        expect(breaks[0].keyValues).toEqual({ region: 'EU', product: 'voice' });
        expect(breaks[0].leftValue).toBe(2);
        expect(breaks[0].rightValue).toBe(1);
    });
});

describe('breakImpacts (UIE-10)', () => {
    const sets: ReconBreakSets = {
        missing_right: {
            rows: [{ key: { region: 'MEA', product: 'voice' }, a: { amount: 10 } }],
            rowCount: 1,
            truncated: false,
        },
        missing_left: {
            rows: [{ key: { region: 'APAC', product: 'sms' }, b: { amount: 7 } }],
            rowCount: 1,
            truncated: false,
        },
        value_break: {
            rows: [{ key: { region: 'EU', product: 'data' }, a: { amount: 114 }, b: { amount: 118 } }],
            rowCount: 1,
            truncated: false,
        },
    };

    it('is |A - B| of the declared column per Break key, an absent side counting 0', () => {
        expect(breakImpacts({ ...CONFIG, impact: { column: 'amount', currency: 'SAR' } }, sets)).toEqual({
            'MEA · voice': 10,
            'APAC · sms': 7,
            'EU · data': 4,
        });
    });

    it('is empty when no impact is declared', () => {
        expect(breakImpacts(CONFIG, sets)).toEqual({});
    });

    it('is empty for a non-compared column when the server carried no value — never an invented 0', () => {
        expect(breakImpacts({ ...CONFIG, impact: { column: 'monthly_fee_sar' } }, sets)).toEqual({});
    });
});

describe('breakImpacts — a carried (non-compared) impact column (operator decision 2026-09-25)', () => {
    // RA-C01 shape: status compared, the monthly fee only carried as impact: {a, b}.
    const STATUS = {
        keyColumns: ['msisdn'],
        compareColumns: [{ column: 'active_flag', toleranceType: 'exact', tolerance: 0 } as CompareColumn],
        impact: { column: 'monthly_fee_sar', currency: 'SAR' },
    };
    const sets: ReconBreakSets = {
        missing_right: {
            rows: [{ key: { msisdn: 'm3' }, a: { active_flag: 1 }, impact: { a: 99, b: null } }],
            rowCount: 1,
            truncated: false,
        },
        missing_left: {
            rows: [{ key: { msisdn: 'm5' }, b: { active_flag: 1 }, impact: { a: null, b: 30 } }],
            rowCount: 1,
            truncated: false,
        },
        value_break: {
            rows: [
                // status differs, both sides know the fee → the fee, NOT |A − B| (which would be 0)
                { key: { msisdn: 'm2' }, a: { active_flag: 1 }, b: { active_flag: 0 }, impact: { a: 149, b: 149 } },
                // the compared side's Dataset lacks the column → the anchor's value
                { key: { msisdn: 'm4' }, a: { active_flag: 0 }, b: { active_flag: 1 }, impact: { a: 50, b: null } },
                // neither side has a value → no entry
                { key: { msisdn: 'm6' }, a: { active_flag: 0 }, b: { active_flag: 1 }, impact: { a: null, b: null } },
            ],
            rowCount: 3,
            truncated: false,
        },
    };

    it('is the value on the side that has it, the anchor first', () => {
        expect(breakImpacts(STATUS, sets)).toEqual({ m3: 99, m5: 30, m2: 149, m4: 50 });
    });

    it('prefers the anchor when the two sides carry different values', () => {
        const differ: ReconBreakSets = {
            value_break: {
                rows: [
                    { key: { msisdn: 'm7' }, a: { active_flag: 1 }, b: { active_flag: 0 }, impact: { a: 199, b: 99 } },
                ],
                rowCount: 1,
                truncated: false,
            },
        };
        expect(breakImpacts(STATUS, differ)).toEqual({ m7: 199 });
    });

    it('ignores carried values when the impact column IS compared — |A − B| of the compared values wins', () => {
        const compared = { ...STATUS, impact: { column: 'active_flag' } };
        expect(breakImpacts(compared, sets)).toEqual({ m3: 1, m5: 1, m2: 1, m4: 1, m6: 1 });
    });
});

describe('duplicate keys — cardinality and the impact of the extra copies', () => {
    const SUBS = {
        keyColumns: ['msisdn'],
        compareColumns: [{ column: 'active_flag', toleranceType: 'exact', tolerance: 0 } as CompareColumn],
        impact: { column: 'fee', currency: 'SAR' },
        raw: { cardinality: 'one_to_one' },
    };
    // m9: B bills it twice (fee 100 summed over 2 records); m8: A has it 3× (fee 30 summed), B 2× (fee 20).
    const sets: ReconBreakSets = {
        value_break: {
            rows: [{ key: { msisdn: 'm9' }, a: { active_flag: 1 }, b: { active_flag: 2 }, impact: { a: 50, b: 100 } }],
            rowCount: 1,
            truncated: false,
        },
        cardinality_break: {
            rows: [
                {
                    key: { msisdn: 'm9' },
                    a: { [RECON_RECORDS]: 1 },
                    b: { [RECON_RECORDS]: 2 },
                    impact: { a: 50, b: 100 },
                },
                {
                    key: { msisdn: 'm8' },
                    a: { [RECON_RECORDS]: 3 },
                    b: { [RECON_RECORDS]: 2 },
                    impact: { a: 30, b: 20 },
                },
                {
                    key: { msisdn: 'm7' },
                    a: { [RECON_RECORDS]: 1 },
                    b: { [RECON_RECORDS]: 2 },
                    impact: { a: 5, b: null },
                },
            ],
            rowCount: 3,
            truncated: false,
        },
    };

    it('reads a declared cardinality from the stored body; blank and many_to_many declare none', () => {
        expect(reconCardinality({ raw: { cardinality: 'one_to_many' } })).toBe('one_to_many');
        expect(reconCardinality({ raw: { cardinality: 'many_to_many' } })).toBeNull();
        expect(reconCardinality({ raw: { cardinality: '' } })).toBeNull();
        expect(reconCardinality({ raw: {} })).toBeNull();
        expect(reconCardinality({})).toBeNull();
        expect(oneSides('one_to_one')).toEqual(['a', 'b']);
        expect(oneSides('one_to_many')).toEqual(['a']);
        expect(oneSides('many_to_one')).toEqual(['b']);
    });

    it('prices a duplicate by its extra copies on the "one" sides, never an invented 0', () => {
        // m9: 100 × 1/2 = 50 · m8: 30 × 2/3 + 20 × 1/2 = 30 · m7: B has no value → no entry
        expect(duplicateImpacts(SUBS, sets)).toEqual({ m9: 50, m8: 30 });
    });

    it('counts nothing on a side the cardinality allows to repeat', () => {
        expect(duplicateImpacts({ ...SUBS, raw: { cardinality: 'one_to_many' } }, sets)).toEqual({ m8: 20 });
    });

    it('uses the compared measure when the impact column is compared', () => {
        const compared = { ...SUBS, impact: { column: 'active_flag' } };
        const cs: ReconBreakSets = {
            cardinality_break: {
                rows: [
                    {
                        key: { msisdn: 'm9' },
                        a: { active_flag: 1, [RECON_RECORDS]: 1 },
                        b: { active_flag: 2, [RECON_RECORDS]: 2 },
                    },
                ],
                rowCount: 1,
                truncated: false,
            },
        };
        expect(duplicateImpacts(compared, cs)).toEqual({ m9: 1 });
    });

    it('is empty with no impact or no cardinality', () => {
        expect(duplicateImpacts({ ...SUBS, impact: undefined }, sets)).toEqual({});
        expect(duplicateImpacts({ ...SUBS, raw: {} }, sets)).toEqual({});
    });

    it('breakImpacts leaves the cardinality set alone, so a value break at the same key keeps its own impact', () => {
        expect(breakImpacts(SUBS, sets)).toEqual({ m9: 50 });
    });
});
