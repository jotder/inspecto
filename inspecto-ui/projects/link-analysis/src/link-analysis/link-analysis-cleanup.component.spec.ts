import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import type { G6GraphData } from '@inspecto/core/graph/graph-types';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { CleanupApply, LinkAnalysisCleanupComponent } from './link-analysis-cleanup.component';

function graph(links: string): G6GraphData {
    const pairs = links.split(' ').map((p) => p.split('-') as [string, string]);
    const ids = [...new Set(pairs.flat())];
    return {
        nodes: ids.map((id) => ({ id, data: { label: id, kind: 'entity' } })),
        edges: pairs.map(([s, t]) => ({ id: `${s}-${t}`, source: s, target: t, data: { kind: 'link' } })),
    };
}

function setup(inputs: Record<string, unknown>) {
    TestBed.configureTestingModule({ imports: [LinkAnalysisCleanupComponent], providers: [provideNoopAnimations()] });
    const f = TestBed.createComponent(LinkAnalysisCleanupComponent);
    for (const [k, v] of Object.entries(inputs)) f.componentRef.setInput(k, v);
    const applied: CleanupApply[] = [];
    const lit: unknown[] = [];
    f.componentInstance.applied.subscribe((a) => applied.push(a));
    f.componentInstance.highlight.subscribe((e) => lit.push(e));
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    const q = (id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
    return { f, c: f.componentInstance, el, q, applied, lit };
}

describe('LinkAnalysisCleanupComponent', () => {
    it('states the effect first, then applies Keep paths with its prefilled reason', async () => {
        const { f, c, el, q, applied } = setup({ graph: graph('s-a a-t s-d'), seeds: ['s', 't'] });
        c.pick('paths');
        f.detectChanges();
        expect(q('cleanup-effect')!.textContent).toContain('hides 1 of 4 entities');
        await expectNoA11yViolations(el);
        q('cleanup-apply')!.click();
        expect(applied).toEqual([{ tool: 'paths', ids: ['d'], reason: 'Clean up: not on a path between seeds, L = 4' }]);
        f.detectChanges();
        expect(q('cleanup-panel')).toBeNull();
    });

    it('Cancel applies nothing', () => {
        const { f, c, q, applied } = setup({ graph: graph('a-b b-c'), selection: ['a'] });
        c.pick('remove');
        f.detectChanges();
        q('cleanup-cancel')!.click();
        expect(applied).toEqual([]);
    });

    it('over the browser cap the path tool is disabled with the reason and offers the server', () => {
        const { f, c, q, el } = setup({ graph: graph('s-a a-t'), seeds: ['s', 't'], nodeCap: 2 });
        c.pick('paths');
        f.detectChanges();
        expect(q('cleanup-blocked')!.textContent).toContain('at most 2 nodes — this graph has 3');
        expect((q('cleanup-apply') as HTMLButtonElement).disabled).toBe(true);
        expect(el.textContent).toContain('Find paths (server)');
    });

    it('Highlight glows the shortest paths, removes nothing, and Esc clears it', () => {
        const { f, c, q, applied, lit } = setup({ graph: graph('s-a a-t s-b b-c c-t'), seeds: ['s', 't'] });
        c.pick('shortest');
        f.detectChanges();
        q('cleanup-apply')!.click();
        f.detectChanges();
        expect(applied).toEqual([]);
        expect(lit[0]).toEqual({ nodeIds: ['s', 'a', 't'], edgeIds: ['s-a', 'a-t'] });
        expect(q('cleanup-clear-glow')).not.toBeNull();
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        expect(lit[1]).toBeNull();
    });

    it('Keep only selected needs a selection', () => {
        const { f, c, q } = setup({ graph: graph('a-b') });
        c.pick('keep');
        f.detectChanges();
        expect(q('cleanup-blocked')!.textContent).toContain('Select entities first');
    });
});
