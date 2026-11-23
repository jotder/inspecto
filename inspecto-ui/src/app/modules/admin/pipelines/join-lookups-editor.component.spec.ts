import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JoinLookupsEditorComponent } from './join-lookups-editor.component';

const STORED = [
    { on: 'SERVED_MSISDN', columns: { provider_desc: 'SERVED_OPER' }, default: { SERVED_OPER: 'unknown' } },
    { on: 'OTHER_MSISDN', columns: {}, details: true },
];

function mount(initial: unknown, inputColumns: string[] = ['SERVED_MSISDN', 'OTHER_MSISDN']) {
    TestBed.configureTestingModule({
        imports: [JoinLookupsEditorComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(JoinLookupsEditorComponent);
    fixture.componentRef.setInput('initial', initial);
    fixture.componentRef.setInput('inputColumns', inputColumns);
    fixture.detectChanges();
    return fixture;
}

describe('JoinLookupsEditorComponent', () => {
    it('round-trips what it loaded when nothing is touched', () => {
        const fixture = mount(STORED);
        expect(fixture.componentInstance.value()).toEqual(STORED);
        expect(fixture.componentInstance.isDirty()).toBe(false);
    });

    it('has no value, and says so, when there are no mappings', () => {
        const fixture = mount(undefined);
        expect(fixture.componentInstance.value()).toBeUndefined();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Add a mapping to name');
    });

    it('adds a mapping with a returned column and a default, and goes dirty', () => {
        const fixture = mount(undefined);
        const c = fixture.componentInstance;
        c.addRow();
        c.patch(0, { on: 'SERVED_MSISDN' });
        c.addColumn(0);
        c.patchColumn(0, 0, { ref: 'provider_desc', out: 'OPER' });
        c.addDefault(0);
        c.patchDefault(0, 0, { name: 'OPER', value: 'n/a' });
        fixture.detectChanges();
        expect(c.value()).toEqual([
            { on: 'SERVED_MSISDN', columns: { provider_desc: 'OPER' }, default: { OPER: 'n/a' } },
        ]);
        expect(c.isDirty()).toBe(true);
        expect(c.validate()).toBe(true);
        c.markPristine();
        expect(c.isDirty()).toBe(false);
    });

    it('refuses a mapping with no input column, on screen as an alert', () => {
        const fixture = mount(undefined);
        fixture.componentInstance.addRow();
        expect(fixture.componentInstance.validate()).toBe(false);
        fixture.detectChanges();
        const alert = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
        expect(alert?.textContent).toContain('needs the column it looks up');
    });

    it('drops the key when the last mapping is removed', () => {
        const fixture = mount([STORED[0]]);
        fixture.componentInstance.removeRow(0);
        expect(fixture.componentInstance.value()).toBeUndefined();
        expect(fixture.componentInstance.isDirty()).toBe(true);
    });

    it('goes back to every Reference column when the last returned column is removed', () => {
        const fixture = mount([{ on: 'A', columns: { k: 'KEY' } }]);
        fixture.componentInstance.removeColumn(0, 0);
        expect(fixture.componentInstance.value()).toEqual([{ on: 'A' }]);
    });

    it('has no accessibility violations', async () => {
        const fixture = mount(STORED);
        await fixture.whenStable();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
