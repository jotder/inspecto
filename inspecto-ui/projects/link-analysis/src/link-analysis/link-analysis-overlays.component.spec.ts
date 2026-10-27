import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { GENERIC_PROFILE, installDomainProfiles, profileFromPack } from '@inspecto/link-analysis/graph/domain-profile';
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
            [countsGraph]="counts()"
            [open]="wsOpen()"
            (openChange)="wsOpen.set($event)"
        ></inspecto-link-analysis-working-set>
    `,
})
class Host {
    readonly legendOpen = signal(true);
    readonly wsOpen = signal(true);
    readonly counts = signal<'' | 'query' | 'query-under-investigation'>('query');
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

describe('Link Analysis working set - which graph it counts (LA-STUDIO-UI-REORG-1 §1)', () => {
    it('names the query graph, and says so again when the canvas draws an Investigation', () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        const note = () => el.querySelector('[data-testid="working-set-counts"]')?.textContent?.trim();
        expect(note()).toBe('Counts the query graph.');
        fixture.componentInstance.counts.set('query-under-investigation');
        fixture.detectChanges();
        expect(note()).toContain('The canvas shows the Investigation’s Working Set');
        fixture.componentInstance.wsOpen.set(false);
        fixture.detectChanges();
        expect(el.textContent).toContain('query graph');
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

    it('every link-kind toggle is at least 24 px high (WCAG 2.5.8), though the chip inside is 20 px', () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const buttons = Array.from(
            (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLButtonElement>(
                '[aria-label="Link kinds"] button',
            ),
        );
        expect(buttons.length).toBeGreaterThan(0);
        for (const b of buttons) expect(getComputedStyle(b).minHeight).toBe('24px');
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

describe('Link Analysis legend - Entity Type rows (ONB-LA-ENDPOINT-TYPE-1)', () => {
    it('shows the type glyph and the registry label; an untyped kind keeps its swatch', async () => {
        TestBed.configureTestingModule({
            imports: [LinkAnalysisLegendComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(LinkAnalysisLegendComponent);
        fixture.componentRef.setInput('items', [
            { kind: 'msisdn', color: 'var(--gamma-primary)', count: 2 },
            { kind: 'entity', color: 'var(--gamma-primary)', count: 1 },
        ]);
        fixture.componentRef.setInput('typeLabels', { msisdn: 'Phone number' });
        fixture.detectChanges();
        const rows = [...(fixture.nativeElement as HTMLElement).querySelectorAll('ul[aria-label="Node kinds"] li')];
        expect(rows[0].querySelector('[data-testid="legend-glyph"]')).not.toBeNull();
        expect(rows[0].textContent).toContain('Phone number');
        expect(rows[1].querySelector('[data-testid="legend-glyph"]')).toBeNull();
        expect(rows[1].textContent).toContain('entity');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('Link Analysis legend - unexpanded row (LA-UNEXPANDED-MARKER-1)', () => {
    it('shows the dotted-outline row with its count only when something is unexpanded', async () => {
        TestBed.configureTestingModule({
            imports: [LinkAnalysisLegendComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(LinkAnalysisLegendComponent);
        fixture.componentRef.setInput('items', [{ kind: 'account', color: 'var(--gamma-primary)', count: 4 }]);
        fixture.detectChanges();
        const row = () =>
            fixture.nativeElement.querySelector('[data-testid="legend-unexpanded"]') as HTMLElement | null;
        expect(row()).toBeNull();

        fixture.componentRef.setInput('unexpanded', 3);
        fixture.detectChanges();
        expect(row()?.textContent).toContain('not expanded yet');
        expect(row()?.textContent).toContain('3');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('offers Next unexpanded and an Only unexpanded switch beside the dotted-outline row (LA-UNEXPANDED-MARKER-1 b)', async () => {
        TestBed.configureTestingModule({
            imports: [LinkAnalysisLegendComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(LinkAnalysisLegendComponent);
        fixture.componentRef.setInput('items', [{ kind: 'account', color: 'var(--gamma-primary)', count: 4 }]);
        fixture.componentRef.setInput('unexpanded', 2);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const next = el.querySelector('[data-testid="next-unexpanded"]') as HTMLButtonElement;
        const only = el.querySelector('[data-testid="only-unexpanded"]') as HTMLButtonElement;
        let nexts = 0;
        let switched: boolean | null = null;
        fixture.componentInstance.nextUnexpanded.subscribe(() => nexts++);
        fixture.componentInstance.onlyUnexpandedChange.subscribe((v) => (switched = v));
        next.click();
        only.click();
        expect(nexts).toBe(1);
        expect(switched).toBe(true);
        expect(only.getAttribute('aria-pressed')).toBe('false');
        fixture.componentRef.setInput('onlyUnexpanded', true);
        fixture.detectChanges();
        expect(only.getAttribute('aria-pressed')).toBe('true');
        await expectNoA11yViolations(el);
        fixture.componentRef.setInput('unexpanded', 0);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="legend-unexpanded-aid"]')).toBeNull();
    });
});

describe('Link Analysis legend - Industry pack relabels', () => {
    it('draws the active pack link-kind and entity-type terms, keeps the raw kind as the key, and falls back', async () => {
        installDomainProfiles(
            [
                GENERIC_PROFILE,
                profileFromPack({
                    id: 'p',
                    name: 'P',
                    description: '',
                    spaceTemplates: [],
                    patternPacks: [],
                    profile: { measureHints: [], timeHints: [], suggestedTools: [] },
                    entityTypes: [],
                    linkKinds: [],
                    terms: [
                        { key: 'link_kind.wire', label: 'Transfer', abbreviation: '', definition: '' },
                        { key: 'entity_type.account', label: 'Wallet', abbreviation: '', definition: '' },
                    ],
                } as never),
            ],
            'p',
        );
        try {
            TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
            const fixture = TestBed.createComponent(Host);
            fixture.detectChanges();
            const el: HTMLElement = fixture.nativeElement;
            const wire = el.querySelector('button[data-kind="wire"]') as HTMLButtonElement;
            expect(wire.textContent).toContain('Transfer');
            expect(wire.getAttribute('aria-label')).toBe('Hide Transfer links');
            expect(el.querySelector('button[data-kind="sms"]')?.textContent).toContain('sms');
            const rows = Array.from(el.querySelectorAll('[aria-label="Node kinds"] li')).map((l) => l.textContent);
            expect(rows[0]).toContain('Wallet');
            expect(rows[1]).toContain('person');
            await expectNoA11yViolations(el);
        } finally {
            installDomainProfiles([], null);
        }
    });
});
