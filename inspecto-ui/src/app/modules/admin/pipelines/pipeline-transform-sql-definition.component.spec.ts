import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthoredNode, ComponentsService, RelationsPreview } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineTransformSqlDefinitionComponent } from './pipeline-transform-sql-definition.component';

let previewCalls: { config: Record<string, unknown>; rows: Record<string, unknown>[] }[] = [];
let previewAnswer: RelationsPreview = {
    inputColumns: ['customer'],
    relations: [{ rel: 'DATA', rowCount: 1, rows: [], columnTypes: [{ name: 'buyer', type: 'VARCHAR' }] }],
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

async function create(node: AuthoredNode = sqlNode(), sampleRows?: Record<string, unknown>[]) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
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

describe('PipelineTransformSqlDefinitionComponent: seeding', () => {
    it('seeds one Keep row per upstream sample column, in order, for a fresh step', async () => {
        const fixture = await create(sqlNode(), [{ order_id: '1', customer: 'A', amount: '9' }]);
        const c = comp(fixture);
        expect(c.fields().map((f) => f.name)).toEqual(['order_id', 'customer', 'amount']);
        expect(c.fields().every((f) => f.verb === 'keep')).toBe(true);
        expect(c.locked()).toBe(false);
    });

    it('loads a stored fields[] config unlocked', async () => {
        const fixture = await create(
            sqlNode({
                sql: 'SELECT order_id AS order_id FROM input',
                fields: [{ id: 'a', name: 'order_id', from: 'order_id', verb: 'keep' }],
            }),
        );
        const c = comp(fixture);
        expect(c.locked()).toBe(false);
        expect(c.fields().map((f) => f.name)).toEqual(['order_id']);
    });

    it('loads a stored hand-written sql (no fields[]) locked, in Advanced', async () => {
        const fixture = await create(sqlNode({ sql: 'SELECT 1 AS one FROM input' }));
        const c = comp(fixture);
        expect(c.locked()).toBe(true);
        expect(c.mode()).toBe('advanced');
        expect(c.sql()).toBe('SELECT 1 AS one FROM input');
    });
});

describe('PipelineTransformSqlDefinitionComponent: lock / unlock', () => {
    it('typing in Advanced sets locked=true', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        const c = comp(fixture);
        expect(c.locked()).toBe(false);
        c.editSql('SELECT 1 AS a FROM input');
        expect(c.locked()).toBe(true);
        expect(c.sql()).toBe('SELECT 1 AS a FROM input');
    });

    it('"Start over from the table" clears locked and regenerates SQL from fields[]', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        const c = comp(fixture);
        c.editSql('SELECT 1 AS a FROM input');
        expect(c.locked()).toBe(true);
        c.startOverFromTable();
        expect(c.locked()).toBe(false);
        expect(c.sql()).toBe(c.generatedSql());
    });

    it('submit persists fields[] only when unlocked, and always persists sql', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        const c = comp(fixture);
        c.submit();
        fixture.detectChanges();
        const applied = fixture.componentInstance.applied!;
        expect(applied.config?.['sql']).toBeTruthy();
        expect(Array.isArray(applied.config?.['fields'])).toBe(true);
    });

    it('submit omits fields[] once locked (hand-written sql only)', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        const c = comp(fixture);
        c.editSql('SELECT 1 AS a FROM input');
        c.submit();
        fixture.detectChanges();
        const applied = fixture.componentInstance.applied!;
        expect(applied.config?.['fields']).toBeUndefined();
        expect(applied.config?.['sql']).toBe('SELECT 1 AS a FROM input');
    });
});

describe('PipelineTransformSqlDefinitionComponent: pagination / search / filter stability (D7)', () => {
    function manyCols(n: number): Record<string, unknown> {
        const row: Record<string, unknown> = {};
        for (let i = 1; i <= n; i++) row[`col_${String(i).padStart(2, '0')}`] = 'v';
        return row;
    }

    it('the # stays the FULL-list position across pages', async () => {
        const fixture = await create(sqlNode(), [manyCols(25)]);
        const c = comp(fixture);
        c.setPageSize(10);
        expect(c.pagedRows()[0].seq).toBe(1);
        c.nextPage();
        expect(c.pagedRows()[0].seq).toBe(11);
        expect(c.pageLabel()).toBe('11–20 of 25');
    });

    it("a search that changes the result set keeps a surviving row's # unchanged", async () => {
        const fixture = await create(sqlNode(), [manyCols(25)]);
        const c = comp(fixture);
        c.setPageSize(10);
        c.nextPage();
        const before = c.pagedRows().find((r) => r.field.name === 'col_15')?.seq;
        c.setQuery('col_1'); // matches col_1, col_10..19 — resets to page 1 per the plan
        expect(c.currentPage()).toBe(0);
        const after = c.allRows().find((r) => r.field.name === 'col_15')?.seq;
        expect(after).toBe(before);
    });

    it('changing the filter resets to page 1', async () => {
        const fixture = await create(sqlNode(), [manyCols(25)]);
        const c = comp(fixture);
        c.setPageSize(10);
        c.nextPage();
        expect(c.currentPage()).toBe(1);
        c.setFilter('changed');
        expect(c.currentPage()).toBe(0);
    });
});

describe('PipelineTransformSqlDefinitionComponent: leave out / restore', () => {
    it('removing a field moves it to Left out; restoring puts it back as a Keep row', async () => {
        const fixture = await create(sqlNode(), [{ a: '1', b: '2' }]);
        const c = comp(fixture);
        const id = c.fields().find((f) => f.name === 'b')!.id;
        c.removeField(id);
        expect(c.fields().map((f) => f.name)).toEqual(['a']);
        expect(c.leftOut().map((l) => l.name)).toEqual(['b']);

        c.restoreLeftOut(c.leftOut()[0].id);
        expect(c.fields().map((f) => f.name)).toEqual(['a', 'b']);
        expect(c.leftOut().length).toBe(0);
    });
});

describe('PipelineTransformSqlDefinitionComponent: Test this Step (B4)', () => {
    it('runs the current sql through ComponentsService.previewTransform', async () => {
        const fixture = await create(sqlNode(), [{ customer: ' Anna ' }]);
        const c = comp(fixture);
        c.runTest();
        fixture.detectChanges();
        expect(previewCalls.length).toBe(1);
        expect(previewCalls[0].config['type']).toBe('transform.sql');
        expect(typeof previewCalls[0].config['sql']).toBe('string');
        expect(c.preview()).toEqual(previewAnswer);
    });

    it('is disabled with no sample rows', async () => {
        const fixture = await create(sqlNode(), []);
        const c = comp(fixture);
        expect(c.canTest()).toBe(false);
    });
});

describe('PipelineTransformSqlDefinitionComponent: accessibility', () => {
    it('has no axe-core violations', async () => {
        const fixture = await create(sqlNode(), [{ order_id: '1' }]);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
