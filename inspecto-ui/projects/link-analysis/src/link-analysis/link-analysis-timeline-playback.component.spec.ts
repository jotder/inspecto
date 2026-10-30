import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { G6GraphData } from '@inspecto/core/graph';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import { LinkAnalysisTimelinePlaybackComponent } from './link-analysis-timeline-playback.component';
import { PLAYBACK_SLICES } from './timeline-playback';

const edge = (id: string, source: string, target: string, ts: string) => ({
    id,
    source,
    target,
    data: { kind: 'x', attrs: { ts } },
});

const GRAPH = {
    nodes: ['a', 'b', 'c'].map((id) => ({ id, data: { label: id, kind: 'entity' } })),
    edges: [edge('e1', 'a', 'b', '2026-01-01T00:00:00Z'), edge('e2', 'b', 'c', '2026-01-03T00:00:00Z')],
} as unknown as G6GraphData;
const EXTENT: [number, number] = [Date.parse('2026-01-01T00:00:00Z'), Date.parse('2026-01-03T00:00:00Z')];

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisTimelinePlaybackComponent],
    template: `<inspecto-link-analysis-timeline-playback
        [graph]="graph()"
        column="ts"
        [extent]="extent()"
        [sealable]="sealable()"
        (highlight)="lit($event)"
        (seal)="sealed($event)"
    />`,
})
class HostComponent {
    readonly graph = signal<G6GraphData | null>(GRAPH);
    readonly extent = signal<[number, number]>(EXTENT);
    readonly sealable = signal(false);
    readonly lit = vi.fn<(s: (GraphSelection & { edgeOpacity?: Map<string, number> }) | null) => void>();
    readonly sealed = vi.fn<(w: { from: string; to: string }) => void>();
}

function setup(reduced = false) {
    vi.stubGlobal('matchMedia', (q: string) => ({
        matches: reduced && q.includes('reduce'),
        media: q,
        addListener: () => undefined,
        removeListener: () => undefined,
        addEventListener: () => undefined,
        removeEventListener: () => undefined,
    }));
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const q = (t: string) => el.querySelector(`[data-test=${t}]`) as HTMLButtonElement;
    return { fixture, host: fixture.componentInstance, el, q };
}

