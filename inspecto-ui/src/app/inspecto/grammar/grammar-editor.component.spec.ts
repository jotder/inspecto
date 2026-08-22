import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ParserDef, ParserPreview, ParsersService } from 'app/inspecto/api';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GrammarEditorComponent } from './grammar-editor.component';

const XML: ParserDef = {
    id: 'xml',
    label: 'XML — XML file format',
    hierarchical: true,
    ingestable: false,
    grammarSchema: [],
};
const ASN1: ParserDef = {
    id: 'asn1',
    label: 'ASN.1 — vendor CDR',
    hierarchical: true,
    ingestable: true,
    ingesterClass: 'com.gamma.asn.Asn1Ingester',
    grammarSchema: [],
};

const TABLE: ParserPreview = {
    kind: 'table',
    columns: ['id', 'msisdn'],
    rows: [{ id: 1, msisdn: 'x' }],
    rowCount: 1,
    rejectedRows: 0,
};

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
                        preview === 'fail'
                            ? throwError(() => ({ status: 422, error: { error: { message: 'nope' } } }))
                            : of(preview),
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

    /**
     * 🔴 Found by driving the real UI: a fixed-width test parse ALWAYS failed with "fixed width needs
     * at least one field", whatever the operator typed. `test()` sent {@link grammar} — the property
     * sheet alone — while the slice table lives in its own `fwFields` FormArray that only
     * {@link value} injects. The request therefore carried no `fixedwidth.fields` at all.
     */
    it('sends the fixed-width slice table with the test parse', () => {
        const fixture = create({ frontend: 'fixedwidth' });
        const c = fixture.componentInstance;
        c.fwFields.clear();
        c.addField();
        c.fwFields.at(0).patchValue({ name: 'ACCOUNT', start: 0, length: 7 });
        c.sample = 'ACC0001rest';

        const sent: Record<string, unknown>[] = [];
        c.previewFn = (_t, grammar) => {
            sent.push(grammar);
            return of(TABLE);
        };

        c.test();

        const fw = sent[0]?.['fixedwidth'] as { fields?: unknown[] } | undefined;
        expect(fw?.fields).toEqual([{ name: 'ACCOUNT', start: 0, length: 7 }]);
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

    it('does not treat selecting a preview-only plugin as an unsaved change', () => {
        // Nothing about a preview-only plugin can be saved, so nothing can be lost by navigating —
        // and a host that saw this as dirty would raise a guard the operator cannot satisfy.
        const fixture = create({}, [XML, ASN1]);
        const c = fixture.componentInstance;

        c.setType('xml');

        expect(c.isDirty()).toBe(false);
    });

    it('treats selecting an INGESTABLE plugin as a real edit', () => {
        const fixture = create({}, [XML, ASN1]);
        const c = fixture.componentInstance;

        c.setType('asn1');

        expect(c.isDirty()).toBe(true);
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

    // ── the 4-tab delimited surface (delimited-grammar-properties plan §4.1, U1) ──

    it('renders the delimited spec set as 4 tabs', () => {
        const fixture = create({ frontend: 'delimited' });
        const labels = Array.from(fixture.nativeElement.querySelectorAll('.mat-mdc-tab')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(labels).toHaveLength(4);
        expect(labels[0]).toContain('Dialect');
        expect(labels[1]).toContain('Types');
        expect(labels[2]).toContain('Robustness');
        expect(labels[3]).toContain('Files & MetaData');
    });

    it('renders the xlsx spec set as 4 tabs, files anchored despite carrying no xlsx option', () => {
        // multiformat X4: the first tab speaks the format's own language (grammarTabsFor), and the
        // 'files' tab renders even with zero specs — it anchors the Collection pointer and the
        // host's [tabFiles] projection (the column-metadata grid).
        const fixture = create({ frontend: 'xlsx', xlsx: { sheet: 'Data' } });
        const labels = Array.from(fixture.nativeElement.querySelectorAll('.mat-mdc-tab')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(labels).toHaveLength(4);
        expect(labels[0]).toContain('Sheet & range');
        expect(labels[1]).toContain('Types');
        expect(labels[2]).toContain('Robustness');
        expect(labels[3]).toContain('Files & MetaData');
        // The seeded sheet survives value() from tab 1 (the R9 mounted-panels rule, xlsx edition).
        const xlsx = fixture.componentInstance.value()['xlsx'] as Record<string, unknown>;
        expect(xlsx['sheet']).toBe('Data');
    });

    /**
     * S4 — the derived schema lands on the Types tab, which on a tabbed format is not the tab the
     * operator is looking at. `showTab` is how the host reveals it, and it must be a NO-OP for an
     * untabbed format so a host may ask without first knowing which formats are tabbed.
     */
    it('showTab steers a tabbed set by id, and ignores an unknown one', () => {
        const c = create({ frontend: 'delimited' }).componentInstance;
        expect(c.tabbed).toBe(true);
        c.showTab('types');
        expect(c.activeTab()).toBe(1);
        c.showTab('nope');
        expect(c.activeTab()).toBe(1); // an unknown id changes nothing
    });

    /** ⚠ One TestBed per test, so the untabbed arm is its own case. */
    it('showTab is a no-op for an untabbed format', () => {
        const c = create({ frontend: 'text_regex' }).componentInstance;
        expect(c.tabbed).toBe(false);
        c.showTab('types');
        expect(c.activeTab()).toBe(0);
    });

    /**
     * S4 — with a host-owned sample the host renders the parse verb beside the sample chips, so the
     * editor must not render a SECOND one. The `own` arm keeps it: there would otherwise be no way to
     * parse at all.
     */
    it('hides its own Test parse button when the host owns the sample', () => {
        const fixture = create({ frontend: 'delimited' });
        // ⚠ Assert the BUTTON, not the page text: a hint elsewhere in the pane names Test parse too,
        // so a textContent check passes while the duplicate button is still on screen.
        const button = () =>
            Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
                (b) => b.textContent?.trim() === 'Test parse',
            );
        expect(button()).toBeTruthy();

        fixture.componentRef.setInput('sampleMode', 'host');
        fixture.detectChanges();
        expect(button()).toBeUndefined();
    });

    it('renders an untabbed spec set (text_regex) flat, exactly as before', () => {
        const flat = create({ frontend: 'text_regex' });
        expect(flat.nativeElement.querySelector('mat-tab-group')).toBeNull();
        expect(flat.nativeElement.querySelectorAll('inspecto-schema-form')).toHaveLength(1);
    });

    it('tabs the json spec set with Format & records first (J1)', () => {
        const json = create({ frontend: 'json', json: { format: 'auto' } });
        const jsonLabels = Array.from(json.nativeElement.querySelectorAll('.mat-mdc-tab')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(jsonLabels[0]).toContain('Format & records');
    });

    it('tabs fixedwidth with Record layout first, the slice table homed into tab 1 (F1)', () => {
        const fw = create({
            frontend: 'fixedwidth',
            fixedwidth: { fields: [{ name: 'ID', start: 0, length: 6 }] },
        });
        const fwLabels = Array.from(fw.nativeElement.querySelectorAll('.mat-mdc-tab')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(fwLabels[0]).toContain('Record layout');
        // The slice table renders INSIDE the tabbed shell now (one template, two mounts) — and stays
        // readable by value() since panels are [hidden]-mounted, never MatTab bodies (the R9 rule).
        // ⚠ mat-tab-group renders its own (empty) [role="tabpanel"] bodies first — OUR panels are the
        // [hidden]-toggled divs labelled by tab, so select by the label or the assertion tests Material.
        const panel = fw.nativeElement.querySelector('[role="tabpanel"][aria-label="Record layout"]');
        expect(panel?.querySelector('[formarrayname="fields"], [formArrayName="fields"]')).not.toBeNull();
        const fwValue = fw.componentInstance.value()['fixedwidth'] as Record<string, unknown>;
        expect((fwValue['fields'] as unknown[]).length).toBe(1);
    });

    it('keeps every tab panel MOUNTED so value() sees unvisited tabs', () => {
        // MatTab bodies instantiate on first activation — the panels live OUTSIDE them, [hidden]-
        // toggled (the R9 rule). A save from tab 1 must still carry tab 3's seeded values.
        const fixture = create({
            frontend: 'delimited',
            delimited: { delimiter: '|', where: 'amount > 0' },
        });

        expect(fixture.nativeElement.querySelectorAll('inspecto-schema-form')).toHaveLength(4);
        const delimited = fixture.componentInstance.value()['delimited'] as Record<string, unknown>;
        expect(delimited['delimiter']).toBe('|');
        expect(delimited['where']).toBe('amount > 0');
    });

    it('validate() steers to the first failing tab', () => {
        const fixture = create({ frontend: 'delimited' });
        const c = fixture.componentInstance;
        const forms = fixture.debugElement
            .queryAll(By.directive(InspectoSchemaFormComponent))
            .map((d) => d.componentInstance as InspectoSchemaFormComponent);
        // filter_target_column lives on the Robustness tab (index 2); min is 0.
        forms[2].form.get('delimited__filter_target_column')?.setValue(-1);

        expect(c.validate()).toBe(false);
        expect(c.activeTab()).toBe(2);
    });

    it('shows a count badge for values set away from default', () => {
        const fixture = create({ frontend: 'delimited', delimited: { quote: "'" } });
        fixture.detectChanges();

        // quote has no default, so seeding it counts as one set value on the Dialect tab.
        expect(fixture.componentInstance.tabBadges()[0].set).toBeGreaterThanOrEqual(1);
        // delimiter ',' and has_header true ARE the defaults — they must not inflate the count.
        expect(fixture.componentInstance.tabBadges()[0].set).toBe(1);
    });

    it('normalizes a legacy comma-joined null_strings string into list chips', () => {
        // The pre-tab UI wrote null_strings as a comma-joined STRING; the engine's strList reads
        // both. Seeding the list control with the raw string would render empty chips and a save
        // would silently drop the stored value.
        const fixture = create({ frontend: 'delimited', delimited: { null_strings: 'NULL,N/A' } });

        const delimited = fixture.componentInstance.value()['delimited'] as Record<string, unknown>;
        expect(delimited['null_strings']).toEqual(['NULL', 'N/A']);
    });

    /** S6: each tab header carries its own accessible NAME — they surfaced unnamed beside their
     *  badge spans in the accessibility tree (plan §1.5). */
    it('names every tab header for assistive tech', () => {
        const fixture = create({ frontend: 'delimited' });
        const names = Array.from(fixture.nativeElement.querySelectorAll('[role="tab"]')).map((t) =>
            ((t as HTMLElement).getAttribute('aria-label') ?? '').trim(),
        );
        expect(names).toHaveLength(4);
        for (const n of names) expect(n.length).toBeGreaterThan(0);
    });

    it('has no a11y violations on the tabbed surface', async () => {
        const fixture = create({ frontend: 'delimited' });

        await expectNoA11yViolations(fixture.nativeElement);
    });
});
