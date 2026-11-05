import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    LinkAnalysisRoleFilterComponent,
    RoleFilterCategory,
    RoleFilterSection,
} from './link-analysis-role-filter.component';
import { DatasetRoleFilters, RoleFilters } from './role-filters';

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

/** A read over several Datasets (LA-COLUMN-ROLES-2 D-3 b): two Datasets sharing a Measure and a Category name. */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisRoleFilterComponent],
    template: `<inspecto-link-analysis-role-filter
        [sections]="sections"
        (apply)="applied($event)"
        (applyByDataset)="appliedByDataset($event)"
    />`,
})
class MultiHostComponent {
    readonly sections: RoleFilterSection[] = [
        {
            key: 'calls',
            title: 'Calls',
            measures: ['amount'],
            categories: [{ name: 'channel', values: ['app', 'web'] }],
            times: ['at'],
        },
        {
            key: 'pays',
            title: 'Payments',
            measures: ['amount'],
            categories: [{ name: 'channel', values: ['agent'] }],
            times: [],
        },
    ];
    readonly applied = vi.fn<(f: RoleFilters) => void>();
    readonly appliedByDataset = vi.fn<(f: DatasetRoleFilters) => void>();
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

    it('over several Datasets: one titled section per Dataset, distinct names, each applied under its Dataset (no violations)', async () => {
        TestBed.configureTestingModule({ imports: [MultiHostComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(MultiHostComponent);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const host = fixture.componentInstance;
        const sections = Array.from(el.querySelectorAll<HTMLElement>('[data-testid^="role-filter-section-"]'));
        expect(sections.map((s) => s.querySelector('h4')?.textContent?.trim())).toEqual(['Calls', 'Payments']);
        // each section is a group named by its heading, and the same column in two sections has two names
        const ids = sections.map((s) => s.getAttribute('aria-labelledby'));
        expect(new Set(ids).size).toBe(2);
        sections.forEach((s, i) => {
            expect(s.getAttribute('role')).toBe('group');
            expect(el.querySelector('#' + ids[i])?.textContent?.trim()).toBe(['Calls', 'Payments'][i]);
        });
        expect(
            Array.from(el.querySelectorAll('[role="group"][aria-label]')).map((g) => g.getAttribute('aria-label')),
        ).toEqual(['Calls: channel values', 'Payments: channel values']);
        const names = Array.from(el.querySelectorAll('[data-testid="measure-amount"] button')).map((b) =>
            b.getAttribute('aria-label'),
        );
        expect(names.filter((n) => n?.startsWith('Calls: amount from'))).toHaveLength(1);
        expect(names.filter((n) => n?.startsWith('Payments: amount from'))).toHaveLength(1);
        expect(el.querySelectorAll('[data-testid="time-window"]')).toHaveLength(1); // only Calls has a Time column

        // a chip in the second section picks for that Dataset only
        (sections[1].querySelector('[data-testid="category-channel"] button') as HTMLButtonElement).click();
        const panel = fixture.debugElement.children[0].componentInstance as LinkAnalysisRoleFilterComponent;
        panel.setMeasure('amount', 'min', 10, 'calls');
        fixture.detectChanges();
        expect(sections[1].querySelector('[data-testid="category-channel"] button')?.getAttribute('aria-pressed')).toBe(
            'true',
        );
        expect(sections[0].querySelector('[aria-pressed="true"]')).toBeNull();
        await expectNoA11yViolations(el);
        (el.querySelector('[data-testid="role-filter-apply"]') as HTMLButtonElement).click();
        expect(host.appliedByDataset).toHaveBeenCalledWith({
            calls: {
                measures: { amount: { min: '10', max: '' } },
                categories: {},
                time: { column: '', from: '', to: '' },
            },
            pays: { measures: {}, categories: { channel: ['agent'] }, time: { column: '', from: '', to: '' } },
        });
        expect(host.applied).not.toHaveBeenCalled();
        // Clear empties every section
        (el.querySelector('[data-testid="role-filter-clear"]') as HTMLButtonElement).click();
        const empty = { measures: {}, categories: {}, time: { column: '', from: '', to: '' } };
        expect(host.appliedByDataset).toHaveBeenLastCalledWith({ calls: empty, pays: empty });
    });
});
