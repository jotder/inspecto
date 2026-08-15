import { describe, expect, it } from 'vitest';

import { liftConfig, lowerGraph } from './pipeline-editable';

/**
 * Parity guard for the mock's lift/lower against the backend `PipelineEditable`. The mock must never
 * be MORE LENIENT than the server: a topology it accepts offline that the real route 422s turns a
 * hard failure into a passing rehearsal.
 *
 * These cases pin the top-level `parsing:` block, which is parser-owned and OVERLAYS
 * `processing.csv_settings` in the engine — so a parser node that cannot see it would edit the losing
 * key and the operator's change would be silently masked.
 */
describe('mock pipeline-editable — the parsing: block is parser-owned', () => {
    /** A pipeline in the Onboarding spelling: parse options in `parsing:`, schema ref in `processing:`. */
    const parsingBlockConfig = () => ({
        name: 'PB',
        active: true,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        parsing: { frontend: 'delimited', delimited: { delimiter: '|', has_header: false } },
        processing: { schema_file: 'pb_schema.toon' },
    });

    it('carries the parsing: block onto the parser node', () => {
        const g = liftConfig(parsingBlockConfig());

        const parser = g.nodes.find((n) => n.type === 'parser');
        expect(parser).toBeDefined();
        expect(parser!.config?.['parsing']).toEqual({
            frontend: 'delimited',
            delimited: { delimiter: '|', has_header: false },
        });
    });

    it('lowers an edited parsing: block back into parsing:, not the legacy key', () => {
        const existing = parsingBlockConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser')!;
        (parser.config!['parsing'] as Record<string, Record<string, unknown>>)['delimited']['delimiter'] = ';';

        const res = lowerGraph(g, existing, true);

        expect('config' in res).toBe(true);
        const config = (res as { config: Record<string, unknown> }).config;
        expect(config['parsing']).toEqual({
            frontend: 'delimited',
            delimited: { delimiter: ';', has_header: false },
        });
        // the losing legacy key must not be resurrected behind the operator's back
        expect((config['processing'] as Record<string, unknown>)['csv_settings']).toBeUndefined();
    });

    it('accepts a parsing: block as satisfying PARSER_NO_SCHEMA', () => {
        const existing = parsingBlockConfig();
        // the parser names ONLY the parsing: block — no schema_file / schemas / segments
        delete (existing.processing as Record<string, unknown>)['schema_file'];
        const g = liftConfig(existing);

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(false);
    });

    it('still refuses a parser naming nothing at all', () => {
        const existing = parsingBlockConfig();
        delete (existing as Record<string, unknown>)['parsing'];
        delete (existing.processing as Record<string, unknown>)['schema_file'];
        const g = liftConfig(existing);

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(true);
        expect((res as { refusals: { code: string }[] }).refusals.map((r) => r.code)).toContain('PARSER_NO_SCHEMA');
    });

    it('presents a bound Grammar as use:, not as a config key', () => {
        const existing = {
            ...parsingBlockConfig(),
            parsing: { frontend: 'delimited', grammar: 'grammar/pipe_delimited' },
        };

        const parser = liftConfig(existing).nodes.find((n) => n.type === 'parser')!;

        expect(parser.use).toBe('grammar/pipe_delimited');
        expect((parser.config?.['parsing'] as Record<string, unknown> | undefined)?.['grammar']).toBeUndefined();
    });

    it('lowers the Grammar binding back into parsing.grammar', () => {
        const g = liftConfig(parsingBlockConfig());
        g.nodes.find((n) => n.type === 'parser')!.use = 'grammar/pipe_delimited';

        const res = lowerGraph(g, parsingBlockConfig(), true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['grammar']).toBe('grammar/pipe_delimited');
    });

    it('clears the ref when the Grammar is unbound', () => {
        const existing = { ...parsingBlockConfig(), parsing: { frontend: 'delimited', grammar: 'grammar/old' } };
        const g = liftConfig(existing);
        g.nodes.find((n) => n.type === 'parser')!.use = undefined;

        const res = lowerGraph(g, existing, true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing?.['grammar']).toBeUndefined();
    });

    it('accepts a Grammar binding as satisfying PARSER_NO_SCHEMA', () => {
        const existing = parsingBlockConfig();
        delete (existing as Record<string, unknown>)['parsing'];
        delete (existing.processing as Record<string, unknown>)['schema_file'];
        const g = liftConfig(existing);
        g.nodes.find((n) => n.type === 'parser')!.use = 'grammar/pipe_delimited';

        expect('refusals' in lowerGraph(g, existing, true)).toBe(false);
    });

    it('does not drop a parsing: block it was never given (non-strict merge)', () => {
        const existing = parsingBlockConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser')!;
        delete parser.config!['parsing'];

        const res = lowerGraph(g, existing, false);

        expect((res as { config: Record<string, unknown> }).config['parsing']).toBeDefined();
    });
});

