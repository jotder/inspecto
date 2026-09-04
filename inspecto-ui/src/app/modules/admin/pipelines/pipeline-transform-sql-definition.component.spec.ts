import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthoredNode, ComponentsService, RelationsPreview } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { InspectoSchemaFieldsEditorComponent } from 'app/inspecto/schema/schema-fields-editor.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineTransformSqlDefinitionComponent, seedSql } from './pipeline-transform-sql-definition.component';

let previewCalls: { config: Record<string, unknown>; rows: Record<string, unknown>[] }[] = [];
const previewAnswer: RelationsPreview = {
    inputColumns: ['customer'],
    relations: [
        {
            rel: 'DATA',
            rowCount: 1,
            rows: [{ buyer: 'Anna', n: 1 }],
            columnTypes: [
                { name: 'buyer', type: 'VARCHAR' },
                { name: 'n', type: 'INTEGER' },
            ],
        },
    ],
    sql: ['SELECT TRIM(customer) AS buyer, 1 AS n FROM input'],
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

describe('seedSql', () => {
    it('lists the upstream columns explicitly, quoting names that are not plain identifiers', () => {
        expect(seedSql(['order_id', 'Order Date'])).toBe('SELECT\n    order_id,\n    "Order Date"\nFROM input');
    });
    it('falls back to SELECT * when no columns are known', () => {
        expect(seedSql([])).toBe('SELECT * FROM input');
    });
});

describe('PipelineTransformSqlDefinitionComponent: seeding', () => {
    it('seeds a fresh Step with an explicit column list from the sample row', async () => {
        const fixture = await create(sqlNode(), [{ order_id: '1', customer: 'A' }]);
        const c = comp(fixture);
        expect(c.sql()).toBe('SELECT\n    order_id,\n    customer\nFROM input');
        expect(fixture.componentInstance.dirty).toBe(false);
        const textarea = fixture.nativeElement.querySelector('textarea') as HTMLTextAreaElement;
        expect(textarea.value).toBe(c.sql());
    });

    it('seeds SELECT * FROM input when there is no sample', async () => {
        const fixture = await create(sqlNode());
        expect(comp(fixture).sql()).toBe('SELECT * FROM input');
    });

    it('loads a stored sql verbatim', async () => {
        const fixture = await create(sqlNode({ sql: 'SELECT 1 AS one FROM input' }), [{ a: '1' }]);
        expect(comp(fixture).sql()).toBe('SELECT 1 AS one FROM input');
    });

    it('says to test first instead of fabricating column types', async () => {
        const fixture = await create(sqlNode(), [{ a: '1' }]);
        expect(fixture.nativeElement.textContent).toContain('Test this Step to see the columns that come out');
        expect(fixture.debugElement.query(By.directive(InspectoSchemaFieldsEditorComponent))).toBeNull();
    });
});

describe('PipelineTransformSqlDefinitionComponent: dirty tracking + Apply', () => {
    it('dirty ⇔ the SQL differs from the loaded value', async () => {
        const fixture = await create(sqlNode({ sql: 'SELECT a FROM input' }), [{ a: '1' }]);
        const c = comp(fixture);
        c.editSql('SELECT b FROM input');
        expect(fixture.componentInstance.dirty).toBe(true);
        c.editSql('SELECT a FROM input');
        expect(fixture.componentInstance.dirty).toBe(false);
    });

    it('Apply emits { sql } only and drops a legacy fields key', async () => {
        const fixture = await create(
            sqlNode({
                sql: 'SELECT order_id FROM input',
                fields: [{ id: 'a', name: 'order_id', from: 'order_id', verb: 'keep' }],
            }),
        );
        const c = comp(fixture);
        c.editSql('SELECT order_id AS id FROM input');
        c.submit();
        fixture.detectChanges();
        const applied = fixture.componentInstance.applied!;
        expect(applied.config).toEqual({ sql: 'SELECT order_id AS id FROM input' });
        expect(applied.id).toBe('sql1');
        expect(fixture.componentInstance.dirty).toBe(false);
    });
});

describe('PipelineTransformSqlDefinitionComponent: Test this Step', () => {
    it('posts the current sql through previewTransform and renders the derived columns table', async () => {
        const fixture = await create(sqlNode(), [{ customer: ' Anna ' }]);
        const c = comp(fixture);
        c.editSql('SELECT TRIM(customer) AS buyer, 1 AS n FROM input');
        c.runTest();
        fixture.detectChanges();
        expect(previewCalls.length).toBe(1);
        expect(previewCalls[0].config).toEqual({
            type: 'transform.sql',
            sql: 'SELECT TRIM(customer) AS buyer, 1 AS n FROM input',
        });
        expect(previewCalls[0].rows).toEqual([{ customer: ' Anna ' }]);
        expect(c.derivedRows()).toEqual([
            { include: true, name: 'buyer', selector: '1', type: 'VARCHAR' },
            { include: true, name: 'n', selector: '2', type: 'INTEGER' },
        ]);
        expect(c.derivedSamples()).toEqual({ '1': 'Anna', '2': '1' });
        const editor = fixture.debugElement.query(By.directive(InspectoSchemaFieldsEditorComponent));
        expect(editor).not.toBeNull();
        expect((editor.componentInstance as InspectoSchemaFieldsEditorComponent).autoTypes()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('buyer');
    });

    it('is disabled with no sample rows', async () => {
        const fixture = await create(sqlNode(), []);
        expect(comp(fixture).canTest()).toBe(false);
    });
});

describe('PipelineTransformSqlDefinitionComponent: accessibility', () => {
    it('has no axe-core violations', async () => {
        const fixture = await create(sqlNode(), [{ order_id: '1' }]);
        comp(fixture).runTest();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
