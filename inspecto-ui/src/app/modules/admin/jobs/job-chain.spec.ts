import { describe, expect, it } from 'vitest';
import { ChainRow, chainErrors, chainRowsValid, chainToParams, parseChain, rowConfigError } from './job-chain';

/**
 * Pure mapping — no TestBed (the house idiom for `job-parameter-specs.ts` and friends).
 *
 * The cases that matter are the DISAGREEMENTS between the two params: they are aligned by position and
 * nothing on the wire enforces that, so every off-by-one has to be represented rather than smoothed.
 */

const CFG = (id: string, config: unknown) => ({ id, config });

describe('parseChain', () => {
    it('pairs a chain with its positionally-aligned configs', () => {
        const rows = parseChain('mask,rollup,report', [
            { config: { columns: 'msisdn' } },
            { config: { by: 'day' } },
            { config: {} },
        ])!;
        expect(rows.map((r) => r.id)).toEqual(['mask', 'rollup', 'report']);
        expect(JSON.parse(rows[0].configText)).toEqual({ columns: 'msisdn' });
        expect(JSON.parse(rows[1].configText)).toEqual({ by: 'day' });
    });

    it('accepts chain_config as a JSON string, the shape the textarea holds', () => {
        const rows = parseChain('mask', '[{"config":{"columns":"a"}}]')!;
        expect(JSON.parse(rows[0].configText)).toEqual({ columns: 'a' });
    });

    it('treats a missing config as an empty one — fewer entries than steps', () => {
        const rows = parseChain('mask,rollup', [{ config: { columns: 'a' } }])!;
        expect(rows).toHaveLength(2);
        expect(rows[1].id).toBe('rollup');
        expect(rows[1].configText).toBe('{}');
        expect(chainRowsValid(rows)).toBe(true); // a step needs no config
    });

    it('🔴 KEEPS a surplus config as a blank-id row instead of dropping it, and blocks the save', () => {
        const rows = parseChain('mask', [{ config: { columns: 'a' } }, { config: { by: 'day' } }])!;
        expect(rows).toHaveLength(2);
        expect(rows[1].id).toBe('');
        expect(JSON.parse(rows[1].configText)).toEqual({ by: 'day' }); // nothing lost
        expect(chainRowsValid(rows)).toBe(false);
        expect(chainErrors(rows)[0]).toMatch(/surplus 'chain_config' entry/);
    });

    it('returns null when chain_config cannot be represented, so the raw field is left alone', () => {
        expect(parseChain('mask', 'not json')).toBeNull();
        expect(parseChain('mask', '{"config":{}}')).toBeNull(); // an object, not an array
        expect(parseChain('mask', '[1,2]')).toBeNull(); // not objects
        expect(parseChain('mask', [[]])).toBeNull(); // arrays are not elements
    });

    it('reads an absent or blank chain_config as no configs at all', () => {
        expect(parseChain('mask,rollup', undefined)!.map((r) => r.configText)).toEqual(['{}', '{}']);
        expect(parseChain('mask', '   ')!).toHaveLength(1);
        expect(parseChain('', [])!).toEqual([]);
    });

    it('keeps a blank chain segment visible rather than silently compacting it', () => {
        const rows = parseChain('mask,,report', [])!;
        expect(rows.map((r) => r.id)).toEqual(['mask', '', 'report']);
        expect(chainRowsValid(rows)).toBe(false);
    });
});

describe('rowConfigError', () => {
    it('accepts a JSON object of scalars and a blank config, rejects everything else', () => {
        expect(rowConfigError({ id: 'a', configText: '{"x":1}' })).toBeNull();
        expect(rowConfigError({ id: 'a', configText: '{"x":"s","y":2,"z":true}' })).toBeNull();
        expect(rowConfigError({ id: 'a', configText: '  ' })).toBeNull();
        expect(rowConfigError({ id: 'a', configText: '{' })).toBe('not valid JSON');
        expect(rowConfigError({ id: 'a', configText: '[1]' })).toBe('must be a JSON object');
        expect(rowConfigError({ id: 'a', configText: 'null' })).toBe('must be a JSON object');
        expect(rowConfigError({ id: 'a', configText: '"s"' })).toBe('must be a JSON object');
    });

    it('🔴 refuses a nested value — the engine stores config values as TEXT and would mangle it', () => {
        expect(rowConfigError({ id: 'a', configText: '{"columns":["a","b"]}' })).toMatch(
            /must be text, a number or true\/false/,
        );
        expect(rowConfigError({ id: 'a', configText: '{"opts":{"deep":1}}' })).toMatch(/nested list or object/);
    });

    it('🔴 refuses a null value — the engine NPEs on it via Map.copyOf rather than reporting it', () => {
        expect(rowConfigError({ id: 'a', configText: '{"x":null}' })).toMatch(/cannot be null — omit the key/);
    });
});

describe('chainErrors', () => {
    it('refuses a comma inside an id — the comma is the separator', () => {
        expect(chainErrors([{ id: 'mask,rollup', configText: '{}' }])[0]).toMatch(/cannot contain a comma/);
    });

    it('reports the step number so the message points at a row', () => {
        const errs = chainErrors([
            { id: 'mask', configText: '{}' },
            { id: 'rollup', configText: '{' },
        ]);
        expect(errs).toEqual(['Step 2 config not valid JSON.']);
    });
});

describe('chainToParams', () => {
    it('emits the two params aligned by construction', () => {
        const rows: ChainRow[] = [
            { id: 'mask', configText: '{"columns":"msisdn"}' },
            { id: 'rollup', configText: '{"by":"day"}' },
        ];
        expect(chainToParams(rows)).toEqual({
            processor: 'mask,rollup',
            chain_config: [{ config: { columns: 'msisdn' } }, { config: { by: 'day' } }],
        });
    });

    it('writes an empty config rather than a short array — alignment survives a config-less step', () => {
        const out = chainToParams([
            { id: 'mask', configText: '  ' },
            { id: 'rollup', configText: '{"by":"day"}' },
        ]);
        expect(out.chain_config).toEqual([{ config: {} }, { config: { by: 'day' } }]);
    });

    it('🔴 carries an unmodelled element key through verbatim', () => {
        const out = chainToParams([
            { id: 'mask', configText: '{"columns":"a"}', element: { config: {}, note: 'authored by hand' } },
        ]);
        expect(out.chain_config[0]).toEqual({ note: 'authored by hand', config: { columns: 'a' } });
    });

    it('round-trips a chain unchanged', () => {
        const processor = 'mask,rollup,report';
        const chain_config = [CFG('x', { columns: 'a' }), CFG('y', {}), CFG('z', { by: 'day' })];
        const rows = parseChain(processor, chain_config)!;
        expect(chainRowsValid(rows)).toBe(true);
        const out = chainToParams(rows);
        expect(out.processor).toBe(processor);
        expect(out.chain_config).toEqual(chain_config); // including each element's own `id` key
    });
});
