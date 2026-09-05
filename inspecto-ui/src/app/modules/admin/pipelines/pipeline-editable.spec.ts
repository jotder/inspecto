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

        const parser = g.nodes.find((n) => n.type === 'parser' || n.type === 'parser.delimited');
        expect(parser).toBeDefined();
        expect(parser!.config?.['parsing']).toEqual({
            frontend: 'delimited',
            delimited: { delimiter: '|', has_header: false },
        });
    });

    it('lowers an edited parsing: block back into parsing:, not the legacy key', () => {
        const existing = parsingBlockConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser' || n.type === 'parser.delimited')!;
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

        const parser = liftConfig(existing).nodes.find((n) => n.type === 'parser' || n.type === 'parser.delimited')!;

        expect(parser.use).toBe('grammar/pipe_delimited');
        expect((parser.config?.['parsing'] as Record<string, unknown> | undefined)?.['grammar']).toBeUndefined();
    });

    it('lowers the Grammar binding back into parsing.grammar', () => {
        const g = liftConfig(parsingBlockConfig());
        g.nodes.find((n) => n.type === 'parser' || n.type === 'parser.delimited')!.use = 'grammar/pipe_delimited';

        const res = lowerGraph(g, parsingBlockConfig(), true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['grammar']).toBe('grammar/pipe_delimited');
    });

    it('clears the ref when the Grammar is unbound', () => {
        const existing = { ...parsingBlockConfig(), parsing: { frontend: 'delimited', grammar: 'grammar/old' } };
        const g = liftConfig(existing);
        g.nodes.find((n) => n.type === 'parser' || n.type === 'parser.delimited')!.use = undefined;

        const res = lowerGraph(g, existing, true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing?.['grammar']).toBeUndefined();
    });

    it('accepts a Grammar binding as satisfying PARSER_NO_SCHEMA', () => {
        const existing = parsingBlockConfig();
        delete (existing as Record<string, unknown>)['parsing'];
        delete (existing.processing as Record<string, unknown>)['schema_file'];
        const g = liftConfig(existing);
        g.nodes.find((n) => n.type === 'parser' || n.type === 'parser.delimited')!.use = 'grammar/pipe_delimited';

        expect('refusals' in lowerGraph(g, existing, true)).toBe(false);
    });

    it('does not drop a parsing: block it was never given (non-strict merge)', () => {
        const existing = parsingBlockConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser' || n.type === 'parser.delimited')!;
        delete parser.config!['parsing'];

        const res = lowerGraph(g, existing, false);

        expect((res as { config: Record<string, unknown> }).config['parsing']).toBeDefined();
    });
});

/**
 * Parity guard for the delimited parser subtype (B6/P3a engine slice, `6bc685cf`). The engine retypes
 * on an EXPLICIT `parsing.frontend: delimited` only, stamps the frontend onto a palette-fresh node,
 * and refuses a contradiction / a second parser-family node / an ingester binding on the subtype —
 * the mock must do exactly the same, in both directions.
 */
