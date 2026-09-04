import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthoredNode, ComponentsService, RelationsPreview } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineTransformSqlDefinitionComponent } from './pipeline-transform-sql-definition.component';
import { SqlField } from './pipeline-transform-sql';

let previewCalls: { config: Record<string, unknown>; rows: Record<string, unknown>[] }[] = [];
let describeCalls: { inputColumns: { name: string; type: string }[]; sql: string }[] = [];
let describeError: string | null = null;
let describeStatus = 422;
let describeAnswer: { columns: { name: string; type: string }[] } = {
    columns: [{ name: 'buyer', type: 'VARCHAR' }],
};

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
            [upstreamColumns]="upstreamColumns"
            (applied)="applied = $event"
            (dirtyChange)="dirty = $event"
        />
    `,
})
class HostComponent {
    node: AuthoredNode = sqlNode();
    sampleRows?: Record<string, unknown>[];
    upstreamColumns?: string[];
    applied?: AuthoredNode;
    dirty = false;
}

function sqlNode(config: Record<string, unknown> = {}): AuthoredNode {
    return { id: 'sql1', type: 'transform.sql', name: 'Clean up orders', config };
}

function field(partial: Partial<SqlField> & Pick<SqlField, 'name' | 'fn'>): SqlField {
    return { id: partial.name, from: '', args: {}, ...partial };
}

async function create(
    node: AuthoredNode = sqlNode(),
    sampleRows?: Record<string, unknown>[],
    upstreamColumns?: string[],
) {
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
                    describeTransform: (inputColumns: { name: string; type: string }[], sql: string) => {
                        describeCalls.push({ inputColumns, sql });
                        if (describeError) {
                            // The real envelope: HttpErrorResponse.status + the API's { error: { message } } body.
                            return throwError(() => ({
                                status: describeStatus,
                                error: { error: { message: describeError } },
                            }));
                        }
                        return of(describeAnswer);
                    },
                },
            },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.componentInstance.sampleRows = sampleRows;
    fixture.componentInstance.upstreamColumns = upstreamColumns;
    fixture.detectChanges();
    return fixture;
}

function comp(fixture: ReturnType<typeof TestBed.createComponent<HostComponent>>) {
    return fixture.debugElement.query(By.directive(PipelineTransformSqlDefinitionComponent))
        .componentInstance as PipelineTransformSqlDefinitionComponent;
}

beforeEach(() => {
    previewCalls = [];
    describeCalls = [];
    describeError = null;
    describeStatus = 422;
    describeAnswer = { columns: [{ name: 'buyer', type: 'VARCHAR' }] };
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

    it('seeds fields from upstreamColumns when sampleRows is undefined', async () => {
        const fixture = await create(sqlNode(), undefined, ['user_id', 'email']);
        const c = comp(fixture);
        expect(c.fields().map((f) => [f.name, f.from, f.fn])).toEqual([
            ['user_id', 'user_id', 'keep'],
            ['email', 'email', 'keep'],
        ]);
        expect(c.generatedSql()).toBe('SELECT\n  user_id AS user_id,\n  email AS email\nFROM input');
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

    it('moveUp and moveDown reorder fields and regenerate the SQL', async () => {
        const fixture = await create(sqlNode(), [{ a: '1', b: '2', c: '3' }]);
        const compInstance = comp(fixture);
        const bId = compInstance.fields()[1].id;
        compInstance.moveUp(bId);
        expect(compInstance.fields().map((f) => f.name)).toEqual(['b', 'a', 'c']);
        expect(compInstance.generatedSql()).toBe('SELECT\n  b AS b,\n  a AS a,\n  c AS c\nFROM input');
        expect(fixture.componentInstance.dirty).toBe(true);

        compInstance.moveDown(bId);
        expect(compInstance.fields().map((f) => f.name)).toEqual(['a', 'b', 'c']);
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

    it('detects duplicate field names and blocks Apply', async () => {
        const fixture = await create(sqlNode(), [{ a: '1', b: '2' }]);
        const c = comp(fixture);
        c.rename(c.fields()[1].id, 'a'); // duplicate name 'a'
        expect(c.allRows()[0].problem).toContain('Duplicate field name');
        expect(c.allRows()[1].problem).toContain('Duplicate field name');
        expect(c.canApply()).toBe(false);
    });

    /**
     * ⚠ The drawer's Apply is armed by `dirtyChange`, and CodeMirror made the hand-written SQL editable —
     * so while `canApply()` also refused `sqlOnly`, typing there armed the button and `submit()` returned
     * early: a real edit, a live button, and nothing saved.
     */
    it('arms Apply on a hand-written SQL edit and saves it', async () => {
        const fixture = await create(sqlNode({ sql: 'SELECT weird FROM input' }), [{ a: '1' }]);
        const c = comp(fixture);
        expect(c.sqlOnly()).toBe(true);
        expect(fixture.componentInstance.dirty).toBe(false);
        c.onSqlEdited('SELECT weird, 1 AS one FROM input');
        fixture.detectChanges();
        expect(fixture.componentInstance.dirty).toBe(true);
        expect(c.canApply()).toBe(true);
        c.submit();
        fixture.detectChanges();
        expect(fixture.componentInstance.applied!.config?.['sql']).toBe('SELECT weird, 1 AS one FROM input');
        expect(fixture.componentInstance.applied!.config?.['fields']).toEqual([]);
        expect(fixture.componentInstance.dirty).toBe(false);
    });

    it('surfaces DuckDB binder errors and blocks Apply', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        const c = comp(fixture);
        c.binderError.set('Binder Error: Referenced column "x" not found');
        expect(c.canApply()).toBe(false);
    });
});

/** The describe effect debounces 300ms before it calls the route — wait it out, then settle the view. */
async function settleDescribe(fixture: { detectChanges: () => void }): Promise<void> {
    await new Promise((r) => setTimeout(r, 350));
    fixture.detectChanges();
}

describe('Transform pane: live schema derivation', () => {
    it('fills the output type from the describe route, without a test run', async () => {
        describeAnswer = { columns: [{ name: 'order_id', type: 'VARCHAR' }] };
        const fixture = await create(sqlNode(), [{ order_id: '1' }]);
        const c = comp(fixture);
        await settleDescribe(fixture);
        expect(describeCalls.length).toBeGreaterThan(0);
        expect(describeCalls.at(-1)!.inputColumns.map((col) => col.name)).toEqual(['order_id']);
        expect(c.allRows()[0].outType).toBe('VARCHAR');
        expect(c.binderError()).toBeNull();
        expect(c.canApply()).toBe(true);
    });

    it('shows a 422 binder refusal and blocks Apply', async () => {
        describeError = 'Binder Error: Referenced column "x" not found';
        describeStatus = 422;
        const fixture = await create(sqlNode(), [{ order_id: '1' }]);
        const c = comp(fixture);
        await settleDescribe(fixture);
        expect(c.binderError()).toContain('Binder Error');
        expect(c.canApply()).toBe(false);
    });

    it('a failure that is NOT a binder refusal never locks Apply', async () => {
        // Offline, a 404 on an older control plane, a 503 — none of them say anything about the SQL,
        // so the pane must keep saving. Blocking here would strand the author with no way out.
        describeError = 'Http failure response: 0 Unknown Error';
        describeStatus = 0;
        const fixture = await create(sqlNode(), [{ order_id: '1' }]);
        const c = comp(fixture);
        await settleDescribe(fixture);
        expect(c.binderError()).toBeNull();
        expect(c.canApply()).toBe(true);
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

describe('Transform pane: live schema derivation', () => {
    it('populates outType from derivedColumnTypes without running a sample test', async () => {
        const fixture = await create(sqlNode(), [{ customer: 'Anna' }]);
        const c = comp(fixture);
        c.derivedColumnTypes.set({ customer: 'VARCHAR' });
        expect(c.allRows()[0].outType).toBe('VARCHAR');
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
