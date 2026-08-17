import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { AuthoredNode, ConfigService, SchemaPreview } from 'app/inspecto/api';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineLoadDefinitionComponent } from './pipeline-load-definition.component';

let schemaMissing = false;
/** What the stubbed `POST /config/preview/schema` answers, and what it was asked. */
let previewAnswer: SchemaPreview | HttpErrorResponse = { columns: [], okCount: 0, rejectedCount: 0, rejectedRows: [] };
let previewCalls: { config: Record<string, unknown>; rows: Record<string, unknown>[] }[] = [];

/** Host so the required signal `node` input binds naturally and outputs are captured. */
@Component({
    standalone: true,
    imports: [PipelineLoadDefinitionComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <app-pipeline-load-definition
            [node]="node"
            [schemaFile]="schemaFile"
            [sample]="sample"
            (applied)="applied = $event"
            (dirtyChange)="dirty = $event"
        />
    `,
})
class HostComponent {
    node: AuthoredNode = mapNode();
    schemaFile = 'spaces/default/config/cdr_schema.toon';
    sample: DefinitionStateService | null = null;
    applied?: AuthoredNode;
    dirty = false;
}

function mapNode(config: Record<string, unknown> = {}): AuthoredNode {
    return { id: 'map', type: 'transform.map', name: 'Map', config };
}

async function create(
    node: AuthoredNode = mapNode(),
    schemaFile = 'spaces/default/config/cdr_schema.toon',
    sample: DefinitionStateService | null = null,
) {
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
                    previewSchema: (config: Record<string, unknown>, rows: Record<string, unknown>[]) => {
                        previewCalls.push({ config, rows });
                        return previewAnswer instanceof HttpErrorResponse
                            ? throwError(() => previewAnswer)
                            : of(previewAnswer);
                    },
                },
            },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.componentInstance.schemaFile = schemaFile;
    fixture.componentInstance.sample = sample;
    fixture.detectChanges();
    return fixture;
}

/** A thread already carrying a parsed sample — the state the Parse drawer leaves behind. */
function threadWithParsedRows(rows: Record<string, unknown>[] = [{ A_NUMBER: '999', DURATION: '12' }]) {
    const state = new DefinitionStateService();
    state.captureSample('cdr.csv', '999,12');
    state.parsePreview.set({
        frontend: 'delimited',
        columns: ['A_NUMBER', 'DURATION'],
        rowCount: rows.length,
        rows,
        rejectedRows: 0,
    });
    return state;
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
     * ⛔ Not offering a type is a UI limit; it must never become data loss. All four types the compiler
     * recognises are authored since 2026-08-17, so the case this guards is now a hand-authored value
     * the pane has never heard of — it keeps it, and the select shows it alongside the offered four.
     */
    it('preserves a transform type it does not offer', async () => {
        const fixture = await create(
            mapNode({
                rules: [{ targetColumn: 'day', sourceExpression: 'FILENAME', transformType: 'LOOKUP' }],
            }),
        );
        const p = pane(fixture);
        expect(p.transformsFor(p.ruleRows.at(0))).toEqual(['DIRECT', 'EXPR', 'CONCAT_DT', 'FILENAME_DATE', 'LOOKUP']);

        p.submit();
        fixture.detectChanges();
        expect(
            (fixture.componentInstance.applied!.config!['rules'] as Record<string, unknown>[])[0]['transformType'],
        ).toBe('LOOKUP');
    });

    // A4: both specialised types pack their parameters into `sourceExpression` as `|`-delimited
    // positions, because a rule is exactly {targetColumn, sourceExpression, transformType} and
    // `MappingCsv` drops any other key. The pane composes that string; it never invents a field.
    describe('structured transform types (A4)', () => {
        it('composes CONCAT_DT from a date and a time column', async () => {
            const fixture = await create(
                mapNode({ rules: [{ targetColumn: 'TRADE_TS', sourceExpression: '', transformType: 'CONCAT_DT' }] }),
            );
            const p = pane(fixture);
            const row = p.ruleRows.at(0);

            // Incomplete is refused up front — the compiler reads parts[1] unconditionally.
            expect(p.ruleProblem(row)).toContain('date column');
            p.setSourcePart(row, 0, 'TRADE_DATE');
            expect(p.ruleProblem(row)).not.toBeNull();
            p.setSourcePart(row, 1, 'TRADE_TIME');
            expect(p.ruleProblem(row)).toBeNull();
            expect(p.sourcePart(row, 0)).toBe('TRADE_DATE');

            // The structured editor must actually RENDER — a behaviour-only assertion would pass
            // against a broken @if branch showing the EXPR free-text box instead.
            const labels = [...fixture.nativeElement.querySelectorAll('mat-label')].map((l) =>
                (l.textContent ?? '').trim(),
            );
            expect(labels).toContain('Date column');
            expect(labels).toContain('Time column');

            p.submit();
            fixture.detectChanges();
            const rules = fixture.componentInstance.applied!.config!['rules'] as Record<string, unknown>[];
            expect(rules[0]['sourceExpression']).toBe('TRADE_DATE|TRADE_TIME');
        });

        it('composes FILENAME_DATE and drops trailing blank positions', async () => {
            const fixture = await create(
                mapNode({
                    rules: [{ targetColumn: 'EVENT_DATE', sourceExpression: '', transformType: 'FILENAME_DATE' }],
                }),
            );
            const p = pane(fixture);
            const row = p.ruleRows.at(0);

            p.setSourcePart(row, 0, 'FILE_NAME');
            // ⚠ NOT `FILE_NAME||` — an empty third position would compile to TRY_STRPTIME(…, '').
            expect(row.getRawValue().sourceExpression).toBe('FILE_NAME');
            expect(p.ruleProblem(row)).toBeNull();

            p.setSourcePart(row, 2, '%Y%m%d');
            expect(row.getRawValue().sourceExpression).toBe('FILE_NAME||%Y%m%d');
            p.setSourcePart(row, 1, 'data_');
            expect(row.getRawValue().sourceExpression).toBe('FILE_NAME|data_|%Y%m%d');
            expect(p.sourcePart(row, 1)).toBe('data_');

            fixture.detectChanges();
            const labels = [...fixture.nativeElement.querySelectorAll('mat-label')].map((l) =>
                (l.textContent ?? '').trim(),
            );
            expect(labels).toEqual(expect.arrayContaining(['File name column', 'Prefix', 'Format']));
        });

        it('refuses a FILENAME_DATE rule that does not write EVENT_DATE', async () => {
            const fixture = await create(
                mapNode({
                    rules: [{ targetColumn: 'day', sourceExpression: 'FILE_NAME', transformType: 'FILENAME_DATE' }],
                }),
            );
            const p = pane(fixture);
            expect(p.ruleProblem(p.ruleRows.at(0))).toContain('EVENT_DATE');

            // Blocked before the round trip — the engine enforces this in three places and would 422.
            p.submit();
            fixture.detectChanges();
            expect(fixture.componentInstance.applied).toBeUndefined();
            expect(String(p.error())).toContain('EVENT_DATE');
        });
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

/**
 * B1 — the mapped-output half. `POST /config/preview/schema` returns `mappedColumns`/`mappedRows` when
 * the posted draft carries `mapping.rules`; this pane is its consumer, running the rules being EDITED
 * over the rows the tab's sample thread already parsed.
 */
describe('PipelineLoadDefinitionComponent — mapped output (B1)', () => {
    const mappedOk: SchemaPreview = {
        columns: ['A_NUMBER', 'DURATION'],
        okCount: 1,
        rejectedCount: 0,
        rejectedRows: [],
        mappedColumns: ['msisdn', 'secs'],
        mappedCount: 1,
        mappedRows: [{ msisdn: '999', secs: 12 }],
    };

    beforeEach(() => {
        schemaMissing = false;
        previewCalls = [];
        previewAnswer = mappedOk;
    });

    it('says what to capture first when the thread has no parsed rows', async () => {
        const fixture = await create(mapNode(), 'spaces/default/config/cdr_schema.toon', new DefinitionStateService());
        expect(pane(fixture).canTest()).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('Capture a sample on the parse step');
    });

    it('posts the FORM rules plus the schema raw over the parsed rows, and renders the mapped grid', async () => {
        const thread = threadWithParsedRows();
        const fixture = await create(mapNode(), 'spaces/default/config/cdr_schema.toon', thread);
        const p = pane(fixture);
        p.ruleRows.at(0).get('targetColumn')?.setValue('msisdn');
        p.testMapping();
        fixture.detectChanges();

        expect(previewCalls).toHaveLength(1);
        const posted = previewCalls[0];
        expect(posted.rows).toEqual([{ A_NUMBER: '999', DURATION: '12' }]);
        expect((posted.config['raw'] as Record<string, unknown>)['fields']).toHaveLength(2);
        const rules = (posted.config['mapping'] as { rules: Record<string, unknown>[] }).rules;
        // The edit is what gets tested — not the node's stored rules.
        expect(rules[0]['targetColumn']).toBe('msisdn');

        const text = fixture.nativeElement.textContent;
        expect(text).toContain('msisdn');
        expect(text).toContain('999');
        expect(text).toContain('1 rows mapped from 1 parsed');
    });

    /** The mapped hop IS the thread's cast hop — the Parse drawer's strip must show the same result. */
    it('mirrors the result into the thread', async () => {
        const thread = threadWithParsedRows();
        const fixture = await create(mapNode(), 'spaces/default/config/cdr_schema.toon', thread);
        pane(fixture).testMapping();
        expect(thread.schemaPreview()?.mappedCount).toBe(1);
        expect(thread.schemaError()).toBeNull();
    });

    it('clears the mapped grid before reporting a refusal — a stale grid must not outlive the rules', async () => {
        const thread = threadWithParsedRows();
        const fixture = await create(mapNode(), 'spaces/default/config/cdr_schema.toon', thread);
        pane(fixture).testMapping();
        previewAnswer = new HttpErrorResponse({ status: 422, error: { error: 'Binder Error: no such column' } });
        pane(fixture).testMapping();
        fixture.detectChanges();

        expect(thread.schemaPreview()).toBeNull();
        expect(pane(fixture).mapped()).toBeNull();
        expect(String(pane(fixture).mapError())).toContain('Binder Error');
        expect(fixture.nativeElement.textContent).toContain('Binder Error');
    });

    it('invalidates the mapped grid as soon as a rule is edited', async () => {
        const thread = threadWithParsedRows();
        const fixture = await create(mapNode(), 'spaces/default/config/cdr_schema.toon', thread);
        const p = pane(fixture);
        p.testMapping();
        expect(p.mapped()).not.toBeNull();

        p.ruleRows.at(0).get('sourceExpression')?.setValue('DURATION');
        fixture.detectChanges();
        expect(p.mapped()).toBeNull();
        expect(fixture.nativeElement.textContent).not.toContain('999');
    });

    /** ⚠ The result must survive its own arrival: writing it must not re-trigger the node effect. */
    it('keeps the result after the change-detection pass that renders it', async () => {
        const thread = threadWithParsedRows();
        const fixture = await create(mapNode(), 'spaces/default/config/cdr_schema.toon', thread);
        const p = pane(fixture);
        p.ruleRows.at(0).get('targetColumn')?.setValue('msisdn');
        p.testMapping();
        fixture.detectChanges();
        fixture.detectChanges();
        expect(p.mapped()?.mappedCount).toBe(1);
        // A re-run of the node effect would also have reseeded the grid over the edit.
        expect(p.ruleRows.at(0).get('targetColumn')?.value).toBe('msisdn');
    });

    it('still edits, and simply cannot test, when the host keeps no thread', async () => {
        const fixture = await create();
        const p = pane(fixture);
        expect(p.canTest()).toBe(false);
        p.testMapping();
        expect(previewCalls).toHaveLength(0);
        expect(p.ruleRows.length).toBe(2);
    });

    /**
     * Found by driving the editor: the pane emitted `dirtyChange` only from the node effect and from
     * `submit()` itself, so the drawer's Apply stayed greyed out through EVERY mapping edit — a builder
     * could author rules, press Apply, and watch the Map Step stay "Needs config" for ever. Driven
     * through the DOM on purpose: `setValue` does not mark a control dirty, only a user edit does, so a
     * programmatic spec would pass against the broken build.
     */
    it('arms the drawer Apply when a rule is edited', async () => {
        const fixture = await create();
        expect(fixture.componentInstance.dirty).toBe(false); // seeding alone must not arm it
        const cell = fixture.nativeElement.querySelectorAll('input[aria-label="Target column"]')[0] as HTMLInputElement;
        cell.value = 'MSISDN';
        cell.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        expect(fixture.componentInstance.dirty).toBe(true);
    });

    it('has no a11y violations with the mapped grid rendered', async () => {
        const fixture = await create(mapNode(), 'spaces/default/config/cdr_schema.toon', threadWithParsedRows());
        pane(fixture).testMapping();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
