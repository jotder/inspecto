import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ParserDef, ParserPreview, ParsersService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GrammarEditorComponent } from './grammar-editor.component';

const XML: ParserDef = { id: 'xml', label: 'XML — XML file format', hierarchical: true, ingestable: false, grammarSchema: [] };
const ASN1: ParserDef = {
    id: 'asn1', label: 'ASN.1 — vendor CDR', hierarchical: true, ingestable: true,
    ingesterClass: 'com.gamma.asn.Asn1Ingester', grammarSchema: [],
};

const TABLE: ParserPreview = { kind: 'table', columns: ['id', 'msisdn'], rows: [{ id: 1, msisdn: 'x' }], rowCount: 1, rejectedRows: 0 };

function create(
    initial: Record<string, unknown> = {},
    served: ParserDef[] | 'fail' = [],
    preview: ParserPreview | 'fail' = TABLE,
): ComponentFixture<GrammarEditorComponent> {
    TestBed.configureTestingModule({
        imports: [GrammarEditorComponent],
        providers: [
            provideNoopAnimations(),
            {
                provide: ParsersService,
                useValue: {
                    list: () => (served === 'fail' ? throwError(() => new Error('offline')) : of(served)),
                    preview: vi.fn(() =>
                        preview === 'fail' ? throwError(() => ({ status: 422, error: { error: { message: 'nope' } } })) : of(preview),
                    ),
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(GrammarEditorComponent);
    fixture.componentRef.setInput('initial', initial);
    fixture.detectChanges();
    return fixture;
}

/**
 * THE Grammar editor (2026-08-04) — one surface for the Onboarding Parsing stage and the Pipelines
 * parse node. It has NO write path, so everything here is about what it hands a host and how it
 * degrades, never about persistence.
 */
describe('GrammarEditorComponent', () => {
    it('renders the four built-in frontends', () => {
        const fixture = create();

        const labels = Array.from(fixture.nativeElement.querySelectorAll('mat-button-toggle')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(labels).toEqual(expect.arrayContaining(['Delimited', 'Fixed width', 'JSON', 'Text / regex']));
    });

    it('still renders the built-ins when the parser catalog cannot be reached', () => {
        // The dialog used to show an EMPTY dropdown here; Onboarding's degrade is the one that wins.
        const fixture = create({}, 'fail');

        expect(fixture.componentInstance.served()).toBeNull();
        expect(fixture.nativeElement.querySelectorAll('mat-button-toggle').length).toBeGreaterThanOrEqual(4);
    });

    it('appends served plugins to the built-in catalog', () => {
        const fixture = create({}, [XML]);

        expect(fixture.componentInstance.pluginTypes().map((p) => p.id)).toEqual(['xml']);
    });

    it('seeds the frontend and its options from the stored block', () => {
        const fixture = create({ frontend: 'json', json: { format: 'array' } });

        expect(fixture.componentInstance.frontend()).toBe('json');
        expect(fixture.componentInstance.value()['frontend']).toBe('json');
    });

    it('normalizes the engine legacy fixed_width spelling', () => {
        expect(create({ frontend: 'fixed_width' }).componentInstance.frontend()).toBe('fixedwidth');
    });

    it('keeps the typed field values across a format switch', () => {
        // Reassigning `specs` rebuilds every control from its default and a stable `initial` is not
        // re-applied — the exact trap that silently wiped the collector form.
        const fixture = create({ frontend: 'delimited', delimited: { delimiter: '|' } });
        const c = fixture.componentInstance;

        c.setFrontend('json');
        fixture.detectChanges();
        c.setFrontend('delimited');
        fixture.detectChanges();

        expect((c.value()['delimited'] as Record<string, unknown>)['delimiter']).toBe('|');
    });

    it('clears the other frontends sub-blocks on save', () => {
        const fixture = create({ frontend: 'json', json: { format: 'array' }, text_regex: { pattern: 'x' } });

        const value = fixture.componentInstance.value();

        // `undefined` is `clearMissingRoots`' delete marker; `nullifyDeletes` turns it into an
        // explicit null only at the wire, because JSON.stringify drops undefined.
        expect('text_regex' in value).toBe(true);
        expect(value['text_regex']).toBeUndefined();
    });

    it('refuses fixed width with no field slices', () => {
        const fixture = create({ frontend: 'fixedwidth' });
        const c = fixture.componentInstance;
        c.fwFields.clear();

        expect(c.validate()).toBe(false);
        expect(c.error()).toContain('at least one field');
    });

    it('emits the preview and exposes its rows', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        const seen: ParserPreview[] = [];
        c.previewed.subscribe((p) => seen.push(p));
        c.sample = 'id,msisdn\n1,x';

        c.test();

        expect(seen).toEqual([TABLE]);
        expect(c.rows()).toEqual([{ id: 1, msisdn: 'x' }]);
    });

    it('surfaces a parse failure instead of a stale result', () => {
        const fixture = create({}, [], 'fail');
        const c = fixture.componentInstance;
        c.sample = 'garbage';

        c.test();

        expect(c.preview()).toBeNull();
        expect(c.error()).toBeTruthy();
    });

    it('uses the host preview override when one is supplied', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        const override = vi.fn(() => of(TABLE));
        c.previewFn = override;
        c.sample = 'id\n1';

        c.test();

        // Onboarding routes built-ins through /config/preview/parsing so the parsed hop feeds the
        // sample thread; the editor must not quietly use its own endpoint instead.
        expect(override).toHaveBeenCalled();
    });

    it('does not test without a sample', () => {
        const fixture = create();
        const c = fixture.componentInstance;

        c.test();

        expect(c.preview()).toBeNull();
    });

    it('offers a sniffed suggestion that differs from the current pick, and applies it', () => {
        const fixture = create({ frontend: 'json' });
        const c = fixture.componentInstance;
        c.sample = 'id|msisdn\n1|x';
        fixture.detectChanges();

        expect(c.suggestion()?.frontend).toBe('delimited');
        c.applySuggestion();

        expect(c.frontend()).toBe('delimited');
    });

    it('flags a plugin the server does not provide', () => {
        const fixture = create({}, [XML]);
        fixture.componentRef.setInput('configuredIngester', 'com.absent.Missing');
        fixture.detectChanges();

        expect(fixture.componentInstance.unservedPlugin()).toBe('com.absent.Missing');
    });

    it('re-selects a saved plugin by its ingester class, without marking the editor dirty', () => {
        // A saved config stores the FQCN, never the parser id — the id is recoverable only from the
        // served catalog. The input arrives AFTER the constructor, so the rehydrate must re-run here.
        const fixture = create({ frontend: 'plugin' }, [XML, ASN1]);
        fixture.componentRef.setInput('configuredIngester', 'com.gamma.asn.Asn1Ingester');
        fixture.detectChanges();

        expect(fixture.componentInstance.pluginDef()?.id).toBe('asn1');
        expect(fixture.componentInstance.pluginIngestable()).toBe(true);
        expect(fixture.componentInstance.isDirty()).toBe(false);
    });

    it('has no a11y violations', async () => {
        const fixture = create({}, [XML]);

        await expectNoA11yViolations(fixture.nativeElement);
    });
});
