import { describe, expect, it } from 'vitest';
import { getViz } from '../viz-registry';
import { autoAssignChannels, recommend } from '../show-me';
import { VizField } from '../viz-types';
import { TREEMAP_PLUGIN } from './index';

const CTX = { datasetId: 'fraud_cases', sourceName: 'fraud_cases' };

describe('TREEMAP_PLUGIN', () => {
    it('is registered as a component-rendered Visualization Type with group / subgroup / value channels', () => {
        expect(getViz('treemap')).toBe(TREEMAP_PLUGIN);
        expect(TREEMAP_PLUGIN.render).toEqual({ kind: 'component', componentKey: 'treemap' });
        expect(TREEMAP_PLUGIN.controls.map((c) => [c.channel, !!c.required])).toEqual([
            ['group', true],
            ['subgroup', false],
            ['value', true],
        ]);
    });

    it('groups by the group (and subgroup) field and aggregates one measure', () => {
        const spec = TREEMAP_PLUGIN.buildQuery(
            {
                group: [{ field: 'typology' }],
                subgroup: [{ field: 'channel' }],
                value: [{ field: 'loss_sar', agg: 'sum' }],
            },
            CTX,
        );
        expect(spec.groupBy).toEqual(['typology', 'channel']);
        expect(spec.measures.map((m) => m.id)).toEqual(['sum_loss_sar']);
        expect(
            TREEMAP_PLUGIN.buildQuery({ group: [{ field: 'typology' }], value: [{ field: 'x', agg: 'count' }] }, CTX)
                .groupBy,
        ).toEqual(['typology']);
    });

    it('normalises rows into {group, subgroup, value}, keeping blanks raw and a missing value as NaN', () => {
        const values = {
            group: [{ field: 'typology' }],
            subgroup: [{ field: 'channel' }],
            value: [{ field: 'loss_sar', agg: 'sum' as const }],
        };
        const props = TREEMAP_PLUGIN.transformProps(
            [
                { typology: 'IRSF', channel: 'Roaming', sum_loss_sar: 1200 },
                { typology: null, channel: 'Retail', sum_loss_sar: '40' },
                { typology: 'Wangiri', channel: 'Online', sum_loss_sar: null },
            ],
            values,
        );
        expect(props.treemap?.[0]).toEqual({ group: 'IRSF', subgroup: 'Roaming', value: 1200 });
        expect(props.treemap?.[1]).toEqual({ group: '', subgroup: 'Retail', value: 40 });
        expect(Number.isNaN(props.treemap?.[2].value)).toBe(true);
    });

    it('one level: rows carry no subgroup at all', () => {
        const props = TREEMAP_PLUGIN.transformProps([{ region: 'Riyadh', count: 3 }], {
            group: [{ field: 'region' }],
            value: [{ field: 'id', agg: 'count' }],
        });
        expect(props.treemap).toEqual([{ group: 'Riyadh', value: 3 }]);
    });

    it('Show-Me offers it for dimensions + one measure, even at high cardinality, but never as the top pick', () => {
        for (const cardinality of [5, 500]) {
            const ranked = recommend([
                { name: 'typology', type: 'string', role: 'dimension', cardinality },
                { name: 'loss_sar', type: 'number', role: 'measure' },
            ]).map((p) => p.meta.type);
            expect(ranked).toContain('treemap');
            expect(ranked[0]).not.toBe('treemap');
        }
        expect(
            recommend([{ name: 'loss_sar', type: 'number', role: 'measure' }]).map((p) => p.meta.type),
        ).not.toContain('treemap');
    });

    it('auto-assignment fills group and value but leaves the optional subgroup empty', () => {
        const fields: VizField[] = [
            { name: 'typology', type: 'string', role: 'dimension' },
            { name: 'channel', type: 'string', role: 'dimension' },
            { name: 'loss_sar', type: 'number', role: 'measure' },
        ];
        const assigned = autoAssignChannels(TREEMAP_PLUGIN, fields);
        expect(assigned.group?.[0].field).toBe('typology');
        expect(assigned.subgroup).toBeUndefined();
        expect(assigned.value?.[0]).toEqual({ field: 'loss_sar', agg: 'sum' });
    });
});