describe('mock pipeline-editable — the delimited parser subtype (parser.delimited)', () => {
    const delimitedConfig = () => ({
        name: 'PD',
        active: true,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        parsing: { frontend: 'delimited', delimited: { delimiter: '|', has_header: false } },
        processing: { schema_file: 'pd_schema.toon' },
    });

    it('lifts an explicit frontend: delimited to the subtype and round-trips verbatim', () => {
        const existing = delimitedConfig();
        const g = liftConfig(existing);

        expect(g.nodes.find((n) => n.type === 'parser.delimited')).toBeDefined();
        const res = lowerGraph(g, existing, true);
        expect((res as { config: Record<string, unknown> }).config).toEqual(delimitedConfig());
    });

    it('keeps the plain parser type when the file never says the word (delimited is the implicit default)', () => {
        const existing = delimitedConfig();
        delete (existing as Record<string, unknown>)['parsing'];

        const g = liftConfig(existing);

        expect(g.nodes.find((n) => n.type === 'parser')).toBeDefined();
        expect(g.nodes.find((n) => n.type === 'parser.delimited')).toBeUndefined();
    });

    it('stamps frontend: delimited onto a palette-fresh subtype node', () => {
        const existing = delimitedConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.delimited')!;
        parser.config = { parsing: { delimited: { delimiter: ';' } } };

        const res = lowerGraph(g, existing, true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('delimited');
    });

    it('refuses a parsing.frontend that contradicts the node type', () => {
        const existing = delimitedConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.delimited')!;
        parser.config = { parsing: { frontend: 'json' } };

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(true);
        const refusals = (res as { refusals: { code: string; nodeId?: string }[] }).refusals;
        expect(refusals[0].code).toBe('PARSER_FRONTEND_MISMATCH');
        expect(refusals[0].nodeId).toBe(parser.id);
    });

    it('refuses a second parser-family node instead of last-one-wins', () => {
        const existing = delimitedConfig();
        const g = liftConfig(existing);
        g.nodes.push({ id: 'parse2', type: 'parser', name: 'Parser', config: { schema_file: 's.toon' } });

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(true);
        const refusals = (res as { refusals: { code: string; nodeId?: string }[] }).refusals;
        expect(refusals[0].code).toBe('MULTI_PARSER');
        expect(refusals[0].nodeId).toBe('parse2');
    });

    it('takes a grammar/ binding but refuses ingester/ on the subtype', () => {
        const existing = delimitedConfig();
        const bound = liftConfig(existing);
        bound.nodes.find((n) => n.type === 'parser.delimited')!.use = 'grammar/pipes';
        const ok = lowerGraph(bound, existing, true);
        const parsing = (ok as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['grammar']).toBe('grammar/pipes');

        const contradicted = liftConfig(existing);
        contradicted.nodes.find((n) => n.type === 'parser.delimited')!.use = 'ingester/com.example.Custom';
        const res = lowerGraph(contradicted, existing, true);
        expect('refusals' in res).toBe(true);
        expect((res as { refusals: { code: string }[] }).refusals[0].code).toBe('UNSUPPORTED_BINDING');
    });
});

/**
 * Parity guard for the fixed-width parser subtype (P3b). Mirrors `PipelineEditableTest`'s P3b block.
 * The two differences from delimited are the whole point: this frontend answers to TWO spellings
 * (`fixedwidth` / `fixed_width`, both read by `PipelineConfigParser#parseFixedWidth`), and it is
 * never implicit — so every fixed-width file retypes, binary included.
 */
describe('mock pipeline-editable — the fixed-width parser subtype (parser.fixedwidth)', () => {
    const fixedWidthConfig = (frontend = 'fixedwidth', extraFw: Record<string, unknown> = {}) => ({
        name: 'FW',
        active: true,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        parsing: {
            frontend,
            fixedwidth: { ...extraFw, fields: [{ name: 'ID', start: 0, length: 6 }] },
        },
        processing: { schema_file: 'fw_schema.toon' },
    });

    it('lifts an explicit frontend: fixedwidth to the subtype and round-trips verbatim', () => {
        const existing = fixedWidthConfig();
        const g = liftConfig(existing);

        expect(g.nodes.find((n) => n.type === 'parser.fixedwidth')).toBeDefined();
        const res = lowerGraph(g, existing, true);
        expect((res as { config: Record<string, unknown> }).config).toEqual(fixedWidthConfig());
    });

    it('lifts the alternate fixed_width spelling too, and does not canonicalise it on the way back', () => {
        const existing = fixedWidthConfig('fixed_width');
        const g = liftConfig(existing);

        expect(g.nodes.find((n) => n.type === 'parser.fixedwidth')).toBeDefined();
        const res = lowerGraph(g, existing, true);
        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('fixed_width');
    });

    it('lifts a binary (record: bytes) config to the subtype as well', () => {
        const g = liftConfig(fixedWidthConfig('fixedwidth', { record: 'bytes', record_length: 24 }));
        expect(g.nodes.find((n) => n.type === 'parser.fixedwidth')).toBeDefined();
    });

    it('stamps the CANONICAL frontend onto a palette-fresh subtype node', () => {
        const existing = fixedWidthConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.fixedwidth')!;
        parser.config = { parsing: { fixedwidth: { fields: [{ name: 'ID', start: 0, length: 6 }] } } };

        const res = lowerGraph(g, existing, true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('fixedwidth');
    });

    it('accepts EITHER spelling on the node but refuses a foreign frontend', () => {
        for (const spelling of ['fixedwidth', 'fixed_width']) {
            const existing = fixedWidthConfig();
            const g = liftConfig(existing);
            g.nodes.find((n) => n.type === 'parser.fixedwidth')!.config = { parsing: { frontend: spelling } };
            expect('refusals' in lowerGraph(g, existing, true)).toBe(false);
        }

        const existing = fixedWidthConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.fixedwidth')!;
        parser.config = { parsing: { frontend: 'delimited' } };

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(true);
        const refusals = (res as { refusals: { code: string; nodeId?: string }[] }).refusals;
        expect(refusals[0].code).toBe('PARSER_FRONTEND_MISMATCH');
        expect(refusals[0].nodeId).toBe(parser.id);
    });
});

/**
 * Parity guard for the ASN.1 parser subtype (P3c). One spelling only, never implicit, and the
 * grammar rides INLINE in the asn1: block (grammar text, root_type, strictness, headers, segments) —
 * so lift/lower is pure carry: nothing here reads inside the block.
 */
describe('mock pipeline-editable — the ASN.1 parser subtype (parser.asn1)', () => {
    const asn1Config = () => ({
        name: 'A1',
        active: true,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        parsing: {
            frontend: 'asn1',
            asn1: {
                grammar: 'CDR DEFINITIONS ::= BEGIN Record ::= SEQUENCE { id [0] IA5String } END',
                root_type: 'Record',
                strictness: 'BER',
                segments: { Record: 'config/record_schema.toon' },
            },
        },
        processing: {},
    });

    it('lifts frontend: asn1 to the subtype and round-trips the block — segments included — verbatim', () => {
        const existing = asn1Config();
        const g = liftConfig(existing);

        expect(g.nodes.find((n) => n.type === 'parser.asn1')).toBeDefined();
        const res = lowerGraph(g, existing, true);
        expect((res as { config: Record<string, unknown> }).config).toEqual(asn1Config());
    });

    it('stamps frontend: asn1 onto a palette-fresh node', () => {
        const existing = asn1Config();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.asn1')!;
        parser.config = { parsing: { asn1: { root_type: 'Record' } } };

        const res = lowerGraph(g, existing, true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('asn1');
    });

    it('refuses a foreign frontend on an asn1 node', () => {
        const existing = asn1Config();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.asn1')!;
        parser.config = { parsing: { frontend: 'delimited' } };

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(true);
        const refusals = (res as { refusals: { code: string; nodeId?: string }[] }).refusals;
        expect(refusals[0].code).toBe('PARSER_FRONTEND_MISMATCH');
        expect(refusals[0].nodeId).toBe(parser.id);
    });

    /**
     * ⚠ The gap the round-trip test caught: `frontend: asn1` makes the config parser synthesize an
     * `Asn1RecordIngester` binding, so the LIFT reads a class back and presents `use: ingester/<fqcn>`
     * on a node whose only authored home is `grammar/`. Refusing it would make every ASN.1 pipeline
     * unsaveable — the enrichment regression by another route — so it is DERIVED and dropped silently.
     * An unrelated homeless ref on the same node still refuses.
     */
    it('drops the synthesized ingester/ ref as derived, and takes a grammar/ binding', () => {
        const existing = asn1Config();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.asn1')!;

        parser.use = 'grammar/vendor_cdr';
        expect('refusals' in lowerGraph(g, existing, true)).toBe(false);

        parser.use = 'ingester/com.gamma.ingester.Asn1RecordIngester';
        expect('refusals' in lowerGraph(g, existing, true)).toBe(false);

        parser.use = 'transform/nope';
        const res = lowerGraph(g, existing, true);
        expect('refusals' in res).toBe(true);
        expect((res as { refusals: { code: string }[] }).refusals[0].code).toBe('UNSUPPORTED_BINDING');
    });
});

/**
 * Parity guard for the generic custom-plugin subtype (P3d slice D). Mirrors `PipelineEditableTest`'s
 * slice-D block: `frontend: plugin` wires the SAME ingester/ingester_config/segments triple the
 * framework already carried before this node type existed, and — unlike every other subtype — this
 * one's `use:` DOES take `ingester/`, because the lift presents the config's own FQCN back as a
 * derived ref, exactly as it always did for the plain `parser` type.
 */
describe('mock pipeline-editable — the generic custom-plugin subtype (parser.plugin)', () => {
    const pluginConfig = () => ({
        name: 'PLUGIN_FEED',
        active: true,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        parsing: {
            frontend: 'plugin',
            plugin: {
                ingester: 'com.example.acme.AcmeFeedIngester',
                ingester_config: { mode: 'strict' },
                segments: { Record: 'config/record_schema.toon' },
            },
        },
        processing: {},
    });

    it('lifts frontend: plugin to the subtype and round-trips the block — segments included — verbatim', () => {
        const existing = pluginConfig();
        const g = liftConfig(existing);

        expect(g.nodes.find((n) => n.type === 'parser.plugin')).toBeDefined();
        const res = lowerGraph(g, existing, true);
        expect((res as { config: Record<string, unknown> }).config).toEqual(pluginConfig());
    });

    it('stamps frontend: plugin onto a palette-fresh node', () => {
        const existing = pluginConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.plugin')!;
        parser.config = { parsing: { plugin: { ingester: 'com.example.acme.AcmeFeedIngester' } } };

        const res = lowerGraph(g, existing, true);

        const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('plugin');
    });

    it('refuses a foreign frontend on a plugin node', () => {
        const existing = pluginConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.plugin')!;
        parser.config = { parsing: { frontend: 'delimited' } };

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(true);
        const refusals = (res as { refusals: { code: string; nodeId?: string }[] }).refusals;
        expect(refusals[0].code).toBe('PARSER_FRONTEND_MISMATCH');
        expect(refusals[0].nodeId).toBe(parser.id);
    });

    /** Unlike every built-in subtype, this one DOES accept a derived ingester/ ref — but only that
     *  exact prefix; an unrelated binding on the same node still refuses. */
    it('accepts the derived ingester/ ref (and a grammar/ binding) but refuses an unrelated one', () => {
        const existing = pluginConfig();
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.type === 'parser.plugin')!;

        parser.use = 'grammar/vendor_feed';
        expect('refusals' in lowerGraph(g, existing, true)).toBe(false);

        parser.use = 'ingester/com.example.acme.AcmeFeedIngester';
        expect('refusals' in lowerGraph(g, existing, true)).toBe(false);

        parser.use = 'transform/nope';
        const res = lowerGraph(g, existing, true);
        expect('refusals' in res).toBe(true);
        expect((res as { refusals: { code: string }[] }).refusals[0].code).toBe('UNSUPPORTED_BINDING');
    });
});

/**
 * Parity guard for the last two built-in frontends to get their own node type (P3d). Mirrors
 * `PipelineEditableTest`'s P3d block. Neither is implicit — delimited alone is the parser's default —
 * so every such file retypes, and each answers to exactly one spelling.
 */
describe('mock pipeline-editable — the JSON and text/regex parser subtypes', () => {
    const cases = [
        { type: 'parser.json', frontend: 'json', grammar: { format: 'newline' } },
        { type: 'parser.text_regex', frontend: 'text_regex', grammar: { pattern: '(?<ID>\\w+) (?<TS>.+)' } },
    ];
    const configFor = (c: (typeof cases)[number]) => ({
        name: 'BF',
        active: true,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        parsing: { frontend: c.frontend, [c.frontend]: { ...c.grammar } },
        processing: { schema_file: 'bf_schema.toon' },
    });

    for (const c of cases) {
        it(`lifts frontend: ${c.frontend} to ${c.type} and round-trips verbatim`, () => {
            const existing = configFor(c);
            const g = liftConfig(existing);

            expect(g.nodes.find((n) => n.type === c.type)).toBeDefined();
            const res = lowerGraph(g, existing, true);
            expect((res as { config: Record<string, unknown> }).config).toEqual(configFor(c));
        });

        it(`stamps frontend: ${c.frontend} onto a palette-fresh ${c.type} node`, () => {
            const existing = configFor(c);
            const g = liftConfig(existing);
            g.nodes.find((n) => n.type === c.type)!.config = { parsing: { [c.frontend]: { ...c.grammar } } };

            const res = lowerGraph(g, existing, true);

            const parsing = (res as { config: Record<string, unknown> }).config['parsing'] as Record<string, unknown>;
            expect(parsing['frontend']).toBe(c.frontend);
        });

        it(`refuses a foreign frontend on a ${c.type} node`, () => {
            const existing = configFor(c);
            const g = liftConfig(existing);
            const parser = g.nodes.find((n) => n.type === c.type)!;
            parser.config = { parsing: { frontend: 'fixedwidth' } };

            const res = lowerGraph(g, existing, true);

            expect('refusals' in res).toBe(true);
            const refusals = (res as { refusals: { code: string; nodeId?: string }[] }).refusals;
            expect(refusals[0].code).toBe('PARSER_FRONTEND_MISMATCH');
            expect(refusals[0].nodeId).toBe(parser.id);
        });

        it(`homes a grammar/ binding on ${c.type} but refuses an ingester/ one`, () => {
            const existing = configFor(c);
            const g = liftConfig(existing);
            const parser = g.nodes.find((n) => n.type === c.type)!;

            parser.use = 'grammar/vendor_feed';
            expect('refusals' in lowerGraph(g, existing, true)).toBe(false);

            // Unlike asn1, nothing synthesizes an ingester for a plain built-in, so a class binding
            // here is an authoring mistake and must say so rather than be dropped.
            parser.use = 'ingester/com.gamma.ingester.Asn1RecordIngester';
            const res = lowerGraph(g, existing, true);
            expect('refusals' in res).toBe(true);
            expect((res as { refusals: { code: string }[] }).refusals[0].code).toBe('UNSUPPORTED_BINDING');
        });
    }
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
        const refusals = refusalsOf(saveable([{ id: 'map', type: 'transform.sql', use: 'transform/orders_std' }]));
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
        g.nodes[1] = {
            id: 'parse',
            type: 'parser',
            use: 'ingester/com.acme.Ingester',
            config: { ingester: 'com.acme.Ingester' },
        };
        expect('config' in lowerGraph(g as never, {}, true)).toBe(true);
    });
});

