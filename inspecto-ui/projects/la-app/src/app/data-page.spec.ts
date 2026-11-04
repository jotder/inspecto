import { describe, expect, it } from 'vitest';
import type { ComponentDef } from '@inspecto/core/api';
import { dataEntry, driftedColumns, formOf, mappingEntry, mappingLine, roleOf, typeOf } from './data-page';

const TYPES = [
    { id: 'account', label: 'Account', classifications: ['ACCOUNT_ID'] },
    { id: 'channel', label: 'Channel', classifications: ['CHANNEL'] },
];
const def = (name: string, content: Record<string, unknown>): ComponentDef =>
    ({ type: 'dataset', name, ref: `dataset/${name}`, content }) as ComponentDef;
const BASE = dataEntry(
    def('transfers', {
        origin: 'import',
        kind: 'stream',
        physicalRef: 'transfers/database',
        importPipeline: 'transfers',
        columns: [
            { name: 'from_account', type: 'string', classification: 'ACCOUNT_ID' },
            { name: 'to_account', type: 'string', classification: 'ACCOUNT_ID' },
            { name: 'channel', type: 'string' },
            { name: 'booked_at', type: 'timestamp' },
        ],
        defaultLinkMapping: { from: 'from_account', to: 'to_account', time: 'booked_at' },
        eventDate: 'booked_at',
    }),
);

describe('the Data page model (LA-DATA-PAGE-1)', () => {
    it('reads an entry: origin, columns, its link mapping', () => {
        expect(BASE.origin).toBe('import');
        expect(BASE.columns.map((c) => c.name)).toEqual(['from_account', 'to_account', 'channel', 'booked_at']);
        expect(mappingLine(BASE.mapping)).toBe('from_account → to_account · when booked_at');
        expect(dataEntry(def('x', { origin: 'mapping', basedOn: 'transfers' })).basedOn).toBe('transfers');
        expect(dataEntry(def('y', {})).origin).toBe('other');
        expect(mappingLine(null)).toBe('No link mapping yet');
    });

    it('LA-PIPELINE-MAPPINGS-1: which Datasets may be a base, and the columns a Mapping reads that its base dropped', () => {
        const cols = [
            { name: 'a', type: 'string' },
            { name: 'b', type: 'string' },
        ];
        expect(BASE.mappable).toBe(true);
        expect(dataEntry(def('piped', { physicalRef: 'p/database', columns: cols })).mappable).toBe(true);
        expect(dataEntry(def('few', { physicalRef: 'p/database', columns: cols.slice(0, 1) })).mappable).toBe(false);
        expect(dataEntry(def('calc', { physicalRef: 'p/database', sql: 'SELECT 1', columns: cols })).mappable).toBe(
            false,
        );
        expect(dataEntry(def('nowhere', { columns: cols })).mappable).toBe(false);
        expect(dataEntry(def('entity_list_watch', { physicalRef: 'entity_list_watch', columns: cols })).mappable).toBe(
            false,
        );
        expect(dataEntry(def('m', { origin: 'mapping', physicalRef: 'p/database', columns: cols })).mappable).toBe(
            false,
        );

        const m = dataEntry(
            def('m', { origin: 'mapping', defaultLinkMapping: { from: 'from_account', to: 'gone', kind: 'Channel' } }),
        );
        expect(driftedColumns(m, BASE)).toEqual(['gone']); // case-insensitive, as the server's column check
        expect(driftedColumns(m, dataEntry(def('bare', { physicalRef: 'x' })))).toEqual([]); // nothing to compare with
    });

    it('a column means the Entity Type that claims its classification; none is a detail', () => {
        expect(typeOf('account_id', TYPES)?.id).toBe('account');
        expect(typeOf(null, TYPES)).toBeNull();
        expect(typeOf('UNKNOWN', TYPES)).toBeNull();
    });

    it('a Mapping reads its base data its own way: the base physicalRef, its columns re-classified for From and To', () => {
        const f = {
            ...formOf(BASE, TYPES),
            name: 'by_channel',
            to: 'channel',
            toType: 'channel',
            time: 'booked_at',
            kind: '',
        };
        expect(formOf(BASE, TYPES).fromType).toBe('account');
        const e = mappingEntry(BASE, f, TYPES);
        expect(e['origin']).toBe('mapping');
        expect(e['basedOn']).toBe('transfers');
        expect(e['physicalRef']).toBe('transfers/database');
        expect(e['defaultLinkMapping']).toEqual({ from: 'from_account', to: 'channel', time: 'booked_at' });
        expect(e['eventDate']).toBe('booked_at');
        expect(e['primaryEntityType']).toBe('account');
        const cols = e['columns'] as { name: string; classification?: string }[];
        expect(cols.find((c) => c.name === 'channel')?.classification).toBe('CHANNEL');
        expect(cols.find((c) => c.name === 'to_account')?.classification).toBe('ACCOUNT_ID'); // untouched
        expect(String(e['description'])).toContain('A Mapping of transfers');
    });

    it('LA-COLUMN-ROLES-1: a stored role wins; an entry without roles reads them from holds and its link mapping', () => {
        const old = dataEntry(
            def('old', {
                columns: [
                    { name: 'from_account', classification: 'ACCOUNT_ID' },
                    { name: 'booked_at' },
                    { name: 'fee' },
                    { name: 'channel' },
                    { name: 'note' },
                ],
                defaultLinkMapping: {
                    from: 'from_account',
                    to: 'note',
                    time: 'booked_at',
                    amount: 'FEE',
                    kind: 'channel',
                },
            }),
        );
        expect(old.columns.map((c) => roleOf(c, old, TYPES))).toEqual([
            'entity',
            'temporal',
            'measure',
            'dimension',
            'detail',
        ]);
        const stored = dataEntry(
            def('new', {
                columns: [
                    { name: 'fee', role: 'Measure', unit: 'USD' },
                    { name: 'from_account', role: 'temporal', classification: 'ACCOUNT_ID' },
                    { name: 'odd', role: 'amount' },
                ],
            }),
        );
        expect(stored.columns[0]).toMatchObject({ role: 'measure', unit: 'USD' });
        expect(roleOf(stored.columns[1], stored, TYPES)).toBe('temporal');
        expect(stored.columns[2].role).toBeNull(); // not a role: read as if none were stored
        expect(roleOf(stored.columns[2], stored, TYPES)).toBe('detail');
    });

    it('a Mapping keeps each column role and unit; a From or To re-classified there is an Entity, or no longer one', () => {
        const base = dataEntry(
            def('t', {
                origin: 'import',
                physicalRef: 't/database',
                columns: [
                    { name: 'a', classification: 'ACCOUNT_ID', role: 'entity' },
                    { name: 'b', classification: 'ACCOUNT_ID', role: 'entity' },
                    { name: 'c', role: 'dimension' },
                    { name: 'fee', role: 'measure', unit: 'USD' },
                ],
            }),
        );
        const e = mappingEntry(
            base,
            { ...formOf(base, TYPES), from: 'a', fromType: '', to: 'c', toType: 'channel' },
            TYPES,
        );
        expect(e['columns']).toEqual([
            { name: 'a', type: 'string' },
            { name: 'b', type: 'string', classification: 'ACCOUNT_ID', role: 'entity' },
            { name: 'c', type: 'string', classification: 'CHANNEL', role: 'entity' },
            { name: 'fee', type: 'string', role: 'measure', unit: 'USD' },
        ]);
    });
});
