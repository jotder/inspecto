import { describe, expect, it } from 'vitest';
import { dbBrowserHandler } from '../handlers/db-browser.handler';
import { MockRequest } from '../mock-http';
import { componentCollection } from '../handlers/components.handler';
import { PIPELINES_COLL } from '../handlers/pipelines.handler';
import { MockStore } from '../mock-store';
import { SPACE_TEMPLATES } from './templates';

interface DatasetContent {
    name: string;
    content: { sourceName: string; columns: Array<{ name: string }> };
}
interface WidgetContent {
    name: string;
    content: { datasetId: string; vizType: string; controls: Record<string, Array<{ field: string }>> };
}
interface DashboardContent {
    content: { tiles: Array<{ widgetId: string }> };
}
interface ReconContent {
    content: { leftDataset: string; rightDataset: string; keyColumns: string[] };
}

/**
 * Referential coherence of every W5 Space-Template seed pack: the blueprint must hang together —
 * datasets resolve to a **catalogued store** (with matching columns), widgets to seeded datasets (with
 * real fields), dashboard tiles to seeded widgets, reconciliations to seeded datasets — so a space
 * created from any template demos end-to-end with zero manual fixes.
 *
 * ⚠ The store assertions go through the SAME door the app uses — `/db/catalog` and `/db/table`, served
 * offline by {@link dbBrowserHandler} — not through the `SAMPLE_SOURCES` map directly. Since split S2 no
 * feature reads that map, so asserting against it would prove only that a seed names a key in a table
 * nothing consults.
 */
const db = dbBrowserHandler({ mockDb: true } as never);

function ask(method: string, url: string, params: Record<string, string> = {}): { status: number; body: unknown } {
    const req: MockRequest = { method, url, params, body: null, space: 'default' };
    const res = db(req, new MockStore());
    expect(res, `${method} ${url} was not handled`).toBeDefined();
    return { status: res!.status ?? 200, body: res!.body };
}

/** The store names `/db/catalog` offers a Dataset — its business groups only. */
function cataloguedStores(): string[] {
    const body = ask('GET', '/api/db/catalog').body as { groups: { id: string; tables: { name: string }[] }[] };
    return body.groups.filter((g) => !g.id.startsWith('ops:')).flatMap((g) => g.tables.map((t) => t.name));
}
describe('space template seed packs', () => {
    for (const template of SPACE_TEMPLATES) {
        describe(template.id, () => {
            const store = new MockStore();
            const space = `t-${template.id}`;
            store.ensureSeeded(space, template.seed);

            const datasets = store.list<DatasetContent>(space, componentCollection('dataset'));
            const widgets = store.list<WidgetContent>(space, componentCollection('widget'));

            it('seeds a non-trivial blueprint (pipelines, datasets, widgets, a dashboard)', () => {
                expect(store.list(space, PIPELINES_COLL).length).toBeGreaterThan(0);
                expect(datasets.length).toBeGreaterThan(1);
                expect(widgets.length).toBeGreaterThanOrEqual(3);
                expect(store.list(space, componentCollection('dashboard')).length).toBe(1);
                expect(store.list(space, componentCollection('requirement')).length).toBe(1);
            });

            it('every dataset names a CATALOGUED store that serves rows and its declared columns', () => {
                const catalogued = cataloguedStores();
                for (const d of datasets) {
                    const source = d.content.sourceName;
                    // A store the picker would not offer cannot be chosen back after a reload.
                    expect(catalogued, `source ${source}`).toContain(source);
                    const res = ask('GET', '/api/db/table', { name: source });
                    expect(res.status, `GET /db/table?name=${source}`).toBe(200);
                    const page = res.body as { rows: Record<string, unknown>[]; columns: { name: string }[] };
                    expect(page.rows.length, `rows of ${source}`).toBeGreaterThan(0);
                    const served = new Set(page.columns.map((c) => c.name));
                    for (const col of d.content.columns) {
                        expect(served, `column ${col.name} of ${source}`).toContain(col.name);
                    }
                }
            });

            it('every widget maps real fields of a seeded dataset', () => {
                for (const w of widgets) {
                    const ds = datasets.find((d) => d.name === w.content.datasetId);
                    expect(ds, `dataset ${w.content.datasetId}`).toBeDefined();
                    const cols = new Set(ds!.content.columns.map((c) => c.name));
                    for (const assignments of Object.values(w.content.controls)) {
                        for (const a of assignments) expect(cols, `field ${a.field}`).toContain(a.field);
                    }
                }
            });

            it('every dashboard tile references a seeded widget', () => {
                const widgetIds = new Set(widgets.map((w) => w.name));
                for (const dash of store.list<DashboardContent>(space, componentCollection('dashboard'))) {
                    for (const tile of dash.content.tiles) expect(widgetIds).toContain(tile.widgetId);
                }
            });

            it('every reconciliation joins two seeded datasets on columns both sides have', () => {
                const dsNames = new Set(datasets.map((d) => d.name));
                for (const r of store.list<ReconContent>(space, componentCollection('reconciliation'))) {
                    expect(dsNames).toContain(r.content.leftDataset);
                    expect(dsNames).toContain(r.content.rightDataset);
                    for (const side of [r.content.leftDataset, r.content.rightDataset]) {
                        const ds = datasets.find((d) => d.name === side)!;
                        const cols = new Set(ds.content.columns.map((c) => c.name));
                        for (const k of r.content.keyColumns) expect(cols).toContain(k);
                    }
                }
            });
        });
    }
});
