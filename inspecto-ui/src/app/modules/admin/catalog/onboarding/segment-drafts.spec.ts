import { describe, expect, it } from 'vitest';
import type { ParserTreeNode } from 'app/inspecto/api';
import { columnNameFor, deriveSegments, IDENTIFIER_RE, schemaDraftFor } from './segment-drafts';

const leaf = (label: string, value = 'v'): ParserTreeNode => ({ label, value });
const node = (label: string, children: ParserTreeNode[]): ParserTreeNode => ({ label, children });

describe('columnNameFor', () => {
    it('turns a dotted selector into a legal upper-case identifier', () => {
        expect(columnNameFor('imsi')).toBe('IMSI');
        expect(columnNameFor('party.number')).toBe('PARTY_NUMBER');
        expect(columnNameFor('a.b.c')).toBe('A_B_C');
    });

    it('never produces a name the engine would reject', () => {
        for (const selector of ['9lives', 'a-b', 'x y', '@weird!', '...', 'ok_1']) {
            expect(IDENTIFIER_RE.test(columnNameFor(selector))).toBe(true);
        }
    });
});

describe('deriveSegments', () => {
    const records: ParserTreeNode[] = [
        node('moCallRecord', [leaf('imsi', '42'), leaf('duration', '7'), node('party', [leaf('number', '999')])]),
        node('smsRecord', [leaf('imsi', '55')]),
        node('moCallRecord', [leaf('imsi', '77')]),
    ];

    it('proposes one segment per distinct record type, in first-seen order', () => {
        expect(deriveSegments(records).map((s) => s.key)).toEqual(['moCallRecord', 'smsRecord']);
    });

    it('maps every LEAF to a column with its dotted path as the selector', () => {
        const [moCall] = deriveSegments(records);
        expect(moCall.columns).toEqual([
            { name: 'IMSI', selector: 'imsi', type: 'VARCHAR' },
            { name: 'DURATION', selector: 'duration', type: 'VARCHAR' },
            { name: 'PARTY_NUMBER', selector: 'party.number', type: 'VARCHAR' },
        ]);
    });

    it('never proposes a container as a column — a container resolves to NULL', () => {
        const selectors = deriveSegments(records)[0].columns.map((c) => c.selector);
        expect(selectors).not.toContain('party');
    });

    it('dedupes repeated sibling labels into one selector', () => {
        const repeated = [node('rec', [leaf('tag', 'a'), leaf('tag', 'b'), leaf('other', 'c')])];
        expect(deriveSegments(repeated)[0].columns.map((c) => c.selector)).toEqual(['tag', 'other']);
    });

    it('skips a record type with no leaves and tolerates empty input', () => {
        expect(deriveSegments([node('empty', [])])).toEqual([]);
        expect(deriveSegments(null)).toEqual([]);
        expect(deriveSegments([])).toEqual([]);
    });
});

describe('schemaDraftFor', () => {
    const draft = schemaDraftFor(
        { key: 'moCallRecord', columns: [{ name: 'IMSI', selector: 'imsi', type: 'VARCHAR' }] },
        'cdr_moCallRecord',
    );

    it('writes raw.fields carrying the dotted selector the ingester reads', () => {
        expect(draft['raw']).toEqual({
            name: 'cdr_moCallRecord',
            format: 'CSV',
            fields: [{ name: 'IMSI', selector: 'imsi', type: 'VARCHAR' }],
        });
    });

    it('partitions on the derived EVENT_TYPE so rows avoid the year=1900 sentinel', () => {
        expect(draft['partitions']).toEqual([{ column: 'event_type', source: 'EVENT_TYPE', type: 'VARCHAR' }]);
    });

    it('maps each column straight through', () => {
        expect(draft['mapping']).toEqual({
            canonicalName: 'cdr_moCallRecord',
            rawName: 'cdr_moCallRecord',
            rules: [{ targetColumn: 'IMSI', sourceExpression: 'IMSI' }],
        });
    });
});
