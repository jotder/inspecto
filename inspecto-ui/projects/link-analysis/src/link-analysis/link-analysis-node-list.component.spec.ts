import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { G6GraphData } from '@inspecto/core/graph';
import { LinkAnalysisNodeListComponent, NODE_LIST_PAGE } from './link-analysis-node-list.component';

/** Masked ids exactly as a canvas would carry them: the list must show these, never resolve them. */
const GRAPH: G6GraphData = {
    nodes: [
        { id: 'e:****1111', data: { label: '****1111', kind: 'msisdn' } },
        { id: 'e:****2222', data: { label: '****2222', kind: 'msisdn' } },
        { id: 'e:****3333', data: { label: '****3333', kind: 'device' } },
        { id: 'e:****4444', data: { label: '****4444', kind: 'device' } },
        { id: 'e:****5555', data: { label: '****5555', kind: 'device' } },
    ],
    edges: [
        { id: 'l1', source: 'e:****1111', target: 'e:****2222', data: { kind: 'calls', count: 7 } as never },
        { id: 'l2', source: 'e:****3333', target: 'e:****1111', data: { kind: 'uses' } },
    ],
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisNodeListComponent],
    template: `<inspecto-link-analysis-node-list
        [graph]="graph()"
        [omittedLinks]="omitted()"
        [pageSize]="pageSize()"
        (nodeSelect)="selected.push($event)"
    />`,
})
class Host {
    readonly graph = signal<G6GraphData | null>(GRAPH);
    readonly omitted = signal(0);
    readonly pageSize = signal(NODE_LIST_PAGE);
    readonly selected: string[] = [];
}

function create() {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
    const f = TestBed.createComponent(Host);
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const rows = (): HTMLElement[] => Array.from(el.querySelectorAll<HTMLElement>('table[role="grid"] tbody tr'));
    const key = (target: HTMLElement, k: string): void => {
        target.dispatchEvent(new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }));
        f.detectChanges();
    };
    return { f, el, rows, key, host: f.componentInstance };
}

