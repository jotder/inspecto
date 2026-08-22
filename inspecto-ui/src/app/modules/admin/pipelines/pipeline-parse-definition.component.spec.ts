import { Component, ChangeDetectionStrategy } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { delay } from 'rxjs/operators';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthoredNode, ConfigService, ParsersService, SpacesService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';
import { GrammarEditorComponent, grammarToCsv, parsingAttributesFor } from 'app/inspecto/grammar';
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
            [pipelineName]="pipelineName"
            [sample]="sample"
            [filenameColumnTarget]="filenameColumnTarget"
            (filenameColumnChange)="filenameColumnChange = $event"
            (applied)="applied = $event"
            (dirtyChange)="dirty = $event"
        />
    `,
})
class HostComponent {
    node: AuthoredNode = delimitedNode();
    pipelineName = '';
    /** The tab's sample thread — null in most specs, exactly as a host that keeps none. */
    sample: DefinitionStateService | null = null;
    /** null = the host's cross-node lineage field is not offered (ambiguous/no sink) — most specs. */
    filenameColumnTarget: { value: string; target: string } | null = null;
    filenameColumnChange?: string | null;
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
    grammarSchema: [{ path: 'ingester_config.mode', label: 'Mode', type: 'STRING', description: 'Decode strictness.' }],
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
const schemaWrites: { type: string; config: Record<string, unknown>; opts?: Record<string, unknown> }[] = [];
let schemaWriteFails = false;
/** Arms the schema BACKWARD save-gate's 422 (BUILDER-1b), which the pane must offer to override. */
let schemaBackwardRefusal = false;
let savedSchemaMissing = false;
/** What the saved schema toon carries beside `raw` — the partitions round-trip fixtures. */
let savedPartitions: Record<string, unknown>[] | null = null;
let savedLegacyPartitionKey: string | null = null;
/** Foreign top-level keys the stored schema toon carries — the unmodeled-key round-trip fixture. */
let savedForeignKeys: Record<string, unknown> | null = null;
/** S4/D7: what the destructive re-derive confirm answers. */
let confirmAnswer = true;

/**
 * `servedDelayMs` reproduces PRODUCTION ordering: `GET /parsers` is an HTTP hop, so the catalog lands
 * after the inputs are bound and after the editor is seeded. A synchronous `of()` hides a whole class
 * of ordering bug (it did — see the async spec below), so the delay is a fixture, not a nicety.
 */
async function create(
    node: AuthoredNode = delimitedNode(),
    served: unknown[] = [],
    servedDelayMs = 0,
    sample: DefinitionStateService | null = null,
    // ⚠ Must be set BEFORE the first detectChanges to matter for loadSavedSchema — the seed effect
    // tracks only the node input, so assigning it afterwards affects rendering, never the load.
    pipelineName = '',
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
                    write: (type: string, config: Record<string, unknown>, opts?: Record<string, unknown>) => {
                        schemaWrites.push({ type, config, opts });
                        if (schemaBackwardRefusal && opts?.['compatibility'] !== 'none') {
                            return throwError(() => ({
                                status: 422,
                                error: { error: { message: 'schema edit is not BACKWARD-compatible; not written' } },
                            }));
                        }
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
                                      ...(savedPartitions ? { partitions: savedPartitions } : {}),
                                      ...(savedLegacyPartitionKey ? { partitionKey: savedLegacyPartitionKey } : {}),
                                      ...(savedForeignKeys ?? {}),
                                  },
                              }),
                },
            },
            { provide: SpacesService, useValue: { currentSpaceId: () => 'default' } },
            {
                provide: InspectoConfirmService,
                useValue: {
                    confirm: () => Promise.resolve(confirmAnswer),
                    confirmDestructive: () => Promise.resolve(confirmAnswer),
                },
            },
            // The sample strip (rendered only with a thread) reports an unreadable file through toastr.
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() } },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.node = node;
    fixture.componentInstance.sample = sample;
    fixture.componentInstance.pipelineName = pipelineName;
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
    beforeEach(() => {
        localStorage.removeItem('inspecto.currentLens');
        confirmAnswer = true;
    });

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
            const fixture = await create(delimitedNode(), [], 0, thread);

            expect(fixture.nativeElement.querySelector('inspecto-sample-panel')).not.toBeNull();
            expect(fixture.nativeElement.textContent).toContain('cdr.csv');
            expect(editor(fixture).sampleMode).toBe('host');
            expect(editor(fixture).sampleText()).toBe('a|b\n1|2\n');
        });

        it('mirrors a table Test parse into the thread', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('cdr.csv', 'a|b\n1|2\n');
            const fixture = await create(delimitedNode(), [], 0, thread);
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
            const fixture = await create(delimitedNode(), [], 0, thread);
            thread.parsePreview.set({ frontend: 'delimited', columns: ['a'], rowCount: 1, rows: [], rejectedRows: 0 });
            const parsers = TestBed.inject(ParsersService) as unknown as { preview: ReturnType<typeof vi.fn> };
            parsers.preview.mockReturnValue(throwError(() => new Error('nope')));
            editor(fixture).test();

            expect(thread.parsePreview()).toBeNull();
            expect(thread.parseError()).toBeTruthy();
        });

        /**
         * The grid keeps the columns of the last parse that WORKED, so after a failed re-test it
         * describes a grammar the pane no longer holds — and Apply stays available (blocking it is the
         * dead end BUILDER-1a closed). Found by driving the Regex drawer: a pattern edited to one that
         * matches nothing still offered five columns from the pattern before it, silently.
         */
        it('marks the derived columns stale when a re-test fails, and clears it on the next success', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('cdr.csv', 'a|b\n1|2\n');
            const fixture = await create(delimitedNode(), [], 0, thread);
            fixture.componentInstance.pipelineName = 'cdr'; // owns `cdr_schema.toon`, so the block renders
            fixture.detectChanges();
            const parsers = TestBed.inject(ParsersService) as unknown as { preview: ReturnType<typeof vi.fn> };
            const table = { kind: 'table', columns: ['a', 'b'], rows: [{ a: '1' }], rowCount: 1, rejectedRows: 0 };
            parsers.preview.mockReturnValue(of(table));
            editor(fixture).test();
            expect(pane(fixture).schemaSeed().length).toBeGreaterThan(0);
            expect(pane(fixture).schemaStale()).toBe(false);

            parsers.preview.mockReturnValue(throwError(() => new Error('nope')));
            editor(fixture).test();
            expect(pane(fixture).schemaStale()).toBe(true);
            fixture.detectChanges();
            expect(fixture.nativeElement.textContent).toContain('These columns came from an earlier test');

            parsers.preview.mockReturnValue(of(table));
            editor(fixture).test();
            expect(pane(fixture).schemaStale()).toBe(false);
        });

        /** ⚠ A record tree is not "rows a downstream step can cast" — leave the thread alone. */
        it('leaves the thread untouched for a tree preview', async () => {
            const thread = new DefinitionStateService();
            thread.captureSample('cdr.ber', 'x');
            const fixture = await create(delimitedNode(), [], 0, thread);
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
     * Name/Description moved to the canvas inspector's rename affordance — this pane defines the
     * Grammar only. It must render no Name field and carry the node's stored identity VERBATIM
     * through an Apply, or a Grammar edit would silently strip a node's name.
     */
    it('renders no Name/Description fields and carries name/description verbatim through Apply', async () => {
        const fixture = await create({ ...delimitedNode(), description: 'pipe-delimited CDR feed' });
        const labels = Array.from(fixture.nativeElement.querySelectorAll('mat-label')).map(
            (l) => (l as HTMLElement).textContent,
        );
        expect(labels).not.toContain('Name');
        expect(labels).not.toContain('Description');

        pane(fixture).submit();

        const applied = fixture.componentInstance.applied!;
        expect(applied.name).toBe('Parser (delimited)');
        expect(applied.description).toBe('pipe-delimited CDR feed');
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

    /**
     * 🔴 BUILDER-1a, found by driving the real UI: a builder pasted a sample, ran Test parse, watched a
     * full output schema derive — and Apply was GREYED OUT. The schema grid is seeded programmatically
     * so its form stays pristine, and the sample is not a form at all, so nothing reported dirty. The
     * derived schema is exactly what Apply persists, so producing one must arm it.
     */
    it('arms Apply when a test parse derives the output schema', async () => {
        const fixture = await create();
        expect(fixture.componentInstance.dirty).toBe(false);

        pane(fixture).onPreviewed({
            kind: 'table' as const,
            columns: ['A', 'B'],
            rows: [{ A: '1', B: '2' }],
            rowCount: 1,
            rejectedRows: 0,
        });
        fixture.detectChanges();

        expect(fixture.componentInstance.dirty).toBe(true);

        pane(fixture).submit();
        fixture.detectChanges();
        expect(fixture.componentInstance.dirty).toBe(false); // Apply consumed it
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

    // U4: "Save as template" is gone from the pane — the Grammar CSV round-trip replaced it
    // (see the 'grammar CSV' describe below); stored templates are created in the Components registry.

    /**
     * P3c: asn1 is not a built-in `ParsingFrontend` — the editor hosts it as the served parser it is —
     * so the pane assembles the block itself (`parsingValue`): the editor's `value()` would stamp its
     * INTERNAL frontend (delimited), and the schema-form does not carry `segments` at all.
     */
    describe('the ASN.1 subtype (parser.asn1)', () => {
        it('locks the editor to the served asn1 type', async () => {
            const fixture = await create(asn1Node(), [ASN1_DEF]);
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
            const fixture = await create(asn1Node(), [ASN1_DEF]);
            expect(pane(fixture).initialSegments()).toEqual([
                { key: 'Record', columns: [{ name: 'IMSI', selector: 'imsi', type: 'VARCHAR' }] },
            ]);
        });

        it('Apply writes one schema toon per segment, THEN emits a block referencing them', async () => {
            const fixture = await create(asn1Node(), [ASN1_DEF]);
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
                const fixture = await create(asn1Node(), [ASN1_DEF]);
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
                const fixture = await create(asn1Node(), [ASN1_DEF]);
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
            const fixture = await create(asn1Node(), [ASN1_DEF]);
            expect(pane(fixture).authorsSegments()).toBe(true);
            expect(fixture.nativeElement.querySelector('inspecto-segments-editor')).not.toBeNull();
        });

        it('does not offer segments on a delimited node', async () => {
            const fixture = await create(delimitedNode(), [ASN1_DEF]);
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
            const fixture = await create(asn1Node(), [ASN1_DEF], 10);
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
            const fixture = await create(asn1Node(), []);

            pane(fixture).submit();

            expect(fixture.componentInstance.applied).toBeUndefined();
            expect(editor(fixture).error()).toContain('not available');
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
            const fixture = await create(pluginNode(), [ACME_DEF, ASN1_DEF]);
            const p = pane(fixture);
            expect(p.pluginChoices().map((x) => x.id)).toEqual(['acme_feed']);
        });

        it('rehydrates the saved ingester on load without marking the pane dirty', async () => {
            const fixture = await create(pluginNode(), [ACME_DEF], 5);
            await new Promise((r) => setTimeout(r, 10));
            fixture.detectChanges();

            expect(pane(fixture).plugin()?.id).toBe('acme_feed');
            expect(fixture.componentInstance.dirty).toBe(false);
        });

        it('offers the segments editor for an ingestable plugin, and authors them on Apply', async () => {
            const fixture = await create(pluginNode(), [ACME_DEF]);
            expect(pane(fixture).authorsSegments()).toBe(true);
        });

        /**
         * This node is ingestable (authorsSegments() is true), so submit() takes the segment-write
         * path — same two-hop contract as ASN.1: schemas are written first, then the block naming them
         * is emitted, only after which the ingester + ingester_config from the schema form land in it.
         */
        it('Apply writes segment schemas then assembles ingester + ingester_config from the served schema', async () => {
            const fixture = await create(pluginNode(), [ACME_DEF]);
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
            const fixture = await create(pluginNode(), []);

            pane(fixture).submit();

            expect(fixture.componentInstance.applied).toBeUndefined();
            expect(editor(fixture).error()).toContain('No ingestable parser plugin');
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
            const fixture = await create(textRegexNode(), [ASN1_DEF]);
            expect(pane(fixture).authorsSegments()).toBe(false);
        });
    });

    /** The editor is locked to the format the node's TYPE means — never a picker.
     *  ⚠ One `create()` per test: `TestBed.configureTestingModule` throws once instantiated. */
    describe('the locked frontend', () => {
        it('derives it from a delimited node type', async () => {
            expect(pane(await create(delimitedNode())).frontend()).toBe('delimited');
        });

        it('derives it from a fixed-width node type', async () => {
            expect(pane(await create(fixedWidthNode())).frontend()).toBe('fixedwidth');
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
            expect(
                pane(fixture)
                    .schemaSeed()
                    .map((r) => r.name),
            ).toEqual(['A_NUMBER', 'DURATION']);
            // Delimited addresses parsed columns by POSITION, so selectors are indices.
            expect(
                pane(fixture)
                    .schemaSeed()
                    .map((r) => r.selector),
            ).toEqual(['0', '1']);

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

        /**
         * 🔴 BUILDER-1b, found by driving the real UI: one pipeline has ONE output schema
         * (`<pipeline>_schema`), so changing its parse FORMAT legitimately drops columns and the
         * BACKWARD save-gate refuses — leaving the builder with a raw 422 and no way forward. The
         * refusal now arms an explicit override; nothing is applied until they take it.
         */
        it('offers to replace an output schema the BACKWARD gate refused, and retries with the override', async () => {
            schemaBackwardRefusal = true;
            try {
                const fixture = await create(unschemadNode());
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();

                pane(fixture).submit();
                fixture.detectChanges();
                expect(pane(fixture).schemaReplaceNeeded()).toBe(true);
                expect(fixture.componentInstance.applied).toBeUndefined(); // nothing applied on a refusal

                pane(fixture).replaceOutputSchema();
                fixture.detectChanges();
                expect(schemaWrites.at(-1)?.opts?.['compatibility']).toBe('none');
                expect(fixture.componentInstance.applied!.config!['schema_file']).toBe('parse_schema.toon');
            } finally {
                schemaBackwardRefusal = false;
            }
        });

        /** ⛔ An ordinary write failure is NOT recoverable by replacing — the banner must stay away. */
        it('does not offer the replace override for an unrelated write failure', async () => {
            schemaWriteFails = true;
            try {
                const fixture = await create(unschemadNode());
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();
                pane(fixture).submit();
                fixture.detectChanges();
                expect(pane(fixture).schemaReplaceNeeded()).toBe(false);
            } finally {
                schemaWriteFails = false;
            }
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
            expect(
                pane(fixture)
                    .schemaSeed()
                    .map((r) => r.name),
            ).toEqual(['IMSI']);

            pane(fixture).onPreviewed(TABLE_PREVIEW);
            fixture.detectChanges();
            expect(
                pane(fixture)
                    .schemaSeed()
                    .map((r) => r.name),
            ).toEqual(['IMSI']);
        });

        /**
         * D1(b): the Files & metadata tab's column-metadata grid — description/unit/classification
         * are merged onto the columns table's rows by selector at submit, so the schema write
         * carries them; the columns table's own form never holds those keys.
         */
        it('persists the metadata grid’s values into the schema write, merged by selector', async () => {
            const n = unschemadNode();
            (n.config as Record<string, unknown>)['schema_file'] = 'parse_schema.toon';
            const fixture = await create(n); // hydrates the one-field saved schema (IMSI)
            fixture.detectChanges();

            const metaInputs = Array.from(
                fixture.nativeElement.querySelectorAll('inspecto-schema-metadata-grid input'),
            ) as HTMLInputElement[];
            expect(metaInputs.length).toBe(3); // one row × description/unit/classification
            metaInputs[0].value = 'subscriber id';
            metaInputs[0].dispatchEvent(new Event('input', { bubbles: true }));
            metaInputs[2].value = 'PII';
            metaInputs[2].dispatchEvent(new Event('input', { bubbles: true }));

            pane(fixture).submit();
            fixture.detectChanges();

            const raw = schemaWrites[0].config['raw'] as Record<string, unknown>;
            const fields = raw['fields'] as Record<string, unknown>[];
            expect(fields[0]['name']).toBe('IMSI');
            expect(fields[0]['description']).toBe('subscriber id');
            expect(fields[0]['classification']).toBe('PII');
            expect(fields[0]['unit']).toBeUndefined();
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
         * S4 — the FIRST derivation is revealed: it lands on the Types & columns tab, which on a tabbed
         * format is not the tab the operator is looking at, so the parse used to look like it did
         * nothing. Later parses must NOT yank the tab out from under someone editing another one.
         */
        it('reveals the Types tab on the first derivation only', async () => {
            const fixture = await create(unschemadNode());
            const ed = editor(fixture);
            expect(ed.tabbed).toBe(true); // delimited renders as tabs
            ed.showTab('dialect');

            pane(fixture).onPreviewed(TABLE_PREVIEW);
            fixture.detectChanges();
            expect(ed.activeTab()).toBe(1); // 'types'

            // The operator moves to another tab and re-parses: the tab stays where they put it.
            ed.showTab('robustness');
            const parked = ed.activeTab();
            pane(fixture).onPreviewed(TABLE_PREVIEW);
            fixture.detectChanges();
            expect(ed.activeTab()).toBe(parked);
        });

        /**
         * 2a-iii: a hydrated schema does not re-derive        /**
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
                expect(
                    pane(fixture)
                        .schemaSeed()
                        .map((r) => r.name),
                ).toEqual(['IMSI']);
            });

            /**
             * S4/D7 — the third verb. Add-new can only append and type changes are deliberately never
             * applied, so a schema whose sample has genuinely moved on had no way back to a derived one.
             * ⚠ It must go through the derive branch of `onPreviewed`, not a second derivation here.
             */
            it('re-derives from the sample on confirm: hydration is cleared and the parse re-runs', async () => {
                const fixture = await create(schemadNode());
                fixture.detectChanges();
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();
                expect(
                    pane(fixture)
                        .schemaSeed()
                        .map((r) => r.name),
                ).toEqual(['IMSI']); // hydrated, un-re-derived

                const p = pane(fixture);
                const test = vi.spyOn(editor(fixture), 'test');
                await p.rederiveSchema();
                expect(test).toHaveBeenCalled(); // the ONE derive path is re-run, not duplicated
                expect(p.schemaDrift()).toBeNull();
                // With hydration cleared, the next preview derives instead of asking about drift.
                p.onPreviewed(TABLE_PREVIEW);
                expect(p.schemaSeed()).toHaveLength(TABLE_PREVIEW.columns.length);
                expect(p.schemaSeed().map((r) => r.name)).not.toContain('IMSI');
            });

            it('leaves the saved schema alone when the destructive confirm is declined', async () => {
                confirmAnswer = false;
                const fixture = await create(schemadNode());
                fixture.detectChanges();
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();

                await pane(fixture).rederiveSchema();
                pane(fixture).onPreviewed(TABLE_PREVIEW); // still hydrated ⇒ still the drift path
                expect(
                    pane(fixture)
                        .schemaSeed()
                        .map((r) => r.name),
                ).toEqual(['IMSI']);
            });

            it('adds the new columns on request, keeping every existing row including excluded ones', async () => {
                const fixture = await create(schemadNode());
                fixture.detectChanges();
                pane(fixture).onPreviewed(TABLE_PREVIEW);
                fixture.detectChanges();

                pane(fixture).addDriftedFields();
                fixture.detectChanges();

                expect(
                    pane(fixture)
                        .schemaSeed()
                        .map((r) => r.name),
                ).toEqual(['IMSI', 'DURATION']);
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

    // ── Data types: Auto / Declared (§4.4, U3) ────────────────────────────────────

    describe('data types mode', () => {
        /** A delimited node with NO schema_file — fresh from the palette, nothing hydrates. */
        function unschemadNode(): AuthoredNode {
            return {
                id: 'parse',
                type: 'parser.delimited',
                name: 'Parser (delimited)',
                config: { parsing: { frontend: 'delimited', delimited: { delimiter: ',' } } },
            };
        }

        const typedPreview = {
            kind: 'table' as const,
            columns: ['id', 'when', 'city'],
            rows: [{ id: '1', when: '2026-07-15', city: 'london' }],
            rowCount: 1,
            rejectedRows: 0,
            columnTypes: [
                { name: 'id', type: 'BIGINT' },
                { name: 'when', type: 'DATE' },
                { name: 'city', type: 'VARCHAR' },
            ],
        };

        it('defaults to Auto (D2) and seeds the preview’s inferred types, narrowed', async () => {
            const fixture = await create(unschemadNode());
            const p = pane(fixture);
            expect(p.typesMode()).toBe('auto');

            p.onPreviewed(typedPreview);
            fixture.detectChanges();

            // BIGINT is KEPT since 2026-08-22 — the engine casts every DuckDB scalar type, so
            // collapsing it into DOUBLE (lossy above 2^53) is no longer honest, just lossy.
            expect(p.schemaSeed().map((r) => r.type)).toEqual(['BIGINT', 'DATE', 'VARCHAR']);
        });

        it('seeds VARCHAR when the server serves no columnTypes (old server, byte-identical)', async () => {
            const fixture = await create(unschemadNode());
            const p = pane(fixture);
            p.onPreviewed({ ...typedPreview, columnTypes: undefined });
            fixture.detectChanges();
            expect(p.schemaSeed().map((r) => r.type)).toEqual(['VARCHAR', 'VARCHAR', 'VARCHAR']);
        });

        it('save snapshots the mode as raw.types and the inferred types into the fields', async () => {
            const fixture = await create(unschemadNode());
            const p = pane(fixture);
            p.onPreviewed(typedPreview);
            fixture.detectChanges();

            p.submit();
            fixture.detectChanges();

            expect(schemaWrites).toHaveLength(1);
            const raw = schemaWrites[0].config['raw'] as Record<string, unknown>;
            expect(raw['types']).toBe('auto');
            const fields = raw['fields'] as { name: string; type: string; synonym?: string }[];
            expect(fields.map((f) => f.type)).toEqual(['BIGINT', 'DATE', 'VARCHAR']);
            expect('synonym' in fields[0]).toBe(false);
        });

        it('Declared mode keeps VARCHAR until "Apply suggested types" stamps the inferred set', async () => {
            const fixture = await create(unschemadNode());
            const p = pane(fixture);
            p.setTypesMode('declared');
            p.onPreviewed(typedPreview);
            expect(p.schemaSeed().map((r) => r.type)).toEqual(['VARCHAR', 'VARCHAR', 'VARCHAR']);

            p.applySuggestedTypes();
            expect(p.schemaSeed().map((r) => r.type)).toEqual(['BIGINT', 'DATE', 'VARCHAR']);
        });

        it('a hydrated schema without the raw.types marker loads as Declared', async () => {
            const fixture = await create();
            fixture.componentInstance.pipelineName = 'cdr';
            fixture.componentInstance.node = delimitedNode(); // new identity → the pane re-loads
            fixture.detectChanges();

            expect(pane(fixture).typesMode()).toBe('declared');
        });

        it('switching the mode marks the pane dirty', async () => {
            const fixture = await create(unschemadNode());
            expect(fixture.componentInstance.dirty).toBe(false);
            pane(fixture).setTypesMode('declared');
            fixture.detectChanges();
            expect(fixture.componentInstance.dirty).toBe(true);
        });
    });

    // ── Grammar CSV round-trip (§4.5, U4) — the drawer's import semantics ─────────

    describe('grammar CSV import', () => {
        function csvNode(): AuthoredNode {
            return {
                id: 'parse',
                type: 'parser.delimited',
                name: 'Parser (delimited)',
                config: { parsing: { frontend: 'delimited', delimited: { delimiter: ',' } } },
            };
        }

        /**
         * The entry points are ONE icon row (operator ask 2026-08-22): the Grammar CSV pair is
         * projected INTO the sample panel, beside upload/paste, instead of sitting in a second row.
         * With no thread the panel is not mounted at all, so the same pair renders in the Grammar
         * row rather than vanishing — which is why it is declared as a template, not inlined twice.
         */
        it('projects the CSV pair into the sample panel’s icon row', async () => {
            const fixture = await create(csvNode(), [], 0, new DefinitionStateService());
            const panel = fixture.nativeElement.querySelector('inspecto-sample-panel');
            expect(panel.querySelector('button[aria-label="Import Grammar from CSV"]')).toBeTruthy();
            expect(panel.querySelector('button[aria-label="Export Grammar as CSV"]')).toBeTruthy();
        });

        /** ⚠ One `create()` per test — `TestBed.configureTestingModule` throws once instantiated. */
        it('falls back to the Grammar row when the host keeps no thread', async () => {
            const fixture = await create(csvNode());
            expect(fixture.nativeElement.querySelector('inspecto-sample-panel')).toBeNull();
            expect(fixture.nativeElement.querySelector('button[aria-label="Import Grammar from CSV"]')).toBeTruthy();
        });

        it('refuses a format-mismatched file outright', async () => {
            const fixture = await create(csvNode());
            await pane(fixture).importCsvText('section,key,attr,value\nmeta,format,,json\n');
            fixture.detectChanges();

            expect(editor(fixture).error()).toContain("'json'");
            expect(pane(fixture).schemaSeed()).toEqual([]);
        });

        it('applies known options + columns wholesale and restores the types mode', async () => {
            const fixture = await create(csvNode());
            const csv = grammarToCsv(
                { format: 'delimited', pipeline: 'orders', types: 'declared' },
                parsingAttributesFor('delimited'),
                { delimited__delimiter: '|', delimited__null_strings: ['NULL'] },
                [{ include: true, name: 'CUSTOMER_ID', selector: '0', type: 'DOUBLE', synonym: 'cust_no' }],
            );

            await pane(fixture).importCsvText(csv);
            fixture.detectChanges();

            const delimited = editor(fixture).value()['delimited'] as Record<string, unknown>;
            expect(delimited['delimiter']).toBe('|');
            expect(delimited['null_strings']).toEqual(['NULL']);
            expect(pane(fixture).schemaSeed()).toEqual([
                { include: true, name: 'CUSTOMER_ID', selector: '0', type: 'DOUBLE', synonym: 'cust_no' },
            ]);
            expect(pane(fixture).typesMode()).toBe('declared');
            expect(fixture.componentInstance.dirty).toBe(true);
        });

        it('lists unknown option keys and does not apply them', async () => {
            const fixture = await create(csvNode());
            await pane(fixture).importCsvText(
                'section,key,attr,value\nmeta,format,,delimited\noption,delimiter,,";"\noption,florble,,42\n',
            );
            fixture.detectChanges();

            expect(pane(fixture).importWarning()).toContain('florble');
            const delimited = editor(fixture).value()['delimited'] as Record<string, unknown>;
            expect(delimited['delimiter']).toBe(';');
            expect('florble' in delimited).toBe(false);
        });
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    /**
     * Partitioning (operator ask 2026-08-22) — the schema toon's `partitions[]`, read → edited →
     * rewritten through the SAME schema write. 🔴 The read half is the data-loss fix: the draft used
     * to carry `raw`+`mapping` only, so `overwrite: true` silently DROPPED a hand-authored
     * partitions[] on every Apply.
     */
    describe('partitions round-trip', () => {
        it('reads the stored partitions[] back and carries them through the schema write', async () => {
            savedPartitions = [
                { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
                { column: 'month', source: 'TXN_DATE', type: 'DATE_MONTH' },
            ];
            try {
                // pipelineName 'cdr' owns delimitedNode()'s cdr_schema.toon, so the saved schema loads.
                const fixture = await create(delimitedNode(), [], 0, null, 'cdr');
                expect(pane(fixture).partitionSeed()).toEqual([
                    { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
                    { column: 'month', source: 'TXN_DATE', type: 'DATE_MONTH' },
                ]);
                fixture.detectChanges();

                pane(fixture).submit();
                fixture.detectChanges();
                expect(schemaWrites).toHaveLength(1);
                expect(schemaWrites[0].config['partitions']).toEqual([
                    { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
                    { column: 'month', source: 'TXN_DATE', type: 'DATE_MONTH' },
                ]);
            } finally {
                savedPartitions = null;
            }
        });

        it('surfaces a legacy partitionKey as the trio the engine synthesises from it', async () => {
            savedLegacyPartitionKey = 'TXN_DATE';
            try {
                const fixture = await create(delimitedNode(), [], 0, null, 'cdr');
                expect(pane(fixture).partitionSeed()).toEqual([
                    { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
                    { column: 'month', source: 'TXN_DATE', type: 'DATE_MONTH' },
                    { column: 'day', source: 'TXN_DATE', type: 'DATE_DAY' },
                ]);
            } finally {
                savedLegacyPartitionKey = null;
            }
        });

        it('carries a foreign top-level key of the stored schema verbatim through the write', async () => {
            // The same data-loss class partitions[] suffered, generalised: an unknown/future
            // top-level key a hand-authored schema toon carries must survive an Apply round-trip.
            savedForeignKeys = { retention_days: 30, x_future: { nested: true } };
            try {
                const fixture = await create(delimitedNode(), [], 0, null, 'cdr');
                pane(fixture).submit();
                fixture.detectChanges();
                expect(schemaWrites).toHaveLength(1);
                expect(schemaWrites[0].config['retention_days']).toBe(30);
                expect(schemaWrites[0].config['x_future']).toEqual({ nested: true });
            } finally {
                savedForeignKeys = null;
            }
        });

        it('modeled keys win over a stored duplicate (a stale stored raw cannot shadow the edit)', async () => {
            savedForeignKeys = { keep: 'me' };
            savedLegacyPartitionKey = 'TXN_DATE';
            try {
                const fixture = await create(delimitedNode(), [], 0, null, 'cdr');
                pane(fixture).submit();
                fixture.detectChanges();
                expect(schemaWrites).toHaveLength(1);
                const config = schemaWrites[0].config;
                expect(config['keep']).toBe('me');
                // partitionKey is modeled — migrated into partitions[] — so re-emitting it verbatim
                // would resurrect the legacy spelling beside the current one.
                expect('partitionKey' in config).toBe(false);
                expect(config['partitions']).toEqual([
                    { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
                    { column: 'month', source: 'TXN_DATE', type: 'DATE_MONTH' },
                    { column: 'day', source: 'TXN_DATE', type: 'DATE_DAY' },
                ]);
            } finally {
                savedForeignKeys = null;
                savedLegacyPartitionKey = null;
            }
        });

        it('writes NO partitions key when none are configured (flat store stays flat)', async () => {
            const fixture = await create(delimitedNode(), [], 0, null, 'cdr');
            pane(fixture).submit();
            fixture.detectChanges();
            expect(schemaWrites).toHaveLength(1);
            expect('partitions' in schemaWrites[0].config).toBe(false);
        });
    });

    /**
     * The cross-node lineage field (operator ask 2026-08-22): `output.filename_column` lives on the
     * SINK node, not this one, so the pane only renders it when the HOST hands in a target — never
     * derives or guesses one itself (P2 stays pure).
     */
    describe('filenameColumnTarget (Files & metadata tab)', () => {
        it('renders nothing when the host has no target (ambiguous/no sink)', async () => {
            const fixture = await create();
            expect(fixture.nativeElement.textContent).not.toContain('Source filename column');
        });

        it('renders the field seeded from the host target, on the Files & metadata tab', async () => {
            const fixture = await create();
            fixture.componentInstance.filenameColumnTarget = { value: 'src_file', target: 'Warehouse' };
            fixture.detectChanges();
            const input = fixture.nativeElement.querySelector(
                'input[aria-label="Source filename column"]',
            ) as HTMLInputElement;
            expect(input.value).toBe('src_file');
            expect(fixture.nativeElement.textContent).toContain('Warehouse');
        });

        it('emits null on a blank commit (clears the column)', async () => {
            const fixture = await create();
            fixture.componentInstance.filenameColumnTarget = { value: 'src_file', target: 'Warehouse' };
            fixture.detectChanges();
            const input = fixture.nativeElement.querySelector(
                'input[aria-label="Source filename column"]',
            ) as HTMLInputElement;
            input.value = '';
            input.dispatchEvent(new Event('change'));
            fixture.detectChanges();
            expect(fixture.componentInstance.filenameColumnChange).toBeNull();
        });

        it('refuses an invalid identifier with an inline alert, and emits nothing for it', async () => {
            const fixture = await create();
            fixture.componentInstance.filenameColumnTarget = { value: '', target: 'Warehouse' };
            fixture.detectChanges();
            const input = fixture.nativeElement.querySelector(
                'input[aria-label="Source filename column"]',
            ) as HTMLInputElement;
            input.value = '0_bad_start';
            input.dispatchEvent(new Event('change'));
            fixture.detectChanges();
            expect(fixture.componentInstance.filenameColumnChange).toBeUndefined();
            const alert = fixture.nativeElement.querySelector('[role="alert"]');
            expect(alert?.textContent).toContain('Must start with a letter');
        });

        it('emits the trimmed valid identifier and clears any prior error', async () => {
            const fixture = await create();
            fixture.componentInstance.filenameColumnTarget = { value: '', target: 'Warehouse' };
            fixture.detectChanges();
            const input = fixture.nativeElement.querySelector(
                'input[aria-label="Source filename column"]',
            ) as HTMLInputElement;
            input.value = ' src_file ';
            input.dispatchEvent(new Event('change'));
            fixture.detectChanges();
            expect(fixture.componentInstance.filenameColumnChange).toBe('src_file');
            expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeNull();
        });

        /**
         * Operator ask 2026-08-22: a configured lineage column is real in the WRITTEN rows but never
         * went through this parse — it is stamped at write time — so the Types tab's schema list must
         * say so, read-only, or a reader of the output schema has no way to know the extra column
         * exists. Never a fake schemaSeed row: that would risk being mistaken for an authored column.
         */
        it('notes the lineage column on the Types tab schema list, only when one is configured', async () => {
            // ⚠ The default delimitedNode() names a schema_file this editor does not own
            // (foreignSchema ⇒ authorsSchema() false ⇒ the whole Types-tab schema block —
            // note included — never renders). The fresh-drop node is the authoring case.
            const node = delimitedNode();
            delete (node.config as Record<string, unknown>)['schema_file'];
            const fixture = await create(node);
            pane(fixture).schemaSeed.set([{ include: true, name: 'MSISDN', selector: '0', type: 'VARCHAR' }]);
            fixture.detectChanges();
            expect(fixture.nativeElement.textContent).not.toContain('lineage column');

            fixture.componentInstance.filenameColumnTarget = { value: 'src_file', target: 'Warehouse' };
            fixture.detectChanges();
            const text = fixture.nativeElement.textContent as string;
            expect(text).toContain('src_file');
            expect(text).toContain('lineage column');
            expect(text).toContain('Warehouse');
            // …and in the Column metadata list too (operator ask 2026-08-22), read-only.
            expect(text).toContain('stamped at write');
            // Read-only: never one of the editable schema rows.
            expect(
                pane(fixture)
                    .schemaSeed()
                    .map((r) => r.name),
            ).not.toContain('src_file');
        });
    });
});
