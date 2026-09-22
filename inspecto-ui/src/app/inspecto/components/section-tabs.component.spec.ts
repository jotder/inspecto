import { describe, expect, it } from 'vitest';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoSectionTabsComponent, SectionTab } from './section-tabs.component';

@Component({
    standalone: true,
    imports: [InspectoSectionTabsComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-section-tabs [tabs]="tabs" [selected]="selected" (selectedChange)="selected = $event" />`,
})
class HostComponent {
    tabs: SectionTab[] = [
        { id: 'grammar', label: 'Grammar', count: 3 },
        { id: 'schema', label: 'Schema', count: 0 },
        { id: 'sink', label: 'Sink' },
    ];
    selected = 'schema';
}

describe('InspectoSectionTabsComponent', () => {
    function create() {
        TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        return fixture;
    }

    it('renders one tab per entry, with a count pill only where a count is given', () => {
        const el: HTMLElement = create().nativeElement;
        const tabs = Array.from(el.querySelectorAll('.mat-mdc-tab')) as HTMLElement[];
        expect(tabs.length).toBe(3);
        const read = (t: HTMLElement) => ({
            label: t.querySelector('.mdc-tab__text-label > span')?.textContent?.trim(),
            // 0 must render as a pill reading "0" — an absent count renders no pill at all.
            count: t.querySelector('inspecto-chip')?.textContent?.trim(),
        });
        expect(tabs.map(read)).toEqual([
            { label: 'Grammar', count: '3' },
            { label: 'Schema', count: '0' },
            { label: 'Sink', count: undefined },
        ]);
    });

    it('selects the tab whose id matches `selected`', () => {
        const el: HTMLElement = create().nativeElement;
        const active = el.querySelector('.mat-mdc-tab.mdc-tab--active');
        expect(active?.textContent).toContain('Schema');
    });

    it('emits the ID, not the index, when another tab is picked — and the selection STAYS', () => {
        const fixture = create();
        // ⚠ Driven through `pick()`, the handler MatTabGroup calls, NOT a synthetic click: measured
        // 2026-09-22, the FIRST synthetic click on a Material tab in jsdom is swallowed regardless of
        // which element it targets (wrapper, `.mdc-tab__content`, label), and later ones alternate —
        // so a click-based assertion here passes or fails on attempt order, not on behaviour. The real
        // click is proven in the preview. What this pins is THIS component's contract.
        const strip = fixture.debugElement.query(By.directive(InspectoSectionTabsComponent))
            .componentInstance as InspectoSectionTabsComponent;

        strip.pick(2);
        fixture.detectChanges();

        expect(fixture.componentInstance.selected).toBe('sink');
        // 🔴 The regression the `index` signal exists to prevent: with `[selectedIndex]` bound to a
        // getter over `selected`, MatTabGroup re-reads the OLD index in the same pass and the tab
        // springs back, so the strip would still show "Schema" here.
        expect(fixture.nativeElement.querySelector('.mat-mdc-tab.mdc-tab--active')?.textContent).toContain('Sink');
    });

    it('ignores a pick of the already-selected tab and of an unknown index', () => {
        const fixture = create();
        const strip = fixture.debugElement.query(By.directive(InspectoSectionTabsComponent))
            .componentInstance as InspectoSectionTabsComponent;
        let emissions = 0;
        strip.selectedChange.subscribe(() => emissions++);

        strip.pick(1); // already 'schema'
        strip.pick(9); // no such tab
        fixture.detectChanges();

        expect(emissions).toBe(0);
        expect(fixture.componentInstance.selected).toBe('schema');
    });

    it('has no axe violations', async () => {
        const fixture = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
