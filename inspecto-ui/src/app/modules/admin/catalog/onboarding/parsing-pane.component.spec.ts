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

/** A served catalog: the four built-ins (schemas irrelevant here) + the preview-only XML plugin. */
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
            { provide: ConfigService, useValue: { write: vi.fn(() => of(WRITE_OK)), previewParsing: vi.fn(() => of(PREVIEW)), ...api } },
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

describe('OnboardingParsingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('initialises from the existing parsing block and normalises the frontend', async () => {
        const { fixture } = await create({ name: 'x', parsing: { frontend: 'fixed_width', fixedwidth: { fields: [{ name: 'id', start: 0, length: 3 }] } } });
        const c = fixture.componentInstance;
        expect(c.frontend()).toBe('fixedwidth');
        expect(c.fwFields.length).toBe(1);
    });

    it('adopts a saved json frontend on (re-)entering the stage, clean until touched', async () => {
        // Re-entering the stage rebuilds the pane from the server-held draft: the saved frontend must
        // win, and merely rendering must NOT mark the stage dirty — a spuriously dirty pane raises the
        // rail's unsaved-changes guard and silently blocks stage navigation.
        const { fixture, state } = await create({ name: 'x', parsing: { frontend: 'json', json: { format: 'newline' } } });
        expect(fixture.componentInstance.frontend()).toBe('json');
        expect(fixture.componentInstance.specs().some((s) => s.key === 'json__format')).toBe(true);
        expect(state.isDirty()).toBe(false);
    });

    it('an unknown frontend falls back to delimited (xml/asn1 are not engine-real)', async () => {
        const { fixture } = await create({ name: 'x', parsing: { frontend: 'xml' } });
        expect(fixture.componentInstance.frontend()).toBe('delimited');
    });

    it('shows the plugin banner instead of the editor for plugin-parsed pipelines', async () => {
        const { fixture } = await create({ name: 'x', processing: { ingester: 'com.example.Ing' } });
        expect(fixture.componentInstance.pluginManaged).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Plugin ingester');
        // Nothing to configure here ⇒ no sample capture either.
        expect(fixture.nativeElement.querySelector('app-onboarding-sample-panel')).toBeNull();
    });

    it('hosts the sample capture panel itself, above the file-type picker', async () => {
        const { fixture } = await create({ name: 'x' });
        const panel = fixture.nativeElement.querySelector('app-onboarding-sample-panel');
        expect(panel).toBeTruthy();
        const toggles = fixture.nativeElement.querySelector('mat-button-toggle-group');
        // DOCUMENT_POSITION_FOLLOWING (4) ⇒ the picker comes after the sample panel.
        expect(panel.compareDocumentPosition(toggles) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('test parse sends the merged draft + sample and stores the preview on the session', async () => {
        const { fixture, state, api } = await create({ name: 'x', dirs: { poll: 'in' } });
        state.captureSample('s.csv', 'a|b\n1|2\n');
        fixture.componentInstance.testParse();
        expect(api.previewParsing).toHaveBeenCalledTimes(1);
        const [draft, sample] = (api.previewParsing as ReturnType<typeof vi.fn>).mock.calls[0] as [Record<string, unknown>, string];
        expect((draft['parsing'] as Record<string, unknown>)['frontend']).toBe('delimited');
        expect(draft['dirs']).toEqual({ poll: 'in' });
        expect(sample).toBe('a|b\n1|2\n');
        expect(state.parsePreview()).toEqual(PREVIEW);
    });

    it('a failed test parse surfaces the error on the session thread', async () => {
        const { fixture, state } = await create(
            { name: 'x' },
            { previewParsing: vi.fn(() => throwError(() => ({ status: 422, error: { message: 'no parse' } }))) },
        );
        state.captureSample('s.csv', 'zzz');
        fixture.componentInstance.testParse();
        expect(state.parsePreview()).toBeNull();
        expect(state.parseError()).toBeTruthy();
    });

    it('switching frontend marks the pane dirty and save clears other frontend blocks', async () => {
        const write = vi.fn((_type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK));
        const { fixture, state } = await create({ name: 'x', parsing: { frontend: 'json', json: { format: 'newline' } } }, { write });
        const c = fixture.componentInstance;
        c.setFrontend('text_regex');
        fixture.detectChanges(); // propagate [specs] so the schema-form rebuilds for the new frontend
        expect(state.isDirty()).toBe(true);
        const pattern = c.schemaForm?.form.get('text_regex__pattern');
        expect(pattern).toBeTruthy();
        pattern?.setValue('(?P<a>\\d+)');
        c.save();
        const written = write.mock.calls[0][1] as Record<string, unknown>;
        const parsing = written['parsing'] as Record<string, unknown>;
        expect(parsing['frontend']).toBe('text_regex');
        expect(parsing['json']).toBeUndefined();
        expect((parsing['text_regex'] as Record<string, unknown>)['pattern']).toBe('(?P<a>\\d+)');
    });

    it('suggests the sniffed frontend and applies it on click — never automatically', async () => {
        const { fixture, state } = await create({ name: 'x' });
        const c = fixture.componentInstance;
        expect(c.suggestion()).toBeNull(); // no sample yet
        state.captureSample('s.ndjson', '{"a": 1}\n{"a": 2}\n');
        fixture.detectChanges();
        expect(c.suggestion()?.frontend).toBe('json');
        expect(c.frontend()).toBe('delimited'); // still the pick — suggestion only
        expect(fixture.nativeElement.textContent).toContain('Looks like NDJSON');
        c.applySuggestion();
        expect(c.frontend()).toBe('json');
        expect(c.suggestion()).toBeNull(); // matches the pick now — chip gone
    });

    it('applying a delimiter suggestion prefills the sniffed delimiter', async () => {
        const { fixture, state } = await create({ name: 'x', parsing: { frontend: 'json' } });
        state.captureSample('s.psv', 'a|b\n1|2\n');
        const c = fixture.componentInstance;
        expect(c.suggestion()?.delimiter).toBe('|');
        c.applySuggestion();
        fixture.detectChanges(); // rebuild the schema-form for the delimited frontend
        await new Promise((r) => setTimeout(r)); // the prefill lands after the rebuild
        expect(c.frontend()).toBe('delimited');
        expect(c.schemaForm?.form.get('delimited__delimiter')?.value).toBe('|');
    });

    it('offers a Tree view of a JSON sample and falls back to the table otherwise', async () => {
        const { fixture, state } = await create({ name: 'x', parsing: { frontend: 'json' } });
        state.captureSample('s.ndjson', '{"id": 1, "meta": {"tag": "x"}}\n');
        state.parsePreview.set({ frontend: 'json', columns: ['id', 'meta'], rowCount: 1, rows: [{ id: '1' }], rejectedRows: 0 });
        const c = fixture.componentInstance;
        expect(c.treeNodes()).toBeTruthy();
        expect(c.resultView()).toBe('table');
        // Render the TREE branch (the table branch is the shared data-table, covered by its own spec).
        c.resultView.set('tree');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('app-parser-tree')).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('top-level keys');
        // A non-JSON frontend has no tree offer.
        c.setFrontend('delimited');
        expect(c.treeNodes()).toBeNull();
    });

    it('renders the parsed table in the shared data-table, seeded FROM parsed', async () => {
        const { fixture, state } = await create({ name: 'x' });
        state.parsePreview.set(PREVIEW);
        fixture.detectChanges();
        const table = fixture.nativeElement.querySelector('inspecto-data-table');
        expect(table).toBeTruthy();
        // Pro tier over the sample rows: the SQL editor seeds `FROM parsed`, so the author
        // never needs to know a real table name.
        expect(fixture.componentInstance.parsedRows()).toEqual(PREVIEW.rows);
    });

    it('offers served plugin types beyond the built-ins, with the served grammar as the form', async () => {
        const { fixture, state } = await create({ name: 'x' });
        const c = fixture.componentInstance;
        expect(c.pluginTypes().map((p) => p.id)).toEqual(['xml']); // built-ins never duplicate
        c.setType('xml');
        fixture.detectChanges();
        expect(c.activeType()).toBe('xml');
        expect(c.specs().map((s) => s.key)).toEqual(['xml__record_element', 'xml__namespace_aware']);
        // Preview-only: Save disabled with the honest note; selection alone is not "unsaved changes".
        expect(fixture.nativeElement.textContent).toContain('flatten configuration');
        const save = Array.from(fixture.nativeElement.querySelectorAll('button'))
            .find((b) => (b as HTMLElement).textContent!.includes('Save parsing')) as HTMLButtonElement;
        expect(save.disabled).toBe(true);
        expect(state.isDirty()).toBe(false);
        // Back to a built-in restores the engine-real specs + Save.
        c.setType('delimited');
        fixture.detectChanges();
        expect(c.pluginDef()).toBeNull();
        expect(c.specs().some((s) => s.key === 'delimited__delimiter')).toBe(true);
    });

    it('plugin Test parse posts the nested grammar and renders a served TREE result', async () => {
        const preview = vi.fn(() => of({
            kind: 'tree' as const, recordCount: 2,
            nodes: [{ label: 'order', type: 'element', children: [{ label: '@id', type: 'attr', value: '1' }] }],
        }));
        const { fixture, state, parsers } = await create({ name: 'x' }, {}, { preview });
        state.captureSample('s.xml', '<orders><order id="1"/><order id="2"/></orders>');
        const c = fixture.componentInstance;
        c.setType('xml');
        fixture.detectChanges();
        c.schemaForm?.form.get('xml__record_element')?.setValue('order');
        c.testParse();
        // The nested grammar carries the touched field AND the schema's declared defaults.
        expect(parsers.preview).toHaveBeenCalledWith('xml',
            { xml: { record_element: 'order', namespace_aware: false } },
            '<orders><order id="1"/><order id="2"/></orders>');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('app-parser-tree')).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('2 records');
        // The sample thread's parsed hop stays builtin-only — a plugin preview never feeds it.
        expect(state.parsePreview()).toBeNull();
    });

    it('a plugin preview failure surfaces the 422 reason', async () => {
        const preview = vi.fn(() => throwError(() => ({ status: 422, error: { message: 'no elements match' } })));
        const { fixture, state } = await create({ name: 'x' }, {}, { preview });
        state.captureSample('s.xml', '<a/>');
        const c = fixture.componentInstance;
        c.setType('xml');
        fixture.detectChanges();
        c.testParse();
        expect(c.pluginError()).toBeTruthy();
        expect(c.pluginPreview()).toBeNull();
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
