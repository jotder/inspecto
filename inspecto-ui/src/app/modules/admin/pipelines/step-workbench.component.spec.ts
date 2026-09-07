import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { FIELD_LIST_CAP } from './step-workbench-fields';
import { InputRelation } from './step-workbench-inputs';
import { StepWorkbenchComponent } from './step-workbench.component';

function mount(columns: string[], types: Record<string, string>, sql: string, inputs: InputRelation[] = []) {
    // ⚠ Reset first: one case mounts TWICE (one input, then a fan-in) and TestBed refuses to be
    // reconfigured once it has been instantiated.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
        imports: [StepWorkbenchComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(StepWorkbenchComponent);
    fixture.componentRef.setInput('columns', columns);
    fixture.componentRef.setInput('types', types);
    fixture.componentRef.setInput('sql', sql);
    fixture.componentRef.setInput('inputs', inputs);
    fixture.detectChanges();
    return fixture;
}

/** The field rows, in render order — the group headings are divs, so only buttons are fields. */
function fieldNames(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('button[aria-label^="Use field "]')).map((b) =>
        b.getAttribute('aria-label')!.replace('Use field ', ''),
    );
}

describe('StepWorkbenchComponent', () => {
    it('groups the fields the SQL references above the ones it does not, and is accessible', async () => {
        const fixture = mount(
            ['amount', 'total_amount', 'note'],
            { amount: 'DOUBLE', total_amount: 'DOUBLE' },
            'SELECT total_amount FROM input',
        );
        const el = fixture.nativeElement as HTMLElement;

        expect(el.textContent).toContain('Used by this step (1)');
        expect(el.textContent).toContain('Not used (2)');
        // The trap the pure spec pins, asserted end-to-end: `amount` must not ride in on `total_amount`.
        expect(fieldNames(el)).toEqual(['total_amount', 'amount', 'note']);

        await expectNoA11yViolations(el);
    });

    it('emits the picked name and writes nothing itself', () => {
        const fixture = mount(['amount'], {}, '');
        const picked: string[] = [];
        fixture.componentInstance.fieldPicked.subscribe((n: string) => picked.push(n));

        const el = fixture.nativeElement as HTMLElement;
        (el.querySelector('button[aria-label="Use field amount"]') as HTMLButtonElement).click();

        expect(picked).toEqual(['amount']);
    });

    it('caps what it RENDERS but not what it searches, and says how many are hidden', () => {
        const wide = Array.from({ length: FIELD_LIST_CAP + 10 }, (_, i) => `col_${i}`);
        const fixture = mount(wide, {}, '');
        const el = fixture.nativeElement as HTMLElement;

        expect(fieldNames(el).length).toBe(FIELD_LIST_CAP);
        expect(el.textContent).toContain('10 more');

        // A column past the cap is invisible until it is typed — and then it is the first thing shown.
        // This is the whole justification for capping instead of scrolling 600 rows.
        fixture.componentInstance.filter.set(`col_${FIELD_LIST_CAP + 5}`);
        fixture.detectChanges();
        expect(fieldNames(el)).toEqual([`col_${FIELD_LIST_CAP + 5}`]);
        expect(el.textContent).not.toContain('more — type to find them');
    });

    it('says so rather than rendering an empty list when there are no upstream columns yet', async () => {
        const fixture = mount([], {}, 'SELECT 1');
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('No upstream columns yet');
        expect(fieldNames(el)).toEqual([]);
        await expectNoA11yViolations(el);
    });

    // ── input strip (S4b) ───────────────────────────────────────────────────────────────────────────
    // The design's gate was "two inbound edges ⇒ select rendered; one ⇒ text". The select half is
    // REFUSED — there is no input key to write (see step-workbench-inputs.ts) — so the gate is that the
    // strip STATES both, and states a route branch as the different input it is.
    it('states the single input it reads, and every input of a fan-in', () => {
        const one = mount(['a'], {}, '', [{ from: 'parse', rel: 'data', label: 'Parse CDRs' }]);
        expect((one.nativeElement as HTMLElement).textContent).toContain('Reads Parse CDRs');
        expect((one.nativeElement as HTMLElement).querySelector('select')).toBeNull();

        const two = mount(['a'], {}, '', [
            { from: 'route', rel: 'route:eu', label: 'Route by region' },
            { from: 'eu', rel: 'data', label: 'EU branch' },
        ]);
        const text = (two.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Reads 2 inputs');
        expect(text).toContain('Route by region · route:eu');
        expect(text).toContain('EU branch');
    });

    it('says the Step is not connected rather than showing an empty strip', () => {
        const fixture = mount(['a'], {}, '', []);
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Reads nothing yet');
    });
});
