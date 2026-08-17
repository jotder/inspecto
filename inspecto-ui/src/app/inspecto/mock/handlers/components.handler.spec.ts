import { describe, expect, it } from 'vitest';
import type { ComponentDef } from '../../api/components.service';
import type { Signal } from '../../signal/signal';
import { registerIntegrityRules } from '../integrity';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { seedDefaultSpace } from '../seeds/default-space.seed';
import { SIGNALS_COLL } from '../signals';
import { componentCollection, componentsHandler } from './components.handler';

const req = (method: string, url: string, body: unknown = null): MockRequest => ({
    method,
    url,
    body,
    params: {},
    space: 'default',
});

function seededStore(): MockStore {
    const store = new MockStore();
    registerIntegrityRules(store);
    store.ensureSeeded('default', seedDefaultSpace);
    return store;
}

describe('componentsHandler', () => {
    const handler = componentsHandler({ mockStudio: true, mockPipelines: true });

    it('lists, creates, gets and deletes a component kind', () => {
        const store = seededStore();
        const listed = handler(req('GET', '/api/components/grammar'), store);
        expect((listed?.body as ComponentDef[]).map((d) => d.name)).toContain('cdr_csv');

        handler(req('POST', '/api/components/grammar', { id: 'tsv', delimiter: '\t' }), store);
        const got = handler(req('GET', '/api/components/grammar/tsv'), store);
        expect((got?.body as ComponentDef).content['delimiter']).toBe('\t');

        const del = handler(req('DELETE', '/api/components/grammar/tsv'), store);
        expect(del?.body).toEqual({ deleted: true });
        expect(handler(req('GET', '/api/components/grammar/tsv'), store)?.body).toBeNull();
    });

    it('409s a create on an existing id (update is PUT) — mirrors the real backend', () => {
        const store = seededStore();
        handler(req('POST', '/api/components/grammar', { id: 'tsv', delimiter: '\t' }), store);
        const dup = handler(req('POST', '/api/components/grammar', { id: 'tsv', delimiter: ';' }), store);
        expect(dup?.status).toBe(409);
        // The stored copy is untouched by the rejected create; PUT replaces it.
        expect(
            (handler(req('GET', '/api/components/grammar/tsv'), store)?.body as ComponentDef).content['delimiter'],
        ).toBe('\t');
        handler(req('PUT', '/api/components/grammar/tsv', { delimiter: ';' }), store);
        expect(
            (handler(req('GET', '/api/components/grammar/tsv'), store)?.body as ComponentDef).content['delimiter'],
        ).toBe(';');
    });

    it('404s a PUT to an id that does not exist — update requires the id to already exist, mirrors the real backend', () => {
        const store = seededStore();
        const put = handler(req('PUT', '/api/components/grammar/does_not_exist', { delimiter: ';' }), store);
        expect(put?.status).toBe(404);
        expect(handler(req('GET', '/api/components/grammar/does_not_exist'), store)?.body).toBeNull();
    });

    it('409s deleting a widget a dashboard tiles, and a dataset a widget binds (R1 generic rules)', () => {
        const store = seededStore();
        // Seeded: dashboard investigation_overview tiles cost_by_tariff, which binds dataset cdr_sample.
        const widgetDel = handler(req('DELETE', '/api/components/widget/cost_by_tariff'), store);
        expect(widgetDel?.status).toBe(409);
        expect(String((widgetDel?.body as { error: string }).error)).toContain('investigation_overview');

        const datasetDel = handler(req('DELETE', '/api/components/dataset/cdr_sample'), store);
        expect(datasetDel?.status).toBe(409);
        expect(String((datasetDel?.body as { error: string }).error)).toContain('cost_by_tariff');
    });

    it('409s deleting a query a widget binds (R3 widget→query→dataset chain)', () => {
        const store = seededStore();
        // Seeded: recent_cost_by_tariff + recent_cost_total both bind the recent_high_cost query.
        const queryDel = handler(req('DELETE', '/api/components/query/recent_high_cost'), store);
        expect(queryDel?.status).toBe(409);
        expect(String((queryDel?.body as { error: string }).error)).toContain('recent_cost_by_tariff');
        expect(store.get('default', componentCollection('query'), 'recent_high_cost')).toBeDefined();
    });

    it('409s a delete while the component is still referenced', () => {
        const store = seededStore();
        // Seeded pipeline cdr_ingest does not bind grammar/cdr_csv via `use`; wire one that does.
        store.put('default', 'authored-pipeline', 'uses_grammar', {
            name: 'uses_grammar',
            active: true,
            nodes: [{ id: 'p', type: 'parser.dsv', name: 'Parse', use: 'grammar/cdr_csv' }],
            edges: [],
        });
        const res = handler(req('DELETE', '/api/components/grammar/cdr_csv'), store);
        expect(res?.status).toBe(409);
        expect(String((res?.body as { error: string }).error)).toContain('uses_grammar');
        expect(store.get('default', componentCollection('grammar'), 'cdr_csv')).toBeDefined();
    });

    it('appends an AUDIT signal per mutation — the audit trail grows with mock authoring, not seed-only', () => {
        const store = seededStore();
        const audits = (): Signal[] => store.list<Signal>('default', SIGNALS_COLL).filter((s) => s.type === 'AUDIT');
        const seeded = audits().length;

        handler(req('POST', '/api/components/grammar', { id: 'tsv', delimiter: '\t' }), store);
        handler(req('PUT', '/api/components/grammar/tsv', { delimiter: ';' }), store);
        handler(req('DELETE', '/api/components/grammar/tsv'), store);

        const mine = audits().filter((s) => (s.payload['attributes'] as Record<string, string>)['target_id'] === 'tsv');
        expect(audits().length).toBe(seeded + 3);
        expect(mine.map((s) => (s.payload['attributes'] as Record<string, string>)['action']).sort()).toEqual([
            'grammar.created',
            'grammar.deleted',
            'grammar.updated',
        ]);
        // Rejected mutations audit nothing: a 409 create and a referenced delete leave no trace.
        handler(req('POST', '/api/components/dataset/x', null), store); // no route match — sanity no-op
        handler(req('POST', '/api/components/widget', { id: 'cost_by_tariff' }), store); // 409 duplicate
        handler(req('DELETE', '/api/components/dataset/cdr_sample'), store); // 409 referenced
        expect(audits().length).toBe(seeded + 3);
        // The Audit-log pane's read path sees them: category + destructive classification carried.
        const del = mine.find(
            (s) => (s.payload['attributes'] as Record<string, string>)['action'] === 'grammar.deleted',
        )!;
        expect((del.payload['attributes'] as Record<string, string>)['action_category']).toBe('destructive');
    });

    it('respects the per-kind flag gating (studio kinds vs registry kinds)', () => {
        const store = seededStore();
        const studioOnly = componentsHandler({ mockStudio: true, mockPipelines: false });
        expect(studioOnly(req('GET', '/api/components/dataset'), store)).toBeDefined();
        expect(studioOnly(req('GET', '/api/components/grammar'), store)).toBeUndefined(); // falls through
    });

    // These pin the mock against the Java `MappingRules` / `validateKind` gate (S6b). A mock that
    // accepted rules the server 422s would greenlight a broken mapping offline — the exact failure
    // mode the mock-strictness rule exists to prevent.
    describe('mapping rules gate', () => {
        const validate = (rules: unknown) =>
            handler(req('POST', '/api/components/mapping/validate', { rules }), seededStore());

        it('reports clean rules as clean', () => {
            const res = validate([{ targetColumn: 'A', sourceExpression: 'a', transformType: 'DIRECT' }]);
            expect(res?.body).toMatchObject({ type: 'mapping', clean: true, findings: [] });
        });

        it('anchors each finding to the cell, exactly as the server does', () => {
            const res = validate([
                { targetColumn: '', sourceExpression: 'a', transformType: '' },
                { targetColumn: 'B', sourceExpression: '', transformType: '' },
                { targetColumn: 'C', sourceExpression: 'x', transformType: 'EXPER' },
                { targetColumn: 'C', sourceExpression: 'y', transformType: '' },
                { targetColumn: 'D', sourceExpression: 'nosep', transformType: 'CONCAT_DT' },
                { targetColumn: 'E', sourceExpression: 'f|p|%Y%m%d', transformType: 'FILENAME_DATE' },
            ]);
            const body = res?.body as { clean: boolean; findings: { fieldPath: string }[] };
            expect(body.clean).toBe(false);
            expect(body.findings.map((f) => f.fieldPath)).toEqual([
                'rules[0].targetColumn',
                'rules[1].sourceExpression',
                'rules[2].transformType',
                'rules[3].targetColumn',
                'rules[4].sourceExpression',
                'rules[5].targetColumn',
            ]);
        });

        it('an empty rule set is not clean, and the finding has no cell anchor', () => {
            const body = validate([])?.body as { clean: boolean; findings: { fieldPath: string }[] };
            expect(body.clean).toBe(false);
            expect(body.findings[0].fieldPath).toBe('');
        });

        it('a malformed body is 400', () => {
            expect(validate(undefined)?.status).toBe(400);
            expect(validate('nope')?.status).toBe(400);
            expect(validate(['nope'])?.status).toBe(400);
        });

        it('REFUSES a create and an update carrying invalid rules, like the write gate does', () => {
            const store = seededStore();
            const bad = { rules: [{ targetColumn: 'A', sourceExpression: 'a', transformType: 'EXPER' }] };
            expect(handler(req('POST', '/api/components/mapping', { id: 'm1', ...bad }), store)?.status).toBe(422);
            expect(store.get('default', componentCollection('mapping'), 'm1')).toBeUndefined();

            const good = { rules: [{ targetColumn: 'A', sourceExpression: 'a', transformType: 'EXPR' }] };
            expect(handler(req('POST', '/api/components/mapping', { id: 'm1', ...good }), store)?.status).toBe(200);
            expect(handler(req('PUT', '/api/components/mapping/m1', bad), store)?.status).toBe(422);
            // and the refused update did NOT overwrite the good rules
            expect(store.get<ComponentDef>('default', componentCollection('mapping'), 'm1')?.content['rules']).toEqual(
                good.rules,
            );
        });

        it('leaves a body carrying no rules alone (other kinds and partial writes are untouched)', () => {
            const store = seededStore();
            expect(
                handler(req('POST', '/api/components/mapping', { id: 'm2', description: 'later' }), store)?.status,
            ).toBe(200);
        });
    });

    /**
     * The INLINE preview (`POST /components/{family}/preview`). It exists offline to rehearse the
     * server's REFUSALS — those are decisions an operator must meet either way. ⚠ It cannot rehearse the
     * OUTCOME: the server runs the production RowShaper on a throwaway DuckDB and the mock has no SQL
     * engine, so the transform arm reports the sample's own row count. ⛔ Do not "fix" that by writing a
     * second evaluator here; that is the passing-rehearsal failure this layer exists to prevent.
     */
    describe('inline preview', () => {
        it('refuses a body with no config, exactly as the route does', () => {
            const res = handler(req('POST', '/api/components/transform/preview', { sampleRows: [{ a: 1 }] }), seededStore());
            expect(res?.status).toBe(400);
        });

        it('refuses a transform config that is not transform.* with the route 422', () => {
            const res = handler(
                req('POST', '/api/components/transform/preview', {
                    config: { type: 'sink.persistent' },
                    sampleRows: [{ a: 1 }],
                }),
                seededStore(),
            );
            expect(res?.status).toBe(422);
        });

        it('refuses an empty sample', () => {
            const res = handler(
                req('POST', '/api/components/transform/preview', { config: { type: 'transform.filter' }, sampleRows: [] }),
                seededStore(),
            );
            expect(res?.status).toBe(400);
        });

        it('answers a transform with the input columns and one relation', () => {
            const res = handler(
                req('POST', '/api/components/transform/preview', {
                    config: { type: 'transform.filter', where: 'a > 0' },
                    sampleRows: [{ a: 1 }, { a: 2, b: 'x' }],
                }),
                seededStore(),
            );
            expect(res?.status).toBe(200);
            const body = res?.body as { inputColumns: string[]; relations: { rel: string; rowCount: number }[] };
            expect(body.inputColumns).toEqual(['a', 'b']);
            expect(body.relations).toEqual([{ rel: 'data', rowCount: 2, rows: [{ a: 1 }, { a: 2, b: 'x' }] }]);
        });

        it('warns when a sink declares no store, and names it when it does', () => {
            const store = seededStore();
            const bare = handler(
                req('POST', '/api/components/sink/preview', { config: {}, sampleRows: [{ a: 1 }] }),
                store,
            );
            expect((bare?.body as { warnings: string[] }).warnings).toEqual(["sink declares no 'store' name"]);
            const named = handler(
                req('POST', '/api/components/sink/preview', { config: { store: 'orders' }, sampleRows: [{ a: 1 }] }),
                store,
            );
            expect(named?.body).toMatchObject({ store: 'orders', rowCount: 1, warnings: [] });
        });
    });

    it('no longer owns the grammar preview — that moved to the served /parsers domain', () => {
        const store = seededStore();
        const res = handler(
            req('POST', '/api/components/grammar/preview', { parserType: 'dsv', sampleText: 'a,b\n1,2' }),
            store,
        );
        expect(res).toBeUndefined(); // falls through to parsers.handler / the real route
    });
});
