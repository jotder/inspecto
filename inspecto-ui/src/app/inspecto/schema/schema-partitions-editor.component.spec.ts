import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoSchemaPartitionsEditorComponent, SchemaPartitionRow } from './schema-partitions-editor.component';

function create(initial: SchemaPartitionRow[] = [], fieldNames: string[] = ['TXN_DATE', 'EVENT_TYPE']) {
    TestBed.configureTestingModule({
        imports: [InspectoSchemaPartitionsEditorComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(InspectoSchemaPartitionsEditorComponent);
    fixture.componentRef.setInput('initial', initial);
    fixture.componentRef.setInput('fieldNames', fieldNames);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

describe('InspectoSchemaPartitionsEditorComponent', () => {
    it('seeds from the stored partitions[] and round-trips them through value()', () => {
        const stored: SchemaPartitionRow[] = [
            { column: 'event_type', source: 'EVENT_TYPE', type: 'VARCHAR' },
            { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
        ];
        const { c } = create(stored);
        expect(c.value()).toEqual(stored);
        expect(c.form.pristine, 'a freshly seeded grid is pristine').toBe(true);
    });

    it('the date launcher adds year/month/day off ONE picked field, in one click (day grain default)', () => {
        const { c } = create();
        c.dateSource.set('TXN_DATE');
        c.addDateGrain();
        expect(c.value()).toEqual([
            { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
            { column: 'month', source: 'TXN_DATE', type: 'DATE_MONTH' },
            { column: 'day', source: 'TXN_DATE', type: 'DATE_DAY' },
        ]);
        expect(c.form.dirty).toBe(true);
        expect(c.mixedDateSources(), 'one source ⇒ a single event time').toBe(false);
    });

    /** A shallower grain is a real choice (monthly = 12 healthy files a year, not 365 tiny ones). */
    it('the grain select cuts shallower schemes: Year, and Year+Month', () => {
        const { c } = create();
        c.dateSource.set('TXN_DATE');
        c.grain.set('year');
        c.addDateGrain();
        expect(c.value()).toEqual([{ column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' }]);

        c.removeRow(c.partitionRows.controls[0]);
        c.grain.set('month');
        c.addDateGrain();
        expect(c.value()).toEqual([
            { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
            { column: 'month', source: 'TXN_DATE', type: 'DATE_MONTH' },
        ]);
    });

    /**
     * Smart pre-pick: exactly ONE date-typed field is unambiguous, so it comes pre-selected. Two or
     * more stays the operator's call — guessing which date is THE event time is how wrong bounds
     * happen — and the pick never overrides one already made.
     */
    it('pre-picks the date field only when exactly one date-typed field exists', () => {
        TestBed.configureTestingModule({
            imports: [InspectoSchemaPartitionsEditorComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(InspectoSchemaPartitionsEditorComponent);
        fixture.componentRef.setInput('fieldNames', ['MSISDN', 'CALL_START', 'LOAD_DATE']);
        fixture.componentRef.setInput('dateFieldNames', ['CALL_START']);
        fixture.detectChanges();
        expect(fixture.componentInstance.dateSource()).toBe('CALL_START');

        fixture.componentRef.setInput('dateFieldNames', ['CALL_START', 'LOAD_DATE']);
        fixture.detectChanges();
        expect(fixture.componentInstance.dateSource(), 'an existing pick is never overridden').toBe('CALL_START');

        // Two candidates from the start ⇒ no guess.
        const f2 = TestBed.createComponent(InspectoSchemaPartitionsEditorComponent);
        f2.componentRef.setInput('fieldNames', ['CALL_START', 'LOAD_DATE']);
        f2.componentRef.setInput('dateFieldNames', ['CALL_START', 'LOAD_DATE']);
        f2.detectChanges();
        expect(f2.componentInstance.dateSource()).toBe('');
    });

    it('warns (never blocks) when the date segments disagree on their source field', () => {
        const { c } = create([
            { column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' },
            { column: 'day', source: 'LOAD_DATE', type: 'DATE_DAY' },
        ]);
        expect(c.mixedDateSources()).toBe(true);
        expect(c.validate(), 'legal to write — the engine degrades bounds, deliberately').toBe(true);
    });

    it('validate refuses a blank segment name or missing source', () => {
        const { c } = create();
        c.addRow(); // {column:'', source:'', type:'VARCHAR'}
        expect(c.validate()).toBe(false);
    });

    it('a stored source the schema no longer carries stays listed, never silently blanked', () => {
        const { c } = create([{ column: 'year', source: 'GONE_COL', type: 'DATE_YEAR' }], ['TXN_DATE']);
        expect(c.sourceChoices(c.partitionRows.controls[0])).toEqual(['GONE_COL', 'TXN_DATE']);
    });

    it('removeRow drops the row and dirties the form', () => {
        const { c } = create([{ column: 'year', source: 'TXN_DATE', type: 'DATE_YEAR' }]);
        c.removeRow(c.partitionRows.controls[0]);
        expect(c.value()).toEqual([]);
        expect(c.form.dirty).toBe(true);
    });

    it('has no a11y violations, empty and populated', async () => {
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        c.dateSource.set('TXN_DATE');
        c.addDateGrain();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