/**
 * Parity guard for the processing-key transform nodes the server lowers (PipelineEditable.LOWERABLE):
 * transform.dedup → processing.dedup {keys, order_by}, transform.join → processing.join
 * {reference, on}, transform.summarize → processing.summarize {group_by, measures}. The mock used to
 * refuse all three with UNSUPPORTED_NODE — stricter than the server, so the offline editor blocked
 * graphs the real backend accepts. Each case pins lift → lower verbatim round-trip + strict removal.
 */
describe('mock pipeline-editable — processing-key transforms (dedup / join / summarize)', () => {
    const processedConfig = () => ({
        name: 'PK',
        active: false,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'PARQUET' },
        collector: { connector: 'local' },
        parsing: { grammar: 'grammar/pipe' },
        processing: {
            join: { reference: 'reference/rates', on: 'currency' },
            dedup: { keys: ['call_id'], order_by: 'event_ts DESC' },
            summarize: { group_by: ['region'], measures: ['sum(amount)'] },
        },
    });

    it('lifts the three processing keys as nodes in the server order (join → dedup → summarize)', () => {
        const g = liftConfig(processedConfig());
        const chain = g.nodes.map((n) => n.type);
        const join = chain.indexOf('transform.join');
        const dedup = chain.indexOf('transform.dedup');
        const summarize = chain.indexOf('transform.summarize');
        expect(join).toBeGreaterThan(-1);
        expect(dedup).toBeGreaterThan(join);
        expect(summarize).toBeGreaterThan(dedup);
        expect(g.nodes.find((n) => n.type === 'transform.join')!.config).toEqual({
            reference: 'reference/rates',
            on: 'currency',
        });
        expect(g.nodes.find((n) => n.type === 'transform.dedup')!.config).toEqual({
            keys: ['call_id'],
            order_by: 'event_ts DESC',
        });
        expect(g.nodes.find((n) => n.type === 'transform.summarize')!.config).toEqual({
            group_by: ['region'],
            measures: ['sum(amount)'],
        });
    });

    it('lowers all three back verbatim — no UNSUPPORTED_NODE, keys byte-stable', () => {
        const existing = processedConfig();
        const g = liftConfig(existing);
        const res = lowerGraph(g, existing, true);
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const processing = (res as { config: Record<string, unknown> }).config['processing'] as Record<string, unknown>;
        expect(processing['join']).toEqual({ reference: 'reference/rates', on: 'currency' });
        expect(processing['dedup']).toEqual({ keys: ['call_id'], order_by: 'event_ts DESC' });
        expect(processing['summarize']).toEqual({ group_by: ['region'], measures: ['sum(amount)'] });
    });

    it('strict lower removes the processing key when its node was deleted (mirrors the server else-if-strict)', () => {
        const existing = processedConfig();
        const g = liftConfig(existing);
        g.nodes = g.nodes.filter((n) => n.type !== 'transform.summarize');
        g.edges = g.edges.filter((e) => e.from !== 'summarize' && e.to !== 'summarize');
        // reconnect dedup → route/sink stretch: dedup fed summarize, summarize fed the sink
        g.edges.push({ from: 'dedup', rel: 'data', to: 'sink' });

        const strict = lowerGraph(g, existing, true);
        expect('config' in strict, JSON.stringify(strict)).toBe(true);
        const sp = (strict as { config: Record<string, unknown> }).config['processing'] as Record<string, unknown>;
        expect(sp['summarize']).toBeUndefined();
        expect(sp['dedup']).toEqual({ keys: ['call_id'], order_by: 'event_ts DESC' }); // siblings untouched

        // non-strict merge must NOT drop a key it was never given
        const lax = lowerGraph(g, processedConfig(), false);
        const lp = (lax as { config: Record<string, unknown> }).config['processing'] as Record<string, unknown>;
        expect(lp['summarize']).toEqual({ group_by: ['region'], measures: ['sum(amount)'] });
    });

    /**
     * ⚠ These four used to be the MULTI_* refusal specs, and they now assert the opposite.
     *
     * Each kind held ONE block, so a second node silently replaced the first; on 2026-08-11 all four
     * flipped to refusing, which made the loss visible while the flat file still had a single slot.
     * The multiplicity plan's slice A3 gave the file an ordered `steps:` chain, so the slot — and with
     * it the reason for the refusal — is gone, and the codes went in the same change that widened the
     * format.
     *
     * **The mock had to move in that same commit, and the direction matters.** The usual failure is a
     * mock more *lenient* than the server. This is the mirror image: a mock still refusing a second
     * dedup would block, offline, a graph the backend now saves happily — same bug, other sign.
     */
    const expectSecondLowersToTheChain = (
        type: string,
        kind: string,
        config: Record<string, unknown>,
        from: Record<string, unknown> = processedConfig(),
    ): void => {
        const existing = from;
        const g = liftConfig(existing);
        const first = g.nodes.find((n) => n.type === type)!;
        expect(first, `the fixture must already carry one ${type}`).toBeTruthy();
        g.nodes.push({ id: 'dup_1', type, config });

        const res = lowerGraph(g, existing, true);
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const out = (res as { config: Record<string, unknown> }).config;

        const steps = out['steps'] as Record<string, unknown>[];
        expect(Array.isArray(steps), 'two of a kind lower to a steps: chain').toBe(true);
        expect(steps.filter((s) => kind in s).length, 'both survive, neither replaces the other').toBe(2);

        // ⚠ Both spellings in one file is a PARSE refusal on the server, so a stale singular key would
        // not merely be untidy — it would write config that can never be loaded again.
        const processing = out['processing'] as Record<string, unknown>;
        for (const legacy of ['dedup', 'join', 'summarize']) expect(processing[legacy]).toBeUndefined();
        expect(out['route']).toBeUndefined();
    };

    it('lowers a SECOND join into the chain rather than replacing the first', () => {
        expectSecondLowersToTheChain('transform.join', 'join', { reference: 'reference/fx', on: 'ccy' });
    });

    it('lowers a SECOND record dedup into the chain', () => {
        expectSecondLowersToTheChain('transform.dedup', 'dedup', { keys: ['imsi'] });
    });

    // Its own fixture: a transform.route node is lifted only from a top-level `route:` block, which the
    // shared processedConfig() (processing-key transforms) deliberately has none of.
    it('lowers a SECOND route into the chain', () => {
        expectSecondLowersToTheChain(
            'transform.route',
            'route',
            { on: 'second' },
            { ...processedConfig(), route: { on: 'first', branches: [] } },
        );
    });

    it('lowers a SECOND summarize into the chain', () => {
        expectSecondLowersToTheChain('transform.summarize', 'summarize', { group_by: ['cell'] });
    });

    /**
     * The safety property that lets A3 ship, mirrored from the server: a graph the singular keys CAN
     * express is written exactly as before, with no `steps:` block. Every existing pipeline is this
     * case, so this is the spec that would catch the mock rewriting files it should have left alone.
     */
    it('leaves a legacy-shaped graph on the singular keys, with no steps: block', () => {
        const existing = processedConfig();
        const res = lowerGraph(liftConfig(existing), existing, true);

        expect('config' in res, JSON.stringify(res)).toBe(true);
        const out = (res as { config: Record<string, unknown> }).config;
        expect(out['steps'], 'an unchanged pipeline must round-trip verbatim').toBeUndefined();
        const processing = out['processing'] as Record<string, unknown>;
        expect(processing['dedup']).toEqual({ keys: ['call_id'], order_by: 'event_ts DESC' });
    });
});

