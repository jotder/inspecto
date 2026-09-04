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
        expect(fixture.nativeElement.textContent).toContain('No columns match');
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
        it('keeps every type the grid offers, verbatim', () => {
            for (const t of ['VARCHAR', 'DOUBLE', 'DATE', 'TIMESTAMP', 'BOOLEAN', 'INTEGER', 'HUGEINT', 'UUID']) {
                expect(narrowToSchemaType(t)).toBe(t);
            }
        });

        /**
         * 🔴 Was `BIGINT → DOUBLE`: honest while `TransformCompiler.direct()` cast only DOUBLE, but
         * LOSSY above 2^53 — exactly the range long numeric identifiers live in. The engine casts
         * every DuckDB scalar type since 2026-08-22, so the inferred type is kept.
         */
        it('keeps BIGINT rather than collapsing it into DOUBLE (precision loss above 2^53)', () => {
            expect(narrowToSchemaType('BIGINT')).toBe('BIGINT');
        });

        it('keeps a parameterised DECIMAL, normalising its spelling', () => {
            expect(narrowToSchemaType('DECIMAL(10,2)')).toBe('DECIMAL(10,2)');
            expect(narrowToSchemaType('decimal( 18 , 4 )')).toBe('DECIMAL(18,4)');
            // A bare DECIMAL has no precision to keep — land on the default the cell then exposes.
            expect(narrowToSchemaType('DECIMAL')).toBe('DECIMAL(18,2)');
        });

        /**
         * ⚠ Still VARCHAR, but for a NEW reason: the engine now REFUSES an unknown type at config
         * load, so passing a server quirk through would author a config that cannot load.
         */
        it('falls back to VARCHAR for a type the engine does not honour', () => {
            expect(narrowToSchemaType('STRUCT(a INT)')).toBe('VARCHAR');
            expect(narrowToSchemaType('JSON')).toBe('VARCHAR');
            expect(narrowToSchemaType('')).toBe('VARCHAR');
        });

        it('is case- and whitespace-insensitive, as server payloads are not guaranteed tidy', () => {
            expect(narrowToSchemaType('  bigint ')).toBe('BIGINT');
            expect(narrowToSchemaType('timestamp')).toBe('TIMESTAMP');
        });
    });

    // ── §4.3 redesign (delimited-grammar-properties U2): ①–⑤ order, icon type menu, synonym ──

    it('renders the mockup\'s column order (R11): Use, #, Name, Type, Sample value, Also known as', async () => {
        const { fixture } = await create(rows(2));
        const el = fixture.nativeElement as HTMLElement;
        const headers = Array.from(el.querySelectorAll('thead th')).map((h) => (h as HTMLElement).textContent?.trim());
        // Header ① is the master checkbox (sr-only "Use"); no Selector column for positional frontends.
        expect(headers).toHaveLength(6);
        expect(headers[0]).toContain('Use');
        expect(headers[1]).toContain('#');
        expect(headers[2]).toContain('Name');
        expect(headers[3]).toContain('Type');
        expect(headers[4]).toContain('Sample value');
        expect(headers[5]).toContain('Also known as');
        expect(el.textContent).toContain('Columns that come out');
        expect(el.textContent).toContain('2 columns');
    });

    it('shows the first parsed value per column by selector, "—" when the sample has none', async () => {
        const { fixture } = await create(rows(2));
        fixture.componentRef.setInput('sampleValues', { col_0: ' Anna Kowalski ' });
        fixture.detectChanges();
        const samples = Array.from(fixture.nativeElement.querySelectorAll('tbody tr td:nth-child(5)')).map((td) =>
            (td as HTMLElement).textContent?.trim(),
        );
        expect(samples).toEqual(['Anna Kowalski', '—']);
        // The full value survives in the title (the cell truncates).
        expect(fixture.nativeElement.querySelector('tbody tr td:nth-child(5) span')?.getAttribute('title')).toBe(
            ' Anna Kowalski ',
        );
    });

    it('appends the filename column as a read-only last row that never reaches value()', async () => {
        const { fixture, c } = await create(rows(2));
        expect(fixture.nativeElement.querySelector('[data-filename-row]')).toBeNull();
        fixture.componentRef.setInput('filenameColumn', { name: 'file_name', sample: 'orders.csv' });
        fixture.detectChanges();
        const row = fixture.nativeElement.querySelector('[data-filename-row]') as HTMLElement;
        expect(row).toBeTruthy();
        expect(row.textContent).toContain('file_name');
        expect(row.textContent).toContain('orders.csv');
        expect(row.textContent).toContain('2'); // # = next position after the two real rows
        expect((row.querySelector('mat-checkbox input') as HTMLInputElement).disabled).toBe(true);
        expect(fixture.nativeElement.querySelectorAll('tbody tr')).toHaveLength(3);
        expect(c.value().map((r) => r.name)).toEqual(['col_0', 'col_1']);
        // The search filters it like any other row.
        c.setSearch('col_');
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-filename-row]')).toBeNull();
    });

    it('shows the Selector column only for name-based frontends', async () => {
        const { fixture } = await create(rows(2));
        fixture.componentRef.setInput('nameBasedSelectors', true);
        fixture.detectChanges();
        const headers = Array.from(fixture.nativeElement.querySelectorAll('thead th')).map((h) =>
            (h as HTMLElement).textContent?.trim(),
        );
        expect(headers).toHaveLength(7);
        expect(headers[6]).toContain('Selector');
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
        expect(err?.textContent).toContain('Must be unique across aliases and names');
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

    // ── source time zone (S2) ─────────────────────────────────────────────────

    it('hides the Source zone column entirely when no column carries an instant', async () => {
        const { fixture, c } = await create(rows(3));
        expect(c.anyTemporal()).toBe(false);
        expect(fixture.nativeElement.textContent).not.toContain('Source zone');
    });

    it('shows the Source zone column once a column is a timestamp, and only on that row', async () => {
        const { fixture, c } = await create([
            { include: true, name: 'CALL_TS', selector: '0', type: 'TIMESTAMP' },
            { include: true, name: 'NOTE', selector: '1', type: 'VARCHAR' },
        ]);
        expect(c.anyTemporal()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('Source zone');
        const zoneInputs = Array.from(
            fixture.nativeElement.querySelectorAll('input[list="inspecto-iana-zones"]'),
        ) as HTMLInputElement[];
        expect(zoneInputs.length).toBe(1); // the VARCHAR row gets an empty cell, not a control
    });

    it('⛔ a DATE column is NOT offered a zone — a date has no instant to shift', async () => {
        const { c } = await create([{ include: true, name: 'D', selector: '0', type: 'DATE' }]);
        expect(c.anyTemporal()).toBe(false);
    });

    it('reveals the column when a row is RETYPED to a timestamp, not only on re-seed', async () => {
        const { fixture, c } = await create(rows(2));
        expect(c.anyTemporal()).toBe(false);
        c.setType(c.filteredEntries()[0].group, 'TIMESTAMP');
        fixture.detectChanges();
        // Regression: anyTemporal once depended on structureVersion alone, which a retype never bumps.
        expect(c.anyTemporal()).toBe(true);
    });

    it('emits a set zone and DROPS a blank one (blank means inherit, not an empty key)', async () => {
        const { c } = await create([
            { include: true, name: 'A', selector: '0', type: 'TIMESTAMP', timezone: 'Asia/Kolkata' },
            { include: true, name: 'B', selector: '1', type: 'TIMESTAMP' },
        ]);
        const v = c.value();
        expect(v[0].timezone).toBe('Asia/Kolkata');
        expect('timezone' in v[1]).toBe(false);
    });

    it('🔴 carries keys the grid does not model through a save', async () => {
        const { c } = await create([
            {
                include: true,
                name: 'A',
                selector: '0',
                type: 'TIMESTAMP',
                timezone_column: 'TZ',
                description: 'when the call started',
                unit: 'ms',
                classification: 'PII',
            },
            { include: true, name: 'TZ', selector: '1', type: 'VARCHAR' },
        ]);
        const v = c.value();
        // The form holds five controls; emitting getRawValue() alone silently dropped the rest.
        expect(v[0].timezone_column).toBe('TZ');
        expect(v[0].description).toBe('when the call started');
        expect(v[0].unit).toBe('ms');
        expect(v[0].classification).toBe('PII');
    });

    it('shows a per-row zone column read-only rather than a box that could contradict it', async () => {
        const { fixture, c } = await create([
            { include: true, name: 'A', selector: '0', type: 'TIMESTAMP', timezone_column: 'TZ' },
            { include: true, name: 'TZ', selector: '1', type: 'VARCHAR' },
        ]);
        expect(c.zoneColumnOf(c.filteredEntries()[0].group, 0)).toBe('TZ');
        expect(fixture.nativeElement.textContent).toContain('per row: TZ');
        expect(fixture.nativeElement.querySelectorAll('input[list="inspecto-iana-zones"]').length).toBe(0);
    });

    it('🔴 flags a TIMESTAMPTZ with no zone anywhere — the engine refuses it at load', async () => {
        const { fixture, c } = await create([{ include: true, name: 'A', selector: '0', type: 'TIMESTAMPTZ' }]);
        expect(c.needsZone(c.filteredEntries()[0].group, 0)).toBe(true);
        expect(fixture.nativeElement.querySelector('[role="alert"]')?.textContent).toContain('Needs a zone');
    });

    it('does not flag a TIMESTAMPTZ that has a zone', async () => {
        const { c } = await create([{ include: true, name: 'A', selector: '0', type: 'TIMESTAMPTZ', timezone: 'UTC' }]);
        expect(c.needsZone(c.filteredEntries()[0].group, 0)).toBe(false);
    });

    it('has no a11y violations with the Source zone column shown', async () => {
        const { fixture } = await create([{ include: true, name: 'CALL_TS', selector: '0', type: 'TIMESTAMP' }]);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
