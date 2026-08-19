import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    InspectoSchemaFieldsEditorComponent,
    SchemaFieldRow,
    narrowToSchemaType,
} from './schema-fields-editor.component';

/** n included VARCHAR rows named col_0 … col_n-1, selector = the name (a json/text_regex sample). */
function rows(n: number): SchemaFieldRow[] {
    return Array.from({ length: n }, (_, i) => ({
        include: true,
        name: `col_${i}`,
        selector: `col_${i}`,
        type: 'VARCHAR',
    }));
}

async function create(seed: SchemaFieldRow[]) {
    TestBed.configureTestingModule({
        imports: [InspectoSchemaFieldsEditorComponent],
        providers: [provideNoopAnimations()],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(InspectoSchemaFieldsEditorComponent);
    fixture.componentRef.setInput('rows', seed);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

/**
 * The shared `raw.fields[]` grid (P4-2a-i). These cases came over verbatim from
 * `onboarding/schema-mapping-pane.component.spec.ts` when the grid was extracted — P3d slice A's rule
 * that a relocation migrates its specs rather than dropping them, so the extraction stays provably
 * behaviour-neutral.
 */
describe('InspectoSchemaFieldsEditorComponent', () => {
    it('renders only one page of a wide sample and pages through the rest', async () => {
        const { fixture, c } = await create(rows(120));
        expect(c.totalCount()).toBe(120);
        expect(c.pagedEntries().length).toBe(50); // default page size, not 120 DOM rows
        expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(50);
        c.onPage({ pageIndex: 2, pageSize: 50, length: 120, previousPageIndex: 0 });
        expect(c.pagedEntries().length).toBe(20);
        expect(c.pagedEntries()[0].index).toBe(100);
    });

    it('search filters by name or source across all pages', async () => {
        const { fixture, c } = await create(rows(30));
        c.setSearch('col_1'); // col_1 + col_10..col_19
        expect(c.filteredEntries().length).toBe(11);
        expect(c.pageIndex()).toBe(0); // search resets paging
        c.setSearch('no_such_column');
        expect(c.filteredEntries().length).toBe(0);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No fields match');
    });

    it('type filter narrows to fields of the chosen type', async () => {
        const { c } = await create(rows(10));
        c.fieldRows.at(3).get('type')?.setValue('DATE');
        c.setTypeFilter('DATE');
        expect(c.filteredEntries().length).toBe(1);
        expect(c.filteredEntries()[0].index).toBe(3);
    });

    it('sorts by name and flips direction on a second click', async () => {
        const { c } = await create([
            { include: true, name: 'BANANA', selector: 'banana', type: 'VARCHAR' },
            { include: true, name: 'APPLE', selector: 'apple', type: 'VARCHAR' },
        ]);
        expect(c.pagedEntries()[0].index).toBe(0); // source order first
        c.sortBy('name');
        expect(c.pagedEntries()[0].group.get('name')?.value).toBe('APPLE');
        expect(c.ariaSort('name')).toBe('ascending');
        c.sortBy('name');
        expect(c.pagedEntries()[0].group.get('name')?.value).toBe('BANANA');
        expect(c.ariaSort('name')).toBe('descending');
    });

    it('master checkbox includes/excludes exactly the filtered rows', async () => {
        const { c } = await create(rows(20));
        c.setSearch('col_1'); // 11 of 20
        c.toggleAllVisible(false);
        expect(c.includedNames().length).toBe(9); // only the matching rows were excluded
        expect(c.visibleIncludeState()).toBe('none');
        c.setSearch('');
        expect(c.visibleIncludeState()).toBe('some');
        expect(c.form.dirty).toBe(true);
    });

    it('validate reveals the page holding an invalid row hidden by search + paging', async () => {
        const { c } = await create(rows(120));
        c.fieldRows.at(100).get('name')?.setValue('9bad'); // starts with a digit → invalid
        c.setSearch('col_2'); // the invalid row is now hidden entirely
        expect(c.validate()).toBe(false);
        expect(c.search()).toBe(''); // filters cleared…
        expect(c.pageIndex()).toBe(2); // …and jumped to the page holding row 101
        expect(String(c.problem())).toContain('101');
    });

    it('refuses a duplicate name, naming the reason for the host', async () => {
        const { c } = await create([
            { include: true, name: 'DUP', selector: '0', type: 'VARCHAR' },
            { include: true, name: 'DUP', selector: '1', type: 'VARCHAR' },
        ]);
        expect(c.validate()).toBe(false);
        expect(String(c.problem())).toContain('Duplicate field name "DUP"');
    });

    it('refuses an empty inclusion, naming the reason for the host', async () => {
        const { c } = await create(rows(3));
        c.toggleAllVisible(false);
        expect(c.validate()).toBe(false);
        expect(String(c.problem())).toContain('Include at least one field');
    });

    it('value() returns the included rows only', async () => {
        const { c } = await create(rows(3));
        c.fieldRows.at(1).get('include')?.setValue(false);
        expect(c.validate()).toBe(true);
        expect(c.value().map((r) => r.name)).toEqual(['col_0', 'col_2']);
    });

    it('renders each type with its data-format icon', async () => {
        const { c } = await create(rows(1));
        expect(c.typeIcon('VARCHAR')).toContain('bars-3-bottom-left');
        expect(c.typeIcon('DOUBLE')).toContain('hashtag');
        expect(c.typeIcon('DATE')).toContain('calendar');
        expect(c.typeIcon('TIMESTAMP')).toContain('clock');
        expect(c.typeIcon(null)).toContain('bars-3-bottom-left'); // unknown falls back to text
        // Every offered type has its own icon (a new type must bring one, not inherit text's).
        const icons = ['VARCHAR', 'DOUBLE', 'DATE', 'TIMESTAMP'].map((t) => c.typeIcon(t));
        expect(new Set(icons).size).toBe(4);
        // NOTE: rendered <mat-icon> presence is NOT asserted here — jsdom has no icon sprite, the
        // registry error aborts the trigger view, and the count reads 0 even though the browser
        // renders it. The visual check lives in the preview, not this spec.
    });

    it('re-seeding rebuilds the grid and returns it to pristine', async () => {
        const { fixture, c } = await create(rows(3));
        // `setValue` alone does NOT dirty a control — only interaction (or an explicit mark) does,
        // which is why `toggleAllVisible` marks the form itself.
        c.toggleAllVisible(false);
        expect(c.form.dirty).toBe(true);
        fixture.componentRef.setInput('rows', rows(2));
        fixture.detectChanges();
        expect(c.totalCount()).toBe(2);
        expect(c.form.dirty).toBe(false);
    });

    /**
     * D4 (P4-3): the client inference fork is gone and types come from the server, whose vocabulary is
     * WIDER than this grid's. Narrowing is what makes that retirement safe.
     */
    describe('narrowToSchemaType', () => {
        it('keeps the four types the grid offers', () => {
            expect(narrowToSchemaType('VARCHAR')).toBe('VARCHAR');
            expect(narrowToSchemaType('DOUBLE')).toBe('DOUBLE');
            expect(narrowToSchemaType('DATE')).toBe('DATE');
            expect(narrowToSchemaType('TIMESTAMP')).toBe('TIMESTAMP');
        });

        it('narrows BIGINT to DOUBLE — the engine has no integer cast, only a DOUBLE one', () => {
            expect(narrowToSchemaType('BIGINT')).toBe('DOUBLE');
        });

        it('falls back to VARCHAR for anything else, BOOLEAN included', () => {
            expect(narrowToSchemaType('BOOLEAN')).toBe('VARCHAR');
            expect(narrowToSchemaType('DECIMAL(10,2)')).toBe('VARCHAR');
            expect(narrowToSchemaType('')).toBe('VARCHAR');
        });

        it('is case- and whitespace-insensitive, as server payloads are not guaranteed tidy', () => {
            expect(narrowToSchemaType('  bigint ')).toBe('DOUBLE');
            expect(narrowToSchemaType('timestamp')).toBe('TIMESTAMP');
        });
    });

    // ── §4.3 redesign (delimited-grammar-properties U2): ①–⑤ order, icon type menu, synonym ──

    it('renders the ①–⑤ column order: include, #, Type, Name, Synonym', async () => {
        const { fixture } = await create(rows(2));
        const headers = Array.from(fixture.nativeElement.querySelectorAll('thead th')).map((h) =>
            (h as HTMLElement).textContent?.trim(),
        );
        // Header ① is the master checkbox (no text); no Source column for positional frontends.
        expect(headers).toHaveLength(5);
        expect(headers[1]).toContain('#');
        expect(headers[2]).toContain('Type');
        expect(headers[3]).toContain('Name');
        expect(headers[4]).toContain('Synonym');
    });

    it('shows the Source column only for name-based frontends', async () => {
        const { fixture } = await create(rows(2));
        fixture.componentRef.setInput('nameBasedSelectors', true);
        fixture.detectChanges();
        const headers = Array.from(fixture.nativeElement.querySelectorAll('thead th')).map((h) =>
            (h as HTMLElement).textContent?.trim(),
        );
        expect(headers).toHaveLength(6);
        expect(headers[5]).toContain('Source');
    });

    it('replaces the type dropdown with an icon-only menu button, labelled and operable', async () => {
        const { fixture, c } = await create(rows(1));
        const btn = fixture.nativeElement.querySelector(
            'tbody button[aria-label^="Column type:"]',
        ) as HTMLButtonElement;
        expect(btn).toBeTruthy();
        expect(btn.getAttribute('aria-label')).toBe('Column type: VARCHAR — change');
        expect(fixture.nativeElement.querySelector('tbody mat-select')).toBeNull();

        // The menu picks a type onto the row's control and dirties the form.
        c.setType(c.fieldRows.at(0), 'DOUBLE');
        fixture.detectChanges();
        expect(c.fieldRows.at(0).get('type')?.value).toBe('DOUBLE');
        expect(c.form.dirty).toBe(true);
        expect(btn.getAttribute('aria-label')).toBe('Column type: DOUBLE — change');
    });

    it('disables the type menu in Auto mode', async () => {
        const { fixture } = await create(rows(1));
        fixture.componentRef.setInput('autoTypes', true);
        fixture.detectChanges();
        const btn = fixture.nativeElement.querySelector(
            'tbody button[aria-label^="Column type:"]',
        ) as HTMLButtonElement;
        expect(btn.disabled).toBe(true);
    });

    it('refuses a synonym duplicating another column name, and RENDERS the row error', async () => {
        const { fixture, c } = await create([
            { include: true, name: 'AMOUNT', selector: '0', type: 'VARCHAR' },
            { include: true, name: 'QTY', selector: '1', type: 'VARCHAR', synonym: 'AMOUNT' },
        ]);
        expect(c.validate()).toBe(false);
        expect(String(c.problem())).toContain('Duplicate synonym "AMOUNT"');
        fixture.detectChanges();
        // The error must reach the screen, not just the problem signal (the invisible-list-error rule).
        const err = fixture.nativeElement.querySelector('mat-error');
        expect(err?.textContent).toContain('Synonym must be unique');
    });

    it('refuses a synonym duplicating another synonym', async () => {
        const { c } = await create([
            { include: true, name: 'A', selector: '0', type: 'VARCHAR', synonym: 'ALIAS' },
            { include: true, name: 'B', selector: '1', type: 'VARCHAR', synonym: 'ALIAS' },
        ]);
        expect(c.validate()).toBe(false);
        expect(String(c.problem())).toContain('Duplicate synonym "ALIAS"');
    });

    it('value() carries the synonym and drops an empty one', async () => {
        // A PADDED synonym fails the identifier pattern in validate() — the same rule the Name
        // column applies — so trimming happens on honest values, not as a laundering step.
        const { c } = await create([
            { include: true, name: 'A', selector: '0', type: 'VARCHAR', synonym: 'cust_no' },
            { include: true, name: 'B', selector: '1', type: 'VARCHAR' },
        ]);
        expect(c.validate()).toBe(true);
        const v = c.value();
        expect(v[0].synonym).toBe('cust_no');
        expect('synonym' in v[1]).toBe(false);
    });

    it('search also matches synonyms', async () => {
        const { c } = await create([
            { include: true, name: 'A', selector: '0', type: 'VARCHAR', synonym: 'CUSTNO' },
            { include: true, name: 'B', selector: '1', type: 'VARCHAR' },
        ]);
        c.setSearch('custno');
        expect(c.filteredEntries().length).toBe(1);
        expect(c.filteredEntries()[0].index).toBe(0);
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create(rows(3));
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
