import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { DataTableComponent } from 'app/inspecto/data-table';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { QueryPanelComponent } from './query-panel.component';
import { emptyGroup, QueryModel, QuerySource } from './query-types';

const SOURCE: QuerySource = {
    name: 'cdr',
    rows: [
        { id: 1, cell: 'A', dur: 30 },
        { id: 2, cell: 'B', dur: 120 },
        { id: 3, cell: 'A', dur: 90 },
    ],
};

async function create() {
    TestBed.configureTestingModule({
        imports: [QueryPanelComponent],
        providers: [provideNoopAnimations(), { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } }],
    });
    await TestBed.compileComponents(); // the embedded data-table has a @defer block (the SQL editor)
    const f = TestBed.createComponent(QueryPanelComponent);
    f.componentInstance.source = SOURCE;
    f.detectChanges();
    return f;
}

function table(f: Awaited<ReturnType<typeof create>>): DataTableComponent {
    return f.debugElement.query(By.directive(DataTableComponent)).componentInstance as DataTableComponent;
}

describe('QueryPanelComponent', () => {
    it('is a thin adapter: forwards rows/sourceName into the embedded pro-tier data-table', async () => {
        const f = await create();
        const t = table(f);
        expect(t.tier()).toBe('pro');
        expect(t.rows()).toEqual(SOURCE.rows);
        expect(t.sourceName()).toBe('cdr');
    });

    it('opens the Filter and SQL panels by default (authoring is the point of this surface)', async () => {
        const f = await create();
        const t = table(f);
        expect(t.sqlOpen()).toBe(true);
        expect(t.filterOpen()).toBe(true);
    });

    it('re-emits the embedded table\'s queryModelChange as queryChange', async () => {
        const f = await create();
        const emitted: { model: QueryModel; sql: string }[] = [];
        f.componentInstance.queryChange.subscribe((e) => emitted.push(e));

        const t = table(f);
        t.onChosen(['id', 'cell']);
        f.detectChanges();

        const last = emitted.at(-1)!;
        expect(last.model.projection).toEqual(['id', 'cell']);
        expect(last.sql).toContain('SELECT "id", "cell"');
    });

    it('seeds the embedded table from initialModel (re-opening a saved query for edit)', async () => {
        const seed: QueryModel = {
            projection: ['cell', 'dur'],
            where: { ...emptyGroup('AND'), items: [{ kind: 'condition', field: 'cell', operator: '=', value: 'A' }] },
            sqlOverride: null,
        };
        TestBed.configureTestingModule({
            imports: [QueryPanelComponent],
            providers: [provideNoopAnimations(), { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } }],
        });
        await TestBed.compileComponents();
        const f = TestBed.createComponent(QueryPanelComponent);
        f.componentInstance.initialModel = seed;
        f.componentInstance.source = SOURCE;
        f.detectChanges();

        const t = table(f);
        expect(t.chosen()).toEqual(['cell', 'dur']);
        expect(t.where().items).toEqual(seed.where.items);
    });

    it('has no a11y violations', async () => {
        const f = await create();
        await expectNoA11yViolations(f.nativeElement);
    });
});
