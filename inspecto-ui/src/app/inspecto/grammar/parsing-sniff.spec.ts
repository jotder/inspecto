import { describe, expect, it } from 'vitest';
import { jsonSampleToTree, sniffFrontend, suggestTypes } from './parsing-sniff';

describe('sniffFrontend', () => {
    it('recognises a JSON array document', () => {
        const s = sniffFrontend('[{"a": 1}, {"a": 2}]');
        expect(s?.frontend).toBe('json');
        expect(s?.reason).toContain('array');
    });

    it('recognises NDJSON', () => {
        const s = sniffFrontend('{"a": 1}\n{"a": 2}\n');
        expect(s?.frontend).toBe('json');
        expect(s?.reason).toContain('NDJSON');
    });

    it('sniffs a consistent CSV delimiter and reports the column count', () => {
        const s = sniffFrontend('id,name,qty\n1,apple,3\n2,pear,5\n');
        expect(s).toEqual({ frontend: 'delimited', reason: '3 columns split by commas', delimiter: ',' });
    });

    it('prefers the candidate splitting into more columns (pipe data containing commas)', () => {
        const s = sniffFrontend('a|b,c|d\n1|2,3|4\n');
        expect(s?.delimiter).toBe('|');
    });

    it('sniffs tabs', () => {
        const s = sniffFrontend('a\tb\n1\t2\n');
        expect(s?.delimiter).toBe('\t');
        expect(s?.reason).toContain('tabs');
    });

    it('returns null for inconsistent or un-delimited text', () => {
        expect(sniffFrontend('just a line of prose\nanother, one with a comma\n')).toBeNull();
        expect(sniffFrontend('')).toBeNull();
        expect(sniffFrontend('one\ntwo\nthree\n')).toBeNull();
    });

    it('does not call malformed JSON lines NDJSON', () => {
        expect(sniffFrontend('{"a": 1}\n{oops\n')).toBeNull();
    });
});

describe('suggestTypes', () => {
    it('suggests DOUBLE, DATE, TIMESTAMP and VARCHAR conservatively', () => {
        const rows = [
            { n: '1.5', d: '2026-07-29', t: '2026-07-29 10:00:00', s: 'abc', m: '1' },
            { n: '-2e3', d: '2026-07-30', t: '2026-07-30T11:30', s: '42', m: 'x' },
        ];
        expect(suggestTypes(['n', 'd', 't', 's', 'm'], rows)).toEqual({
            n: 'DOUBLE',
            d: 'DATE',
            t: 'TIMESTAMP',
            s: 'VARCHAR', // mixed values
            m: 'VARCHAR', // one numeric + one not ⇒ no vote
        });
    });

    it('blanks and NULLs do not vote; an all-blank column stays VARCHAR', () => {
        const rows = [
            { a: '7', b: null },
            { a: '', b: '' },
            { a: '9', b: null },
        ];
        expect(suggestTypes(['a', 'b'], rows)).toEqual({ a: 'DOUBLE', b: 'VARCHAR' });
    });
});

describe('jsonSampleToTree', () => {
    it('builds a record forest from NDJSON with nested containers', () => {
        const nodes = jsonSampleToTree('{"id": 1, "meta": {"tag": "x"}, "vals": [1, 2]}\n')!;
        expect(nodes).toHaveLength(1);
        const rec = nodes[0];
        expect(rec.label).toBe('record 1');
        expect(rec.children!.map((c) => c.label)).toEqual(['id', 'meta', 'vals']);
        const meta = rec.children![1];
        expect(meta.type).toBe('object');
        expect(meta.children![0]).toEqual({ label: 'tag', type: 'string', value: 'x' });
        const vals = rec.children![2];
        expect(vals.type).toBe('array');
        expect(vals.children!.map((c) => c.label)).toEqual(['[0]', '[1]']);
    });

    it('accepts a JSON array document and caps the record count', () => {
        const doc = JSON.stringify(Array.from({ length: 60 }, (_, i) => ({ i })));
        expect(jsonSampleToTree(doc)!.length).toBe(50);
    });

    it('returns null when nothing parses', () => {
        expect(jsonSampleToTree('a,b\n1,2\n')).toBeNull();
        expect(jsonSampleToTree('')).toBeNull();
    });
});
