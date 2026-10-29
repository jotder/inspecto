import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import type { G6GraphData } from '@inspecto/core/graph';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import type { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import { FoundAction, LinkAnalysisDiscoverComponent } from './link-analysis-discover.component';
import type { PackTerms } from './grow-exclude';

const T: PackTerms = { node: 'wallet', nodes: 'wallets', link: 'transfer', links: 'transfers' };
const n = (id: string) => ({ id, data: { label: id, kind: 'entity' } });
const e = (s: string, t: string) => ({ id: `${s}->${t}`, source: s, target: t, data: { kind: 'p2p' } });
const G: G6GraphData = {
    nodes: ['HUB', 'A', 'B', 'C', 'D', 'E'].map(n),
    edges: [e('A', 'HUB'), e('B', 'HUB'), e('C', 'HUB'), e('D', 'HUB'), e('E', 'HUB'), e('A', 'B')],
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisDiscoverComponent],
    template: `
        <inspecto-link-analysis-discover
            [graph]="graph()"
            [terms]="terms"
            [nodeOptions]="options"
            [startWhyNot]="startWhyNot()"
            [listWhyNot]="listWhyNot()"
            (show)="shown.push($event)"
            (startFrom)="started.push($event)"
            (addToList)="added.push($event)"
        />
    `,
})
class Host {
    readonly graph = signal<G6GraphData | null>(G);
    readonly terms = T;
    readonly options = G.nodes.map((x) => ({ id: x.id, label: x.data.label }));
    readonly startWhyNot = signal('');
    readonly listWhyNot = signal('');
    readonly shown: GraphSelection[] = [];
    readonly started: FoundAction[] = [];
    readonly added: FoundAction[] = [];
}

function mount() {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const click = (sel: string) => {
        (el.querySelector(sel) as HTMLButtonElement).click();
        fixture.detectChanges();
    };
    const button = (label: string) =>
        Array.from(el.querySelectorAll('button')).find((b) => b.getAttribute('aria-label') === label) as HTMLButtonElement;
    return { fixture, el, host: fixture.componentInstance, click, button };
}

describe('LinkAnalysisDiscoverComponent - Find something interesting (LA-ONBOARD-1 phase 7, UC-12)', () => {
    it('three plain questions; Who is busiest reads each row in the pack words; the actions carry the row (no violations)', async () => {
        const { el, host, click, button } = mount();
        const asked = Array.from(el.querySelectorAll('[role="group"] button')).map((b) => b.textContent!.trim());
        expect(asked).toEqual(['Busiest nodes', 'Tight groups', 'Route from A to B']);
        click('[data-testid="la-discover-busiest"]');
        const readings = Array.from(el.querySelectorAll('[data-testid="la-discover-reading"]')).map((p) => p.textContent!.trim());
        expect(readings[0]).toBe('HUB takes part in 5 transfers, the most on this graph.');
        expect(readings.every((r) => /\d+ transfers?/.test(r) && r.endsWith('.'))).toBe(true); // never a bare number
        expect(el.querySelector('[data-testid="la-discover-hint"]')?.textContent).toContain(
            'HUB has 5 transfers. Add it to an exclusion list?',
        );
        button('Show on graph: HUB').click();
        expect(host.shown[0].nodeIds).toEqual(['HUB']);
        expect(host.shown[0].edgeIds).toHaveLength(5);
        button('Start from these: HUB').click();
        expect(host.started[0]).toMatchObject({ row: { values: ['HUB'] }, what: 'HUB, the busiest wallet' });
        button('Add to a list: HUB').click();
        expect(host.added[0].purpose).toBeUndefined();
        (Array.from(el.querySelectorAll('[data-testid="la-discover-hint"] button'))[0] as HTMLButtonElement).click();
        expect(host.added[1]).toMatchObject({ purpose: 'exclusion', row: { values: ['HUB'] } });
        await expectNoA11yViolations(el);
    });

    it('How does A reach B asks From and To first, then reads the way', async () => {
        const { el, fixture, click } = mount();
        click('[data-testid="la-discover-route"]');
        const go = el.querySelector('[data-testid="la-discover-route-go"]') as HTMLButtonElement;
        // X6: disabled with its reason, still focusable, and a click does nothing.
        expect(go.getAttribute('aria-disabled')).toBe('true');
        expect(el.querySelector('[data-testid="la-discover-route-why"]')?.textContent?.trim()).toBe(
            'Pick a From and a To node first.',
        );
        expect(go.getAttribute('aria-describedby')).toContain('la-discover-route-why');
        go.click();
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-discover-reading"]')).toBeNull();
        const discover = fixture.debugElement.query((x) => x.componentInstance instanceof LinkAnalysisDiscoverComponent)
            .componentInstance as LinkAnalysisDiscoverComponent;
        discover.from.setValue('C');
        discover.to.setValue('B');
        fixture.detectChanges();
        expect(go.getAttribute('aria-disabled')).not.toBe('true');
        expect(el.querySelector('[data-testid="la-discover-route-why"]')).toBeNull();
        click('[data-testid="la-discover-route-go"]');
        expect(el.querySelector('[data-testid="la-discover-reading"]')?.textContent?.trim()).toBe(
            'C and B are linked through 2 transfers, not all in one direction: C – HUB – B.',
        );
        await expectNoA11yViolations(el);
    });

    it('an action the screen cannot take is disabled with the reason said, never a silent grey button', () => {
        const { el, host, fixture, click, button } = mount();
        host.startWhyNot.set('This graph was not drawn from a Dataset with a link, so nothing can start from it.');
        host.listWhyNot.set('Adding to a list needs the Incident management capability.');
        fixture.detectChanges();
        click('[data-testid="la-discover-busiest"]');
        expect(button('Start from these: HUB').disabled).toBe(true);
        expect(button('Add to a list: HUB').disabled).toBe(true);
        expect(el.querySelector('[data-testid="la-discover-why-not"]')?.textContent).toContain('not drawn from a Dataset');
        expect(el.querySelector('[data-testid="la-discover-hint"] button')).toBeNull(); // the hint keeps its sentence only
    });

    it('with no graph, it says to draw one first', () => {
        const { el, host, fixture } = mount();
        host.graph.set(null);
        fixture.detectChanges();
        expect(el.textContent).toContain('Draw a graph first');
        expect(el.querySelector('[data-testid="la-discover-busiest"]')).toBeNull();
    });
});
