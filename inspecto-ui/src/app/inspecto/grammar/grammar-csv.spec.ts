import { describe, expect, it } from 'vitest';
import { parsingAttributesFor } from './parsing-attributes';
import { grammarCsvFilename, grammarToCsv, parseGrammarCsv } from './grammar-csv';
import type { SchemaFieldRow } from 'app/inspecto/schema';

const SPECS = parsingAttributesFor('delimited');

const COLUMNS: SchemaFieldRow[] = [
    // D1(b): the Catalog metadata attrs ride the round-trip — the import used to drop them silently.
    {
        include: true,
        name: 'CUSTOMER_ID',
        selector: '0',
        type: 'DOUBLE',
        synonym: 'cust_no',
        description: 'billing account',
        unit: 'n/a',
        classification: 'PII',
    },
    { include: false, name: 'NOTE', selector: '1', type: 'VARCHAR' },
];

const VALUES: Record<string, unknown> = {
    delimited__delimiter: '|',
    delimited__has_header: true,
    delimited__quote: "'",
    delimited__null_strings: ['NULL', 'N/A'],
    encoding: 'latin-1',
};

/** §4.5: the format is pinned by the round-trip — export → import → deep-equal. */
describe('grammar CSV round-trip', () => {
    it('round-trips options, meta and columns deep-equal', () => {
        const csv = grammarToCsv(
            { format: 'delimited', pipeline: 'orders_daily', types: 'auto' },
            SPECS,
            VALUES,
            COLUMNS,
        );
        const back = parseGrammarCsv(csv, SPECS);

        expect(back.meta).toEqual({ format: 'delimited', pipeline: 'orders_daily', types: 'auto' });
        expect(back.options).toEqual(VALUES);
        expect(back.unknownKeys).toEqual([]);
        expect(back.columns).toEqual(COLUMNS);
    });

    it('writes engine key names, not editor spec keys', () => {
        const csv = grammarToCsv({ format: 'delimited', pipeline: 'p' }, SPECS, VALUES, []);
        expect(csv).toContain('option,delimiter,,|');
        expect(csv).not.toContain('delimited__');
    });

    it('quotes values carrying the CSV delimiter (a null_strings list survives Excel)', () => {
        const csv = grammarToCsv({ format: 'delimited', pipeline: 'p' }, SPECS, VALUES, []);
        expect(csv).toContain('"NULL,N/A"');
        const back = parseGrammarCsv(csv, SPECS);
        expect(back.options['delimited__null_strings']).toEqual(['NULL', 'N/A']);
    });

    it('lists unknown option keys and does NOT apply them (the engine would silently drop them)', () => {
        const back = parseGrammarCsv(
            'section,key,attr,value\nmeta,format,,delimited\noption,delimiter,,";"\noption,florble,,42\n',
            SPECS,
        );
        expect(back.options).toEqual({ delimited__delimiter: ';' });
        expect(back.unknownKeys).toEqual(['florble']);
        expect('florble' in back.options).toBe(false);
    });

    it('refuses a file with no meta,format row', () => {
        expect(() => parseGrammarCsv('section,key,attr,value\noption,delimiter,,";"\n', SPECS)).toThrow(/meta,format/);
    });

    it('a file without columns imports options only (columns: null — never a wholesale wipe)', () => {
        const back = parseGrammarCsv('meta,format,,delimited\noption,delimiter,,";"\n', SPECS);
        expect(back.columns).toBeNull();
    });

    it('round-trips an xlsx Grammar with bare engine key names (multiformat X4)', () => {
        const xlsxSpecs = parsingAttributesFor('xlsx');
        const values: Record<string, unknown> = {
            xlsx__sheet: 'Invoices',
            xlsx__range: 'A1:H500',
            xlsx__header: true,
            xlsx__ignore_errors: true,
        };
        const csv = grammarToCsv({ format: 'xlsx', pipeline: 'inv' }, xlsxSpecs, values, []);
        expect(csv).toContain('option,sheet,,Invoices');
        expect(csv).not.toContain('xlsx__');

        const back = parseGrammarCsv(csv, xlsxSpecs);
        expect(back.meta.format).toBe('xlsx');
        expect(back.options).toEqual(values);
        expect(back.unknownKeys).toEqual([]);
    });

    it('still reads a pre-generalization file carrying the raw spec-key spelling', () => {
        // Files exported before engineKeyOf stripped every frontend prefix wrote the spec key
        // verbatim for non-delimited frontends — those spellings must keep importing.
        const fwSpecs = parsingAttributesFor('fixedwidth');
        const back = parseGrammarCsv(
            'section,key,attr,value\nmeta,format,,fixedwidth\noption,fixedwidth__min_record_length,,40\n',
            fwSpecs,
        );
        expect(back.options).toEqual({ fixedwidth__min_record_length: 40 });
        expect(back.unknownKeys).toEqual([]);
    });

    it('names the file <pipeline>_parser.csv, verbatim per the operator', () => {
        expect(grammarCsvFilename('orders_daily')).toBe('orders_daily_parser.csv');
        expect(grammarCsvFilename('bad name!')).toBe('bad_name_parser.csv');
        expect(grammarCsvFilename('')).toBe('grammar_parser.csv');
    });
});

describe('source time zone spec', () => {
    const FRONTENDS = ['delimited', 'fixedwidth', 'json', 'xlsx'] as const;

    it('is offered on every frontend that declares the format lists it governs', () => {
        for (const f of FRONTENDS) {
            const spec = parsingAttributesFor(f).find((a) => a.key === 'source_timezone');
            expect(spec, `missing on ${f}`).toBeTruthy();
            expect(spec!.section).toBe('types'); // beside date_formats / timestamp_formats
        }
    });

    it('⛔ carries NO default — a spec default materializes into every value() and mutates copies', () => {
        for (const f of FRONTENDS) {
            const spec = parsingAttributesFor(f).find((a) => a.key === 'source_timezone')!;
            expect(spec.default).toBeUndefined();
        }
    });

    it('is PARSING-level, not a delimited__ key — the zone is a fact about the data', () => {
        const spec = parsingAttributesFor('delimited').find((a) => a.key === 'source_timezone')!;
        expect(spec.key).not.toContain('__');
    });

    it('leads with a blank option so "wall clock" is a named choice, and offers real zones', () => {
        const spec = parsingAttributesFor('delimited').find((a) => a.key === 'source_timezone')!;
        expect(spec.options?.[0].value).toBe('');
        // UTC rather than a region id: the ICU list's spelling of a given region varies by runtime
        // (this one carries Asia/Calcutta, not Asia/Kolkata), but UTC is added explicitly.
        expect(spec.options?.some((o) => o.value === 'UTC')).toBe(true);
        expect(spec.options?.some((o) => o.value === '+05:30')).toBe(false);
    });
});
