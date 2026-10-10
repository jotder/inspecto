import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import type { G6GraphData } from '@inspecto/core/graph';
import {
    LinkAnalysisLegendComponent,
    LinkAnalysisWorkingSetComponent,
    hideLinkKinds,
    toggleHiddenKind,
} from './link-analysis-overlays.component';

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
            [edgeKinds]="['sms', 'wire']"
            [hiddenEdgeKinds]="hidden()"
            [edgeColors]="{ wire: 'var(--gamma-primary)' }"
            (edgeKindToggle)="toggled.push($event); hidden.set(toggle(hidden(), $event))"
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
    readonly hidden = signal<string[]>([]);
    readonly toggled: string[] = [];
    readonly toggle = toggleHiddenKind;
}

describe('Link Analysis overlays', () => {
    it('render the swatches and stats, minimise to pills, and are a11y-clean in both states', async () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelectorAll('[aria-label="Node kinds"] li')).toHaveLength(2);
        expect(el.querySelectorAll('[aria-label="Link kinds"] button')).toHaveLength(2);
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

describe('Link Analysis legend - link-kind chips', () => {
    it('one pressed chip per link kind; pressing hides that kind, pressing again shows it', async () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        const chip = (k: string) =>
            el.querySelector(`[aria-label="Link kinds"] button[data-kind="${k}"]`) as HTMLButtonElement;
        expect(chip('wire').getAttribute('aria-pressed')).toBe('true');
        expect(chip('wire').getAttribute('aria-label')).toBe('Hide wire links');

        chip('wire').click();
        fixture.detectChanges();
        expect(fixture.componentInstance.toggled).toEqual(['wire']);
        expect(chip('wire').getAttribute('aria-pressed')).toBe('false');
        expect(chip('wire').getAttribute('aria-label')).toBe('Show wire links');
        expect(chip('sms').getAttribute('aria-pressed')).toBe('true');
        await expectNoA11yViolations(el);

        chip('wire').click();
        fixture.detectChanges();
        expect(chip('wire').getAttribute('aria-pressed')).toBe('true');
    });

    it('toggleHiddenKind adds and removes a kind, the set kept sorted', () => {
        expect(toggleHiddenKind([], 'wire')).toEqual(['wire']);
        expect(toggleHiddenKind(['wire'], 'call')).toEqual(['call', 'wire']);
        expect(toggleHiddenKind(['call', 'wire'], 'wire')).toEqual(['call']);
    });

    it('hideLinkKinds drops the hidden kinds (folded counts included), keeps every node and the extra fields', () => {
        const g: G6GraphData & { omittedLinks: number } = {
            nodes: [
                { id: 'a', data: { label: 'a', kind: 'entity' } },
                { id: 'b', data: { label: 'b', kind: 'entity' } },
            ],
            edges: [
                { id: 'e1', source: 'a', target: 'b', data: { kind: 'call · 3' } },
                { id: 'e2', source: 'a', target: 'b', data: { kind: 'sms' } },
            ],
            omittedLinks: 7,
        } as G6GraphData & { omittedLinks: number };
        const out = hideLinkKinds(g, ['call'])!;
        expect(out.edges.map((e) => e.id)).toEqual(['e2']);
        expect(out.nodes).toHaveLength(2);
        expect(out.omittedLinks).toBe(7);
        expect(hideLinkKinds(g, [])).toBe(g);
        expect(hideLinkKinds(null, ['call'])).toBeNull();
    });
});