describe('LinkAnalysisNodeListComponent (LA-A11Y-AUDIT-1: the canvas text alternative)', () => {
    it('is a labelled grid of the SAME nodes the canvas draws, masked ids untouched, with link counts', () => {
        const { el, rows } = create();
        const grid = el.querySelector('table[role="grid"]') as HTMLElement;
        expect(grid.getAttribute('aria-label')).toBe('Nodes');
        expect(Array.from(grid.querySelectorAll('th')).map((h) => h.textContent?.trim())).toEqual([
            'Node',
            'Kind',
            'Links',
        ]);
        expect(rows().map((r) => r.dataset['nodeId'])).toEqual(GRAPH.nodes.map((n) => n.id));
        expect(rows()[0].textContent).toContain('****1111');
        expect(rows()[0].textContent).not.toContain('1111111'); // nothing resolved or unmasked
        expect(Array.from(rows()[0].querySelectorAll('td')).map((c) => c.textContent?.trim())).toEqual([
            '****1111',
            'msisdn',
            '2',
        ]);
        expect(rows()[4].querySelectorAll('td')[2].textContent?.trim()).toBe('0');
        expect(el.querySelector('[data-testid="node-list-status"]')?.textContent).toContain('Showing 1–5 of 5 nodes');
    });

    it('roving tabindex: one tab stop; arrows, Home and End move it and focus', () => {
        const { f, rows, key } = create();
        const tabbable = (): number[] => rows().flatMap((r, i) => (r.tabIndex === 0 ? [i] : []));
        expect(tabbable()).toEqual([0]);
        rows()[0].focus();
        key(rows()[0], 'ArrowDown');
        expect(tabbable()).toEqual([1]);
        expect(document.activeElement).toBe(rows()[1]);
        key(rows()[1], 'ArrowDown');
        key(rows()[2], 'ArrowUp');
        expect(tabbable()).toEqual([1]);
        key(rows()[1], 'End');
        expect(tabbable()).toEqual([4]);
        expect(document.activeElement).toBe(rows()[4]);
        key(rows()[4], 'ArrowDown'); // no wrap past the last row
        expect(tabbable()).toEqual([4]);
        key(rows()[4], 'Home');
        expect(tabbable()).toEqual([0]);
        f.destroy();
    });

    it('Enter / Space / click select the node (the host gets the same event as a canvas click) and list its links', () => {
        const { el, f, rows, key, host } = create();
        expect(el.querySelector('[data-testid="node-list-links-title"]')).toBeNull();
        key(rows()[0], 'Enter');
        expect(host.selected).toEqual(['e:****1111']);
        expect(rows()[0].getAttribute('aria-selected')).toBe('true');
        expect(rows()[1].getAttribute('aria-selected')).toBe('false');
        const links = Array.from(el.querySelectorAll('table[aria-label="Links of ****1111"] tbody tr')).map((r) =>
            Array.from(r.querySelectorAll('td')).map((c) => c.textContent?.trim()),
        );
        expect(links).toEqual([
            ['out', '****2222', 'calls', '7'],
            ['in', '****3333', 'uses', '1'],
        ]);
        key(rows()[2], ' ');
        rows()[3].click();
        f.detectChanges();
        expect(host.selected).toEqual(['e:****1111', 'e:****3333', 'e:****4444']);
        expect(rows()[3].getAttribute('aria-selected')).toBe('true');
        expect(el.textContent).toContain('This node has no links.');
    });

    it('an empty graph says so OUTSIDE any grid or list role (axe aria-required-children)', async () => {
        const { f, el, host } = create();
        host.graph.set({ nodes: [], edges: [] });
        f.detectChanges();
        const msg = el.querySelector('[data-testid="node-list-empty"]') as HTMLElement;
        expect(msg.textContent).toContain('There are no nodes to list.');
        expect(el.querySelector('[role="grid"], [role="listbox"], table')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('pages: states the range and the total, and never renders more than one page of rows', () => {
        const { f, el, rows, host } = create();
        host.pageSize.set(2);
        f.detectChanges();
        const status = (): string => el.querySelector('[data-testid="node-list-status"]')?.textContent ?? '';
        expect(rows()).toHaveLength(2);
        expect(status()).toContain('Showing 1–2 of 5 nodes');
        const buttons = Array.from(el.querySelectorAll('button'));
        const next = buttons.find((b) => b.textContent?.includes('Next')) as HTMLButtonElement;
        const prev = buttons.find((b) => b.textContent?.includes('Previous')) as HTMLButtonElement;
        expect(prev.disabled).toBe(true);
        next.click();
        f.detectChanges();
        expect(rows().map((r) => r.dataset['nodeId'])).toEqual(['e:****3333', 'e:****4444']);
        expect(status()).toContain('Showing 3–4 of 5 nodes');
        expect(rows()[0].tabIndex).toBe(0); // focus stop resets to the first row of the new page
        next.click();
        f.detectChanges();
        expect(rows()).toHaveLength(1);
        expect(next.disabled).toBe(true);
        prev.click();
        f.detectChanges();
        expect(status()).toContain('Showing 3–4 of 5 nodes');
    });

    it('a 1 200-node graph renders one page (500 rows), with the total stated', () => {
        const { f, el, rows, host } = create();
        host.graph.set({
            nodes: Array.from({ length: 1200 }, (_, i) => ({ id: `n${i}`, data: { label: `N${i}`, kind: 'k' } })),
            edges: [],
        });
        f.detectChanges();
        expect(rows()).toHaveLength(NODE_LIST_PAGE);
        expect(el.querySelector('[data-testid="node-list-status"]')?.textContent).toContain(
            'Showing 1–500 of 1,200 nodes',
        );
        expect(el.textContent).toContain('Page 1 of 3');
    }, 30_000);

    it('a node with many links shows a first page of them, with a way to show more', () => {
        const { f, el, key, rows, host } = create();
        host.pageSize.set(2);
        host.graph.set({
            nodes: GRAPH.nodes,
            edges: Array.from({ length: 5 }, (_, i) => ({
                id: `x${i}`,
                source: 'e:****1111',
                target: 'e:****2222',
                data: { kind: 'calls' },
            })),
        });
        f.detectChanges();
        key(rows()[0], 'Enter');
        const status = (): string => el.querySelector('[data-testid="node-list-links-status"]')?.textContent ?? '';
        expect(status()).toMatch(/Showing 2\s+of\s+5 links/);
        (
            Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('more links')) as HTMLElement
        ).click();
        f.detectChanges();
        expect(status()).toMatch(/Showing 4\s+of\s+5 links/);
    });

    it('says when Working Set links are over the render limit (the list does not have them either)', () => {
        const { f, el, host } = create();
        expect(el.querySelector('[data-testid="node-list-omitted"]')).toBeNull();
        host.omitted.set(1250);
        f.detectChanges();
        const note = el.querySelector('[data-testid="node-list-omitted"]') as HTMLElement;
        expect(note.textContent).toContain('1,250 more links');
        expect(note.getAttribute('role')).toBe('status');
    });

    it('has no axe violations with nodes and a selected node', async () => {
        const { el, key, rows } = create();
        key(rows()[0], 'Enter');
        await expectNoA11yViolations(el);
    });
});
