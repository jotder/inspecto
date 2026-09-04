import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthoredNode, ComponentsService, RelationsPreview } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineTransformSqlDefinitionComponent } from './pipeline-transform-sql-definition.component';
import { SqlField } from './pipeline-transform-sql';

let previewCalls: { config: Record<string, unknown>; rows: Record<string, unknown>[] }[] = [];
const previewAnswer: RelationsPreview = {
    inputColumns: ['customer'],
    relations: [
        {
            rel: 'DATA',
            rowCount: 1,
            rows: [{ buyer: 'Anna' }],
            columnTypes: [{ name: 'buyer', type: 'VARCHAR' }],
        },
    ],
    sql: ['SELECT TRIM(customer) AS buyer FROM input'],
};

@Component({
    standalone: true,
    imports: [PipelineTransformSqlDefinitionComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <app-pipeline-transform-sql-definition
            [node]="node"
            [sampleRows]="sampleRows"
            (applied)="applied = $event"
            (dirtyChange)="dirty = $event"
        />
    `,
})
class HostComponent {
    node: AuthoredNode = sqlNode();
    sampleRows?: Record<string, unknown>[];
    applied?: AuthoredNode;
    dirty = false;
}

function sqlNode(config: Record<string, unknown> = {}): AuthoredNode {
    return { id: 'sql1', type: 'transform.sql', name: 'Clean up orders', config };
}

function field(partial: Partial<SqlField> & Pick<SqlField, 'name' | 'fn'>): SqlField {
    return { id: partial.name, from: '', args: {}, ...partial };
}

async function create(node: AuthoredNode = sqlNode(), sampleRows?: Record<string, unknown>[]) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            // the preview table's theme service walks up to GAMMA_APP_CONFIG — stub it, as the data-table's own spec does
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            {
                provide: ComponentsService,
                useValue: {
                    previewTransform: (config: Record<string, unknown>, rows: Record<string, unknown>[]) => {
                        previewCalls.push({ config, rows });
                        return of(previewAnswer);
                    },
                },
            },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.componentInstance.sampleRows = sampleRows;
    fixture.detectChanges();
    return fixture;
}

function comp(fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>) {
    return fixture.debugElement.query(By.directive(PipelineTransformSqlDefinitionComponent))
        .componentInstance as PipelineTransformSqlDefinitionComponent;
}

beforeEach(() => {
    previewCalls = [];
});

describe('Transform pane: seeding', () => {
    it('seeds one "keep" row per upstream column', async () => {
        const fixture = await create(sqlNode(), [{ order_id: '1', customer: 'A' }]);
        const c = comp(fixture);
        expect(c.fields().map((f) => [f.name, f.from, f.fn])).toEqual([
            ['order_id', 'order_id', 'keep'],
            ['customer', 'customer', 'keep'],
        ]);
        expect(c.generatedSql()).toBe('SELECT\n  order_id AS order_id,\n  customer AS customer\nFROM input');
    });

    /** The defect the operator hit: a Step was armed for Apply merely by being opened. */
    it('opens CLEAN — merely rendering the Step never arms Apply', async () => {
        const fixture = await create(sqlNode(), [{ order_id: '1' }]);
        expect(fixture.componentInstance.dirty).toBe(false);
    });

    it('rebuilds the grid from persisted fields rather than re-seeding', async () => {
        const stored = [field({ name: 'buyer', from: 'customer', fn: 'text.trim' })];
        const fixture = await create(sqlNode({ sql: 'anything', fields: stored }), [{ customer: 'A', extra: 'x' }]);
        const c = comp(fixture);
        expect(c.fields().map((f) => f.name)).toEqual(['buyer']);
        expect(c.sqlOnly()).toBe(false);
    });

    it('opens hand-written SQL read-only rather than inventing a grid that would rewrite it', async () => {
        const fixture = await create(sqlNode({ sql: 'SELECT weird FROM input' }), [{ a: '1' }]);
        const c = comp(fixture);
        expect(c.sqlOnly()).toBe(true);
        expect(c.storedSql()).toBe('SELECT weird FROM input');
        expect(c.generatedSql()).toBe('SELECT weird FROM input');
        expect(fixture.nativeElement.textContent).toContain('cannot be shown as a field list');
    });

    it('can start a grid from the incoming columns, replacing hand-written SQL only when asked', async () => {
        const fixture = await create(sqlNode({ sql: 'SELECT weird FROM input' }), [{ a: '1' }]);
        const c = comp(fixture);
        c.startGridFromColumns();
        fixture.detectChanges();
        expect(c.sqlOnly()).toBe(false);
        expect(c.generatedSql()).toBe('SELECT\n  a AS a\nFROM input');
        expect(fixture.componentInstance.dirty).toBe(true);
    });
});

describe('Transform pane: editing', () => {
    it('picking a function seeds its parameter defaults and regenerates the SQL', async () => {
        const fixture = await create(sqlNode(), [{ amount: '1' }]);
        const c = comp(fixture);
        const id = c.fields()[0].id;
        c.setFunction(id, 'num.round');
        expect(c.fields()[0].args).toEqual({ decimals: '0' });
        c.setArg(id, 'decimals', '2');
        expect(c.generatedSql()).toBe('SELECT\n  ROUND(amount, 2) AS amount\nFROM input');
        expect(fixture.componentInstance.dirty).toBe(true);
    });

    it('renaming a field changes only its alias', async () => {
        const fixture = await create(sqlNode(), [{ customer: 'A' }]);
        const c = comp(fixture);
        c.rename(c.fields()[0].id, 'buyer');
        expect(c.generatedSql()).toBe('SELECT\n  customer AS buyer\nFROM input');
    });

    it('leaving a field out remembers its column so it can be put back', async () => {
        const fixture = await create(sqlNode(), [{ a: '1', b: '2' }]);
        const c = comp(fixture);
        c.removeField(c.fields()[0].id);
        expect(c.leftOut()).toEqual(['a']);
        expect(c.generatedSql()).toBe('SELECT\n  b AS b\nFROM input');
        c.restore('a');
        expect(c.leftOut()).toEqual([]);
        expect(c.fields().map((f) => f.name)).toEqual(['b', 'a']);
    });

    it('adds a calculated field with no source column', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        const c = comp(fixture);
        c.addCalculated();
        const added = c.fields()[1];
        expect(added.fn).toBe('custom');
        expect(added.from).toBe('');
    });
});

describe('Transform pane: Apply', () => {
    it('writes BOTH the generated sql and the fields that produced it', async () => {
        const fixture = await create(sqlNode(), [{ customer: 'A' }]);
        const c = comp(fixture);
        c.setFunction(c.fields()[0].id, 'text.trim');
        c.rename(c.fields()[0].id, 'buyer');
        c.submit();
        fixture.detectChanges();
        const applied = fixture.componentInstance.applied!;
        expect(applied.config?.['sql']).toBe('SELECT\n  TRIM(customer) AS buyer\nFROM input');
        expect((applied.config?.['fields'] as SqlField[]).map((f) => [f.name, f.from, f.fn])).toEqual([
            ['buyer', 'customer', 'text.trim'],
        ]);
        expect(fixture.componentInstance.dirty).toBe(false);
    });

    it('refuses to Apply while a row cannot compile', async () => {
        const fixture = await create(sqlNode(), [{ amount: '1' }]);
        const c = comp(fixture);
        c.setFunction(c.fields()[0].id, 'num.round');
        c.setArg(c.fields()[0].id, 'decimals', 'lots');
        expect(c.canApply()).toBe(false);
        c.submit();
        fixture.detectChanges();
        expect(fixture.componentInstance.applied).toBeUndefined();
    });

    it('re-Applying the same content clears dirty', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        const c = comp(fixture);
        c.rename(c.fields()[0].id, 'b');
        expect(fixture.componentInstance.dirty).toBe(true);
        c.submit();
        expect(fixture.componentInstance.dirty).toBe(false);
    });
});

describe('Transform pane: wide tables', () => {
    const wide = () => [Object.fromEntries(Array.from({ length: 65 }, (_, i) => [`col_${i + 1}`, String(i)]))];

    it('pages at 10 by default and # stays the position in the FULL list', async () => {
        const fixture = await create(sqlNode(), wide());
        const c = comp(fixture);
        expect(c.allRows().length).toBe(65);
        expect(c.pagedRows().length).toBe(10);
        expect(c.pageLabel()).toBe('1–10 of 65');
        c.nextPage();
        expect(c.pagedRows()[0].seq).toBe(11);
        expect(c.pageLabel()).toBe('11–20 of 65');
    });

    it('offers 10 / 20 / 100 and resets to the first page', async () => {
        const fixture = await create(sqlNode(), wide());
        const c = comp(fixture);
        expect([...c.pageSizes]).toEqual([10, 20, 100]);
        c.nextPage();
        c.setPageSize(100);
        expect(c.currentPage()).toBe(0);
        expect(c.pagedRows().length).toBe(65);
    });

    it('search narrows the view without changing what is written', async () => {
        const fixture = await create(sqlNode(), wide());
        const c = comp(fixture);
        const sqlBefore = c.generatedSql();
        c.setQuery('col_64');
        expect(c.visibleRows().map((r) => r.field.name)).toEqual(['col_64']);
        expect(c.visibleRows()[0].seq).toBe(64);
        expect(c.showing()).toBe('Showing 1 of 65 fields');
        expect(c.generatedSql()).toBe(sqlBefore);
    });

    it('filters are view-only lenses with counts', async () => {
        const fixture = await create(sqlNode(), [{ a: '1', b: '2' }]);
        const c = comp(fixture);
        c.setFunction(c.fields()[0].id, 'text.upper');
        expect(c.counts().changed).toBe(1);
        c.setFilter('changed');
        expect(c.visibleRows().map((r) => r.field.name)).toEqual(['a']);
        expect(c.generatedSql()).toContain('b AS b');
    });

    it('never strands the view on a page that no longer exists', async () => {
        const fixture = await create(sqlNode(), wide());
        const c = comp(fixture);
        c.nextPage();
        c.nextPage();
        c.setQuery('col_1');
        expect(c.currentPage()).toBe(0);
        expect(c.pagedRows().length).toBeGreaterThan(0);
    });
});

describe('Transform pane: Test this Step', () => {
    it('posts the GENERATED sql and fills "Comes out as" from the run', async () => {
        const fixture = await create(sqlNode(), [{ customer: ' Anna ' }]);
        const c = comp(fixture);
        c.setFunction(c.fields()[0].id, 'text.trim');
        c.rename(c.fields()[0].id, 'buyer');
        c.runTest();
        fixture.detectChanges();
        expect(previewCalls.length).toBe(1);
        expect(previewCalls[0].config).toEqual({
            type: 'transform.sql',
            sql: 'SELECT\n  TRIM(customer) AS buyer\nFROM input',
        });
        expect(c.allRows()[0].outType).toBe('VARCHAR');
        expect(c.allRows()[0].sample).toBe('Anna');
    });

    it('is disabled with no sample rows', async () => {
        const fixture = await create(sqlNode(), []);
        expect(comp(fixture).canTest()).toBe(false);
    });
});

describe('Transform pane: accessibility', () => {
    it('has no axe-core violations', async () => {
        const fixture = await create(sqlNode(), [{ order_id: '1', customer: 'A' }]);
        comp(fixture).runTest();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
