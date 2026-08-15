import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ParserDef, ParsersService, ParsingPreview } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingParsingPaneComponent } from './parsing-pane.component';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const PREVIEW: ParsingPreview = {
    frontend: 'delimited',
    columns: ['a'],
    rowCount: 1,
    rows: [{ a: '1' }],
    rejectedRows: 0,
};
const WRITE_OK = {
    type: 'pipeline',
    written: true,
    path: 'x.toon',
    name: 'x',
    bytes: 1,
    overwritten: false,
    findings: [],
};

/** A served catalog: a built-in (schema irrelevant here) + the preview-only XML plugin. */
const XML_DEF: ParserDef = {
    id: 'xml',
    label: 'XML — XML file format',
    hierarchical: true,
    ingestable: false,
    grammarSchema: [
        { path: 'xml.record_element', label: 'Record element', type: 'STRING' },
        { path: 'xml.namespace_aware', label: 'Namespace aware', type: 'BOOL', defaultValue: false },
    ],
};
const CATALOG: ParserDef[] = [
    { id: 'delimited', label: 'Delimited — …', hierarchical: false, ingestable: true, grammarSchema: [] },
    XML_DEF,
];

/** An ingestable plugin: `ingestable && ingesterClass` is what unlocks the segments editor. */
const ASN1_DEF: ParserDef = {
    id: 'asn1',
    label: 'ASN.1 — BER/DER',
    hierarchical: true,
    ingestable: true,
    ingesterClass: 'com.gamma.ingester.Asn1RecordIngester',
    grammarSchema: [{ path: 'asn1.root_type', label: 'Root type', type: 'STRING' }],
};
const ASN1_CATALOG: ParserDef[] = [CATALOG[0], ASN1_DEF];

/** A saved stream: one segment, whose columns live in the referenced schema toon. */
const SAVED_SEGMENTS = {
    name: 'cdr',
    parsing: {
        frontend: 'plugin',
        plugin: { ingester: ASN1_DEF.ingesterClass, segments: { moCallRecord: './config/cdr_moCallRecord.toon' } },
    },
};
const SAVED_SCHEMA = {
    type: 'schema',
    name: 'cdr_moCallRecord',
    path: './config/cdr_moCallRecord.toon',
    config: {
        raw: {
            name: 'cdr_moCallRecord',
            format: 'CSV',
            fields: [
                { name: 'IMSI', selector: 'imsi', type: 'VARCHAR' },
                { name: 'PARTY_NUMBER', selector: 'party.number', type: 'VARCHAR' },
            ],
        },
    },
};

