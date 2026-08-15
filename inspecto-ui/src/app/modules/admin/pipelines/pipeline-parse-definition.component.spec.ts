import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthoredNode, ComponentDef, ParsersService } from 'app/inspecto/api';
import { GrammarEditorComponent } from 'app/inspecto/grammar';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineParseDefinitionComponent } from './pipeline-parse-definition.component';

/**
 * Host so the required signal `node` input binds naturally and outputs are captured — these specs pin
 * the P3a Parse definition pane: the delimited path of `grammar-editor.dialog`, re-hosted in the
 * definition drawer over the SAME shared Grammar editor, pure (Apply emits, host persists).
 */
@Component({
    standalone: true,
    imports: [PipelineParseDefinitionComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <app-pipeline-parse-definition
            [node]="node"
            [templates]="templates"
            (applied)="applied = $event"
            (dirtyChange)="dirty = $event"
            (saveAsTemplate)="template = $event"
        />
    `,
})
class HostComponent {
    node: AuthoredNode = delimitedNode();
    templates: ComponentDef[] = [];
    applied?: AuthoredNode;
    dirty = false;
    template?: Record<string, unknown>;
}

function delimitedNode(): AuthoredNode {
    return {
        id: 'parse',
        type: 'parser.delimited',
        name: 'Parser (delimited)',
        config: {
            schema_file: 'cdr_schema.toon',
            parsing: { frontend: 'delimited', delimited: { delimiter: '|', has_header: false } },
        },
    };
}

async function create(node: AuthoredNode = delimitedNode(), templates: ComponentDef[] = []) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ParsersService,
                useValue: { list: () => of([]), preview: vi.fn(() => of({ kind: 'table', rows: [] })) },
            },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.componentInstance.templates = templates;
    fixture.detectChanges();
    return fixture;
}

function pane(fixture: ComponentFixture<HostComponent>): PipelineParseDefinitionComponent {
    return fixture.debugElement.query(By.directive(PipelineParseDefinitionComponent)).componentInstance;
}

function editor(fixture: ComponentFixture<HostComponent>): GrammarEditorComponent {
    return fixture.debugElement.query(By.directive(GrammarEditorComponent)).componentInstance;
}

describe('PipelineParseDefinitionComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('renders the shared Grammar editor with the format picker locked', async () => {
        const fixture = await create();
        const ed = editor(fixture);
        expect(ed).not.toBeNull();
        expect(ed.lockType).toBe(true);
        // Locked = the format toggle is not offered; the node's TYPE is the format (B6).
        const toggle = fixture.nativeElement.querySelector('mat-button-toggle-group[aria-label="File format"]');
        expect(toggle?.closest('.hidden')).not.toBeNull();
    });

    it('seeds the editor from the node’s inline parsing: block', async () => {
        const fixture = await create();
        expect(editor(fixture).value()['delimited']).toEqual({ delimiter: '|', has_header: false });
    });

    it('Apply rebuilds the node with the edited Grammar in parsing:, frontend stamped', async () => {
        const fixture = await create();
        editor(fixture).schemaForm!.form.patchValue({ delimited__delimiter: ';' });
        fixture.detectChanges();

        pane(fixture).submit();

        const applied = fixture.componentInstance.applied!;
        expect(applied).toBeDefined();
        const parsing = applied.config!['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('delimited');
        expect((parsing['delimited'] as Record<string, unknown>)['delimiter']).toBe(';');
        // The rest of the node's config is not the Grammar's to touch.
        expect(applied.config!['schema_file']).toBe('cdr_schema.toon');
    });

    /**
     * The palette seeds a new node as `{id, type}` with no config at all — so the pane must host a
     * node with no `parsing:` block and still Apply a block the save path accepts. A delimited
     * Grammar is complete without a schema (`has_header` reads the header row), which is why
     * PARSER_NO_SCHEMA is satisfied by the block's mere presence.
     */
    it('hosts a palette-fresh node with no config and Applies a complete delimited block', async () => {
        const fixture = await create({ id: 'parser_delimited_1', type: 'parser.delimited' });
        expect(editor(fixture).value()['delimited']).toEqual({ delimiter: ',', has_header: true });

        pane(fixture).submit();

        const applied = fixture.componentInstance.applied!;
        const parsing = applied.config!['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('delimited');
        expect(parsing['delimited']).toEqual({ delimiter: ',', has_header: true });
    });

    it('reports dirty on an edit, and pristine again after Apply', async () => {
        const fixture = await create();
        editor(fixture).schemaForm!.form.patchValue({ delimited__delimiter: ';' });
        editor(fixture).schemaForm!.form.markAsDirty();
        fixture.debugElement
            .query(By.directive(PipelineParseDefinitionComponent))
            .nativeElement.dispatchEvent(new Event('input', { bubbles: true }));
        fixture.detectChanges();
        expect(fixture.componentInstance.dirty).toBe(true);

        pane(fixture).submit();
        fixture.detectChanges();
        expect(fixture.componentInstance.dirty).toBe(false);
    });

    /**
     * S1: the pane EMITS the block and never writes it — a `grammar` registry component is a third
     * entity, so the host owns that write (P2 pure-pane rule).
     */
    it('Save as template emits the block, leaves the node alone, and does not consume edits', async () => {
        const fixture = await create();
        editor(fixture).schemaForm!.form.patchValue({ delimited__delimiter: ';' });
        editor(fixture).schemaForm!.form.markAsDirty();
        fixture.debugElement
            .query(By.directive(PipelineParseDefinitionComponent))
            .nativeElement.dispatchEvent(new Event('input', { bubbles: true }));
        fixture.detectChanges();
        expect(fixture.componentInstance.dirty).toBe(true);

        pane(fixture).requestSaveAsTemplate();
        fixture.detectChanges();

        expect(fixture.componentInstance.template).toEqual(
            expect.objectContaining({ frontend: 'delimited', delimited: expect.objectContaining({ delimiter: ';' }) }),
        );
        // Saving a template neither persists to the node nor consumes the unapplied edits.
        expect(fixture.componentInstance.applied).toBeUndefined();
        expect(fixture.componentInstance.dirty).toBe(true);
    });

    describe('start from a template (S2)', () => {
        const TEMPLATES = [
            { name: 'pipe_delimited', ref: 'grammar/pipe_delimited', type: 'grammar', content: { delimiter: '|', has_header: false } },
            { name: 'nested_tsv', ref: 'grammar/nested_tsv', type: 'grammar', content: { frontend: 'delimited', delimited: { delimiter: '\t' } } },
            { name: 'invoice_xml', ref: 'grammar/invoice_xml', type: 'grammar', content: { parser_type: 'xml', record_xpath: '//x' } },
            { name: 'mainframe_fixed', ref: 'grammar/mainframe_fixed', type: 'grammar', content: { frontend: 'fixedwidth' } },
        ] as unknown as ComponentDef[];

        /** A component naming another frontend could only author a PARSER_FRONTEND_MISMATCH block. */
        it('offers only delimited-compatible templates', async () => {
            const fixture = await create(delimitedNode(), TEMPLATES);
            expect(pane(fixture).delimitedTemplates().map((t) => t.name)).toEqual(['pipe_delimited', 'nested_tsv']);
        });

        /**
         * ⚠ The regression this slice exists to prevent: a LEGACY FLAT component's keys sit at top
         * level, match no `delimited__*` spec key, and seed the form's DEFAULTS — so picking
         * `pipe_delimited` used to silently yield `delimiter: ','`. Probed before the fix.
         */
        it('copies a legacy flat template without losing its stored settings', async () => {
            const fixture = await create(delimitedNode(), TEMPLATES);
            pane(fixture).applyTemplate('pipe_delimited');
            fixture.detectChanges();

            expect(editor(fixture).value()['delimited']).toEqual({ delimiter: '|', has_header: false });
        });

        it('copies an already-nested template unchanged', async () => {
            const fixture = await create(delimitedNode(), TEMPLATES);
            pane(fixture).applyTemplate('nested_tsv');
            fixture.detectChanges();

            expect((editor(fixture).value()['delimited'] as Record<string, unknown>)['delimiter']).toBe('\t');
        });

        /**
         * Re-seeding `[initial]` marks the editor PRISTINE, so the pick must be tracked by the pane —
         * otherwise a real change leaves Apply disabled.
         */
        it('reports dirty after a pick, and Applies the copy inline with no binding', async () => {
            const fixture = await create(delimitedNode(), TEMPLATES);
            pane(fixture).applyTemplate('pipe_delimited');
            fixture.detectChanges();
            expect(fixture.componentInstance.dirty).toBe(true);

            pane(fixture).submit();
            fixture.detectChanges();

            const applied = fixture.componentInstance.applied!;
            expect(applied.use).toBeUndefined(); // a copy, never a binding
            expect((applied.config!['parsing'] as Record<string, unknown>)['delimited']).toEqual({
                delimiter: '|',
                has_header: false,
            });
            expect(fixture.componentInstance.dirty).toBe(false);
        });

        it('hides the picker when no delimited template exists', async () => {
            const fixture = await create(delimitedNode(), [TEMPLATES[2]]);
            expect(fixture.nativeElement.querySelector('mat-select')).toBeNull();
        });
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
