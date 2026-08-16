import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthoredNode, ConfigService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineLoadDefinitionComponent } from './pipeline-load-definition.component';

let schemaMissing = false;

/** Host so the required signal `node` input binds naturally and outputs are captured. */
@Component({
    standalone: true,
    imports: [PipelineLoadDefinitionComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <app-pipeline-load-definition
            [node]="node"
            [schemaFile]="schemaFile"
            (applied)="applied = $event"
            (dirtyChange)="dirty = $event"
        />
    `,
})
class HostComponent {
    node: AuthoredNode = mapNode();
    schemaFile = 'spaces/default/config/cdr_schema.toon';
    applied?: AuthoredNode;
    dirty = false;
}

function mapNode(config: Record<string, unknown> = {}): AuthoredNode {
    return { id: 'map', type: 'transform.map', name: 'Map', config };
}

async function create(node: AuthoredNode = mapNode(), schemaFile = 'spaces/default/config/cdr_schema.toon') {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ConfigService,
                useValue: {
                    read: () =>
                        schemaMissing
                            ? throwError(() => ({ status: 404 }))
                            : of({
                                  config: {
                                      raw: {
                                          fields: [
                                              { name: 'A_NUMBER', selector: '0', type: 'VARCHAR' },
                                              { name: 'DURATION', selector: '1', type: 'DOUBLE' },
                                          ],
                                      },
                                  },
                              }),
                },
            },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.componentInstance.schemaFile = schemaFile;
    fixture.detectChanges();
    return fixture;
}

function pane(fixture: ComponentFixture<HostComponent>): PipelineLoadDefinitionComponent {
    return fixture.debugElement.query(By.directive(PipelineLoadDefinitionComponent)).componentInstance;
}

/**
 * P4-2b — the Load pane authors `processing.map`'s `rules` on a `transform.map` node. Its field list
 * comes from the PARSER's schema_file (operator decision 2026-08-16): the editor has no sample thread,
 * and the rules map FROM the schema's fields anyway.
 */
describe('PipelineLoadDefinitionComponent', () => {
    beforeEach(() => (schemaMissing = false));

    it('seeds one identity rule per schema field when the node has none', async () => {
        const fixture = await create();
        const p = pane(fixture);
        expect(p.fields()).toEqual(['A_NUMBER', 'DURATION']);
        expect(p.ruleRows.controls.map((g) => g.getRawValue())).toEqual([
            { targetColumn: 'A_NUMBER', sourceExpression: 'A_NUMBER', transformType: 'DIRECT' },
            { targetColumn: 'DURATION', sourceExpression: 'DURATION', transformType: 'DIRECT' },
        ]);
    });

    it('prefers the rules already authored on the node over a fresh identity seed', async () => {
        const fixture = await create(
            mapNode({ rules: [{ targetColumn: 'msisdn', sourceExpression: 'A_NUMBER', transformType: 'DIRECT' }] }),
        );
        expect(pane(fixture).ruleRows.length).toBe(1);
        expect(pane(fixture).ruleRows.at(0).get('targetColumn')?.value).toBe('msisdn');
    });

    /** Blank/omitted IS DIRECT in TransformCompiler — a legacy rule must not render an empty select. */
    it('reads a blank transformType as DIRECT', async () => {
        const fixture = await create(mapNode({ rules: [{ targetColumn: 'x', sourceExpression: 'A_NUMBER' }] }));
        expect(pane(fixture).ruleRows.at(0).get('transformType')?.value).toBe('DIRECT');
    });

    it('emits the node carrying only rules, leaving columns untouched', async () => {
        const fixture = await create(mapNode({ columns: ['keep', 'me'] }));
        pane(fixture).submit();
        fixture.detectChanges();

        const applied = fixture.componentInstance.applied!;
        expect(applied.config!['columns']).toEqual(['keep', 'me']);
        expect(applied.config!['rules']).toEqual([
            { targetColumn: 'A_NUMBER', sourceExpression: 'A_NUMBER', transformType: 'DIRECT' },
            { targetColumn: 'DURATION', sourceExpression: 'DURATION', transformType: 'DIRECT' },
        ]);
    });

    it('refuses a duplicate target column — one rule per output column', async () => {
        const fixture = await create();
        const p = pane(fixture);
        p.ruleRows.at(1).get('targetColumn')?.setValue('A_NUMBER');
        p.submit();
        fixture.detectChanges();

        expect(fixture.componentInstance.applied).toBeUndefined();
        expect(String(p.error())).toContain('Duplicate target column "A_NUMBER"');
    });

    it('refuses an invalid target column', async () => {
        const fixture = await create();
        const p = pane(fixture);
        p.ruleRows.at(0).get('targetColumn')?.setValue('9bad');
        p.submit();
        fixture.detectChanges();

        expect(fixture.componentInstance.applied).toBeUndefined();
        expect(String(p.error())).toContain('valid target column');
    });

    /**
     * ⛔ Not offering CONCAT_DT/FILENAME_DATE is a UI limit; it must never become data loss. A rule
     * already carrying one keeps it, and the select shows it alongside the two offered types.
     */
    it('preserves a transform type it does not offer', async () => {
        const fixture = await create(
            mapNode({
                rules: [{ targetColumn: 'day', sourceExpression: 'FILENAME', transformType: 'FILENAME_DATE' }],
            }),
        );
        const p = pane(fixture);
        expect(p.transformsFor(p.ruleRows.at(0))).toEqual(['DIRECT', 'EXPR', 'FILENAME_DATE']);

        p.submit();
        fixture.detectChanges();
        expect((fixture.componentInstance.applied!.config!['rules'] as Record<string, unknown>[])[0]['transformType'])
            .toBe('FILENAME_DATE');
    });

    it('still edits the node rules when the schema cannot be read, reporting the lost picker', async () => {
        schemaMissing = true;
        const fixture = await create(
            mapNode({ rules: [{ targetColumn: 'x', sourceExpression: 'A', transformType: 'EXPR' }] }),
        );
        const p = pane(fixture);
        expect(p.fields()).toEqual([]);
        expect(String(p.error())).toContain('schema');
        // The authored rules survive: only the source PICKER lost its options.
        expect(p.ruleRows.length).toBe(1);
    });

    it('says what to do first when no schema file is bound yet', async () => {
        const fixture = await create(mapNode(), '');
        expect(pane(fixture).ruleRows.length).toBe(0);
        expect(fixture.nativeElement.textContent).toContain('Define the output schema on the parse step first');
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
