import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { delay } from 'rxjs/operators';
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

/** The P3b twin: same pane, different node type — the format is read off the type, not passed in. */
function fixedWidthNode(): AuthoredNode {
    return {
        id: 'parse',
        type: 'parser.fixedwidth',
        name: 'Parser (fixed width)',
        config: {
            schema_file: 'cdr_schema.toon',
            parsing: {
                frontend: 'fixedwidth',
                fixedwidth: { fields: [{ name: 'ID', start: 0, length: 6 }] },
            },
        },
    };
}

/** The P3c twin: asn1 is a SERVED parser (schema off GET /parsers), locked by the type all the same. */
function asn1Node(): AuthoredNode {
    return {
        id: 'parse',
        type: 'parser.asn1',
        name: 'Parser (ASN.1)',
        config: {
            parsing: {
                frontend: 'asn1',
                asn1: {
                    grammar: 'CDR DEFINITIONS ::= BEGIN Record ::= SEQUENCE { id [0] IA5String } END',
                    root_type: 'Record',
                    strictness: 'BER',
                    segments: { Record: 'config/record_schema.toon' },
                },
            },
        },
    };
}

/** The served definition the editor renders asn1 from — the shape parsers.handler.ts transcribes. */
const ASN1_DEF = {
    id: 'asn1',
    label: 'ASN.1 — BER/DER encoded records',
    hierarchical: true,
    ingestable: true,
    ingesterClass: 'com.gamma.ingester.Asn1RecordIngester',
    grammarSchema: [
        { path: 'asn1.grammar', label: 'ASN.1 grammar', type: 'STRING', description: 'X.680 module text.' },
        { path: 'asn1.root_type', label: 'Root type', type: 'STRING', description: 'Record binding type.' },
        {
            path: 'asn1.strictness',
            label: 'Strictness',
            type: 'ENUM',
            enumValues: ['BER', 'DER', 'CER'],
            defaultValue: 'BER',
            description: 'Encoding rules.',
        },
    ],
};

/**
 * `servedDelayMs` reproduces PRODUCTION ordering: `GET /parsers` is an HTTP hop, so the catalog lands
 * after the inputs are bound and after the editor is seeded. A synchronous `of()` hides a whole class
 * of ordering bug (it did — see the async spec below), so the delay is a fixture, not a nicety.
 */
async function create(
    node: AuthoredNode = delimitedNode(),
    templates: ComponentDef[] = [],
    served: unknown[] = [],
    servedDelayMs = 0,
) {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ParsersService,
                useValue: {
                    list: () => (servedDelayMs ? of(served).pipe(delay(servedDelayMs)) : of(served)),
                    preview: vi.fn(() => of({ kind: 'table', rows: [] })),
                },
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

    /**
     * P3c: asn1 is not a built-in `ParsingFrontend` — the editor hosts it as the served parser it is —
     * so the pane assembles the block itself (`parsingValue`): the editor's `value()` would stamp its
     * INTERNAL frontend (delimited), and the schema-form does not carry `segments` at all.
     */
    describe('the ASN.1 subtype (parser.asn1)', () => {
        it('locks the editor to the served asn1 type', async () => {
            const fixture = await create(asn1Node(), [], [ASN1_DEF]);
            expect(pane(fixture).frontend()).toBe('asn1');
            expect(editor(fixture).pluginDef()?.id).toBe('asn1');
            expect(editor(fixture).lockType).toBe(true);
        });

        it('Apply stamps frontend: asn1 and carries the node’s segments VERBATIM', async () => {
            const fixture = await create(asn1Node(), [], [ASN1_DEF]);
            editor(fixture).schemaForm!.form.patchValue({ asn1__root_type: 'CallEventRecord' });
            fixture.detectChanges();

            pane(fixture).submit();

            const applied = fixture.componentInstance.applied!;
            const parsing = applied.config!['parsing'] as Record<string, unknown>;
            expect(parsing['frontend']).toBe('asn1');
            const a = parsing['asn1'] as Record<string, unknown>;
            expect(a['root_type']).toBe('CallEventRecord');
            expect(a['grammar']).toContain('DEFINITIONS');
            // The drawer does not author segments (Onboarding owns that transaction) — dropping them
            // on Apply would silently turn an ingest-capable config preview-only.
            expect(a['segments']).toEqual({ Record: 'config/record_schema.toon' });
        });

        /**
         * 🔴 The regression the offline preview caught, which every synchronous spec missed: in the
         * real app `GET /parsers` resolves AFTER the editor is seeded, so the schema form's specs are
         * swapped from the built-in set to asn1's — and that rebuild discarded the seeded values,
         * leaving an empty grammar that Apply would then write back over a deployed config. Delayed
         * here to reproduce the production ordering.
         */
        it('keeps the stored grammar when the served catalog arrives AFTER the seed', async () => {
            const fixture = await create(asn1Node(), [], [ASN1_DEF], 10);
            await new Promise((r) => setTimeout(r, 30));
            fixture.detectChanges();

            const values = editor(fixture).schemaForm!.form.getRawValue() as Record<string, unknown>;
            expect(String(values['asn1__grammar'] ?? '')).toContain('DEFINITIONS');
            expect(values['asn1__root_type']).toBe('Record');
        });

        /**
         * ⚠ The asn1 form is SERVED. With no catalog its fields do not exist, so building the block
         * from the form would write an EMPTY grammar over a deployed one and report success.
         */
        it('refuses to Apply when the served asn1 parser is absent, rather than emptying the block', async () => {
            const fixture = await create(asn1Node(), [], []);

            pane(fixture).submit();

            expect(fixture.componentInstance.applied).toBeUndefined();
            expect(editor(fixture).error()).toContain('not available');
        });

        it('Save as template strips segments — a template is grammar, not a deployment', async () => {
            const fixture = await create(asn1Node(), [], [ASN1_DEF]);
            pane(fixture).requestSaveAsTemplate();
            fixture.detectChanges();

            const template = fixture.componentInstance.template!;
            expect(template['frontend']).toBe('asn1');
            const a = template['asn1'] as Record<string, unknown>;
            expect(a['grammar']).toContain('DEFINITIONS');
            expect(a['segments']).toBeUndefined();
        });
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
            expect(pane(fixture).seedableTemplates().map((t) => t.name)).toEqual(['pipe_delimited', 'nested_tsv']);
        });

        /**
         * The mirror image (P3b): the same pane on a fixed-width node offers the DISJOINT set. Note
         * `pipe_delimited` — an undeclared legacy flat component — is offered to delimited but NOT
         * here: undeclared means the engine's implicit default, and seeding a slice table from a
         * `{delimiter: '|'}` map would invent a Grammar the operator never wrote.
         */
        it('offers only fixed-width-compatible templates on a fixed-width node', async () => {
            const fixture = await create(fixedWidthNode(), TEMPLATES);
            expect(pane(fixture).seedableTemplates().map((t) => t.name)).toEqual(['mainframe_fixed']);
        });

        /** The editor is locked to the format the node's TYPE means — never a picker.
         *  ⚠ One `create()` per test: `TestBed.configureTestingModule` throws once instantiated. */
        it('derives the locked frontend from a delimited node type', async () => {
            expect(pane(await create(delimitedNode(), TEMPLATES)).frontend()).toBe('delimited');
        });

        it('derives the locked frontend from a fixed-width node type', async () => {
            expect(pane(await create(fixedWidthNode(), TEMPLATES)).frontend()).toBe('fixedwidth');
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