/**
 * AUTHOR-1 parity: a `use:` component ref the flat config has no home for must refuse on BOTH sides.
 *
 * The editor's component picker is keyed on a node's category, not its type, so it writes
 * `transform/<id>` onto any TRANSFORM node and `sink/<id>` onto any sink — and nothing, in either
 * language, resolves those refs. Both lowerings dropped them in silence: the save reported success and
 * the binding was gone. If only the backend refused, the offline preview would rehearse a save the
 * real route 422s, which is the hole this whole file exists to close.
 */
describe('mock pipeline-editable — UNSUPPORTED_BINDING', () => {
    const saveable = (extra: Record<string, unknown>[] = []) => ({
        name: 'B',
        active: true,
        nodes: [
            { id: 'src', type: 'acquisition', use: 'connection/cdr', config: { poll: '/in' } },
            { id: 'parse', type: 'parser', config: { schema_file: 's.toon' } },
            ...extra,
            { id: 'write', type: 'sink.persistent', config: { database: '/db' } },
        ],
        edges: [],
    });

    const refusalsOf = (g: ReturnType<typeof saveable>) => {
        const res = lowerGraph(g as never, {}, true);
        expect('refusals' in res, 'expected a refusal').toBe(true);
        return (res as { refusals: { code: string; nodeId?: string; message: string }[] }).refusals;
    };

    it('refuses a transform component ref on a map node, naming the node and the ref', () => {
        const refusals = refusalsOf(saveable([{ id: 'map', type: 'transform.map', use: 'transform/orders_std' }]));
        expect(refusals).toHaveLength(1);
        expect(refusals[0].code).toBe('UNSUPPORTED_BINDING');
        expect(refusals[0].nodeId).toBe('map');
        expect(refusals[0].message).toContain('transform/orders_std');
    });

    it('refuses the same ref on every other transform kind, and on a sink', () => {
        for (const type of [
            'transform.filter',
            'transform.join',
            'transform.dedup',
            'transform.summarize',
            'transform.route',
        ]) {
            const refusals = refusalsOf(saveable([{ id: 't', type, use: 'transform/x' }]));
            expect(refusals[0].code, type).toBe('UNSUPPORTED_BINDING');
        }
        const g = saveable();
        (g.nodes.at(-1) as Record<string, unknown>)['use'] = 'sink/warehouse';
        expect(refusalsOf(g)[0].code).toBe('UNSUPPORTED_BINDING');
    });

    it('leaves the two homed bindings alone — the refusal is per-kind, not a ban on use:', () => {
        const res = lowerGraph(saveable() as never, {}, true);
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const config = (res as { config: Record<string, unknown> }).config;
        expect((config['collector'] as Record<string, unknown>)['connection']).toBe('cdr');
    });

    // \u26a0 The editor writes `use: enrichment/<name>` onto the node itself every time it saves the
    // companion, so refusing it (2026-08-14 \u2192 15) made every pipeline holding an enrichment node
    // unsaveable. The ref is DERIVED from the companion, not authored \u2014 dropped, never refused.
    it('does not refuse an enrichment node\u2019s companion binding', () => {
        const g = saveable([{ id: 'enrich', type: 'enrichment', use: 'enrichment/customer_lookup' }]);
        expect('config' in lowerGraph(g as never, {}, true)).toBe(true);
    });

    it('still refuses an unhomed ref on an enrichment node \u2014 only its own prefix is derived', () => {
        const refusals = refusalsOf(saveable([{ id: 'enrich', type: 'enrichment', use: 'transform/x' }]));
        expect(refusals[0].code).toBe('UNSUPPORTED_BINDING');
        expect(refusals[0].nodeId).toBe('enrich');
    });

    it('does not refuse a plugin parser\u2019s synthesized ingester/ binding', () => {
        const g = saveable();
        g.nodes[1] = { id: 'parse', type: 'parser', use: 'ingester/com.acme.Ingester', config: { ingester: 'com.acme.Ingester' } };
        expect('config' in lowerGraph(g as never, {}, true)).toBe(true);
    });
});

