import { describe, expect, it } from 'vitest';
import { STREAM_BUNDLE_FORMAT, StreamBundle } from 'app/inspecto/transfer/stream-bundle';
import { planTemplateCopy } from './onboarding-template';

/** A template's export as `save-as-template` leaves it: every binding stamped with the template id. */
function templateBundle(pipeline: Record<string, unknown>): StreamBundle {
    return {
        format: STREAM_BUNDLE_FORMAT,
        version: 1,
        exportedAt: '2026-10-11T10:00:00.000Z',
        source: { space: 'demo', name: 'orders_tpl', contentHash: 'abc' },
        kind: 'stream',
        pipeline,
        requires: [],
    };
}

const STAMPED = {
    template: true,
    stream: 'orders_tpl',
    collector: { id: 'orders_tpl', connector: 'local' },
    output: { format: 'parquet', ducklake: { data_path: 'data/templates/orders_tpl/ducklake', catalog: 'x' } },
    processing: { threads: 2 },
};

describe('planTemplateCopy', () => {
    it('sheds every template stamp so the copy is a runnable pipeline of its own', () => {
        const plan = planTemplateCopy(templateBundle(structuredClone(STAMPED)), { name: 'orders_eu' });
        const p = plan.pipeline;
        expect(p['template']).toBeUndefined();
        expect(p['id']).toBe('orders_eu');
        expect(p['active']).toBe(false);
        expect(p['stream']).toBe('orders_eu');
        expect(p['collector']).toEqual({ id: 'orders_eu', connector: 'local' });
        expect((p['output'] as Record<string, unknown>)['ducklake']).toEqual({
            data_path: 'data/orders_eu/ducklake',
            catalog: 'x',
        });
        expect((p['processing'] as Record<string, unknown>)['threads']).toBe(2); // body preserved
        expect((p['dirs'] as Record<string, string>)['poll']).toBe('data/inbox/orders_eu');
    });

    it('re-points the legacy `source:` collector spelling too', () => {
        const plan = planTemplateCopy(templateBundle({ template: true, source: { id: 'orders_tpl' } }), {
            name: 'orders_eu',
        });
        expect(plan.pipeline['source']).toEqual({ id: 'orders_eu' });
    });

    it('leaves a binding the author set deliberately on the template as authored', () => {
        const plan = planTemplateCopy(
            templateBundle({
                template: true,
                stream: 'shared_orders',
                collector: { id: 'sftp_orders' },
                output: { ducklake: { data_path: 'data/lakes/orders' } },
            }),
            { name: 'orders_eu' },
        );
        expect(plan.pipeline['stream']).toBe('shared_orders');
        expect(plan.pipeline['collector']).toEqual({ id: 'sftp_orders' });
        expect((plan.pipeline['output'] as Record<string, unknown>)['ducklake']).toEqual({
            data_path: 'data/lakes/orders',
        });
    });

    it('says it is a copy and names what could not be read, ahead of the import notes', () => {
        const plan = planTemplateCopy(templateBundle(structuredClone(STAMPED)), {
            name: 'orders_eu',
            label: 'Orders template',
            missing: ['schema "orders_tpl_schema"'],
        });
        expect(plan.notes[0]).toContain('"Orders template"');
        expect(plan.notes[0]).toContain('a copy, not a link');
        expect(plan.notes[1]).toContain('schema "orders_tpl_schema"');
        expect(plan.notes.join(' ')).toContain('re-derived');
    });
});
