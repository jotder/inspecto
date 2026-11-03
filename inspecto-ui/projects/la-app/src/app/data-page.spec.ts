import { describe, expect, it } from 'vitest';
import type { ComponentDef } from '@inspecto/core/api';
import { dataEntry, formOf, mappingEntry, mappingLine, typeOf } from './data-page';

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
});
