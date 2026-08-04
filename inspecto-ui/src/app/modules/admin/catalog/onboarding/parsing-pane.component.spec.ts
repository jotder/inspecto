import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ParserDef, ParsersService, ParsingPreview } from 'app/inspecto/api';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingParsingPaneComponent } from './parsing-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const PREVIEW: ParsingPreview = { frontend: 'delimited', columns: ['a'], rowCount: 1, rows: [{ a: '1' }], rejectedRows: 0 };
const WRITE_OK = { type: 'pipeline', written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: false, findings: [] };

/** A served catalog: a built-in (schema irrelevant here) + the preview-only XML plugin. */
const XML_DEF: ParserDef = {
    id: 'xml', label: 'XML — XML file format', hierarchical: true, ingestable: false,
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
    id: 'asn1', label: 'ASN.1 — BER/DER', hierarchical: true, ingestable: true,
    ingesterClass: 'com.gamma.ingester.Asn1RecordIngester',
    grammarSchema: [{ path: 'asn1.root_type', label: 'Root type', type: 'STRING' }],
};
const ASN1_CATALOG: ParserDef[] = [CATALOG[0], ASN1_DEF];

/** A saved stream: one segment, whose columns live in the referenced schema toon. */
const SAVED_SEGMENTS = {
    name: 'cdr',
    parsing: { frontend: 'plugin', plugin: { ingester: ASN1_DEF.ingesterClass, segments: { moCallRecord: './config/cdr_moCallRecord.toon' } } },
};
const SAVED_SCHEMA = {
    type: 'schema', name: 'cdr_moCallRecord', path: './config/cdr_moCallRecord.toon',
    config: {
        raw: {
            name: 'cdr_moCallRecord', format: 'CSV',
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
            OnboardingStateService,
            { provide: ConfigService, useValue: { patch: vi.fn(() => of(WRITE_OK)), previewParsing: vi.fn(() => of(PREVIEW)), ...api } },
            { provide: ParsersService, useValue: { list: vi.fn(() => of(CATALOG)), preview: vi.fn(), ...parsers } },
            // The data-table's grid theme chains to the app shell's GAMMA_APP_CONFIG — stub it out.
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    await TestBed.compileComponents(); // the data-table pro tier @defer-loads its SQL editor
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    const fixture = TestBed.createComponent(OnboardingParsingPaneComponent);
    fixture.detectChanges();
    return { fixture, state, api: TestBed.inject(ConfigService), parsers: TestBed.inject(ParsersService) };
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
 * built-in vs a plugin takes, the cross-stage state it must keep feeding, the two write paths, the
 * dirty registry and the lens gate.
 */
describe('OnboardingParsingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('seeds the shared editor from the existing parsing block, clean until touched', async () => {
        const { fixture, state } = await create({
            name: 'x',
            parsing: { frontend: 'fixed_width', fixedwidth: { fields: [{ name: 'id', start: 0, length: 3 }] } },
        });
        const editor = fixture.componentInstance.grammar!;

        expect(editor.frontend()).toBe('fixedwidth'); // the engine's legacy spelling normalized
        expect(editor.fwFields.length).toBe(1);
        // Merely rendering must NOT mark the stage dirty — that would raise the rail's unsaved-changes
        // guard and silently block stage navigation for a config nobody touched.
        expect(state.isDirty()).toBe(false);
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
        const { fixture, state, api } = await create({ name: 'x', dirs: { poll: 'in' } });
        state.captureSample('s.csv', 'a|b\n1|2\n');
        fixture.detectChanges();

        fixture.componentInstance.grammar!.test();

        const [draft, text] = (api.previewParsing as ReturnType<typeof vi.fn>).mock.calls[0];
        expect((draft as Record<string, unknown>)['dirs']).toEqual({ poll: 'in' }); // merged over the draft
        expect((draft as Record<string, unknown>)['parsing']).toBeTruthy();
        expect(text).toBe('a|b\n1|2\n');
        // The Sample panel renders this and the Schema stage's `hasSource` gate depends on it.
        expect(state.parsePreview()).toEqual(PREVIEW);
    });

    it('invalidates a stale schema cast-check when the sample is re-parsed', async () => {
        const { fixture, state } = await create({ name: 'x' });
        state.captureSample('s.csv', 'a\n1\n');
        state.schemaPreview.set({ columns: [], okCount: 1, rejectedCount: 0 } as never);
        fixture.detectChanges();

        fixture.componentInstance.grammar!.test();

        expect(state.schemaPreview()).toBeNull();
    });

    it('surfaces a built-in parse failure on the session, not just in the editor', async () => {
        const previewParsing = vi.fn(() => throwError(() => ({ status: 422, error: { message: 'bad delimiter' } })));
        const { fixture, state } = await create({ name: 'x' }, { previewParsing });
        state.captureSample('s.csv', 'zzz');
        fixture.detectChanges();

        fixture.componentInstance.grammar!.test();

        expect(state.parseError()).toBeTruthy();
        expect(state.parsePreview()).toBeNull();
    });

    it('a PLUGIN test parse uses the stateless route and never feeds the sample thread', async () => {
        const preview = vi.fn(() => of({
            kind: 'tree' as const, recordCount: 2,
            nodes: [{ label: 'order', type: 'element', children: [{ label: '@id', type: 'attr', value: '1' }] }],
        }));
        const { fixture, state, api, parsers } = await create({ name: 'x' }, {}, { preview });
        state.captureSample('s.xml', '<orders><order id="1"/></orders>');
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
        expect(state.parsePreview()).toBeNull();
    });

    it('writes the nested keys the parser reads, never the flat form keys', async () => {
        const patch = vi.fn((_type: string, _name: string, _patch: Record<string, unknown>) => of(WRITE_OK));
        const { fixture } = await create({ name: 'x', parsing: { frontend: 'json', json: { format: 'array' } } }, { patch });
        const pane = fixture.componentInstance;
        fixture.detectChanges();

        pane.grammar!.schemaForm?.form.get('json__records_path')?.setValue('payload.records');
        pane.save();

        const parsing = (patch.mock.calls[0][2] as Record<string, unknown>)['parsing'] as Record<string, unknown>;
        expect((parsing['json'] as Record<string, unknown>)['records_path']).toBe('payload.records');
        expect(parsing['json__records_path']).toBeUndefined(); // the flat form must not leak to disk
    });

    it('re-selects the served parser a saved plugin config names, without dirtying the stage', async () => {
        // Matched by ingesterClass: a guided Save writes the FQCN, never the parser id.
        const { fixture, state } = await create(
            SAVED_SEGMENTS,
            { read: vi.fn(() => of(SAVED_SCHEMA)) },
            { list: vi.fn(() => of(ASN1_CATALOG)) },
        );

        expect(fixture.componentInstance.plugin()?.id).toBe('asn1');
        expect(fixture.componentInstance.pluginIngestable()).toBe(true);
        expect(state.isDirty()).toBe(false);
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
        const { fixture, state } = await create({ name: 'x' });
        fixture.componentInstance.grammar!.setType('xml');
        fixture.detectChanges();

        expect(fixture.nativeElement.textContent).toContain('flatten configuration');
        const save = Array.from(fixture.nativeElement.querySelectorAll('button'))
            .find((b) => (b as HTMLElement).textContent!.includes('Save parsing')) as HTMLButtonElement;
        expect(save.disabled).toBe(true);
        expect(state.isDirty()).toBe(false);
    });

    it('writes every segment schema BEFORE the block that references them', async () => {
        const order: string[] = [];
        const write = vi.fn(() => { order.push('write'); return of(WRITE_OK); });
        const patch = vi.fn((_type: string, _name: string, _patch: Record<string, unknown>) => {
            order.push('patch');
            return of(WRITE_OK);
        });
        const { fixture } = await create(
            SAVED_SEGMENTS,
            { read: vi.fn(() => of(SAVED_SCHEMA)), write, patch },
            { list: vi.fn(() => of(ASN1_CATALOG)) },
        );
        fixture.detectChanges();

        fixture.componentInstance.save();

        // The pipeline must never name a schema file that does not exist yet.
        expect(order).toEqual(['write', 'patch']);
        const parsing = (patch.mock.calls[0][2] as Record<string, unknown>)['parsing'] as Record<string, unknown>;
        const plugin = parsing['plugin'] as Record<string, unknown>;
        expect(plugin['ingester']).toBe(ASN1_DEF.ingesterClass);
        expect(Object.keys(plugin['segments'] as Record<string, unknown>)).toEqual(['moCallRecord']);
    });

    it('registers a dirty check with the stage nav and drops it on destroy', async () => {
        const { fixture, state } = await create({ name: 'x' });
        fixture.componentInstance.grammar!.setFrontend('json');

        expect(state.isDirty()).toBe(true);

        fixture.destroy();
        expect(state.isDirty()).toBe(false);
    });

    it('does not save at all without the workbench lens', async () => {
        const patch = vi.fn(() => of(WRITE_OK));
        const { fixture } = await create({ name: 'x' }, { patch });
        vi.spyOn(fixture.componentInstance['lens'], 'canAuthorWorkbench').mockReturnValue(false);

        fixture.componentInstance.save();

        expect(patch).not.toHaveBeenCalled();
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
