import { describe, expect, it } from 'vitest';
import { BundleItem, buildBundle, targetIndex } from './bundle';
import { ImportDraft, draftPlacement, draftPrerequisites } from './import-draft';

const DS: BundleItem = { kind: 'dataset', id: 'sales', content: { name: 'sales' } };
const W: BundleItem = { kind: 'widget', id: 'w1', content: { vizType: 'bar', datasetId: 'sales', controls: {} } };
const DASH: BundleItem = {
    kind: 'dashboard',
    id: 'd1',
    content: { name: 'd1', tiles: [{ widgetId: 'w1', span: 1 }] },
};

describe('draftPrerequisites', () => {
    it('is the target closure inside the bundle, minus itself and minus what this Space already holds', () => {
        const bundle = buildBundle([DASH, W, DS], null);
        const target = bundle.items.find((i) => i.id === 'd1')!;
        expect(
            draftPrerequisites(bundle, targetIndex([]), target)
                .map((i) => `${i.kind}/${i.id}`)
                .sort(),
        ).toEqual(['dataset/sales', 'widget/w1']);
        // the Dataset already exists here: only the Widget is imported first; the existing one is never overwritten
        expect(draftPrerequisites(bundle, targetIndex([DS]), target).map((i) => i.id)).toEqual(['w1']);
    });

    it('is empty for an item that references nothing the bundle carries', () => {
        const bundle = buildBundle([DS, W], null);
        expect(draftPrerequisites(bundle, targetIndex([]), bundle.items.find((i) => i.id === 'sales')!)).toEqual([]);
    });
});

describe('draftPlacement', () => {
    const draft = (id: string, targetExists: boolean): ImportDraft => ({
        kind: 'dashboard',
        id,
        content: {},
        sourceSpace: null,
        targetExists,
        integrity: [],
        prerequisites: [],
    });

    it('opens in place on the same id, or on the create route for a new id', () => {
        expect(draftPlacement('d1', draft('d1', true))).toBe('here');
        expect(draftPlacement(undefined, draft('fresh', false))).toBe('here');
    });

    it('routes elsewhere for another id, or for an existing id reached from the create route (D6)', () => {
        expect(draftPlacement('d1', draft('d2', true))).toBe('elsewhere');
        expect(draftPlacement('d1', draft('fresh', false))).toBe('elsewhere');
        expect(draftPlacement(undefined, draft('d1', true))).toBe('elsewhere');
    });
});
