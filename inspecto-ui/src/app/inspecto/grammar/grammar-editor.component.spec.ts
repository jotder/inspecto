import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ParserDef, ParserPreview, ParsersService } from 'app/inspecto/api';
import { DataTableComponent } from 'app/inspecto/data-table';
import { INSPECTO_GRID_DARK, InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { GrammarEditorComponent } from './grammar-editor.component';

/** Minimal host for asserting content projected into [sampleCsvActions] (S3). */
@Component({
    standalone: true,
    imports: [GrammarEditorComponent],
    template: `
        <inspecto-grammar-editor [initial]="{}">
            <button id="export-btn" sampleCsvActions type="button">Export</button>
        </inspecto-grammar-editor>
    `,
})
class ProjectionHost {}

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
            // The Parsed tab's <inspecto-data-table tier="pro"> injects the real grid theme service —
            // only needed when a test actually renders a preview (grammar-editor.dialog.spec.ts precedent).
            { provide: InspectoGridThemeService, useValue: { theme: () => INSPECTO_GRID_DARK } },
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

    // ── the collapsible delimited surface (parse-pane-redesign-plan S2, replacing the 4-tab §4.1 U1) ──

    it('renders the delimited spec set as 3 collapsible sections, each ONE flat schema-form', () => {
        const fixture = create({ frontend: 'delimited' });
        const el = fixture.nativeElement as HTMLElement;
        const labels = Array.from(el.querySelectorAll('mat-panel-title')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        // Files & metadata is dissolved (redesign D2/R5): compression lives on Dialect now.
        expect(labels).toHaveLength(3);
        // R12: the sections speak the approved mockup's language, not the engine's.
        expect(labels[0]).toContain('How the file is written');
        expect(labels[1]).toContain('How values are understood');
        expect(labels[2]).toContain('When a row looks wrong');
        expect(labels.join(' ')).not.toContain('Files');
        // R1: one schema-form per section, flat — no tier disclosures, no per-row reset/sample column.
        expect(el.querySelectorAll('inspecto-schema-form')).toHaveLength(3);
        expect(el.textContent).not.toContain('Optional settings');
        expect(el.querySelector('[aria-label="Show advanced settings"]')).toBeNull();
        expect(el.querySelector('[aria-label^="Reset "]')).toBeNull();
        // R5: the Row filter (SQL) is a transform.filter Step, not a parse property.
        expect(el.textContent).not.toContain('Row filter');
        expect(fixture.componentInstance.controlFor('delimited__where')).toBeNull();
        // …while the moved Files key is still authored, on Dialect.
        expect(fixture.componentInstance.controlFor('compression')).toBeTruthy();
    });

    it('renders the xlsx spec set as 3 sections', () => {
        // multiformat X4: the first section speaks the format's own language (grammarTabsFor).
        const fixture = create({ frontend: 'xlsx', xlsx: { sheet: 'Data' } });
        const labels = Array.from(fixture.nativeElement.querySelectorAll('mat-panel-title')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(labels).toHaveLength(3);
        expect(labels[0]).toContain('Sheet & range');
        expect(labels[1]).toContain('How values are understood');
        expect(labels[2]).toContain('When a row looks wrong');
        // The seeded sheet survives value() from the (collapsed) first section (S2's mounted-panels
        // rule, xlsx edition).
        const xlsx = fixture.componentInstance.value()['xlsx'] as Record<string, unknown>;
        expect(xlsx['sheet']).toBe('Data');
    });

    /**
     * S4 — the derived schema lands on the Types section, which on a sectioned format is not
     * necessarily open. `showTab` is how the host reveals (expands) it, and it must be a NO-OP for an
     * unsectioned format so a host may ask without first knowing which formats are sectioned.
     */
    it('showTab expands a sectioned set by id, and ignores an unknown one', () => {
        const c = create({ frontend: 'delimited' }).componentInstance;
        expect(c.tabbed).toBe(true);
        c.showTab('types');
        expect(c.isExpanded('types')).toBe(true);
        c.showTab('nope');
        expect(c.isExpanded('types')).toBe(true); // an unknown id changes nothing
    });

    /** ⚠ One TestBed per test, so the unsectioned arm is its own case. */
    it('showTab is a no-op for an unsectioned format', () => {
        const c = create({ frontend: 'text_regex' }).componentInstance;
        expect(c.tabbed).toBe(false);
        c.showTab('types');
        expect(c.isExpanded('types')).toBe(false);
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

    it('renders an unsectioned spec set (text_regex) flat, exactly as before', () => {
        const flat = create({ frontend: 'text_regex' });
        expect(flat.nativeElement.querySelector('mat-accordion')).toBeNull();
        expect(flat.nativeElement.querySelectorAll('inspecto-schema-form')).toHaveLength(1);
    });

    it('sections the json spec set with Format & records first (J1)', () => {
        const json = create({ frontend: 'json', json: { format: 'auto' } });
        const jsonLabels = Array.from(json.nativeElement.querySelectorAll('mat-panel-title')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(jsonLabels[0]).toContain('Format & records');
    });

    it('sections fixedwidth with Record layout first, the slice table homed into section 1 (F1)', () => {
        const fw = create({
            frontend: 'fixedwidth',
            fixedwidth: { fields: [{ name: 'ID', start: 0, length: 6 }] },
        });
        const fwLabels = Array.from(fw.nativeElement.querySelectorAll('mat-panel-title')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(fwLabels[0]).toContain('Record layout');
        // The slice table renders INSIDE the first mat-expansion-panel (one template, two mounts) —
        // and stays readable by value() because panel content is mounted directly, never behind
        // `matExpansionPanelContent`'s lazy template (S2's replacement for the old R9 rule).
        const panel = fw.nativeElement.querySelector('mat-expansion-panel');
        expect(panel?.querySelector('[formarrayname="fields"], [formArrayName="fields"]')).not.toBeNull();
        const fwValue = fw.componentInstance.value()['fixedwidth'] as Record<string, unknown>;
        expect((fwValue['fields'] as unknown[]).length).toBe(1);
    });

    it('keeps every section MOUNTED so value() sees an unopened section', () => {
        // mat-expansion-panel content is placed directly (not via matExpansionPanelContent), so it
        // stays in the DOM whether open or collapsed — a save must still carry a never-opened
        // section's seeded values.
        const fixture = create({
            frontend: 'delimited',
            delimited: { delimiter: '|', rejects_limit: 5 },
        });

        expect(fixture.nativeElement.querySelectorAll('inspecto-schema-form')).toHaveLength(3);
        const delimited = fixture.componentInstance.value()['delimited'] as Record<string, unknown>;
        expect(delimited['delimiter']).toBe('|');
        expect(delimited['rejects_limit']).toBe(5); // seeded on the collapsed Robustness section
    });

    /**
     * 🔴 LOAD-BEARING (S2) — the exact defect R9 existed to prevent, now proven WITHOUT the hack.
     * `mat-expansion-panel` content is placed directly in this template (never wrapped in
     * `<ng-template matExpansionPanelContent>`, Material's own lazy-mount mechanism), so Angular
     * Material keeps it mounted whether the panel is expanded or collapsed. This test types a value
     * into a section that is NEVER opened and confirms it survives `value()` — the read every host's
     * save goes through — proving the mounting theory rather than assuming it.
     */
    it('a value typed into a COLLAPSED, never-opened section survives value() and a save round-trip', () => {
        const fixture = create({ frontend: 'delimited' });
        const c = fixture.componentInstance;

        // Only Dialect (the first section) opens by default — Robustness is never expanded.
        expect(c.isExpanded('robustness')).toBe(false);

        // rejects_limit lives on Robustness; controlFor resolves it through that section's schema-form.
        const control = c.controlFor('delimited__rejects_limit');
        expect(control).toBeTruthy();
        control!.setValue(25);
        control!.markAsDirty();

        expect(c.isExpanded('robustness')).toBe(false); // still collapsed — nothing opened it

        // value() is exactly what a host reads to persist (the "save").
        const delimited = c.value()['delimited'] as Record<string, unknown>;
        expect(delimited['rejects_limit']).toBe(25);
    });

    it('validate() expands every failing section, not just the first', () => {
        const fixture = create({ frontend: 'delimited' });
        const c = fixture.componentInstance;
        // filter_target_column lives on Robustness; min is 0.
        c.controlFor('delimited__filter_target_column')?.setValue(-1);

        expect(c.isExpanded('robustness')).toBe(false);
        expect(c.validate()).toBe(false);
        expect(c.isExpanded('robustness')).toBe(true);
    });

    it('shows a warn indicator on a section header holding a validation failure', () => {
        const fixture = create({ frontend: 'delimited' });
        const c = fixture.componentInstance;
        c.controlFor('delimited__filter_target_column')?.setValue(-1);
        c.validate();
        fixture.detectChanges();

        expect(c.sectionBadges()[2].invalid).toBe(true); // Robustness is section index 2
        const warn = fixture.nativeElement.querySelector('[aria-label="This section has an invalid value"]');
        expect(warn).not.toBeNull();
    });

    it('shows a count badge for values set away from default', () => {
        const fixture = create({ frontend: 'delimited', delimited: { quote: "'" } });
        fixture.detectChanges();

        // quote has no default, so seeding it counts as one set value on the Dialect section.
        expect(fixture.componentInstance.sectionBadges()[0].set).toBeGreaterThanOrEqual(1);
        // delimiter ',' and has_header true ARE the defaults — they must not inflate the count.
        expect(fixture.componentInstance.sectionBadges()[0].set).toBe(1);
    });

    it('normalizes a legacy comma-joined null_strings string into list chips', () => {
        // The pre-section UI wrote null_strings as a comma-joined STRING; the engine's strList reads
        // both. Seeding the list control with the raw string would render empty chips and a save
        // would silently drop the stored value.
        const fixture = create({ frontend: 'delimited', delimited: { null_strings: 'NULL,N/A' } });

        const delimited = fixture.componentInstance.value()['delimited'] as Record<string, unknown>;
        expect(delimited['null_strings']).toEqual(['NULL', 'N/A']);
    });

    /** Each panel's title carries its own accessible NAME, asserted via `aria-label` — never
     *  Material's own panel roles/classes (the standing spec trap this surface has always carried). */
    it('names every section header for assistive tech', () => {
        const fixture = create({ frontend: 'delimited' });
        const names = Array.from(fixture.nativeElement.querySelectorAll('mat-panel-title[aria-label]')).map((t) =>
            ((t as HTMLElement).getAttribute('aria-label') ?? '').trim(),
        );
        expect(names).toHaveLength(3);
        for (const n of names) expect(n.length).toBeGreaterThan(0);
    });

    it('has no a11y violations on the sectioned surface', async () => {
        const fixture = create({ frontend: 'delimited' });

        await expectNoA11yViolations(fixture.nativeElement);
    });

    // ── S3 (parse-pane redesign): Sample | Parsed tabs ──────────────────────────────

    it('renders the Sample and Parsed tabs when sampleMode is own', () => {
        const fixture = create();
        const labels = Array.from(fixture.nativeElement.querySelectorAll('.mat-mdc-tab .mdc-tab__text-label')).map(
            (e) => (e as HTMLElement).textContent?.trim(),
        );
        expect(labels).toEqual(['Sample', 'Parsed']);
    });

    it('does not render Sample/Parsed tabs when the host owns the sample', () => {
        const fixture = create();
        fixture.componentRef.setInput('sampleMode', 'host');
        fixture.detectChanges();

        expect(fixture.nativeElement.querySelector('mat-tab-group')).toBeNull();
    });

    /**
     * The merged textarea reuses {@link GrammarEditorComponent.onSampleText} verbatim — the same
     * rule that already cleared captured bytes for an xlsx file sample. Typing after a captured
     * workbook must clear `sampleB64` exactly as before, now proven through the merged Sample tab.
     */
    it('typing into the merged Sample textarea clears any previously captured file bytes', () => {
        const fixture = create({ frontend: 'xlsx' });
        const c = fixture.componentInstance;
        c.sampleB64.set('d29ya2Jvb2s=');
        fixture.detectChanges();

        const textarea = fixture.nativeElement.querySelector('textarea[aria-label="Sample content"]') as HTMLElement;
        expect(textarea).toBeTruthy();
        textarea.dispatchEvent(new Event('focus'));
        (textarea as HTMLTextAreaElement).value = 'a,b\n1,2';
        textarea.dispatchEvent(new Event('input'));

        expect(c.sampleB64()).toBeNull();
        expect(c.sampleText()).toBe('a,b\n1,2');
    });

    it('the Sample tab projects a host [sampleCsvActions] button above the textarea', () => {
        TestBed.configureTestingModule({
            imports: [ProjectionHost],
            providers: [
                provideNoopAnimations(),
                { provide: ParsersService, useValue: { list: () => of([]), preview: vi.fn(() => of(TABLE)) } },
            ],
        });
        const fixture = TestBed.createComponent(ProjectionHost);
        fixture.detectChanges();

        const btn = fixture.nativeElement.querySelector('#export-btn');
        expect(btn).toBeTruthy();
        // Projected content lands inside the Sample tab body, above the textarea (DOM order).
        const textarea = fixture.nativeElement.querySelector('textarea[aria-label="Sample content"]');
        expect(btn!.compareDocumentPosition(textarea!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    // ── S3: Parsed tab page size ─────────────────────────────────────────────────

    it('defaults the Parsed page size to 10 and offers 10/25/50/100', () => {
        const fixture = create();
        const c = fixture.componentInstance;

        expect(c.pageSize()).toBe(10);
        expect(c.pageSizeOptions).toEqual([10, 25, 50, 100]);
    });

    it('page size selection persists across a re-parse', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        c.sample = 'id,msisdn\n1,x';

        c.pageSize.set(50);
        c.test();
        fixture.detectChanges();

        expect(c.pageSize()).toBe(50);
        expect(fixture.componentInstance.preview()).not.toBeNull();
    });

    it('the Parsed tab data table receives the selected page size', () => {
        const fixture = create();
        const c = fixture.componentInstance;
        c.sample = 'id,msisdn\n1,x';
        c.pageSize.set(25);
        c.test();
        fixture.detectChanges();

        // MatTab lazily mounts an inactive tab's body — activate "Parsed" before it renders the
        // data-table, the same reason S2's expansion panels (not tabs) host the property forms.
        const tabLabels = Array.from(
            fixture.nativeElement.querySelectorAll('.mat-mdc-tab .mdc-tab__text-label'),
        ) as HTMLElement[];
        tabLabels
            .find((l) => l.textContent?.trim() === 'Parsed')
            ?.closest('.mat-mdc-tab')
            ?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
        fixture.detectChanges();

        const table = fixture.debugElement.query(By.directive(DataTableComponent))?.componentInstance;
        expect(table?.pageSize()).toBe(25);
    });
});
