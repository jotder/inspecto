import { provideHttpClient, withXhr } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GraphSourceId } from '@inspecto/core/graph';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import { provideLaHostServices } from 'app/modules/admin/studio/la-host.providers';
import { InspectoOptionPickerComponent } from '@inspecto/core/components/option-picker.component';
import { LinkAnalysisQueryPanelComponent } from './link-analysis-query-panel.component';

function make(sourceId: GraphSourceId = 'entity-projection', extraProviders: unknown[] = []) {
    TestBed.configureTestingModule({
        imports: [LinkAnalysisQueryPanelComponent],
        providers: [
            ...provideLaHostServices(),
            ...(extraProviders as []),
            provideNoopAnimations(),
            // The panel now hosts <inspecto-ai-assist> (projection_author), which injects AgentService +
            // ToastrService — without these every test in this file dies at createComponent.
            provideHttpClient(withXhr()),
            {
                provide: ToastrService,
                useValue: { info: () => undefined, error: () => undefined },
            },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisQueryPanelComponent);
    const c = fixture.componentInstance;
    fixture.componentRef.setInput('sourceId', sourceId);
    fixture.componentRef.setInput('sources', [
        {
            id: 'entity-projection',
            label: 'Entity/Link',
            query: () => Promise.resolve({ nodes: [], edges: [] }),
        },
    ]);
    fixture.detectChanges();
    return { fixture, c };
}

describe('LinkAnalysisQueryPanelComponent', () => {
    it('entity-projection: needs a dataset + source/target columns, then builds a projection', () => {
        const { c } = make('entity-projection');
        expect(c.buildQuery()).toEqual({
            error: expect.stringMatching(/source and target/),
        });

        c.queryForm.patchValue({
            datasetId: 'ds',
            sourceCol: 'a',
            targetCol: 'b',
            linkKindCol: 'rel',
        });
        expect(c.buildQuery()).toEqual({
            projection: {
                datasetId: 'ds',
                sourceCol: 'a',
                targetCol: 'b',
                linkKindCol: 'rel',
                attrCols: undefined,
                entityType: undefined,
            },
        });
    });

    it('entity-projection: combining extra mappings requires an entity type on each', () => {
        const { c } = make('entity-projection');
        c.queryForm.patchValue({ datasetId: 'ds', sourceCol: 'a', targetCol: 'b' });
        c.addMapping();
        c.extraMappings.at(0).patchValue({ datasetId: 'ds2', sourceCol: 'x', targetCol: 'y' });
        expect(c.buildQuery()).toEqual({
            error: expect.stringMatching(/entity type/),
        });

        c.queryForm.patchValue({ entityType: 'person' });
        c.extraMappings.at(0).patchValue({ entityType: 'account' });
        const q = c.buildQuery();
        expect('projections' in q && q.projections).toHaveLength(2);
    });

    it('provenance: needs a pipeline; folds extra pipelines into roots', () => {
        const { c } = make('provenance');
        expect(c.buildQuery()).toEqual({
            error: expect.stringMatching(/pipeline/i),
        });

        c.queryForm.patchValue({ pipeline: 'p1', counts: true });
        expect(c.buildQuery()).toEqual({ from: 'p1', counts: true });

        c.queryForm.patchValue({ extraPipelines: ['p2'] });
        expect(c.buildQuery()).toEqual({ roots: ['p1', 'p2'], counts: true });
    });

    it('lineage: single root vs multi-root with depth/direction', () => {
        const { c } = make('lineage');
        c.queryForm.patchValue({ from: 'table:cdr', depth: 3, direction: 'out' });
        expect(c.buildQuery()).toEqual({
            from: 'table:cdr',
            depth: 3,
            direction: 'out',
        });

        c.queryForm.patchValue({ extraRoots: 'table:orders, table:invoices' });
        expect(c.buildQuery()).toEqual({
            roots: ['table:cdr', 'table:orders', 'table:invoices'],
            depth: 3,
            direction: 'out',
        });
    });

    it('patchFormFromView repopulates the form from a saved view', () => {
        const { c } = make('entity-projection');
        c.patchFormFromView({
            id: 'v',
            name: 'V',
            sourceId: 'entity-projection',
            query: {
                projection: { datasetId: 'ds', sourceCol: 's', targetCol: 't' },
            },
        });
        expect(c.queryForm.getRawValue().datasetId).toBe('ds');
        expect(c.queryForm.getRawValue().sourceCol).toBe('s');
    });

    it('patchFormFromView reads projections[] as well as projection', () => {
        const { c } = make('entity-projection');
        c.patchFormFromView({
            id: 'v',
            name: 'V',
            sourceId: 'entity-projection',
            query: {
                projections: [
                    {
                        datasetId: 'ds1',
                        sourceCol: 's1',
                        targetCol: 't1',
                        attrCols: ['w'],
                        entityType: 'person',
                    },
                    {
                        datasetId: 'ds2',
                        sourceCol: 's2',
                        targetCol: 't2',
                        entityType: 'account',
                    },
                ],
            },
        });
        const f = c.queryForm.getRawValue();
        expect(f.datasetId).toBe('ds1');
        expect(f.attrCols).toEqual(['w']);
        expect(f.entityType).toBe('person');
        expect(c.extraMappings.length).toBe(1);
        expect(c.extraMappings.at(0).getRawValue()).toMatchObject({
            datasetId: 'ds2',
            entityType: 'account',
        });
        // The round trip is what a multi-mapping saved view needs: it used to load first-only.
        expect(c.buildQuery()).toMatchObject({
            projections: [{ datasetId: 'ds1' }, { datasetId: 'ds2' }],
        });
    });

    it('projection_author: applying a draft patches the form and persists nothing', () => {
        const { c } = make('entity-projection');
        c.applyProjectionDraft({
            config: {
                query: {
                    projections: [
                        {
                            datasetId: 'cdr',
                            sourceCol: 'caller_id',
                            targetCol: 'callee_id',
                            linkKindCol: 'call_type',
                            attrCols: ['duration_sec'],
                        },
                    ],
                },
            },
        });
        expect(c.queryForm.getRawValue()).toMatchObject({
            datasetId: 'cdr',
            sourceCol: 'caller_id',
            targetCol: 'callee_id',
            linkKindCol: 'call_type',
            attrCols: ['duration_sec'],
            entityType: '', // unset on a single mapping — it would change node ids
        });
        expect(c.queryForm.dirty).toBe(true);
    });

    it("projection_author args carry the pane's own column list, since no route returns one", async () => {
        const { c } = make('entity-projection');
        // Order matters: picking a dataset RE-RESOLVES datasetColumns from `datasets()`, which is empty
        // here — and that resolution is async now, so let it land BEFORE seeding the columns the way
        // onDatasetPicked would from a real Dataset. Seeding first would just be overwritten.
        c.queryForm.patchValue({ datasetId: 'cdr' });
        await Promise.resolve();
        c.datasetColumns.set(['caller_id', 'callee_id']);
        expect(c.aiProjectionArgs()).toEqual({
            datasetId: 'cdr',
            columns: ['caller_id', 'callee_id'],
        });
        // No baseline until the mapping is complete enough to build — a create, so all fields read added.
        expect(c.aiCurrentProjection()).toBeNull();
        c.queryForm.patchValue({ sourceCol: 'caller_id', targetCol: 'callee_id' });
        expect(c.aiCurrentProjection()).toMatchObject({
            query: { projections: [{ datasetId: 'cdr' }] },
        });
    });

    it('asks the rows seam for a picked Dataset‘s columns when it declares none', async () => {
        // The case the old code got wrong: a dataset that declares no columns fell back to the keys of
        // the store's first OFFLINE SAMPLE row, so a real store — which has no sample — offered nothing.
        const columns = vi.fn(() => Promise.resolve([{ name: 'caller_id', type: 'string' }]));
        const { fixture, c } = make('entity-projection', [{ provide: DatasetRowsService, useValue: { columns } }]);
        fixture.componentRef.setInput('datasets', [
            { id: 'cdr', name: 'cdr', kind: 'physical', sourceName: 'switch_cdr_live', columns: [] },
        ]);
        c.queryForm.patchValue({ datasetId: 'cdr' });
        await fixture.whenStable();
        expect(columns).toHaveBeenCalledWith(expect.objectContaining({ sourceName: 'switch_cdr_live' }));
        expect(c.datasetColumns()).toEqual(['caller_id']);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = make();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── LA-08: multi-Dataset projection ──

    it('entity-projection-multi: builds node + edge mappings in the /inv/projection/multi body shape', () => {
        const { c } = make('entity-projection-multi');
        expect(c.buildQuery()).toEqual({ error: expect.stringMatching(/at least one node or edge mapping/) });

        c.addNodeMapping().patchValue({
            datasetId: 'people',
            idColumn: 'ID',
            labelColumn: 'NAME',
            category: ' person ',
        });
        c.addEdgeMapping().patchValue({ datasetId: 'calls', sourceColumn: 'A', targetColumn: 'B' });
        expect(c.buildQuery()).toEqual({
            multi: {
                nodes: [{ dataset: 'people', idColumn: 'ID', labelColumn: 'NAME', category: 'person' }],
                edges: [{ dataset: 'calls', sourceColumn: 'A', targetColumn: 'B', type: undefined }],
            },
        });
    });

    it('entity-projection-multi: refuses a half-filled mapping instead of silently dropping it', () => {
        const { c } = make('entity-projection-multi');
        c.addEdgeMapping().patchValue({ datasetId: 'calls', sourceColumn: 'A' });
        expect(c.buildQuery()).toEqual({ error: expect.stringMatching(/source and target/) });
    });

    it('entity-projection-multi: a saved view round-trips through the form', () => {
        const { c } = make('entity-projection-multi');
        const multi = {
            nodes: [{ dataset: 'people', idColumn: 'ID', labelColumn: undefined, category: undefined }],
            edges: [{ dataset: 'calls', sourceColumn: 'A', targetColumn: 'B', type: 'called' }],
        };
        c.patchFormFromView({
            id: 'v',
            name: 'v',
            sourceId: 'entity-projection-multi',
            query: { multi },
        } as never);
        expect(c.nodeMappings.length).toBe(1);
        expect(c.edgeMappings.length).toBe(1);
        expect(c.buildQuery()).toEqual({ multi });
    });

    it('entity-projection-multi: renders the mapping rows with no a11y violations', async () => {
        const { fixture } = make('entity-projection-multi');
        const el: HTMLElement = fixture.nativeElement;
        const button = (text: string) =>
            Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes(text))!;
        button('Add node mapping').click();
        button('Add edge mapping').click();
        fixture.detectChanges();
        expect(el.querySelector('[aria-label="Node mapping 1"]')).not.toBeNull();
        expect(el.querySelector('[aria-label="Edge mapping 1"]')).not.toBeNull();
        await expectNoA11yViolations(el);
    });

    it('entity-projection-multi: authors node/edge attributes and a per-edge filter, and round-trips them', () => {
        const { c } = make('entity-projection-multi');
        c.addNodeMapping().patchValue({ datasetId: 'people', idColumn: 'ID', attributes: ['CITY'] });
        c.addEdgeMapping().patchValue({ datasetId: 'calls', sourceColumn: 'A', targetColumn: 'B', attributes: ['CH'] });
        // an empty tree is no constraint, so no `filter` travels
        expect(c.buildQuery()).toMatchObject({
            multi: {
                nodes: [{ attributes: ['CITY'] }],
                edges: [{ attributes: ['CH'], filter: undefined }],
            },
        });
        const own = c.edgeFilters()[0];
        own.items.push({ kind: 'condition', field: 'CH', operator: '=', value: 'sms' });
        const q = c.buildQuery() as { multi: { edges: { filter?: unknown }[] } };
        expect(q.multi.edges[0].filter).toEqual(own);
        expect(q.multi.edges[0].filter).not.toBe(own); // a copy: the editor keeps mutating its own tree

        c.patchFormFromView({ id: 'v', name: 'v', sourceId: 'entity-projection-multi', query: q } as never);
        expect(c.edgeFilters()).toHaveLength(1);
        expect(c.edgeFilters()[0].items).toHaveLength(1);
        c.removeEdgeMapping(0);
        expect(c.edgeFilters()).toEqual([]);
    });

    it('entity-projection-multi: renders the per-edge filter editor with no a11y violations', async () => {
        const { fixture } = make('entity-projection-multi');
        const el: HTMLElement = fixture.nativeElement;
        Array.from(el.querySelectorAll('button'))
            .find((b) => b.textContent?.includes('Add edge mapping'))!
            .click();
        fixture.detectChanges();
        expect(el.querySelector('[aria-label="Filter for edge mapping 1"]')).not.toBeNull();
        await expectNoA11yViolations(el);
    });
});

describe('LinkAnalysisQueryPanelComponent - starting guidance (operator 2026-10-10)', () => {
    const DATASETS = [
        {
            id: 'maintenance_backups',
            name: 'maintenance_backups',
            sourceName: 'mb',
            columns: [
                { name: 'file', type: 'string' },
                { name: 'size', type: 'number' },
            ],
        },
        {
            id: 'mule_transfers_dataset',
            name: 'mule_transfers_dataset',
            sourceName: 'mt',
            description: 'Account-to-account transfers. Synthetic.',
            columns: [
                { name: 'TXN_ID', type: 'string' },
                { name: 'PAYER_ACCOUNT', type: 'string' },
                { name: 'PAYEE_ACCOUNT', type: 'string' },
                { name: 'AMOUNT', type: 'number' },
            ],
        },
    ] as never[];

    function withDatasets(extraProviders: unknown[] = []) {
        const m = make('entity-projection', extraProviders);
        m.fixture.componentRef.setInput('datasets', DATASETS);
        m.fixture.detectChanges();
        return m;
    }

    const settle = async (fixture: { whenStable(): Promise<unknown>; detectChanges(): void }) => {
        await fixture.whenStable();
        fixture.detectChanges();
    };

    it('lists the link-shaped Dataset first, each with a reason and a column preview', () => {
        const { c } = withDatasets();
        const options = c.datasetOptions();
        expect(options.map((o) => o.value)).toEqual(['mule_transfers_dataset', 'maintenance_backups']);
        expect(options[0].hint).toContain('PAYER_ACCOUNT and PAYEE_ACCOUNT look like the two ends of a link');
        expect(options[0].hint).toContain('Columns: TXN_ID, PAYER_ACCOUNT, PAYEE_ACCOUNT, AMOUNT');
        expect(options[1].hint).toContain('No obvious from/to column pair');
    });

    it('picking a Dataset pre-fills Source and Target (editable), so the query is ready to run', async () => {
        const { c, fixture } = withDatasets();
        c.queryForm.patchValue({ datasetId: 'mule_transfers_dataset' });
        await settle(fixture);
        expect(c.queryForm.getRawValue()).toMatchObject({ sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT' });
        const note = fixture.nativeElement.querySelector('[data-testid="prefilled-note"]') as HTMLElement;
        expect(note.textContent).toContain('PAYER_ACCOUNT to PAYEE_ACCOUNT');
        expect(c.buildQuery()).toMatchObject({
            projection: { sourceCol: 'PAYER_ACCOUNT', targetCol: 'PAYEE_ACCOUNT' },
        });

        // the analyst changes one end: it is theirs now, and the note goes
        c.queryForm.patchValue({ targetCol: 'TXN_ID' });
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="prefilled-note"]')).toBeNull();
    });

    it('never overwrites a mapping that is already there (a loaded saved view)', async () => {
        const { c, fixture } = withDatasets();
        c.queryForm.patchValue({ datasetId: 'mule_transfers_dataset', sourceCol: 'TXN_ID', targetCol: 'AMOUNT' });
        await settle(fixture);
        expect(c.queryForm.getRawValue()).toMatchObject({ sourceCol: 'TXN_ID', targetCol: 'AMOUNT' });
        expect(c.prefilled()).toBeNull();
    });

    it('a Dataset with no obvious pair is left blank for the analyst to choose', async () => {
        const { c, fixture } = withDatasets();
        c.queryForm.patchValue({ datasetId: 'maintenance_backups' });
        await settle(fixture);
        expect(c.queryForm.getRawValue()).toMatchObject({ sourceCol: '', targetCol: '' });
    });

    it('the pickers are outlined, and Derive mapping is explained in plain words', () => {
        const { fixture } = withDatasets();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelectorAll('button[data-outlined]').length).toBeGreaterThanOrEqual(5);
        const help = el.querySelector('[data-testid="derive-mapping-help"]') as HTMLElement;
        expect(help.textContent).toContain('needs permission to author configuration');
        expect(help.textContent).toContain('use the columns filled in above');
    });

    it('Explore a Dataset opens the Dataset picker, and the pick that follows runs the query', async () => {
        const { c, fixture } = withDatasets();
        const picker = fixture.debugElement
            .queryAll(By.directive(InspectoOptionPickerComponent))
            .map((d) => d.componentInstance as InspectoOptionPickerComponent)
            .find((p) => p.label() === 'Dataset') as InspectoOptionPickerComponent;
        const open = vi.spyOn(picker, 'open').mockImplementation(() => undefined);
        const runs: unknown[] = [];
        c.run.subscribe(() => runs.push(1));
        c.startExplore();
        expect(open).toHaveBeenCalledTimes(1);
        c.queryForm.patchValue({ datasetId: 'mule_transfers_dataset' });
        await settle(fixture);
        expect(runs).toHaveLength(1);

        // a later plain pick does not run by itself
        c.queryForm.patchValue({ datasetId: 'maintenance_backups', sourceCol: '', targetCol: '' });
        await settle(fixture);
        expect(runs).toHaveLength(1);
    });

    it('Explore does not run a query it cannot map (no suggestion = the analyst chooses columns first)', async () => {
        const { c, fixture } = withDatasets();
        const runs: unknown[] = [];
        c.run.subscribe(() => runs.push(1));
        c.startExplore();
        c.queryForm.patchValue({ datasetId: 'maintenance_backups' });
        await settle(fixture);
        expect(runs).toHaveLength(0);
    });

    it('ranks a Dataset that declares no columns by the columns probed off its store', async () => {
        const columns = vi.fn(() =>
            Promise.resolve([
                { name: 'caller', type: 'string' },
                { name: 'callee', type: 'string' },
            ]),
        );
        const { c, fixture } = make('entity-projection', [{ provide: DatasetRowsService, useValue: { columns } }]);
        fixture.componentRef.setInput('datasets', [
            { id: 'a_plain', name: 'a_plain', sourceName: 'p', columns: [{ name: 'x', type: 'number' }] },
            { id: 'cdr', name: 'cdr', sourceName: 'cdr_store', columns: [] },
        ]);
        fixture.detectChanges();
        await settle(fixture);
        expect(columns).toHaveBeenCalledTimes(1);
        expect(c.datasetOptions().map((o) => o.value)).toEqual(['cdr', 'a_plain']);
    });

    it('is a11y-clean with the guidance showing', async () => {
        const { c, fixture } = withDatasets();
        c.queryForm.patchValue({ datasetId: 'mule_transfers_dataset' });
        await settle(fixture);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
