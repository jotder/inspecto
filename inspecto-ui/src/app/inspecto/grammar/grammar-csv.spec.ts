import { describe, expect, it } from 'vitest';
import { parsingAttributesFor } from './parsing-attributes';
import { grammarCsvFilename, grammarToCsv, parseGrammarCsv } from './grammar-csv';
import type { SchemaFieldRow } from 'app/inspecto/schema';

const SPECS = parsingAttributesFor('delimited');

const COLUMNS: SchemaFieldRow[] = [
    { include: true, name: 'CUSTOMER_ID', selector: '0', type: 'DOUBLE', synonym: 'cust_no' },
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

    it('names the file <pipeline>_parser.csv, verbatim per the operator', () => {
        expect(grammarCsvFilename('orders_daily')).toBe('orders_daily_parser.csv');
        expect(grammarCsvFilename('bad name!')).toBe('bad_name_parser.csv');
        expect(grammarCsvFilename('')).toBe('grammar_parser.csv');
    });
});
