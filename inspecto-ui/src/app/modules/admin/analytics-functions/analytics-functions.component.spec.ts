import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AnalyticsCatalogue, AnalyticsCatalogueService } from 'app/inspecto/api';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { AnalyticsFunctionsComponent } from './analytics-functions.component';

const CATALOGUE: AnalyticsCatalogue = {
    total: 3,
    kernels: [
        { id: 'statistics', label: 'Statistics', count: 2 },
        { id: 'similarity', label: 'Similarity', count: 1 },
    ],
    functions: [
        {
            id: 'stats.mean',
            label: 'Mean',
            kernel: 'statistics',
            cost: 'linear',
            result: 'scalar',
            forms: ['jvm', 'sql'],
            step: null,
            summary: 'Arithmetic mean.',
            params: [],
        },
        {
            id: 'stats.quantile',
            label: 'Quantile',
            kernel: 'statistics',
            cost: 'linearithmic',
            result: 'scalar',
            forms: ['jvm', 'sql'],
            step: null,
            summary: 'The q-quantile.',
            params: [{ name: 'q', type: 'decimal', default: null, min: 0, max: 1, description: 'The quantile.' }],
        },
        {
            id: 'similarity.score',
            label: 'Text similarity',
            kernel: 'similarity',
            cost: 'linear',
            result: 'column',
            forms: ['jvm', 'sql'],
            step: 'transform.similarity',
            summary: 'Similarity of two text columns.',
            params: [],
        },
    ],
};

async function create(catalogue: unknown = of(CATALOGUE)) {
    const toastr = { error: vi.fn(), warning: vi.fn() };
    const api = { catalogue: vi.fn(() => catalogue) } as unknown as AnalyticsCatalogueService;
    TestBed.configureTestingModule({
        imports: [AnalyticsFunctionsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: AnalyticsCatalogueService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(AnalyticsFunctionsComponent);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, toastr };
}

describe('AnalyticsFunctionsComponent', () => {
    it('loads the catalogue on init and offers an All tab plus one tab per kernel with counts', async () => {
        const { fixture, api } = await create();
        expect(api.catalogue).toHaveBeenCalledTimes(1);
        expect(fixture.componentInstance.tabs().map((t) => [t.id, t.count])).toEqual([
            ['all', 3],
            ['statistics', 2],
            ['similarity', 1],
        ]);
        expect(fixture.componentInstance.rows()).toHaveLength(3);
    });

    it('narrows the rows to the chosen kernel', async () => {
        const { fixture } = await create();
        fixture.componentInstance.kernel.set('similarity');
        expect(fixture.componentInstance.rows().map((f) => f.id)).toEqual(['similarity.score']);
    });

    it('degrades to an empty list and a plain failure toast when the load fails', async () => {
        const { fixture, toastr } = await create(throwError(() => ({ status: 500 })));
        expect(fixture.componentInstance.rows()).toEqual([]);
        expect(toastr.error).toHaveBeenCalledWith('Failed to load the Analytics function catalogue');
    });

    it('renders the loaded state with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
