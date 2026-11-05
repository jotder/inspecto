import { describe, expect, it } from 'vitest';
import type { ComponentDef } from '@inspecto/core/api';
import {
    dataEntry,
    driftedColumns,
    formOf,
    mappingEntry,
    carriedChoices,
    mappingLine,
    recordTypeProposals,
    roleOf,
    typeOf,
} from './data-page';

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
            primaryTime: 'booked_at',
            kind: '',
        };
        expect(formOf(BASE, TYPES).fromType).toBe('account');
        const e = mappingEntry(BASE, f, TYPES);
        expect(e['origin']).toBe('mapping');
        expect(e['basedOn']).toBe('transfers');
        expect(e['physicalRef']).toBe('transfers/database');
        expect(formOf(BASE, TYPES).primaryTime).toBe('booked_at'); // the old name `time`, read
        expect(e['defaultLinkMapping']).toEqual({ from: 'from_account', to: 'channel', primaryTime: 'booked_at' });
        expect(e['eventDate']).toBe('booked_at');
        expect(e['primaryEntityType']).toBe('account');
        const cols = e['columns'] as { name: string; classification?: string }[];
        expect(cols.find((c) => c.name === 'channel')?.classification).toBe('CHANNEL');
        expect(cols.find((c) => c.name === 'to_account')?.classification).toBe('ACCOUNT_ID'); // untouched
        // LA-COLUMN-ROLES-1 P1: the primary Time takes its role when the base states none
        expect(cols.find((c) => c.name === 'booked_at')).toMatchObject({ role: 'temporal' });
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

describe('recordTypeProposals (ENTITY-TYPE-RECORD-ROLES-1)', () => {
    const transfer = {
        id: 'transfer',
        label: 'Transfer',
        likelyTypes: [],
        roleHints: { time: ['booked'], measure: ['amount', 'fee'], category: ['channel', 'status', 'from'] },
    };
    const entry = dataEntry({
        type: 'dataset',
        name: 'transfers',
        ref: 'dataset/transfers',
        content: {
            recordType: 'transfer',
            columns: [
                { name: 'from_account', type: 'string', classification: 'ACCOUNT_ID' },
                { name: 'to_account', type: 'string' },
                { name: 'channel', type: 'string' },
                { name: 'status', type: 'string', role: 'detail' },
                { name: 'amount', type: 'number', role: 'detail' },
                { name: 'fee', type: 'number', role: 'dimension' },
                { name: 'booked', type: 'string' },
            ],
            defaultLinkMapping: { from: 'from_account', to: 'to_account' },
        },
    } as ComponentDef);

    it("proposes the record type's roles for the columns that say none or Detail, never over a set role or the mapping", () => {
        expect(recordTypeProposals(entry, transfer, TYPES)).toEqual([
            { column: 'channel', role: 'dimension', hint: 'channel' },
            { column: 'status', role: 'dimension', hint: 'status' },
            { column: 'amount', role: 'measure', hint: 'amount' },
        ]);
    });

    it('proposes nothing without a record type or its hints', () => {
        expect(recordTypeProposals(entry, null, TYPES)).toEqual([]);
        expect(recordTypeProposals(entry, { ...transfer, roleHints: undefined }, TYPES)).toEqual([]);
    });
});

describe('a Mapping\'s own "On each link" marks (LA-COLUMN-ROLES-2)', () => {
    const base = dataEntry({
        type: 'dataset',
        name: 't',
        ref: 'dataset/t',
        content: {
            origin: 'import',
            physicalRef: 't/database',
            columns: [
                { name: 'a', classification: 'ACCOUNT_ID', role: 'entity' },
                { name: 'b', classification: 'ACCOUNT_ID', role: 'entity' },
                { name: 'c', role: 'dimension', carried: false },
                { name: 'fee', role: 'measure' },
                { name: 'note', role: 'detail' },
            ],
            defaultLinkMapping: { from: 'a', to: 'b' },
        },
    } as ComponentDef);

    it("a new Mapping starts from its base's marks; the Mapping's own marks are written, not the base's", () => {
        const f = { ...formOf(base, TYPES), name: 'm' };
        expect(f.notCarried).toEqual(['c']);
        const cols = (fc: typeof f) =>
            mappingEntry(base, fc, TYPES)['columns'] as { name: string; carried?: boolean }[];
        expect(cols(f).find((c) => c.name === 'c')?.carried).toBe(false);
        const own = cols({ ...f, notCarried: ['fee'] });
        expect(own.find((c) => c.name === 'c')?.carried).toBeUndefined(); // carried on this Mapping, not on its base
        expect(own.find((c) => c.name === 'fee')?.carried).toBe(false);
        // a Detail is never marked: it rides along anyway
        expect(cols({ ...f, notCarried: ['note'] }).find((c) => c.name === 'note')?.carried).toBeUndefined();
    });

    it("editing a Mapping starts from the Mapping's own marks", () => {
        const m = dataEntry({
            type: 'dataset',
            name: 'm',
            ref: 'dataset/m',
            content: {
                origin: 'mapping',
                basedOn: 't',
                physicalRef: 't/database',
                columns: [
                    { name: 'fee', role: 'measure', carried: false },
                    { name: 'c', role: 'dimension' },
                ],
                defaultLinkMapping: { from: 'a', to: 'b' },
            },
        } as ComponentDef);
        expect(formOf(m, TYPES, 'm').notCarried).toEqual(['fee']);
    });

    it("offers the base's Time, Measure and Category columns, never the Mapping's ends", () => {
        expect(carriedChoices(base, { from: 'a', to: 'b' }, TYPES)).toEqual(['c', 'fee']);
        expect(carriedChoices(base, { from: 'a', to: 'c' }, TYPES)).toEqual(['fee']);
    });
});
