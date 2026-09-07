import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { MEASURE_AGGS } from './measure-grammar';
import { SummarizeEditorComponent } from './summarize-editor.component';

function mount(columns: string[], groupBy: string[], measures: string[]) {
    TestBed.configureTestingModule({
        imports: [SummarizeEditorComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(SummarizeEditorComponent);
    fixture.componentRef.setInput('columns', columns);
    fixture.componentRef.setInput('initialGroupBy', groupBy);
    fixture.componentRef.setInput('initialMeasures', measures);
    fixture.detectChanges();
    return fixture;
}

describe('SummarizeEditorComponent', () => {
    it('offers exactly the contract-pinned aggregates — never a hard-coded list', () => {
        // The design's gate for S4c. MEASURE_AGGS comes from measure-grammar.contract.json, which
        // MeasureGrammarContractTest compares against the engine's MeasureCompiler.AGGS — so an option
        // the engine does not accept cannot appear here without that Java suite going red.
        const fixture = mount(['amount'], [], ['sum(amount)']);
        const options = Array.from(
            (fixture.nativeElement as HTMLElement).querySelectorAll('select[aria-label^="Aggregate"] option'),
        ).map((o) => (o as HTMLOptionElement).value);
        expect(options).toEqual(['', ...MEASURE_AGGS]);
    });

    it('round-trips what it loaded when nothing is touched', () => {
        const fixture = mount(['amount'], ['region'], ['count', 'sum(amount)']);
        expect(fixture.componentInstance.value()).toEqual({
            group_by: ['region'],
            measures: ['count', 'sum(amount)'],
        });
        expect(fixture.componentInstance.isDirty()).toBe(false);
    });

    it('round-trips a measure it cannot parse, as a text row', async () => {
        // 🔴 The data-loss case. Opening a hand-written node and pressing Apply must not drop the value.
        const fixture = mount([], [], ['median(x)']);
        // ⚠ ngModel writes the control's value in a microtask, so the input is still empty on the first
        // synchronous pass — assert the DOM only after it settles, or this reads as a component bug.
        await fixture.whenStable();
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('as written');
        expect((el.querySelector('input[aria-label="Measure 1"]') as HTMLInputElement).value).toBe('median(x)');
        expect(fixture.componentInstance.value().measures).toEqual(['median(x)']);
        // …and it is REFUSED rather than applied silently: the grammar's own message, not a new one.
        expect(fixture.componentInstance.validate()).toBe(false);
    });

    it('adds a grouping column from the upstream list and drops it from what is offered', () => {
        const fixture = mount(['region', 'day'], [], []);
        fixture.componentInstance.addGroup('region');
        fixture.detectChanges();
        expect(fixture.componentInstance.availableColumns()).toEqual(['day']);
        expect(fixture.componentInstance.isDirty()).toBe(true);
        expect(fixture.componentInstance.value().group_by).toEqual(['region']);
    });

    it('takes a typed column when nothing has been parsed yet, so the node is authorable early', () => {
        const fixture = mount([], [], []);
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('input[aria-label="Add a grouping column by name"]')).not.toBeNull();
        fixture.componentInstance.typedGroup.set('region');
        fixture.componentInstance.commitTypedGroup();
        expect(fixture.componentInstance.value().group_by).toEqual(['region']);
    });

    it('refuses a grouping column the engine would refuse, and says why in its own words', () => {
        const fixture = mount([], ['sum(amount)'], []);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('put aggregates in Measures');
        expect(fixture.componentInstance.validate()).toBe(false);
    });

    it('hides the column control for count, which the engine compiles without one', () => {
        const fixture = mount(['amount'], [], ['count']);
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('input[aria-label="Column, measure 1"]')).toBeNull();
        expect(el.textContent).toContain('count needs no column');
    });

    it('drops an unfinished new row rather than writing a blank measure', () => {
        const fixture = mount(['amount'], [], ['sum(amount)']);
        fixture.componentInstance.addRow();
        expect(fixture.componentInstance.value().measures).toEqual(['sum(amount)']);
        // An empty row is not an error — it is an edit in progress, so Apply is not blocked by it.
        expect(fixture.componentInstance.validate()).toBe(true);
    });

    it('is accessible', async () => {
        const fixture = mount(['region', 'amount'], ['region'], ['count', 'sum(amount)']);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
