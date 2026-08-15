import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthoredNode, ParsersService } from 'app/inspecto/api';
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
        <app-pipeline-parse-definition [node]="node" (applied)="applied = $event" (dirtyChange)="dirty = $event" />
    `,
})
class HostComponent {
    node: AuthoredNode = delimitedNode();
    applied?: AuthoredNode;
    dirty = false;
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

async function create(node: AuthoredNode = delimitedNode()) {
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

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
