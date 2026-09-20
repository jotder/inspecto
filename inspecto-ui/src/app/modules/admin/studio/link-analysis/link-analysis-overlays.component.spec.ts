import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisLegendComponent, LinkAnalysisWorkingSetComponent } from './link-analysis-overlays.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisLegendComponent, LinkAnalysisWorkingSetComponent],
    template: `
        <inspecto-link-analysis-legend
            [items]="[
                { kind: 'account', color: 'var(--gamma-primary)', count: 4 },
                { kind: 'person', color: 'var(--gamma-primary)', count: 2 },
            ]"
            [edgeKinds]="['wire']"
            [open]="legendOpen()"
            (openChange)="legendOpen.set($event)"
        ></inspecto-link-analysis-legend>
        <inspecto-link-analysis-working-set
            [stats]="[
                { label: 'Nodes', value: '6 / 9' },
                { label: 'Links', value: '5' },
            ]"
            [truncated]="true"
            [open]="wsOpen()"
            (openChange)="wsOpen.set($event)"
        ></inspecto-link-analysis-working-set>
    `,
})
class Host {
    readonly legendOpen = signal(true);
    readonly wsOpen = signal(true);
}

describe('Link Analysis overlays', () => {
    it('render the swatches and stats, minimise to pills, and are a11y-clean in both states', async () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelectorAll('[aria-label="Node kinds"] li')).toHaveLength(2);
        expect(el.textContent).toContain('links: wire');
        expect(el.textContent).toContain('6 / 9');
        expect(el.textContent).toContain('truncated');
        await expectNoA11yViolations(el);

        (el.querySelector('[aria-label="Minimize the legend"]') as HTMLButtonElement).click();
        (el.querySelector('[aria-label="Minimize the working set"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(fixture.componentInstance.legendOpen()).toBe(false);
        expect(el.querySelector('[aria-label="Node kinds"]')).toBeNull();
        const pill = el.querySelector('[aria-label="Show the working set"]') as HTMLButtonElement;
        expect(pill.textContent).toContain('truncated'); // the loop signal survives minimising
        await expectNoA11yViolations(el);

        pill.click();
        fixture.detectChanges();
        expect(fixture.componentInstance.wsOpen()).toBe(true);
    });
});
