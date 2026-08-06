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
        expect((res as { refusals: { code: string }[] }).refusals.map((r) => r.code)).toContain(
            'PARSER_NO_SCHEMA',
        );
    });

    it('presents a bound Grammar as use:, not as a config key', () => {
        const existing = { ...parsingBlockConfig(), parsing: { frontend: 'delimited', grammar: 'grammar/pipe_delimited' } };

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
        expect(g.nodes.find((n) => n.type === 'transform.join')!.config).toEqual({ reference: 'reference/rates', on: 'currency' });
        expect(g.nodes.find((n) => n.type === 'transform.dedup')!.config).toEqual({ keys: ['call_id'], order_by: 'event_ts DESC' });
        expect(g.nodes.find((n) => n.type === 'transform.summarize')!.config).toEqual({ group_by: ['region'], measures: ['sum(amount)'] });
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
});
