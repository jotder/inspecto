import { describe, expect, it } from 'vitest';
import { ENTITY_NORMALISERS } from '@inspecto/core/graph';
import type { EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import type {
    EntityTypeConfig,
    LinkAnalysisLimits,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import {
    DatasetEntry,
    NORMALISER_OPTIONS,
    changeResult,
    columnNote,
    columnRows,
    datasetEntries,
    entityTypeIdFor,
    exampleKey,
    newEntityType,
    newEntityTypeProblem,
    settingsBodyWithTypes,
    splitByPresence,
    suggestType,
} from './entity-columns';

const headline = {
    distinct: null,
    approximate: false,
    rows: null,
    dataAsOf: null,
    computedAt: null,
    assessed: false,
    reason: 'not computed yet',
};
function type(id: string, classifications: string[], bindings: [string, string][] = []): EntityTypeSummary {
    return {
        id,
        label: id[0].toUpperCase() + id.slice(1),
        normaliser: 'upper-trim',
        masked: false,
        classifications,
        bindings: bindings.map(([dataset, column]) => ({ dataset, column, role: 'attribute' as const })),
        headline,
    };
}
const ACCOUNT = type(
    'account',
    ['ACCOUNT'],
    [
        ['transfers', 'from_account'],
        ['transfers', 'to_account'],
    ],
);
const DEVICE = type('device', ['HANDSET', 'IMEI'], [['logins', 'imei']]);
const CELL = type('cell', ['CELL', 'CELL_ID']);
const TYPES = [CELL, DEVICE, ACCOUNT];

const TRANSFERS: DatasetEntry = {
    id: 'transfers',
    contentHash: 'h1',
    content: {
        columns: [
            { name: 'from_account', classification: 'account' },
            { name: 'to_account', classification: 'ACCOUNT' },
            { name: 'amount' },
            { name: 'ref_id' },
            { name: 'channel', classification: 'PII' },
        ],
        primaryEntityType: 'account',
        defaultLinkMapping: { from: 'FROM_ACCOUNT', to: 'to_account' },
    },
};
const LOGINS: DatasetEntry = {
    id: 'logins',
    contentHash: 'h2',
    content: {
        columns: [{ name: 'imei', classification: 'IMEI' }, { name: 'cellId' }, { name: 'when' }],
        primaryEntityType: 'device',
    },
};

describe('entity-columns', () => {
    it('reads the registry list as entries, tolerating a missing hash or content', () => {
        expect(datasetEntries([{ name: 'a', contentHash: 'x', content: { k: 1 } }, { name: 'b' }])).toEqual([
            { id: 'a', contentHash: 'x', content: { k: 1 } },
            { id: 'b', contentHash: '', content: {} },
        ]);
    });

    it('splits the types into in-your-data (primary first, else server order) and not-in-your-data', () => {
        const s = splitByPresence(TYPES, [TRANSFERS, LOGINS]);
        // DEVICE and ACCOUNT are both primary somewhere and bound: server order kept among them; CELL binds nowhere.
        expect(s.present.map((t) => t.id)).toEqual(['device', 'account']);
        expect(s.absent.map((t) => t.id)).toEqual(['cell']);
        expect(s.primaryIn).toEqual({ account: ['transfers'], device: ['logins'] });

        const noPrimary = splitByPresence(TYPES, [
            { ...LOGINS, content: { ...LOGINS.content, primaryEntityType: undefined } },
        ]);
        expect(noPrimary.present.map((t) => t.id)).toEqual(['device', 'account']);
        const accountPrimary = splitByPresence(TYPES, [TRANSFERS]);
        expect(accountPrimary.present.map((t) => t.id)).toEqual(['account', 'device']);
    });

    it('a type named primary but held by no column is still not in your data', () => {
        const s = splitByPresence(TYPES, [
            { ...TRANSFERS, content: { ...TRANSFERS.content, primaryEntityType: 'cell' } },
        ]);
        expect(s.absent.map((t) => t.id)).toEqual(['cell']);
    });

    it('lists the identifier-looking columns: classified, a link end, a name that suggests a type or ends in id', () => {
        const rows = columnRows([TRANSFERS, LOGINS], TYPES);
        expect(rows.map((r) => `${r.dataset}.${r.column}:${r.reason}:${r.entityType}:${r.suggestion}`)).toEqual([
            'transfers.from_account:classified:account:null',
            'transfers.to_account:classified:account:null',
            'transfers.ref_id:suggested:null:null',
            'transfers.channel:classified:null:null',
            'logins.imei:classified:device:null',
            'logins.cellId:suggested:null:cell',
        ]);
        expect(rows[0].linkEnd).toBe('from');
        expect(rows[1].linkEnd).toBe('to');
        // `all` adds the rest, in column order
        expect(columnRows([TRANSFERS], TYPES, true).map((r) => r.column)).toEqual([
            'from_account',
            'to_account',
            'amount',
            'ref_id',
            'channel',
        ]);
    });

    it('a name ending in id says it identifies something in any case, a word merely ending in id does not', () => {
        const ds: DatasetEntry = {
            id: 'orders',
            contentHash: '',
            content: {
                columns: ['ORDER_ID', 'Customer_ID', 'userID', 'orderId', 'id', 'ID', 'paid', 'VALID', 'note'].map(
                    (name) => ({ name }),
                ),
            },
        };
        expect(columnRows([ds], TYPES).map((r) => r.column)).toEqual([
            'ORDER_ID',
            'Customer_ID',
            'userID',
            'orderId',
            'id',
            'ID',
        ]);
    });

    it('a link end with no classification is listed for its link', () => {
        const ds: DatasetEntry = {
            id: 'calls',
            contentHash: '',
            content: {
                columns: [{ name: 'a_party' }, { name: 'b_party' }],
                defaultLinkMapping: { from: 'a_party', to: 'b_party' },
            },
        };
        const rows = columnRows([ds], TYPES);
        expect(rows.map((r) => [r.column, r.reason, r.linkEnd])).toEqual([
            ['a_party', 'link', 'from'],
            ['b_party', 'link', 'to'],
        ]);
    });

    it('suggests a type from a word of the column name, never from part of a word', () => {
        expect(suggestType('from_account', TYPES)?.id).toBe('account');
        expect(suggestType('PayerAccount', TYPES)?.id).toBe('account');
        expect(suggestType('serving_cell_id', TYPES)?.id).toBe('cell');
        expect(suggestType('accountant', TYPES)).toBeNull();
        expect(suggestType('excellent', TYPES)).toBeNull();
    });

    it('notes say why a column is listed', () => {
        const rows = columnRows([TRANSFERS, LOGINS], TYPES);
        expect(columnNote(rows[0], TYPES)).toBe('Links start here');
        expect(columnNote(rows[3], TYPES)).toBe('Classified PII, which no Entity Type claims');
        expect(columnNote(rows[5], TYPES)).toBe('Looks like Cell');
    });

    it('offers a plain-words choice for every normaliser the server knows, and nothing else', () => {
        expect(NORMALISER_OPTIONS.map((o) => o.value).sort()).toEqual([...ENTITY_NORMALISERS].sort());
    });

    it('derives a valid id from a name and refuses what the server would', () => {
        expect(entityTypeIdFor('  Vehicle plate ')).toBe('vehicle_plate');
        expect(entityTypeIdFor('3D model')).toBe('t_3d_model');
        expect(entityTypeIdFor('x'.repeat(40))).toHaveLength(32);
        expect(entityTypeIdFor('!!!')).toBe('');
        const inForce: EntityTypeConfig[] = TYPES.map(({ id, label, classifications }) => ({
            id,
            label,
            normaliser: 'upper-trim' as const,
            masked: false,
            classifications,
        }));
        expect(newEntityTypeProblem('Vehicle plate', inForce)).toBe('');
        expect(newEntityTypeProblem('', inForce)).toBe('Give the Entity Type a name.');
        expect(newEntityTypeProblem('???', inForce)).toContain('at least one letter');
        expect(newEntityTypeProblem('Entity', inForce)).toContain('reserved');
        expect(newEntityTypeProblem('ACCOUNT', inForce)).toContain('already an Entity Type called');
        expect(newEntityTypeProblem('Imei', inForce)).toContain('"Device" already claims the classification IMEI');
        expect(newEntityType(' Vehicle plate', 'digits')).toEqual({
            id: 'vehicle_plate',
            label: 'Vehicle plate',
            normaliser: 'digits',
            masked: false,
            classifications: ['VEHICLE_PLATE'],
        });
    });

    it('shows how an example value is kept under each rule', () => {
        expect(exampleKey(' ab  12-3 ', 'upper-trim')).toBe('AB 12-3');
        expect(exampleKey('35-2099', 'digits')).toBe('352099');
        expect(exampleKey('0044 20 7946', 'e164')).toBe('+44207946');
        expect(exampleKey('ACME Ltd.', 'default')).toBe('acme ltd');
    });

    it('builds a settings body that changes only the Entity Types and round-trips the other non-limit keys', () => {
        const served = {
            projectionNodeCap: 900,
            analysisNodeCap: null,
            suspicionNodeCap: null,
            maskingMode: 'typed',
            entityTypes: null,
            entityTypesInForce: [],
            index: { enabled: true, maxDiskBytes: 5, keepVersions: null },
            graphRun: { maxNodes: 10, maxEdges: null, timeoutMs: null, propagatedRiskWeights: [1, 0.5] },
            embed: { refuseClassifications: ['PII'], frameAncestors: null, defaultTtlHours: 4 },
        } as LinkAnalysisLimits;
        const t = newEntityType('Plate', 'upper-trim');
        expect(settingsBodyWithTypes(served, [t])).toEqual({
            maskingMode: 'typed',
            entityTypes: [t],
            index: { enabled: true },
            graphRun: { propagatedRiskWeights: [1, 0.5] },
            embed: { refuseClassifications: ['PII'], frameAncestors: null },
        });
        // nothing stated: only the two keys (the limits are kept by the server when left out)
        expect(
            settingsBodyWithTypes(
                {
                    projectionNodeCap: null,
                    analysisNodeCap: null,
                    suspicionNodeCap: null,
                    entityTypes: null,
                    entityTypesInForce: [],
                },
                [t],
            ),
        ).toEqual({ maskingMode: null, entityTypes: [t] });
    });

    it('states a change from the Entity Types as read after it', () => {
        const after = [ACCOUNT, DEVICE, CELL];
        expect(
            changeResult(
                { kind: 'column', dataset: 'transfers', column: 'to_account', entityType: 'account', previous: null },
                after,
            ),
        ).toBe('"to_account" in transfers now holds Account. Account is in 2 columns of 1 Dataset.');
        expect(
            changeResult(
                { kind: 'column', dataset: 'logins', column: 'cellId', entityType: null, previous: 'cell' },
                after,
            ),
        ).toBe('"cellId" in logins no longer holds an Entity Type. Cell is not in your data.');
        expect(changeResult({ kind: 'primary', dataset: 'logins', entityType: 'device' }, after)).toBe(
            'logins now leads with Device.',
        );
        expect(changeResult({ kind: 'primary', dataset: 'logins', entityType: null }, after)).toBe(
            'logins has no primary Entity Type.',
        );
        expect(changeResult({ kind: 'type', entityType: 'cell' }, after)).toContain('Cell was added.');
        expect(changeResult(null, after)).toBe('');
    });
});
