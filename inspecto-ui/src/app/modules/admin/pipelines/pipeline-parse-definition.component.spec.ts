import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthoredNode, ComponentDef, ConfigService, ParsersService, SpacesService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { GrammarEditorComponent } from 'app/inspecto/grammar';
import { InspectoSegmentsEditorComponent } from 'app/inspecto/segments';
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
            [pipelineName]="pipelineName"
            [sample]="sample"
            (applied)="applied = $event"
            (dirtyChange)="dirty = $event"
            (saveAsTemplate)="template = $event"
        />
    `,
})
class HostComponent {
    node: AuthoredNode = delimitedNode();
    templates: ComponentDef[] = [];
    pipelineName = '';
    /** The tab's sample thread — null in most specs, exactly as a host that keeps none. */
    sample: DefinitionStateService | null = null;
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

/** The P3d twins: ordinary built-ins the shared editor already rendered — only the node type is new. */
function jsonNode(): AuthoredNode {
    return {
        id: 'parse',
        type: 'parser.json',
        name: 'Parser (JSON)',
        config: {
            schema_file: 'cdr_schema.toon',
            parsing: { frontend: 'json', json: { format: 'array', records_path: 'payload.records' } },
        },
    };
}

function textRegexNode(): AuthoredNode {
    return {
        id: 'parse',
        type: 'parser.text_regex',
        name: 'Parser (text/regex)',
        config: {
            schema_file: 'cdr_schema.toon',
            parsing: { frontend: 'text_regex', text_regex: { pattern: '(?<ID>\\w+) (?<TS>.+)' } },
        },
    };
}

/** The P3d slice D twin: the generic plugin node has no single served identity, so its config names
 *  the deployed FQCN directly rather than a fixed frontend id. */
function pluginNode(): AuthoredNode {
    return {
        id: 'parse',
        type: 'parser.plugin',
        name: 'Parser (plugin)',
        config: {
            schema_file: 'cdr_schema.toon',
            parsing: {
                frontend: 'plugin',
                plugin: {
                    ingester: 'com.example.acme.AcmeFeedIngester',
                    ingester_config: { mode: 'strict' },
                    segments: { Record: 'config/record_schema.toon' },
                },
            },
        },
    };
}

/** A served ingestable plugin — deliberately NOT `asn1` (which has its own dedicated node type and
 *  must never appear in this node's picker). */
const ACME_DEF = {
    id: 'acme_feed',
    label: 'Acme Feed — vendor binary format',
    hierarchical: true,
    ingestable: true,
    ingesterClass: 'com.example.acme.AcmeFeedIngester',
    grammarSchema: [
        { path: 'ingester_config.mode', label: 'Mode', type: 'STRING', description: 'Decode strictness.' },
    ],
};

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

/** Segment-schema writes the pane made, in order — the two-hop contract is what these assert. */
const schemaWrites: { type: string; config: Record<string, unknown> }[] = [];
let schemaWriteFails = false;
let savedSchemaMissing = false;

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
    sample: DefinitionStateService | null = null,
) {
    schemaWrites.length = 0;
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
            {
                provide: ConfigService,
                useValue: {
                    write: (type: string, config: Record<string, unknown>) => {
                        schemaWrites.push({ type, config });
                        return schemaWriteFails ? throwError(() => new Error('disk full')) : of({ written: true });
                    },
                    // The node's saved `asn1.segments` re-hydrate from the schema toons they point at —
                    // keys AND columns. Returning a real one exercises that path; a 404 would leave a
                    // keys-only draft, which validation correctly refuses (that is its own test).
                    // B3: the drift diff the 2a-iii indicator consumes.
                    suggestSchema: () =>
                        of({
                            fields: [],
                            mapping: { rules: [] },
                            drift: {
                                drifted: true,
                                added: [{ name: 'DURATION', type: 'VARCHAR' }],
                                missing: [],
                                typeChanged: [{ name: 'IMSI', declared: 'VARCHAR', suggested: 'BIGINT' }],
                            },
                        }),
                    read: () =>
                        savedSchemaMissing
                            ? throwError(() => ({ status: 404 }))
                            : of({
                                  config: {
                                      raw: {
                                          name: 'record',
                                          fields: [{ name: 'IMSI', selector: 'imsi', type: 'VARCHAR' }],
                                      },
                                  },
                              }),
                },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'default' } },
            // The sample strip (rendered only with a thread) reports an unreadable file through toastr.
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() } },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.componentInstance.templates = templates;
    fixture.componentInstance.sample = sample;
    fixture.detectChanges();
    return fixture;
}

function pane(fixture: ComponentFixture<HostComponent>): PipelineParseDefinitionComponent {
    return fixture.debugElement.query(By.directive(PipelineParseDefinitionComponent)).componentInstance;
}

function editor(fixture: ComponentFixture<HostComponent>): GrammarEditorComponent {
    return fixture.debugElement.query(By.directive(GrammarEditorComponent)).componentInstance;
}

function segmentsEditor(fixture: ComponentFixture<HostComponent>): InspectoSegmentsEditorComponent {
    return fixture.debugElement.query(By.directive(InspectoSegmentsEditorComponent)).componentInstance;
}

describe('PipelineParseDefinitionComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    /**
     * The per-tab sample thread. The pane does not own it — it renders the strip the host hands in and
     * mirrors Test parse into it, so the chips and every downstream step read the same result.
     */
    describe('sample thread', () => {
        it('renders no strip, and leaves the editor its own sample box, without a thread', async () => {
            const fixture = await create();
            expect(fixture.nativeElement.querySelector('inspecto-sample-panel')).toBeNull();
            expect(editor(fixture).sampleMode).toBe('own');
        });

        it('renders the strip and hands the editor the thread’s sample', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('cdr.csv', 'a|b\n1|2\n');
            const fixture = await create(delimitedNode(), [], [], 0, thread);

            expect(fixture.nativeElement.querySelector('inspecto-sample-panel')).not.toBeNull();
            expect(fixture.nativeElement.textContent).toContain('cdr.csv');
            expect(editor(fixture).sampleMode).toBe('host');
            expect(editor(fixture).sampleText()).toBe('a|b\n1|2\n');
        });

        it('mirrors a table Test parse into the thread', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('cdr.csv', 'a|b\n1|2\n');
            const fixture = await create(delimitedNode(), [], [], 0, thread);
            const parsers = TestBed.inject(ParsersService) as unknown as { preview: ReturnType<typeof vi.fn> };
            parsers.preview.mockReturnValue(
                of({ kind: 'table', columns: ['a', 'b'], rows: [{ a: '1' }], rowCount: 1, rejectedRows: 0 }),
            );
            editor(fixture).test();

            expect(thread.parsePreview()?.columns).toEqual(['a', 'b']);
            expect(thread.parseError()).toBeNull();
        });

        /**
         * ⚠ The failure path is the whole reason this is a `previewFn`: `previewed` fires on SUCCESS
         * only, so a stale "parsed" chip would otherwise stand over a grammar that no longer parses.
         */
        it('records a failed Test parse, clearing the stale parsed result', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('cdr.csv', 'a|b\n');
            const fixture = await create(delimitedNode(), [], [], 0, thread);
            thread.parsePreview.set({ frontend: 'delimited', columns: ['a'], rowCount: 1, rows: [], rejectedRows: 0 });
            const parsers = TestBed.inject(ParsersService) as unknown as { preview: ReturnType<typeof vi.fn> };
            parsers.preview.mockReturnValue(throwError(() => new Error('nope')));
            editor(fixture).test();

            expect(thread.parsePreview()).toBeNull();
            expect(thread.parseError()).toBeTruthy();
        });

        /** ⚠ A record tree is not "rows a downstream step can cast" — leave the thread alone. */
        it('leaves the thread untouched for a tree preview', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('cdr.ber', 'x');
            const fixture = await create(delimitedNode(), [], [], 0, thread);
            const parsers = TestBed.inject(ParsersService) as unknown as { preview: ReturnType<typeof vi.fn> };
            parsers.preview.mockReturnValue(of({ kind: 'tree', nodes: [] }));
            editor(fixture).test();

            expect(thread.parsePreview()).toBeNull();
            expect(thread.parseError()).toBeNull();
        });
    });

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

        /**
         * The two-hop contract: schema toons are written BEFORE the block that references them, so a
         * config never names a file that does not exist. Segments are authored here now (P3d re-scope)
         * rather than carried verbatim, and the emitted paths are the ones just written.
         */
        it('re-hydrates the saved segment’s COLUMNS, not just its key', async () => {
            const fixture = await create(asn1Node(), [], [ASN1_DEF]);
            expect(pane(fixture).initialSegments()).toEqual([
                { key: 'Record', columns: [{ name: 'IMSI', selector: 'imsi', type: 'VARCHAR' }] },
            ]);
        });

        it('Apply writes one schema toon per segment, THEN emits a block referencing them', async () => {
            const fixture = await create(asn1Node(), [], [ASN1_DEF]);
            fixture.componentInstance.pipelineName = 'asn1_cdr';
            fixture.detectChanges();
            editor(fixture).schemaForm!.form.patchValue({ asn1__root_type: 'CallEventRecord' });
            fixture.detectChanges();

            pane(fixture).submit();

            // hop 1: the schema toon, named by the Onboarding convention
            expect(schemaWrites).toHaveLength(1);
            expect(schemaWrites[0].type).toBe('schema');
            expect((schemaWrites[0].config['raw'] as Record<string, unknown>)['name']).toBe('asn1_cdr_Record');
            // hop 2: the block, pointing at what was just written
            const parsing = fixture.componentInstance.applied!.config!['parsing'] as Record<string, unknown>;
            expect(parsing['frontend']).toBe('asn1');
            const a = parsing['asn1'] as Record<string, unknown>;
            expect(a['root_type']).toBe('CallEventRecord');
            expect(a['grammar']).toContain('DEFINITIONS');
            expect(a['segments']).toEqual({ Record: 'asn1_cdr_Record.toon' }); // W3: portable, beside the config
        });

        /** A node pointing at schemas that failed to write is the state the ordering exists to prevent. */
        it('applies NOTHING when the segment-schema write fails', async () => {
            schemaWriteFails = true;
            try {
                const fixture = await create(asn1Node(), [], [ASN1_DEF]);
                pane(fixture).submit();

                expect(fixture.componentInstance.applied).toBeUndefined();
                expect(editor(fixture).error()).toContain('Could not save the segment schemas');
            } finally {
                schemaWriteFails = false;
            }
        });

        /**
         * A segment whose schema toon could not be read back is keys-only, and a segment with no
         * columns cannot describe a Table — so Apply refuses rather than writing an empty schema.
         */
        it('refuses to Apply a keys-only segment whose saved schema is missing', async () => {
            savedSchemaMissing = true;
            try {
                const fixture = await create(asn1Node(), [], [ASN1_DEF]);
                pane(fixture).submit();

                expect(schemaWrites).toHaveLength(0);
                expect(fixture.componentInstance.applied).toBeUndefined();
                expect(editor(fixture).error()).toContain('needs at least one column');
            } finally {
                savedSchemaMissing = false;
            }
        });

        /** Segments are only meaningful for a parser that can actually load — and only for asn1 here. */
        it('offers the segments editor for an ingestable ASN.1 node, never for a built-in format', async () => {
            const fixture = await create(asn1Node(), [], [ASN1_DEF]);
            expect(pane(fixture).authorsSegments()).toBe(true);
            expect(fixture.nativeElement.querySelector('inspecto-segments-editor')).not.toBeNull();
        });

        it('does not offer segments on a delimited node', async () => {
            const fixture = await create(delimitedNode(), [], [ASN1_DEF]);
            expect(pane(fixture).authorsSegments()).toBe(false);
            expect(fixture.nativeElement.querySelector('inspecto-segments-editor')).toBeNull();
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

    /**
     * The generic custom-plugin subtype (P3d slice D). Unlike every other subtype this node's format is
     * NOT one served identity — the pane offers its own picker over whichever ingestable plugins the
     * catalog serves, excluding ones already homed by their own dedicated entry (asn1).
     * ⚠ One `create()` per test.
     */
    describe('the generic custom-plugin subtype (parser.plugin)', () => {
        it('offers only ingestable plugins, excluding ones with their own dedicated type', async () => {
            const fixture = await create(pluginNode(), [], [ACME_DEF, ASN1_DEF]);
            const p = pane(fixture);
            expect(p.pluginChoices().map((x) => x.id)).toEqual(['acme_feed']);
        });

        it('rehydrates the saved ingester on load without marking the pane dirty', async () => {
            const fixture = await create(pluginNode(), [], [ACME_DEF], 5);
            await new Promise((r) => setTimeout(r, 10));
            fixture.detectChanges();

            expect(pane(fixture).plugin()?.id).toBe('acme_feed');
            expect(fixture.componentInstance.dirty).toBe(false);
        });

        it('offers the segments editor for an ingestable plugin, and authors them on Apply', async () => {
            const fixture = await create(pluginNode(), [], [ACME_DEF]);
            expect(pane(fixture).authorsSegments()).toBe(true);
        });

        /**
         * This node is ingestable (authorsSegments() is true), so submit() takes the segment-write
         * path — same two-hop contract as ASN.1: schemas are written first, then the block naming them
         * is emitted, only after which the ingester + ingester_config from the schema form land in it.
         */
        it('Apply writes segment schemas then assembles ingester + ingester_config from the served schema', async () => {
            const fixture = await create(pluginNode(), [], [ACME_DEF]);
            editor(fixture).schemaForm!.form.patchValue({ ingester_config__mode: 'lenient' });
            fixture.detectChanges();
            const segments = segmentsEditor(fixture);
            vi.spyOn(segments, 'validate').mockReturnValue(true);

            pane(fixture).submit();

            const applied = fixture.componentInstance.applied!;
            const parsing = applied.config!['parsing'] as Record<string, unknown>;
            expect(parsing['frontend']).toBe('plugin');
            const plugin = parsing['plugin'] as Record<string, unknown>;
            expect(plugin['ingester']).toBe('com.example.acme.AcmeFeedIngester');
            expect(plugin['ingester_config']).toEqual({ mode: 'lenient' });
            expect(plugin['segments']).toBeDefined();
        });

        /**
         * ⚠ This node has no single served identity, so "unavailable" covers nothing-picked-yet too —
         * not just a jar that failed to deploy. Applying must refuse rather than write a hollow block.
         */
        it('refuses to Apply when no plugin has been picked or rehydrated', async () => {
            const fixture = await create(pluginNode(), [], []);

            pane(fixture).submit();

            expect(fixture.componentInstance.applied).toBeUndefined();
            expect(editor(fixture).error()).toContain('No ingestable parser plugin');
        });

        it('Save as template strips segments but keeps the ingester class', async () => {
            const fixture = await create(pluginNode(), [], [ACME_DEF]);
            pane(fixture).requestSaveAsTemplate();
            fixture.detectChanges();

            const template = fixture.componentInstance.template!;
            expect(template['frontend']).toBe('plugin');
            const p = template['plugin'] as Record<string, unknown>;
            expect(p['ingester']).toBe('com.example.acme.AcmeFeedIngester');
            expect(p['segments']).toBeUndefined();
        });
    });

    /**
     * The JSON and text/regex subtypes (P3d). Nothing about the pane changed for them — the point of
     * these tests is exactly that: an entry in `PARSE_NODE_FRONTENDS` plus the node type behind it is
     * the whole slice, and the seed / lock / Apply path is the shared one.
     * ⚠ One `create()` per test — `TestBed.configureTestingModule` throws once instantiated.
     */
    describe('the JSON and text/regex subtypes', () => {
        it('locks the editor to json and seeds the stored block', async () => {
            const fixture = await create(jsonNode());
            expect(pane(fixture).frontend()).toBe('json');
            expect(editor(fixture).value()['json']).toEqual({ format: 'array', records_path: 'payload.records' });
        });

        it('locks the editor to text_regex and seeds the stored pattern', async () => {
            const fixture = await create(textRegexNode());
            expect(pane(fixture).frontend()).toBe('text_regex');
            expect(editor(fixture).value()['text_regex']).toEqual({ pattern: '(?<ID>\\w+) (?<TS>.+)' });
        });

        it('Apply stamps the frontend and keeps the rest of the node config', async () => {
            const fixture = await create(jsonNode());
            editor(fixture).schemaForm!.form.patchValue({ json__format: 'auto' });
            fixture.detectChanges();

            pane(fixture).submit();

            const parsing = fixture.componentInstance.applied!.config!['parsing'] as Record<string, unknown>;
            expect(parsing['frontend']).toBe('json');
            expect((parsing['json'] as Record<string, unknown>)['format']).toBe('auto');
            expect(fixture.componentInstance.applied!.config!['schema_file']).toBe('cdr_schema.toon');
        });

        /** Segments are the ASN.1 load path; a built-in frontend has none to author. */
        it('offers no segments editor on a text/regex node', async () => {
            const fixture = await create(textRegexNode(), [], [ASN1_DEF]);
            expect(pane(fixture).authorsSegments()).toBe(false);
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

    /**
     * P4-2a-ii — the flat formats author their output schema here, because `schema_file` is the PARSER
     * node's key (not `transform.map`'s), which is what §4b's icon table meant by "+ output schema".
     */
    describe('output schema', () => {
        /** A delimited node with NO schema yet — the fresh-drop case. */
        function unschemadNode(): AuthoredNode {
            const n = delimitedNode();
            delete (n.config as Record<string, unknown>)['schema_file'];
            return n;
        }

        const TABLE_PREVIEW = {
            kind: 'table' as const,
            columns: ['a number', 'DURATION'],
            rows: [{ 'a number': '55', DURATION: '30' }],
            rowCount: 1,
            rejectedRows: 0,
        };

        it('derives fields from a flat preview, writes the toon, THEN names it on the node', async () => {
            const fixture = await create(unschemadNode());
            pane(fixture).onPreviewed(TABLE_PREVIEW);
            fixture.detectChanges();

            // Derived, not hand-typed: the column name is sanitised into an identifier.
            expect(pane(fixture).schemaSeed().map((r) => r.name)).toEqual(['A_NUMBER', 'DURATION']);
            // Delimited addresses parsed columns by POSITION, so selectors are indices.
            expect(pane(fixture).schemaSeed().map((r) => r.selector)).toEqual(['0', '1']);

            pane(fixture).submit();
            fixture.detectChanges();

            // hop 1: the schema toon
            expect(schemaWrites).toHaveLength(1);
            expect(schemaWrites[0].type).toBe('schema');
            expect((schemaWrites[0].config['raw'] as Record<string, unknown>)['name']).toBe('parse_schema');
            // hop 2: the node naming what was just written
            // W3: the PORTABLE bare ref — resolves config-relative first, so the space tree moves.
            expect(fixture.componentInstance.applied!.config!['schema_file']).toBe('parse_schema.toon');
        });

        it('applies nothing when the schema write fails, so no node names a missing file', async () => {
            schemaWriteFails = true;
            try {
                const fixture = await create(unschemadNode());
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();
                pane(fixture).submit();
                fixture.detectChanges();
                expect(schemaWrites).toHaveLength(1);
                expect(fixture.componentInstance.applied).toBeUndefined();
            } finally {
                schemaWriteFails = false;
            }
        });

        /** ⛔ The clobber guard: a saved schema is the truth, and a fresh parse must not replace it. */
        it('does not re-derive over a schema read back from disk', async () => {
            const n = unschemadNode();
            (n.config as Record<string, unknown>)['schema_file'] = 'spaces/default/config/parse_schema.toon';
            const fixture = await create(n);
            fixture.detectChanges();
            // The harness's read() returns a one-field saved schema.
            expect(pane(fixture).schemaSeed().map((r) => r.name)).toEqual(['IMSI']);

            pane(fixture).onPreviewed(TABLE_PREVIEW);
            fixture.detectChanges();
            expect(pane(fixture).schemaSeed().map((r) => r.name)).toEqual(['IMSI']);
        });

        /**
         * 🔴 W3 compat guard. This pane now WRITES the portable bare `<name>.toon`, but every pipeline
         * saved before that carries `spaces/<space>/config/<name>.toon` and is just as much ours.
         * `foreignSchema` therefore compares by NAME — a path comparison would have declared every
         * pre-W3 pipeline's own schema hand-authored and quietly stopped maintaining it.
         */
        it('still owns a pre-W3 schema_file written as a full space path', async () => {
            const n = unschemadNode();
            (n.config as Record<string, unknown>)['schema_file'] = 'spaces/default/config/parse_schema.toon';
            const fixture = await create(n);
            fixture.detectChanges();
            expect(pane(fixture).foreignSchema()).toBe(false);
        });

        it('leaves a hand-authored schema_file alone — no editor, no write', async () => {
            const fixture = await create(delimitedNode()); // schema_file: 'cdr_schema.toon' (foreign)
            expect(pane(fixture).foreignSchema()).toBe(true);
            expect(pane(fixture).authorsSchema()).toBe(false);

            pane(fixture).onPreviewed(TABLE_PREVIEW);
            fixture.detectChanges();
            pane(fixture).submit();
            fixture.detectChanges();

            expect(schemaWrites).toHaveLength(0);
            expect(fixture.componentInstance.applied!.config!['schema_file']).toBe('cdr_schema.toon');
        });

        /**
         * 2a-iii: a hydrated schema does not re-derive — it asks what changed (B3). The indicator is the
         * consumer of the drift diff, and "add new" is the only half of a re-sync that cannot clobber.
         */
        describe('drift', () => {
            function schemadNode(): AuthoredNode {
                const n = unschemadNode();
                (n.config as Record<string, unknown>)['schema_file'] = 'spaces/default/config/parse_schema.toon';
                return n;
            }

            it('asks for drift instead of re-deriving, and reports what changed', async () => {
                const fixture = await create(schemadNode());
                fixture.detectChanges();
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();

                // The harness's suggestSchema returns a drift block naming one added column.
                expect(pane(fixture).schemaDrift()?.drifted).toBe(true);
                expect(fixture.nativeElement.textContent).toContain('no longer matches the saved schema');
                // The saved schema is untouched by merely observing drift.
                expect(pane(fixture).schemaSeed().map((r) => r.name)).toEqual(['IMSI']);
            });

            it('adds the new columns on request, keeping every existing row including excluded ones', async () => {
                const fixture = await create(schemadNode());
                fixture.detectChanges();
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();

                pane(fixture).addDriftedFields();
                fixture.detectChanges();

                expect(pane(fixture).schemaSeed().map((r) => r.name)).toEqual(['IMSI', 'DURATION']);
                expect(pane(fixture).schemaDrift()).toBeNull();
            });
        });

        it('applies straight through when no parse has run — a parser may be defined before its schema', async () => {
            const fixture = await create(unschemadNode());
            pane(fixture).submit();
            fixture.detectChanges();

            expect(schemaWrites).toHaveLength(0);
            expect(fixture.componentInstance.applied).toBeDefined();
            expect(fixture.componentInstance.applied!.config!['schema_file']).toBeUndefined();
        });
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
