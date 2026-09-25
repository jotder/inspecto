import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { ToastrService } from 'ngx-toastr';
import { ComponentsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DatasetResultService } from 'app/inspecto/viz/dataset-result.service';
import { DatasetRows, DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { SAMPLE_SOURCES } from 'app/inspecto/fixtures/sample-sources';
import { runSpec } from 'app/inspecto/viz/query-spec';
import { Dataset } from '../datasets/dataset-types';
import { DatasetsService } from '../datasets/datasets.service';
import { WidgetsService } from './widgets.service';
import { ExploreComponent } from './explore.component';

const DS: Dataset = {
    id: 'cdr_sample',
    name: 'cdr_sample',
    kind: 'virtual',
    sourceName: 'cdr',
    columns: [
        { name: 'tariff', type: 'string', role: 'dimension' },
        { name: 'duration_s', type: 'number', role: 'measure' },
    ],
    measures: [],
    calculated: [],
};

const CDR_PAGE: DatasetRows = {
    rows: SAMPLE_SOURCES['cdr'],
    columns: [
        { name: 'tariff', type: 'string', cardinality: 3 },
        { name: 'duration_s', type: 'number' },
    ],
    truncated: false,
};

function create(ds: Dataset = DS, page: DatasetRows = CDR_PAGE) {
    TestBed.configureTestingModule({
        imports: [ExploreComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: DatasetsService, useValue: { list: () => of([ds]), get: () => of(ds) } },
            { provide: WidgetsService, useValue: { list: () => of([]), get: () => of(null), save: () => of(null) } },
            {
                provide: ComponentsService,
                useValue: {
                    list: () =>
                        of([
                            {
                                type: 'geo-map-view',
                                name: 'dhaka-network',
                                ref: '',
                                content: { name: 'Example — Dhaka cell network' },
                            },
                        ]),
                },
            },
            // Pass-through to the offline runSpec (no cache, no HttpClient) — byte-identical M1 behaviour.
            { provide: DatasetResultService, useValue: { run: runSpec, clear: () => undefined } },
            // The rows seam, stubbed at its offline answer: the store's sample page, and the server-derived
            // cardinality it would carry live for a dimension column.
            {
                provide: DatasetRowsService,
                useValue: { rows: () => Promise.resolve(page) },
            },
            { provide: MatDialog, useValue: { open: () => ({ afterClosed: () => of(undefined) }) } },
            {
                provide: ToastrService,
                useValue: { warning: () => undefined, success: () => undefined, error: () => undefined },
            },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    return TestBed.createComponent(ExploreComponent);
}

describe('ExploreComponent', () => {
    it('loads datasets on init', () => {
        const fixture = create();
        fixture.detectChanges();
        expect(fixture.componentInstance.datasets()).toHaveLength(1);
    });

    it('selecting a dataset picks a recommended viz and auto-assigns channels', async () => {
        const fixture = create();
        const c = fixture.componentInstance;
        c.onSelectDataset('cdr_sample');
        await fixture.whenStable(); // the rows page lands before Show-Me reads the field cardinalities
        expect(c.dataset()?.id).toBe('cdr_sample');
        expect(c.vizType()).toBeTruthy();
        // a measure field got mapped onto some channel
        const mapped = Object.values(c.controls()).some((vals) => vals?.some((v) => v.field === 'duration_s'));
        expect(mapped).toBe(true);
        // A dimension's cardinality comes from the seam (the store derives it), not from counting the page.
        expect(c.fields().find((f) => f.name === 'tariff')?.cardinality).toBe(3);
    });

    it('a Dataset declaring no columns maps the columns the store served, role-seeded', async () => {
        // A Stream's Dataset registered at go-live carries no column list; the builder must not go empty.
        const fixture = create({ ...DS, columns: [] });
        const c = fixture.componentInstance;
        c.onSelectDataset('cdr_sample');
        await fixture.whenStable();
        expect(c.fields().map((f) => [f.name, f.role])).toEqual([
            ['tariff', 'dimension'],
            ['duration_s', 'measure'],
        ]);
        const mapped = Object.values(c.controls()).some((vals) => vals?.some((v) => v.field === 'duration_s'));
        expect(mapped).toBe(true);
    });

    it('offers calculated columns as fields, typed + counted from the relation the seam served', async () => {
        // The relation page (GET /datasets/{id}/rows) carries the calculated columns; the raw store would not.
        const calculated = [
            { name: 'match_date', expr: "cast(strptime(DATE, '%B %d,%Y') AS date)" },
            { name: 'match_runs', expr: 'FIRST_INGS_SCORE + SECOND_INGS_SCORE' },
            { name: 'city', expr: "trim(split_part(VENUE, ',', 2))" },
            { name: 'unserved', expr: 'upper(TEAM1)' },
        ];
        const page: DatasetRows = {
            rows: [{ tariff: 'a', duration_s: 1, match_date: '2025-03-22', match_runs: 351, city: 'Kolkata' }],
            columns: [
                ...CDR_PAGE.columns,
                { name: 'match_date', type: 'date' },
                { name: 'match_runs', type: 'number' },
                { name: 'city', type: 'string', cardinality: 12 },
            ],
            truncated: false,
        };
        const fixture = create({ ...DS, calculated }, page);
        const c = fixture.componentInstance;
        c.onSelectDataset('cdr_sample');
        await fixture.whenStable();
        const f = (n: string) => c.fields().find((x) => x.name === n);
        expect(f('match_date')).toMatchObject({ type: 'date', role: 'temporal' });
        expect(f('match_runs')).toMatchObject({ type: 'number', role: 'measure' });
        expect(f('city')).toMatchObject({ type: 'string', role: 'dimension', cardinality: 12 });
        // not served (a failed read) is still offered — as a text dimension — rather than silently missing
        expect(f('unserved')).toMatchObject({ type: 'string', role: 'dimension' });
        // declared columns keep their place ahead of the calculated ones
        expect(
            c
                .fields()
                .slice(0, 2)
                .map((x) => x.name),
        ).toEqual(['tariff', 'duration_s']);
    });

    it('a calculated column the Dataset also declares is not listed twice', async () => {
        const fixture = create({
            ...DS,
            columns: [...DS.columns, { name: 'city', type: 'string', role: 'dimension', label: 'City' }],
            calculated: [{ name: 'city', expr: "trim(split_part(VENUE, ',', 2))" }],
        });
        const c = fixture.componentInstance;
        c.onSelectDataset('cdr_sample');
        await fixture.whenStable();
        expect(c.fields().filter((x) => x.name === 'city')).toHaveLength(1);
        expect(c.fields().find((x) => x.name === 'city')?.label).toBe('City');
    });

    it('selecting a view-bound plugin swaps the field mapper for the saved-view picker', () => {
        const c = create().componentInstance;
        c.setVizType('geo-map');
        expect(c.viewBound()).toBe(true);
        expect(c.controls()).toEqual({});
        expect(c.savedViews()).toEqual([{ id: 'dhaka-network', name: 'Example — Dhaka cell network' }]);
    });

    it('renders the initial (no dataset) state with no a11y violations', async () => {
        const fixture = create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
