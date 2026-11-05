import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisRoleFilterComponent, RoleFilterCategory } from './link-analysis-role-filter.component';
import { RoleFilters } from './role-filters';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisRoleFilterComponent],
    template: `<inspecto-link-analysis-role-filter
        [measures]="['amount']"
        [categories]="categories()"
        [times]="['booked_at']"
        (apply)="applied($event)"
    />`,
})
class HostComponent {
    readonly categories = signal<RoleFilterCategory[]>([{ name: 'channel', values: ['mobile', 'online'] }]);
    readonly applied = vi.fn<(f: RoleFilters) => void>();
}

function setup() {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const panel = fixture.debugElement.children[0].componentInstance as LinkAnalysisRoleFilterComponent;
    return { fixture, host: fixture.componentInstance, el, panel };
}

describe('LinkAnalysisRoleFilterComponent (LA-COLUMN-ROLES-1 P3)', () => {
    it('a range per Measure, a value per Category as a pressed toggle, a Time window, then one apply (no violations)', async () => {
        const { fixture, host, el, panel } = setup();
        expect(el.querySelector('[data-testid="measure-amount"]')).not.toBeNull();
        const chips = Array.from(el.querySelectorAll<HTMLButtonElement>('[data-testid="category-channel"] button'));
        expect(chips.map((b) => b.textContent?.trim())).toEqual(['mobile', 'online']);
        chips[1].click();
        fixture.detectChanges();
        expect(chips[1].getAttribute('aria-pressed')).toBe('true');
        panel.setMeasure('amount', 'min', 100);
        panel.setTime('column', 'booked_at');
        fixture.detectChanges();
        (el.querySelector('[data-testid="role-filter-apply"]') as HTMLButtonElement).click();
        expect(host.applied).toHaveBeenCalledWith({
            measures: { amount: { min: '100', max: '' } },
            categories: { channel: ['online'] },
            time: { column: 'booked_at', from: '', to: '' },
        });
        await expectNoA11yViolations(el);
    });

    it('a Category with many values gets a find box; the values shown narrow to it, the picked one stays', () => {
        const { fixture, host, el } = setup();
        host.categories.set([{ name: 'cell', values: Array.from({ length: 20 }, (_, i) => `CELL-${i}`) }]);
        fixture.detectChanges();
        const panel = fixture.debugElement.children[0].componentInstance as LinkAnalysisRoleFilterComponent;
        expect(el.querySelector('[data-testid="find-cell"]')).not.toBeNull();
        panel.toggle('cell', 'CELL-3');
        panel.setFind('cell', 'cell-1');
        fixture.detectChanges();
        const shown = Array.from(el.querySelectorAll('[data-testid="category-cell"] [role="group"] button')).map((b) =>
            b.textContent?.trim(),
        );
        expect(shown).toEqual(['CELL-1', 'CELL-3', ...Array.from({ length: 10 }, (_, i) => `CELL-${10 + i}`)]);
    });

    it('an upside-down range is refused on screen and Apply is off; Clear applies no filter', () => {
        const { fixture, host, el, panel } = setup();
        panel.setMeasure('amount', 'min', 9);
        panel.setMeasure('amount', 'max', 3);
        fixture.detectChanges();
        expect((el.querySelector('[data-testid="role-filter-apply"]') as HTMLButtonElement).disabled).toBe(true);
        expect(el.textContent).toContain('The lowest must not be above the highest.');
        (el.querySelector('[data-testid="role-filter-clear"]') as HTMLButtonElement).click();
        expect(host.applied).toHaveBeenLastCalledWith({
            measures: {},
            categories: {},
            time: { column: '', from: '', to: '' },
        });
    });
});
