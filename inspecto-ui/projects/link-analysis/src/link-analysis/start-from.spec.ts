import { describe, expect, it } from 'vitest';
import type { EntityTypeSummary } from '@inspecto/link-analysis/api/entity-profile.service';
import type { LaDataset } from '@inspecto/link-analysis/la-host';
import { domainProfile } from '@inspecto/link-analysis/graph/domain-profile';
import { installTelecomFixture } from '@inspecto/link-analysis/graph/domain-profile.testing';
import { presetRung } from './investigate-number';
import type { LinkAnalysisView } from './link-analysis.service';
import {
    datasetForType,
    genericBinding,
    hasRoute,
    parsePastedValues,
    seedNodeOf,
    startCatalogue,
    startMapping,
    notInDatasetMessage,
    startOutcome,
    suggestionLine,
    wholeDatasetWarning,
} from './start-from';

const ds = (id: string, extra: Partial<LaDataset> = {}): LaDataset => ({ id, name: id, sourceName: id, ...extra });
const type = (id: string, bindings: EntityTypeSummary['bindings'], label = id): EntityTypeSummary => ({
    id,
    label,
    normaliser: 'default',
    masked: false,
    classifications: [],
    bindings,
    headline: {
        assessed: false,
        reason: null,
        distinct: null,
        approximate: false,
        rows: null,
        dataAsOf: null,
        computedAt: null,
    },
});

