import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, ParsingPreview } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingParsingPaneComponent } from './parsing-pane.component';
import { OnboardingStateService } from './onboarding-state.service';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };
const PREVIEW: ParsingPreview = { frontend: 'delimited', columns: ['a'], rowCount: 1, rows: [{ a: '1' }], rejectedRows: 0 };
const WRITE_OK = { type: 'pipeline', written: true, path: 'x.toon', name: 'x', bytes: 1, overwritten: false, findings: [] };

function create(config: Record<string, unknown>, api: Partial<ConfigService> = {}) {
    TestBed.configureTestingModule({
        imports: [OnboardingParsingPaneComponent],
        providers: [
            provideNoopAnimations(),
            OnboardingStateService,
            { provide: ConfigService, useValue: { write: vi.fn(() => of(WRITE_OK)), previewParsing: vi.fn(() => of(PREVIEW)), ...api } },
            { provide: ToastrService, useValue: TOASTR },
        ],
    });
    const state = TestBed.inject(OnboardingStateService);
    state.config.set(config);
    const fixture = TestBed.createComponent(OnboardingParsingPaneComponent);
    fixture.detectChanges();
    return { fixture, state, api: TestBed.inject(ConfigService) };
}

describe('OnboardingParsingPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('initialises from the existing parsing block and normalises the frontend', () => {
        const { fixture } = create({ name: 'x', parsing: { frontend: 'fixed_width', fixedwidth: { fields: [{ name: 'id', start: 0, length: 3 }] } } });
        const c = fixture.componentInstance;
        expect(c.frontend()).toBe('fixedwidth');
        expect(c.fwFields.length).toBe(1);
    });

    it('adopts a saved json frontend on (re-)entering the stage, clean until touched', () => {
        // Re-entering the stage rebuilds the pane from the server-held draft: the saved frontend must
        // win, and merely rendering must NOT mark the stage dirty — a spuriously dirty pane raises the
        // rail's unsaved-changes guard and silently blocks stage navigation.
        const { fixture, state } = create({ name: 'x', parsing: { frontend: 'json', json: { format: 'newline' } } });
        expect(fixture.componentInstance.frontend()).toBe('json');
        expect(fixture.componentInstance.specs().some((s) => s.key === 'json__format')).toBe(true);
        expect(state.isDirty()).toBe(false);
    });

    it('an unknown frontend falls back to delimited (xml/asn1 are not engine-real)', () => {
        const { fixture } = create({ name: 'x', parsing: { frontend: 'xml' } });
        expect(fixture.componentInstance.frontend()).toBe('delimited');
    });

    it('shows the plugin banner instead of the editor for plugin-parsed pipelines', () => {
        const { fixture } = create({ name: 'x', processing: { ingester: 'com.example.Ing' } });
        expect(fixture.componentInstance.pluginManaged).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Plugin ingester');
        // Nothing to configure here ⇒ no sample capture either.
        expect(fixture.nativeElement.querySelector('app-onboarding-sample-panel')).toBeNull();
    });

    it('hosts the sample capture panel itself, above the file-type picker', () => {
        const { fixture } = create({ name: 'x' });
        const panel = fixture.nativeElement.querySelector('app-onboarding-sample-panel');
        expect(panel).toBeTruthy();
        const toggles = fixture.nativeElement.querySelector('mat-button-toggle-group');
        // DOCUMENT_POSITION_FOLLOWING (4) ⇒ the picker comes after the sample panel.
        expect(panel.compareDocumentPosition(toggles) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('test parse sends the merged draft + sample and stores the preview on the session', () => {
        const { fixture, state, api } = create({ name: 'x', dirs: { poll: 'in' } });
        state.captureSample('s.csv', 'a|b\n1|2\n');
        fixture.componentInstance.testParse();
        expect(api.previewParsing).toHaveBeenCalledTimes(1);
        const [draft, sample] = (api.previewParsing as ReturnType<typeof vi.fn>).mock.calls[0] as [Record<string, unknown>, string];
        expect((draft['parsing'] as Record<string, unknown>)['frontend']).toBe('delimited');
        expect(draft['dirs']).toEqual({ poll: 'in' });
        expect(sample).toBe('a|b\n1|2\n');
        expect(state.parsePreview()).toEqual(PREVIEW);
    });

    it('a failed test parse surfaces the error on the session thread', () => {
        const { fixture, state } = create(
            { name: 'x' },
            { previewParsing: vi.fn(() => throwError(() => ({ status: 422, error: { message: 'no parse' } }))) },
        );
        state.captureSample('s.csv', 'zzz');
        fixture.componentInstance.testParse();
        expect(state.parsePreview()).toBeNull();
        expect(state.parseError()).toBeTruthy();
    });

    it('switching frontend marks the pane dirty and save clears other frontend blocks', () => {
        const write = vi.fn((_type: string, _config: Record<string, unknown>, _opts?: unknown) => of(WRITE_OK));
        const { fixture, state } = create({ name: 'x', parsing: { frontend: 'json', json: { format: 'newline' } } }, { write });
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

    it('suggests the sniffed frontend and applies it on click — never automatically', () => {
        const { fixture, state } = create({ name: 'x' });
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
        const { fixture, state } = create({ name: 'x', parsing: { frontend: 'json' } });
        state.captureSample('s.psv', 'a|b\n1|2\n');
        const c = fixture.componentInstance;
        expect(c.suggestion()?.delimiter).toBe('|');
        c.applySuggestion();
        fixture.detectChanges(); // rebuild the schema-form for the delimited frontend
        await new Promise((r) => setTimeout(r)); // the prefill lands after the rebuild
        expect(c.frontend()).toBe('delimited');
        expect(c.schemaForm?.form.get('delimited__delimiter')?.value).toBe('|');
    });

    it('offers a Tree view of a JSON sample and falls back to the table otherwise', () => {
        const { fixture, state } = create({ name: 'x', parsing: { frontend: 'json' } });
        state.captureSample('s.ndjson', '{"id": 1, "meta": {"tag": "x"}}\n');
        state.parsePreview.set({ frontend: 'json', columns: ['id', 'meta'], rowCount: 1, rows: [{ id: '1' }], rejectedRows: 0 });
        const c = fixture.componentInstance;
        expect(c.treeNodes()).toBeTruthy();
        expect(c.resultView()).toBe('table');
        // Render the TREE branch (the table branch mounts the query panel, which needs the app shell's DI).
        c.resultView.set('tree');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('app-parser-tree')).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('top-level keys');
        // A non-JSON frontend has no tree offer.
        c.setFrontend('delimited');
        expect(c.treeNodes()).toBeNull();
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