describe('LinkAnalysisTimelinePlaybackComponent', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => {
        vi.useRealTimers();
        vi.unstubAllGlobals();
    });

    it('starts idle with accessible, named controls', async () => {
        const { el, q, host } = setup();
        expect(q('playback-play').getAttribute('aria-label')).toBe('Play');
        expect(q('playback-scrub').getAttribute('aria-label')).toBe('Timeline position'); // T-076
        expect(q('playback-back').getAttribute('aria-label')).toBe('Previous slice');
        expect(q('playback-forward').getAttribute('aria-label')).toBe('Next slice');
        expect(q('playback-status').textContent).toContain('Not started');
        expect(host.lit).not.toHaveBeenCalled();
        vi.useRealTimers(); // axe schedules with timers
        await expectNoA11yViolations(el);
    });

    it('steps forward and back, highlighting the links active in the slice', () => {
        const { fixture, host, q } = setup();
        q('playback-forward').click();
        fixture.detectChanges();
        expect(host.lit).toHaveBeenLastCalledWith(expect.objectContaining({ nodeIds: ['a', 'b'], edgeIds: ['e1'] }));
        expect(q('playback-status').textContent).toContain('Slice 1 of 20');
        expect(q('playback-status').textContent).toContain('1 link active');
        q('playback-forward').click();
        fixture.detectChanges();
        expect(host.lit).toHaveBeenLastCalledWith(null);
        expect(q('playback-status').textContent).toContain('0 links active');
        q('playback-back').click();
        fixture.detectChanges();
        expect(host.lit).toHaveBeenLastCalledWith(expect.objectContaining({ nodeIds: ['a', 'b'], edgeIds: ['e1'] }));
        // disabled with its reason: still focusable, so the tooltip says why (T-076)
        expect(q('playback-back').getAttribute('aria-disabled')).toBe('true');
    });

    it('plays on a timer, pauses on demand, and stops at the end', () => {
        const { fixture, host, q } = setup();
        q('playback-play').click();
        fixture.detectChanges();
        expect(q('playback-play').getAttribute('aria-label')).toBe('Pause');
        expect(q('playback-status').getAttribute('aria-live')).toBe('off');
        vi.advanceTimersByTime(3000);
        fixture.detectChanges();
        expect(q('playback-status').textContent).toContain('Slice 4 of 20');
        q('playback-play').click();
        fixture.detectChanges();
        const paused = q('playback-status').textContent;
        vi.advanceTimersByTime(5000);
        fixture.detectChanges();
        expect(q('playback-status').textContent).toBe(paused);
        expect(q('playback-status').getAttribute('aria-live')).toBe('polite');

        q('playback-play').click();
        vi.advanceTimersByTime(PLAYBACK_SLICES * 1000);
        fixture.detectChanges();
        expect(q('playback-status').textContent).toContain(`Slice ${PLAYBACK_SLICES} of ${PLAYBACK_SLICES}`);
        expect(q('playback-play').getAttribute('aria-label')).toBe('Play');
        expect(host.lit).toHaveBeenLastCalledWith(expect.objectContaining({ nodeIds: ['b', 'c'], edgeIds: ['e2'] }));
    });

    it('scrubs with the slider and clears the highlight on reset', () => {
        const { fixture, host, q } = setup();
        const scrub = q('playback-scrub') as unknown as HTMLInputElement;
        scrub.value = String(PLAYBACK_SLICES - 1);
        scrub.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        expect(host.lit).toHaveBeenLastCalledWith(expect.objectContaining({ nodeIds: ['b', 'c'], edgeIds: ['e2'] }));
        q('playback-reset').click();
        fixture.detectChanges();
        expect(host.lit).toHaveBeenLastCalledWith(null);
        expect(q('playback-status').textContent).toContain('Not started');
    });

    it('plays under reduced motion too, with no extra note: Play is user-started and pausable', async () => {
        const { fixture, el, q } = setup(true);
        expect(q('playback-play').disabled).toBe(false);
        expect(el.textContent).not.toContain('reduced motion');
        q('playback-play').click();
        vi.advanceTimersByTime(2000);
        fixture.detectChanges();
        expect(q('playback-status').textContent).toContain('Slice 3 of 20');
        q('playback-play').click();
        fixture.detectChanges();
        vi.useRealTimers(); // axe schedules with timers
        await expectNoA11yViolations(el);
    });

    it('does not change the graph it reads, and stops its timer when destroyed', () => {
        const before = JSON.stringify(GRAPH);
        const { fixture, host, q } = setup();
        q('playback-play').click();
        fixture.detectChanges();
        expect(JSON.stringify(host.graph())).toBe(before);
        fixture.destroy();
        host.lit.mockClear();
        vi.advanceTimersByTime(5000);
        expect(host.lit).not.toHaveBeenCalled();
    });

    it('starts over when the range changes', () => {
        const { fixture, host, q } = setup();
        q('playback-forward').click();
        fixture.detectChanges();
        host.extent.set([EXTENT[0], EXTENT[1] + 86_400_000]);
        fixture.detectChanges();
        expect(q('playback-status').textContent).toContain('Not started');
        expect(host.lit).toHaveBeenLastCalledWith(null);
    });
    it('widens the highlight back from the handle with the Window picker', () => {
        const { fixture, host, q } = setup();
        const scrub = q('playback-scrub') as unknown as HTMLInputElement;
        scrub.value = String(PLAYBACK_SLICES - 1);
        scrub.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        expect(host.lit).toHaveBeenLastCalledWith(expect.objectContaining({ edgeIds: ['e2'] }));
        const pick = q('playback-window') as unknown as HTMLSelectElement;
        pick.value = '7d';
        pick.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        expect(host.lit).toHaveBeenLastCalledWith(expect.objectContaining({ edgeIds: ['e1', 'e2'] }));
        expect(q('playback-status').textContent).toContain('2026-01-01 00:00 to 2026-01-03 00:00 UTC. 2 links active');
    });

    it('fades older links in the window by default, and stops fading when Age fade is off', () => {
        const { fixture, host, q } = setup();
        const pick = q('playback-window') as unknown as HTMLSelectElement;
        pick.value = 'all';
        pick.dispatchEvent(new Event('change'));
        const scrub = q('playback-scrub') as unknown as HTMLInputElement;
        scrub.value = String(PLAYBACK_SLICES - 1);
        scrub.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        const faded = host.lit.mock.lastCall![0]!.edgeOpacity!;
        expect(faded.get('e2')).toBe(1); // at the handle
        expect(faded.get('e1')).toBe(0.45); // at the window's start: the floor, still above the 0.2 dim
        const fade = q('playback-fade') as unknown as HTMLInputElement;
        fade.checked = false;
        fade.dispatchEvent(new Event('change'));
        fixture.detectChanges();
        expect(host.lit.mock.lastCall![0]!.edgeOpacity).toBeUndefined();
    });

    it('offers Seal only when the host allows it, and emits the window as [from, to)', () => {
        const { fixture, host, q } = setup();
        q('playback-forward').click();
        fixture.detectChanges();
        expect(q('playback-seal')).toBeNull();
        host.sealable.set(true);
        fixture.detectChanges();
        const pick = q('playback-window') as unknown as HTMLSelectElement;
        pick.value = 'all';
        pick.dispatchEvent(new Event('change'));
        const scrub = q('playback-scrub') as unknown as HTMLInputElement;
        scrub.value = String(PLAYBACK_SLICES - 1);
        scrub.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        q('playback-seal').click();
        // the last slice keeps its end: the half-open op gets one millisecond past it
        expect(host.sealed).toHaveBeenCalledWith({ from: '2026-01-01T00:00:00.000Z', to: '2026-01-03T00:00:00.001Z' });
    });
});