async function create(
    config: Record<string, unknown>,
    api: Partial<ConfigService> = {},
    parsers: Partial<ParsersService> = {},
) {
    TestBed.configureTestingModule({
        imports: [OnboardingParsingPaneComponent],
        providers: [
            provideNoopAnimations(),
            DefinitionStateService,
            {
                provide: ConfigService,
                useValue: {
                    write: vi.fn(() => of(WRITE_OK)),
                    previewParsing: vi.fn(() => of(PREVIEW)),
                    ...api,
                },
            },
            { provide: ParsersService, useValue: { list: vi.fn(() => of(CATALOG)), preview: vi.fn(), ...parsers } },
            // The data-table's grid theme chains to the app shell's GAMMA_APP_CONFIG — stub it out.
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    await TestBed.compileComponents(); // the data-table pro tier @defer-loads its SQL editor
    const definition = TestBed.inject(DefinitionStateService);
    const fixture: ComponentFixture<OnboardingParsingPaneComponent> =
        TestBed.createComponent(OnboardingParsingPaneComponent);
    fixture.componentRef.setInput('config', config);
    // The pure contract is observed through the outputs — the HOST persists the emitted block.
    const applied: Record<string, unknown>[] = [];
    const dirty: boolean[] = [];
    fixture.componentInstance.applied.subscribe((v) => applied.push(v));
    fixture.componentInstance.dirtyChange.subscribe((v) => dirty.push(v));
    fixture.detectChanges();
    return {
        fixture,
        definition,
        applied,
        dirty,
        api: TestBed.inject(ConfigService),
        parsers: TestBed.inject(ParsersService),
    };
}

describe('OnboardingParsingPaneComponent — saved segments re-hydrate', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('reads each segment schema back so re-editing does not need a destructive re-derive', async () => {
        const read = vi.fn(() => of(SAVED_SCHEMA));
        const { fixture } = await create(SAVED_SEGMENTS, { read }, { list: vi.fn(() => of(ASN1_CATALOG)) });
        const pane = fixture.componentInstance;

        // Resolved by schema-toon BASENAME, not recomputed from the segment key.
        expect(read).toHaveBeenCalledWith('schema', 'cdr_moCallRecord');
        expect(pane.initialSegments()).toEqual([
            {
                key: 'moCallRecord',
                columns: [
                    { name: 'IMSI', selector: 'imsi', type: 'VARCHAR' },
                    { name: 'PARTY_NUMBER', selector: 'party.number', type: 'VARCHAR' },
                ],
            },
        ]);
        expect(pane.segmentsLoading()).toBe(false);
    });

    it('degrades to keys-only when the schema toon is missing (404), silently', async () => {
        const read = vi.fn(() => throwError(() => ({ status: 404 })));
        const { fixture } = await create(SAVED_SEGMENTS, { read }, { list: vi.fn(() => of(ASN1_CATALOG)) });

        expect(fixture.componentInstance.initialSegments()).toEqual([{ key: 'moCallRecord', columns: [] }]);
        expect(TOASTR.warning).not.toHaveBeenCalled();
    });

    it('warns but still renders the segment when the read fails for a real reason', async () => {
        TOASTR.warning.mockClear();
        const read = vi.fn(() => throwError(() => ({ status: 500 })));
        const { fixture } = await create(SAVED_SEGMENTS, { read }, { list: vi.fn(() => of(ASN1_CATALOG)) });

        expect(fixture.componentInstance.initialSegments()).toEqual([{ key: 'moCallRecord', columns: [] }]);
        expect(TOASTR.warning).toHaveBeenCalled();
    });

    it('does not read anything when the pipeline has no saved segments', async () => {
        const read = vi.fn(() => of(SAVED_SCHEMA));
        await create({ name: 'cdr', parsing: { frontend: 'delimited' } }, { read });
        expect(read).not.toHaveBeenCalled();
    });
});

/**
 * The pane is a thin HOST over the shared `<inspecto-grammar-editor>` (2026-08-04). The format
 * catalog, options form, sniffing and result rendering are covered by that component's own spec —
 * what is tested here is the WIRING and the parts Onboarding still owns: which preview route a
 * built-in vs a plugin takes, the cross-stage state it must keep feeding, the plugin path's
 * schema-writes-before-emit ordering, the pure dirty contract and the lens gate. Since D2 the pane
 * does not write the pipeline block — the host does, so what a save produces is asserted on
 * `applied`.
 */
describe('OnboardingParsingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('seeds the shared editor from the existing parsing block, clean until touched', async () => {
        const { fixture, dirty } = await create({
            name: 'x',
            parsing: { frontend: 'fixed_width', fixedwidth: { fields: [{ name: 'id', start: 0, length: 3 }] } },
        });
        const editor = fixture.componentInstance.grammar!;

        expect(editor.frontend()).toBe('fixedwidth'); // the engine's legacy spelling normalized
        expect(editor.fwFields.length).toBe(1);
        // Merely rendering must NOT mark the stage dirty — that would raise the rail's unsaved-changes
        // guard and silently block stage navigation for a config nobody touched.
        expect(dirty).toEqual([]);
    });

    it('hosts the sample capture panel itself, above the editor', async () => {
        const { fixture } = await create({ name: 'x' });
        const panel = fixture.nativeElement.querySelector('app-onboarding-sample-panel');
        const editor = fixture.nativeElement.querySelector('inspecto-grammar-editor');

        expect(panel).toBeTruthy();
        // DOCUMENT_POSITION_FOLLOWING (4) ⇒ the editor comes after the sample panel.
        expect(panel.compareDocumentPosition(editor) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
        // …and the editor must not draw a SECOND sample box beside the stage's own strip.
        expect(editor.querySelector('textarea')).toBeNull();
    });

    it('routes a BUILT-IN test parse through the draft preview and feeds the sample thread', async () => {
        const { fixture, definition, api } = await create({ name: 'x', dirs: { poll: 'in' } });
        definition.captureSample('s.csv', 'a|b\n1|2\n');
        fixture.detectChanges();

        fixture.componentInstance.grammar!.test();

        const [draft, text] = (api.previewParsing as ReturnType<typeof vi.fn>).mock.calls[0];
        expect((draft as Record<string, unknown>)['dirs']).toEqual({ poll: 'in' }); // merged over the draft
        expect((draft as Record<string, unknown>)['parsing']).toBeTruthy();
        expect(text).toBe('a|b\n1|2\n');
        // The Sample panel renders this and the Schema stage's `hasSource` gate depends on it.
        expect(definition.parsePreview()).toEqual(PREVIEW);
    });

    it('invalidates a stale schema cast-check when the sample is re-parsed', async () => {
        const { fixture, definition } = await create({ name: 'x' });
        definition.captureSample('s.csv', 'a\n1\n');
        definition.schemaPreview.set({ columns: [], okCount: 1, rejectedCount: 0 } as never);
        fixture.detectChanges();

        fixture.componentInstance.grammar!.test();

        expect(definition.schemaPreview()).toBeNull();
    });

    it('surfaces a built-in parse failure on the session, not just in the editor', async () => {
        const previewParsing = vi.fn(() => throwError(() => ({ status: 422, error: { message: 'bad delimiter' } })));
        const { fixture, definition } = await create({ name: 'x' }, { previewParsing });
        definition.captureSample('s.csv', 'zzz');
        fixture.detectChanges();

        fixture.componentInstance.grammar!.test();

        expect(definition.parseError()).toBeTruthy();
        expect(definition.parsePreview()).toBeNull();
    });

    it('a PLUGIN test parse uses the stateless route and never feeds the sample thread', async () => {
        const preview = vi.fn(() =>
            of({
                kind: 'tree' as const,
                recordCount: 2,
                nodes: [{ label: 'order', type: 'element', children: [{ label: '@id', type: 'attr', value: '1' }] }],
            }),
        );
        const { fixture, definition, api, parsers } = await create({ name: 'x' }, {}, { preview });
        definition.captureSample('s.xml', '<orders><order id="1"/></orders>');
        fixture.detectChanges();
        const editor = fixture.componentInstance.grammar!;
        editor.setType('xml');
        fixture.detectChanges();

        editor.schemaForm?.form.get('xml__record_element')?.setValue('order');
        editor.test();

        // The nested grammar carries the touched field AND the schema's declared defaults.
        expect(parsers.preview).toHaveBeenCalledWith(
            'xml',
            { xml: { record_element: 'order', namespace_aware: false } },
            '<orders><order id="1"/></orders>',
        );
        expect(api.previewParsing).not.toHaveBeenCalled();
        // The parsed hop is fed only by the built-ins a draft can actually go live with.
        expect(definition.parsePreview()).toBeNull();
    });

    it('emits the nested keys the parser reads, never the flat form keys', async () => {
        const { fixture, applied } = await create({
            name: 'x',
            parsing: { frontend: 'json', json: { format: 'array' } },
        });
        const pane = fixture.componentInstance;
        fixture.detectChanges();

        pane.grammar!.schemaForm?.form.get('json__records_path')?.setValue('payload.records');
        pane.save();

        expect(applied).toHaveLength(1);
        expect((applied[0]['json'] as Record<string, unknown>)['records_path']).toBe('payload.records');
        expect(applied[0]['json__records_path']).toBeUndefined(); // the flat form must not leak to disk
    });

    it('re-selects the served parser a saved plugin config names, without dirtying the stage', async () => {
        // Matched by ingesterClass: a guided Save writes the FQCN, never the parser id.
        const { fixture, dirty } = await create(
            SAVED_SEGMENTS,
            { read: vi.fn(() => of(SAVED_SCHEMA)) },
            { list: vi.fn(() => of(ASN1_CATALOG)) },
        );

        expect(fixture.componentInstance.plugin()?.id).toBe('asn1');
        expect(fixture.componentInstance.pluginIngestable()).toBe(true);
        expect(dirty).toEqual([]);
    });

    it('warns when the configured ingester is not served here', async () => {
        // The honest-failure case: the pane falls back to a built-in and must SAY so, rather than
        // silently presenting the pipeline as delimited.
        const { fixture } = await create(
            { name: 'x', processing: { ingester: 'com.example.NotDeployed' } },
            {},
            { list: vi.fn(() => of(ASN1_CATALOG)) },
        );

        expect(fixture.componentInstance.grammar!.unservedPlugin()).toBe('com.example.NotDeployed');
        expect(fixture.nativeElement.textContent).toContain('com.example.NotDeployed');
    });

    it('offers no Save for a preview-only plugin, and selecting one is not an unsaved change', async () => {
        const { fixture, dirty } = await create({ name: 'x' });
        fixture.componentInstance.grammar!.setType('xml');
        fixture.detectChanges();

        expect(fixture.nativeElement.textContent).toContain('flatten configuration');
        const save = Array.from(fixture.nativeElement.querySelectorAll('button')).find((b) =>
            (b as HTMLElement).textContent!.includes('Save parsing'),
        ) as HTMLButtonElement;
        expect(save.disabled).toBe(true);
        expect(dirty).toEqual([]);
    });

    it('writes every segment schema BEFORE emitting the block that references them', async () => {
        const order: string[] = [];
        const write = vi.fn(() => {
            order.push('write');
            return of(WRITE_OK);
        });
        const { fixture, applied } = await create(
            SAVED_SEGMENTS,
            { read: vi.fn(() => of(SAVED_SCHEMA)), write },
            { list: vi.fn(() => of(ASN1_CATALOG)) },
        );
        fixture.componentInstance.applied.subscribe(() => order.push('applied'));
        fixture.detectChanges();

        fixture.componentInstance.save();

        // The pipeline must never name a schema file that does not exist yet.
        expect(order).toEqual(['write', 'applied']);
        expect(applied).toHaveLength(1);
        const plugin = applied[0]['plugin'] as Record<string, unknown>;
        expect(applied[0]['frontend']).toBe('plugin');
        expect(plugin['ingester']).toBe(ASN1_DEF.ingesterClass);
        expect(Object.keys(plugin['segments'] as Record<string, unknown>)).toEqual(['moCallRecord']);
    });

    it('emits nothing and stays dirty when a segment schema write fails', async () => {
        TOASTR.error.mockClear();
        const write = vi.fn(() => throwError(() => ({ status: 500 })));
        const { fixture, applied } = await create(
            SAVED_SEGMENTS,
            { read: vi.fn(() => of(SAVED_SCHEMA)), write },
            { list: vi.fn(() => of(ASN1_CATALOG)) },
        );
        fixture.detectChanges();

        fixture.componentInstance.save();

        expect(applied).toHaveLength(0);
        expect(TOASTR.error).toHaveBeenCalled();
        expect(fixture.componentInstance.writing()).toBe(false);
    });

    it('reports edits as dirty to the host', async () => {
        const { fixture, dirty } = await create({ name: 'x' });
        fixture.componentInstance.grammar!.setFrontend('json');
        fixture.componentInstance.onInteraction();

        expect(dirty).toEqual([true]);
    });

    it('re-seeding the config input returns the pane to pristine — this is how a saved pane resets', async () => {
        const { fixture, dirty } = await create({ name: 'x' });
        fixture.componentInstance.grammar!.setFrontend('json');
        fixture.componentInstance.onInteraction();
        expect(dirty).toEqual([true]);

        // What the host does after a SUCCESSFUL save: hand back the newly-persisted draft.
        fixture.componentRef.setInput('config', { name: 'x', parsing: { frontend: 'json' } });
        fixture.detectChanges();

        expect(dirty).toEqual([true, false]);
    });

    it('a failed save leaves the input identical, so the pane stays dirty and the guard still fires', async () => {
        const { fixture, dirty } = await create({ name: 'x' });
        fixture.componentInstance.grammar!.setFrontend('json');
        fixture.componentInstance.onInteraction();
        expect(dirty).toEqual([true]);

        // No re-seed happens on failure — the host's config never advanced.
        fixture.detectChanges();

        expect(dirty).toEqual([true]);
    });

    it('does not save at all without the workbench lens', async () => {
        const { fixture, applied } = await create({ name: 'x' });
        vi.spyOn(fixture.componentInstance['lens'], 'canAuthorWorkbench').mockReturnValue(false);

        fixture.componentInstance.save();

        expect(applied).toHaveLength(0);
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