/**
 * AUTHOR-1 (a): the authored half of the projection slot (a `transform.sql` node with id `map`). Until this slice both sides answered
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
        const map = g.nodes.find((n) => n.id === 'map');
        expect(map, 'the server emits a derived slot node here; the offline graph must not be shorter').toBeDefined();
        expect(map!.type, 'the slot is a Record Transformer — transform.map is gone').toBe('transform.sql');
        expect(map!.config ?? {}, 'derived only — nothing was authored to carry').toEqual({});
        expect(
            g.edges.some((e) => e.to === 'map'),
            'the derived node must be wired into the chain, not left orphaned',
        ).toBe(true);

        // …and it round-trips to nothing: a derived-only node must not invent a processing.map on save.
        const res = lowerGraph(g, bare, true);
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const processing = (res as { config: Record<string, unknown> }).config['processing'] as Record<string, unknown>;
        expect(processing['map'], 'a node with no authored keys lowers to no processing.map').toBeUndefined();
    });

    it('lifts processing.map onto a map node and lowers it back verbatim', () => {
        const existing = mapConfig();
        const g = liftConfig(existing);

        const map = g.nodes.find((n) => n.id === 'map');
        expect(map, 'an authored projection needs a node to carry it').toBeDefined();
        expect(map!.config?.['columns']).toEqual(columns);

        const res = lowerGraph(g, existing, true);
        expect('config' in res, JSON.stringify(res)).toBe(true);
        expect((res as { config: Record<string, unknown> }).config).toEqual(existing);
    });

    it('does not lower the lift-derived schema as authored config', () => {
        const res = lowerGraph(
            saveable([{ id: 'map', type: 'transform.sql', config: { schema: { mapping: { rules: [] } } } }]) as never,
            {},
            true,
        );
        expect('config' in res, JSON.stringify(res)).toBe(true);
        const processing = (res as { config: Record<string, unknown> }).config['processing'] as Record<string, unknown>;
        expect(processing['map']).toBeUndefined();
    });

    it('refuses a map key that is neither authored nor derived', () => {
        const refusals = refusalsOf(saveable([{ id: 'map', type: 'transform.sql', config: { flavour: 'vanilla' } }]));
        expect(refusals).toHaveLength(1);
        expect(refusals[0].code).toBe('UNSUPPORTED_MAP_KEY');
        expect(refusals[0].nodeId).toBe('map');
        expect(refusals[0].message).toContain('flavour');
    });

    it('refuses authored columns alongside a declared mapping_file', () => {
        const refusals = refusalsOf(
            saveable([{ id: 'map', type: 'transform.sql', config: { columns } }], { mapping_file: 'm.toon' }),
        );
        expect(refusals).toHaveLength(1);
        expect(refusals[0].code).toBe('MAPPING_CONFLICT');
        expect(refusals[0].nodeId).toBe('map');
    });

    it('refuses two map nodes whose authored config has drifted apart', () => {
        const refusals = refusalsOf(
            saveable([
                { id: 'map_a', type: 'transform.sql', config: { columns } },
                { id: 'map_b', type: 'transform.sql', config: { columns: [{ name: 'x', expr: '1' }] } },
            ]),
        );
        expect(refusals).toHaveLength(1);
        expect(refusals[0].code).toBe('MULTI_MAP_CONFIG');
        expect(refusals[0].nodeId).toBe('map_b');
    });

    it('keeps processing.map when the chain outgrows the singular keys and becomes steps:', () => {
        const res = lowerGraph(
            saveable([
                { id: 'map', type: 'transform.sql', config: { columns } },
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

/**
 * Parity guard for P5-a: marker dedup moved off its own `transform.dedup.marker` node onto the
 * acquisition node, where the fingerprint policy already lived. Mirrors `PipelineEditableTest`'s
 * three cases — a mock that kept emitting the node would put the offline editor one node AHEAD of
 * the server, which is the node-count drift MOCK-1 exists to catch, in reverse.
 */
