import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';

import { NodeRule } from '@inspecto/core/graph';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisDisplayRulesComponent } from './link-analysis-display-rules.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisDisplayRulesComponent],
    template: `<inspecto-link-analysis-display-rules
        [rules]="rules()"
        [attributes]="attributes()"
        [kinds]="['msisdn', 'imei']"
        [swatches]="['swatch-EF4444', 'swatch-22C55E']"
        (rulesChange)="emitted = $event; rules.set($event)"
    />`,
})
class HostComponent {
    rules = signal<NodeRule[]>([]);
    attributes = signal<string[]>(['risk']);
    emitted: NodeRule[] | null = null;
}

describe('LinkAnalysisDisplayRulesComponent', () => {
    it('adds a rule from the form, lists it, removes it, and has no a11y violations', async () => {
        TestBed.configureTestingModule({ imports: [HostComponent] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        const host = fixture.componentInstance;

        expect(el.textContent).toContain('No rules');
        const add = Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Add rule'))!;
        expect(add.disabled).toBe(true); // attribute, value and colour all required

        const set = (id: string, value: string) => {
            const c = el.querySelector<HTMLInputElement | HTMLSelectElement>(`#${id}`)!;
            c.value = value;
            c.dispatchEvent(new Event(c.tagName === 'SELECT' ? 'change' : 'input'));
        };
        set('la-rule-attr', 'risk');
        set('la-rule-op', 'gt');
        set('la-rule-value', '5');
        el.querySelector<HTMLButtonElement>('button[aria-pressed]')!.click();
        fixture.detectChanges();
        expect(add.disabled).toBe(false);
        add.click();
        fixture.detectChanges();

        expect(host.emitted).toEqual([{ attr: 'risk', op: 'gt', value: '5', color: 'swatch-EF4444' }]);
        expect(el.textContent).toContain('risk is greater than 5');
        await expectNoA11yViolations(fixture.nativeElement);

        el.querySelector<HTMLButtonElement>('button[aria-label^="Remove the rule"]')!.click();
        fixture.detectChanges();
        expect(host.emitted).toEqual([]);
    });

    it('says so when the loaded nodes carry no attributes', () => {
        TestBed.configureTestingModule({ imports: [HostComponent] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.componentInstance.attributes.set([]);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('carry no attributes');
        expect(fixture.nativeElement.querySelector('form')).toBeNull();
    });
});