/**
 * AUTHOR-1 (a): the authored half of a `transform.map` node. Until this slice both sides answered
 * `written: true` and dropped it. The mock flips in the SAME commit as the server — it is contract,
 * not fixture — so these mirror `PipelineEditableTest`'s map cases one for one.
 */
describe('mock pipeline-editable — the authored map projection (processing.map)', () => {
    const columns = [
        { name: 'id_upper', expr: 'UPPER(id)' },
        { name: 'amt_major', expr: 'amt / 100' },
    ];
    const mapConfig = () => ({
        name: 'M',
        active: true,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        processing: { schema_file: 'm_schema.toon', map: { columns } },
    });

    const saveable = (extra: Record<string, unknown>[] = [], parserCfg: Record<string, unknown> = {}) => ({
        name: 'M',
        active: true,
        nodes: [
            { id: 'src', type: 'acquisition', config: { poll: '/in' } },
            { id: 'parse', type: 'parser', config: { schema_file: 's.toon', ...parserCfg } },
            ...extra,
            { id: 'write', type: 'sink.persistent', config: { database: '/db' } },
        ],
        edges: [],
    });
    const refusalsOf = (g: ReturnType<typeof saveable>) => {
        const res = lowerGraph(g as never, {}, true);
        expect('refusals' in res, 'expected a refusal').toBe(true);
        return (res as { refusals: { code: string; nodeId?: string; message: string }[] }).refusals;
    };

    // MOCK-1: the backend's `PipelineLift.branch` emits a map node on EVERY path — only its config is
    // conditional. The mock used to emit one only for an authored projection, so the offline editor drew a
    // graph one node shorter than the server's for most pipelines. ⚠ When that was fixed the entire UI
    // suite still passed with a ZERO delta: nothing anywhere pinned the derived node, which is precisely
    // why the drift survived. This is that missing guard — without it the fix silently regresses.
    it('lifts a map node even when nothing authored processing.map, and lowers it to nothing', () => {
        const bare = mapConfig() as Record<string, unknown>;
        (bare['processing'] as Record<string, unknown>) = { schema_file: 'm_schema.toon' };

        const g = liftConfig(bare);
        const map = g.nodes.find((n) => n.type === 'transform.map');
        expect(map, 'the server emits a derived map node here; the offline graph must not be shorter')
            .toBeDefined();
        expect(map!.config ?? {}, 'derived only — nothing was authored to carry').toEqual({});
        expect(
            g.edges.some((e) => e.to === 'map'),
            'the derived node must be wired into the chain, not left orphaned',
        ).toBe(true);

        // …and it round-trips to nothing: a derived-only node must not invent a processing.map on save.
        const res = lowerGraph(g, bare, true);
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const processing = (res as { config: Record<string, unknown> }).config['processing'] as Record<
            string,
            unknown
        >;
        expect(processing['map'], 'a node with no authored keys lowers to no processing.map').toBeUndefined();
    });

    it('lifts processing.map onto a map node and lowers it back verbatim', () => {
        const existing = mapConfig();
        const g = liftConfig(existing);

        const map = g.nodes.find((n) => n.type === 'transform.map');
        expect(map, 'an authored projection needs a node to carry it').toBeDefined();
        expect(map!.config?.['columns']).toEqual(columns);

        const res = lowerGraph(g, existing, true);
        expect('config' in res, JSON.stringify(res)).toBe(true);
        expect((res as { config: Record<string, unknown> }).config).toEqual(existing);
    });

    it('does not lower the lift-derived schema as authored config', () => {
        const res = lowerGraph(
            saveable([{ id: 'map', type: 'transform.map', config: { schema: { mapping: { rules: [] } } } }]) as never,
            {},
            true,
        );
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const processing = (res as { config: Record<string, unknown> }).config['processing'] as Record<string, unknown>;
        expect(processing['map']).toBeUndefined();
    });

    it('refuses a map key that is neither authored nor derived', () => {
        const refusals = refusalsOf(saveable([{ id: 'map', type: 'transform.map', config: { flavour: 'vanilla' } }]));
        expect(refusals).toHaveLength(1);
        expect(refusals[0].code).toBe('UNSUPPORTED_MAP_KEY');
        expect(refusals[0].nodeId).toBe('map');
        expect(refusals[0].message).toContain('flavour');
    });

    it('refuses authored columns alongside a declared mapping_file', () => {
        const refusals = refusalsOf(
            saveable([{ id: 'map', type: 'transform.map', config: { columns } }], { mapping_file: 'm.toon' }),
        );
        expect(refusals).toHaveLength(1);
        expect(refusals[0].code).toBe('MAPPING_CONFLICT');
        expect(refusals[0].nodeId).toBe('map');
    });

    it('refuses two map nodes whose authored config has drifted apart', () => {
        const refusals = refusalsOf(
            saveable([
                { id: 'map_a', type: 'transform.map', config: { columns } },
                { id: 'map_b', type: 'transform.map', config: { columns: [{ name: 'x', expr: '1' }] } },
            ]),
        );
        expect(refusals).toHaveLength(1);
        expect(refusals[0].code).toBe('MULTI_MAP_CONFIG');
        expect(refusals[0].nodeId).toBe('map_b');
    });

    it('keeps processing.map when the chain outgrows the singular keys and becomes steps:', () => {
        const res = lowerGraph(
            saveable([
                { id: 'map', type: 'transform.map', config: { columns } },
                { id: 'dd1', type: 'transform.dedup', config: { keys: ['a'] } },
                { id: 'dd2', type: 'transform.dedup', config: { keys: ['b'] } },
            ]) as never,
            {},
            true,
        );
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const config = (res as { config: Record<string, unknown> }).config;
        expect(config['steps'], 'two dedups cannot fit the singular key').toBeDefined();
        expect((config['processing'] as Record<string, unknown>)['map']).toEqual({ columns });
    });
});