describe('mock pipeline-editable — marker dedup rides acquisition (P5-a)', () => {
    const markerConfig = () => ({
        name: 'MD',
        active: true,
        dirs: { poll: '/in', database: '/db', markers: '/markers' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        processing: {
            schema_file: 's.toon',
            duplicate_check: { enabled: true, marker_extension: '.done', retention_days: 30 },
        },
    });

    it('lifts the keys onto acq (no marker node) and lowers them back to their own blocks', () => {
        const g = liftConfig(markerConfig());
        expect(g.nodes.find((n) => n.type === 'transform.dedup.marker')).toBeUndefined();
        const acq = g.nodes.find((n) => n.type === 'acquisition')!;
        expect(acq.config).toMatchObject({
            duplicate_check: true,
            marker_extension: '.done',
            retention_days: 30,
            markers_dir: '/markers',
        });
        // the parser is fed directly, with no dedup node in between
        expect(g.edges.find((e) => e.to === 'parse')?.from).toBe('acq');

        const res = lowerGraph(g, markerConfig(), true);
        const config = (res as { config: Record<string, unknown> }).config;
        expect((config['processing'] as Record<string, unknown>)['duplicate_check']).toEqual({
            enabled: true,
            marker_extension: '.done',
            retention_days: 30,
        });
        expect((config['dirs'] as Record<string, unknown>)['markers']).toBe('/markers');
        // ⚠ the leak guard: acq's config is dumped wholesale into collector:, so a borrowed key
        // missing from ACQ_FOREIGN_KEYS would land in a block nothing reads it from
        const collector = config['collector'] as Record<string, unknown>;
        for (const k of ['duplicate_check', 'marker_extension', 'retention_days', 'markers_dir'])
            expect(collector[k], `${k} must not leak into collector:`).toBeUndefined();
    });

    it('still lowers a legacy standalone marker node — read-compat, never re-emitted', () => {
        const g = liftConfig(markerConfig());
        const acq = g.nodes.find((n) => n.type === 'acquisition')!;
        for (const k of ['duplicate_check', 'marker_extension', 'retention_days', 'markers_dir']) delete acq.config![k];
        g.nodes.push({
            id: 'dedup_marker',
            type: 'transform.dedup.marker',
            config: { retention_days: 7 },
        });

        const res = lowerGraph(g, markerConfig(), true);
        const processing = (res as { config: Record<string, unknown> }).config['processing'] as Record<string, unknown>;
        expect(processing['duplicate_check']).toEqual({ enabled: true, retention_days: 7 });
    });

    it('lets an explicit duplicate_check:false beat a stale marker node', () => {
        const g = liftConfig(markerConfig());
        g.nodes.find((n) => n.type === 'acquisition')!.config!['duplicate_check'] = false;
        g.nodes.push({
            id: 'dedup_marker',
            type: 'transform.dedup.marker',
            config: { retention_days: 7 },
        });

        const res = lowerGraph(g, markerConfig(), true);
        const config = (res as { config: Record<string, unknown> }).config;
        expect((config['processing'] as Record<string, unknown>)['duplicate_check']).toBeUndefined();
        expect((config['dirs'] as Record<string, unknown>)['markers']).toBeUndefined();
    });
});

describe('mock pipeline-editable — processing.disabled_steps (Phase 4 S4a)', () => {
    // Mirrors PipelineLift's overlay + PipelineEditable.lower's derivation, pinned so the offline
    // editor can never show a different disabled state than the backend lifts.
    const cfgWithDisabled = () => ({
        name: 'DS',
        active: false,
        dirs: { poll: '/in', database: '/db' },
        output: { format: 'CSV' },
        collector: { connector: 'local' },
        processing: { schema_file: 'ds_schema.toon', disabled_steps: ['parse'] },
    });

    it('overlays enabled:false onto the named node on lift', () => {
        const g = liftConfig(cfgWithDisabled());
        const parser = g.nodes.find((n) => n.id === 'parse');
        expect(parser?.config?.['enabled']).toBe(false);
        expect(g.nodes.find((n) => n.id === 'acq')?.config?.['enabled']).toBeUndefined();
    });

    it('derives the list back from node state on lower — round-trip', () => {
        const existing = cfgWithDisabled();
        const res = lowerGraph(liftConfig(existing), existing, false);
        expect('config' in res).toBe(true);
        const config = (res as { config: Record<string, unknown> }).config;
        expect((config['processing'] as Record<string, unknown>)['disabled_steps']).toEqual(['parse']);
    });

    it('a re-enable clears the entry even on a lenient (draft) lower', () => {
        const existing = cfgWithDisabled();
        const g = liftConfig(existing);
        for (const n of g.nodes) if (n.config) delete n.config['enabled'];
        const res = lowerGraph(g, existing, false);
        const config = (res as { config: Record<string, unknown> }).config;
        expect((config['processing'] as Record<string, unknown>)['disabled_steps']).toBeUndefined();
    });

    it("accepts the Java spelling 'false' (string) too — PipelineNode.enabled() parity", () => {
        const existing = { ...cfgWithDisabled(), processing: { schema_file: 'ds_schema.toon' } };
        const g = liftConfig(existing);
        const parser = g.nodes.find((n) => n.id === 'parse');
        if (parser) parser.config = { ...(parser.config ?? {}), enabled: 'false' };
        const res = lowerGraph(g, existing, false);
        const config = (res as { config: Record<string, unknown> }).config;
        expect((config['processing'] as Record<string, unknown>)['disabled_steps']).toEqual(['parse']);
    });
});

describe('mock pipeline-editable — an authored sinks: block IS the destination list (MOCK-LIFT-1)', () => {
    // The case that exposed the divergence: every sinks[] entry differs from dirs.database. The backend
    // parser synthesises the output:/dirs.database shorthand ONLY when `sinks:` is absent, and
    // dirs.database stays the fan-out's BASE (IngestSinkWriter re-roots each destination against it).
    const fanOut = () => ({
        name: 'FAN',
        active: false,
        dirs: { poll: '/in', database: '/db', temp: '/tmp' },
        output: { format: 'PARQUET' },
        sinks: [
            { database: '/out/a', format: 'CSV' },
            { database: '/out/b', format: 'CSV' },
        ],
        processing: { threads: 1, schema_file: 's.toon' },
    });

    it('lifts one sink node per entry — no invented trunk — with the backend id grammar', () => {
        const g = liftConfig(fanOut());
        const sinks = g.nodes.filter((n) => n.type === 'sink.persistent');
        expect(sinks.map((n) => n.id)).toEqual(['sink__d0', 'sink__d1']);
        expect(sinks.map((n) => n.config?.['database'])).toEqual(['/out/a', '/out/b']);
        // The config-level keys the lower reads back ride the FIRST destination.
        expect(sinks[0].config?.['temp']).toBe('/tmp');
        expect(sinks[0].config?.['threads']).toBe(1);
    });

    it('round-trips without inventing a destination or moving the fan-out base', () => {
        const cfg = fanOut();
        const res = lowerGraph(liftConfig(cfg), cfg, false);
        expect('config' in res).toBe(true);
        const out = (res as { config: Record<string, unknown> }).config;
        expect(out['sinks']).toEqual([
            { database: '/out/a', format: 'CSV' },
            { database: '/out/b', format: 'CSV' },
        ]);
        // 🔴 The regression this pins: dirs.database is the BASE, never the first destination.
        expect((out['dirs'] as Record<string, unknown>)['database']).toBe('/db');
        expect((out['output'] as Record<string, unknown>)['format']).toBe('PARQUET');
    });

    it('keeps a single-entry block a block — collapsing it would move the write root', () => {
        const cfg = { ...fanOut(), sinks: [{ database: '/out/only', format: 'CSV' }] };
        const g = liftConfig(cfg);
        expect(g.nodes.filter((n) => n.type === 'sink.persistent').map((n) => n.id)).toEqual(['sink']);
        const res = lowerGraph(g, cfg, false);
        const out = (res as { config: Record<string, unknown> }).config;
        expect(out['sinks']).toEqual([{ database: '/out/only', format: 'CSV' }]);
        expect((out['dirs'] as Record<string, unknown>)['database']).toBe('/db');
    });

    it('still lowers a shorthand pipeline (no sinks: block) to output:/dirs.database', () => {
        const cfg = {
            name: 'ONE',
            active: false,
            dirs: { poll: '/in', database: '/db' },
            output: { format: 'CSV' },
            processing: { threads: 1, schema_file: 's.toon' },
        };
        const res = lowerGraph(liftConfig(cfg), cfg, false);
        const out = (res as { config: Record<string, unknown> }).config;
        expect(out['sinks']).toBeUndefined();
        expect((out['dirs'] as Record<string, unknown>)['database']).toBe('/db');
    });
});

/**
 * MIDBRANCH-1 (R3): per-branch `steps:` sub-chains. The mirror must flatten exactly as
 * `PipelineLift.emitSinks` does (route:<key> → step₁ → … → branch sink, ids `<kind>__<key>`),
 * lower them back into `route.branches[].steps[]` exactly as `PipelineEditable.routeSection`
 * does (pairing by the chain's TERMINAL sink), and refuse the same malformed shapes
 * (UNSUPPORTED_BRANCH_STEP) — the offline lower must produce what the server produces.
 */
describe('mock pipeline-editable — route branch steps (MIDBRANCH-1)', () => {
    const branchStepsConfig = () => ({
        name: 'RB',
        active: false,
        dirs: { poll: '/in', database: '/hi_db' },
        collector: { connector: 'local' },
        processing: { schema_file: 'rb_schema.toon' },
        sinks: [{ database: '/hi_db' }, { database: '/lo_db' }],
        route: {
            mode: 'case',
            default: 'lo',
            branches: [
                {
                    key: 'hi',
                    where: 'AMT >= 200',
                    database: '/hi_db',
                    steps: [{ filter: { where: 'AMT > 0' } }, { dedup: { keys: ['ID'] } }],
                },
                { key: 'lo', where: 'AMT < 200', database: '/lo_db' },
            ],
        },
    });

    it('lifts a branch chain into flattened nodes wired route:<key> → step → … → sink', () => {
        const g = liftConfig(branchStepsConfig());

        const filter = g.nodes.find((n) => n.id === 'filter__hi');
        const dedup = g.nodes.find((n) => n.id === 'dedup__hi');
        expect(filter?.type).toBe('transform.filter');
        expect(filter?.config).toEqual({ where: 'AMT > 0' });
        expect(dedup?.type).toBe('transform.dedup');
        expect(dedup?.config).toEqual({ keys: ['ID'] });
        expect(g.edges).toContainEqual({ from: 'route', rel: 'route:hi', to: 'filter__hi' });
        expect(g.edges).toContainEqual({ from: 'filter__hi', rel: 'data', to: 'dedup__hi' });
        expect(g.edges).toContainEqual({ from: 'dedup__hi', rel: 'data', to: 'sink__d0' });
        // the branch without steps keeps the pre-R3 direct edge
        expect(g.edges).toContainEqual({ from: 'route', rel: 'route:lo', to: 'sink__d1' });
    });

    it("lowers the chain back into that branch's steps[], in order, pairing by the terminal sink", () => {
        const existing = branchStepsConfig();
        const g = liftConfig(existing);

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(false);
        const route = (res as { config: Record<string, unknown> }).config['route'] as Record<string, unknown>;
        const branches = route['branches'] as Record<string, unknown>[];
        expect(branches[0]['database']).toBe('/hi_db');
        expect(branches[0]['steps']).toEqual([{ filter: { where: 'AMT > 0' } }, { dedup: { keys: ['ID'] } }]);
        expect('steps' in branches[1]).toBe(false);
    });

    it('drops a stale steps key when the author deletes the chain nodes', () => {
        const existing = branchStepsConfig();
        const g = liftConfig(existing);
        g.nodes = g.nodes.filter((n) => n.id !== 'filter__hi' && n.id !== 'dedup__hi');
        g.edges = g.edges.filter((e) => !e.from.includes('__hi') && e.to !== 'filter__hi');
        g.edges.push({ from: 'route', rel: 'route:hi', to: 'sink__d0' });

        const res = lowerGraph(g, existing, true);

        const route = (res as { config: Record<string, unknown> }).config['route'] as Record<string, unknown>;
        const branches = route['branches'] as Record<string, unknown>[];
        expect('steps' in branches[0]).toBe(false);
    });

    it("refuses a branch chain that does not end at a persistent sink — the server's code", () => {
        const existing = branchStepsConfig();
        const g = liftConfig(existing);
        // sever the chain's tail so dedup__hi dead-ends
        g.edges = g.edges.filter((e) => !(e.from === 'dedup__hi' && e.to === 'sink__d0'));

        const res = lowerGraph(g, existing, true);

        expect('refusals' in res).toBe(true);
        expect((res as { refusals: { code: string }[] }).refusals.map((r) => r.code)).toContain(
            'UNSUPPORTED_BRANCH_STEP',
        );
    });

    it('a no-edit lift → lower round trip reproduces the file, branch steps included', () => {
        const existing = branchStepsConfig();
        const res = lowerGraph(liftConfig(existing), existing, true);
        expect('config' in res).toBe(true);
        expect((res as { config: Record<string, unknown> }).config['route']).toEqual(existing.route);
    });
});