describe('start-from (LA-ONBOARD-1 OB-9)', () => {
    it('startMapping: the default link wins, then a saved view, then the classified link ends', () => {
        const view: LinkAnalysisView = {
            id: 'v',
            name: 'Ring',
            sourceId: 'entity-projection',
            query: {
                projection: { datasetId: 'mule', sourceCol: 'PAYER', targetCol: 'PAYEE', linkKindCol: 'CHANNEL' },
            },
        };
        expect(
            startMapping(
                ds('t', {
                    defaultLinkMapping: { from: 'from_account', to: 'to_account', kind: 'channel', time: 'ts' },
                }),
                [],
                [],
            ),
        ).toEqual({
            mapping: { datasetId: 't', sourceCol: 'from_account', targetCol: 'to_account', linkKindCol: 'channel' },
            timeCol: 'ts',
            from: 'default link',
        });
        expect(startMapping(ds('mule'), [view], [])).toEqual({
            mapping: { datasetId: 'mule', sourceCol: 'PAYER', targetCol: 'PAYEE', linkKindCol: 'CHANNEL' },
            from: 'saved view Ring',
        });
        expect(
            startMapping(
                ds('x'),
                [],
                [
                    { column: 'a', role: 'source' },
                    { column: 'b', role: 'target' },
                ],
            ),
        ).toEqual({ mapping: { datasetId: 'x', sourceCol: 'a', targetCol: 'b' }, from: 'column types' });
        expect(startMapping(ds('y'), [], [{ column: 'a', role: 'attribute' }])).toBeNull();
    });

    it('startCatalogue: a Dataset with a link lists the types its link ends hold, primary first; attribute types are not starts', () => {
        const cat = startCatalogue(
            [
                ds('transfers', {
                    primaryEntityType: 'account',
                    defaultLinkMapping: { from: 'from_account', to: 'to_account' },
                }),
                ds('no_link'),
            ],
            [],
            [
                type('device', [{ dataset: 'transfers', column: 'device_id', role: 'attribute' }], 'Device'),
                type(
                    'account',
                    [
                        { dataset: 'transfers', column: 'from_account', role: 'source' },
                        { dataset: 'transfers', column: 'to_account', role: 'target' },
                    ],
                    'Account',
                ),
            ],
        );
        expect(cat.datasets.map((d) => d.id)).toEqual(['transfers']);
        expect(cat.datasets[0]).toMatchObject({
            primaryEntityType: 'account',
            types: ['account'],
            mapping: {
                datasetId: 'transfers',
                sourceCol: 'from_account',
                targetCol: 'to_account',
                entityType: 'account',
            },
        });
        expect(cat.types).toEqual([{ id: 'account', label: 'Account' }]);
    });

    it('startCatalogue: with the registry unreadable the primary type still starts, labelled by its id', () => {
        const cat = startCatalogue(
            [ds('t', { primaryEntityType: 'account', defaultLinkMapping: { from: 'a', to: 'b' } })],
            [],
            null,
        );
        expect(cat.types).toEqual([{ id: 'account', label: 'account' }]);
    });

    it('datasetForType: the named Dataset when it links the type, else the first that does', () => {
        const cat = startCatalogue(
            [
                ds('calls', { primaryEntityType: 'msisdn', defaultLinkMapping: { from: 'a', to: 'b' } }),
                ds('transfers', { primaryEntityType: 'account', defaultLinkMapping: { from: 'c', to: 'd' } }),
            ],
            [],
            null,
        );
        expect(datasetForType(cat, 'account', 'transfers')?.id).toBe('transfers');
        expect(datasetForType(cat, 'account', 'calls')?.id).toBe('transfers'); // calls does not link accounts
        expect(datasetForType(cat, 'account', 'gone')?.id).toBe('transfers');
        expect(datasetForType(cat, 'imei')).toBeNull();
    });

    it('genericBinding: the start Dataset mapping typed by the seed, its time column, the server rung', () => {
        const [d] = startCatalogue(
            [ds('t', { primaryEntityType: 'account', defaultLinkMapping: { from: 'a', to: 'b', time: 'ts' } })],
            [],
            null,
        ).datasets;
        expect(genericBinding('ACC-1', 'account', d, 'generic')).toEqual({
            seed: 'ACC-1',
            profileId: 'generic',
            projection: { datasetId: 't', sourceCol: 'a', targetCol: 'b', entityType: 'account' },
            timeCol: 'ts',
            presets: {},
            maxDegree: 6,
        });
    });

    it('genericBinding: the card sends the active profile presets, so its expand carries the same fan-out cap as the toolbox', () => {
        installTelecomFixture();
        const [d] = startCatalogue([ds('t', { primaryEntityType: 'msisdn', defaultLinkMapping: { from: 'a', to: 'b' } })], [], null)
            .datasets;
        const b = genericBinding('MULE-HUB-01', 'msisdn', d, 'telecom');
        expect(b.presets).toEqual(domainProfile('telecom').investigate!.expand);
        expect(presetRung(b.presets, false).maxFanOut).toBe(500);
    });

    it('parsePastedValues splits lines, commas, semicolons and tabs; trims; drops blanks and repeats', () => {
        expect(parsePastedValues(' A \nB,C;\tD\r\nA\n\n')).toEqual(['A', 'B', 'C', 'D']);
        expect(parsePastedValues(' \n ')).toEqual([]);
    });

    it('seedNodeOf: by label, else (a masked read) the node at an end of every row', () => {
        const read = {
            nodes: [
                { id: 'masked:aa', data: { label: 'masked:aa', kind: 'entity' } },
                { id: 'masked:bb', data: { label: 'masked:bb', kind: 'entity' } },
                { id: 'masked:cc', data: { label: 'masked:cc', kind: 'entity' } },
            ],
            edges: [
                { id: '1', source: 'masked:bb', target: 'masked:aa', data: { kind: 'link' } },
                { id: '2', source: 'masked:cc', target: 'masked:bb', data: { kind: 'link' } },
            ],
        };
        expect(seedNodeOf(read, 'masked:cc')).toBe('masked:cc');
        expect(seedNodeOf(read, '+249912000001')).toBe('masked:bb');
    });

    it('words: the outcome, the whole-Dataset warning and a type-ahead row', () => {
        expect(
            startOutcome({
                drawn: 2,
                nodes: 12,
                held: 1,
                unknown: ['NOPE'],
                notDrawn: 0,
                dataset: 'Transfers',
                terms: { node: 'account', nodes: 'accounts' },
            }),
        ).toBe(
            'Drew 2 entities and 10 linked accounts. NOPE is not in Transfers. ' +
                '1 busy account is held; Expand… (G) goes one degree further.',
        );
        // T-041: one entity, the toast in canonical words (never Grow)
        expect(
            startOutcome({ drawn: 1, nodes: 4, held: 3, unknown: [], notDrawn: 0, dataset: 'T', terms: { node: 'wallet', nodes: 'wallets' } }),
        ).toBe('Drew 1 entity and 3 linked wallets. 3 busy wallets are held; Expand… (G) goes one degree further.');
        // T-016: what a start that found nothing says
        expect(notInDatasetMessage(['NOPE'], 'Transfers')).toBe('NOPE is not in Transfers. Check the spelling or pick another Dataset.');
        expect(notInDatasetMessage(['A', 'B'], 'Transfers')).toBe(
            'None of these are in Transfers: A, B. Check the spelling or pick another Dataset.',
        );
        expect(wholeDatasetWarning(312, 1000, 'Transfers', 'Account')).toBeNull();
        expect(wholeDatasetWarning(null, 10, 'Transfers', 'Account')).toBeNull();
        expect(wholeDatasetWarning(1240, 1000, 'Transfers', 'Account')).toContain('about 1,240 Account entities');
        expect(suggestionLine({ links: 1, firstSeen: null, lastSeen: null })).toBe('1 link');
        expect(suggestionLine({ links: 10, firstSeen: '2026-09-03T00:00:00Z', lastSeen: '2026-09-30T00:00:00Z' })).toBe(
            '10 links, first seen 3 Sep 2026, last seen 30 Sep 2026',
        );
    });

    it('hasRoute finds a shell child route', () => {
        expect(hasRoute([{ path: '', children: [{ path: 'add-data' }] }], 'add-data')).toBe(true);
        expect(hasRoute([{ path: 'studio', children: [{ path: 'link-analysis' }] }], 'add-data')).toBe(false);
    });
});
