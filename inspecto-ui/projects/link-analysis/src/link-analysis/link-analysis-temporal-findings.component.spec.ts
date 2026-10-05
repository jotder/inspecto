import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';

import { G6GraphData, resolveEntityId } from '@inspecto/core/graph';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GraphSelection } from '@inspecto/link-analysis/graph/graph-analysis';
import { TemporalPatternResult } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisTemporalFindingsComponent, TemporalRunRequest } from './link-analysis-temporal-findings.component';
import { TemporalFindingsState, allTemporalSelection, temporalResultToState } from './temporal-findings';

const id = (raw: string, end: 'source' | 'target') => resolveEntityId([], raw, () => false, end);

const GRAPH: G6GraphData = {
    nodes: ['a', 'b', 'c'].map((v) => ({ id: id(v, 'source'), data: { label: v, kind: 'entity' } })),
    edges: [{ id: 'e1', source: id('a', 'source'), target: id('b', 'target'), data: { kind: 'x' } }],
} as G6GraphData;

const RESULT: TemporalPatternResult = {
    mode: 'burst',
    results: [
        { source: 'a', target: 'b', events: 7, start: '2026-01-01T00:00:00', end: '2026-01-01T00:00:30' },
        { source: 'c', target: 'b', events: 5, start: '2026-01-02T00:00:00', end: '2026-01-02T00:00:10' },
    ],
    truncated: true,
    rowCapped: true,
    skippedNoTime: 2,
    timeNote: 'Times are the Dataset own wall-clock values.',
    fences: { maxRows: 200000, maxResults: 1000, timeoutMs: 5000 },
};

describe('temporalResultToState', () => {
    it('highlights the drawn link, counts the undrawn one, and never adds to the graph', () => {
        const before = JSON.stringify(GRAPH);
        const s = temporalResultToState(RESULT, GRAPH);
        expect(JSON.stringify(GRAPH)).toBe(before);
        expect(s.findings.map((f) => f.onCanvas)).toEqual([true, false]);
        expect(s.findings[0].selection.edgeIds).toEqual(['e1']);
        expect(s.offCanvas).toBe(1);
        expect(allTemporalSelection(s)).toEqual({
            nodeIds: [id('a', 'source'), id('b', 'target')],
            edgeIds: ['e1'],
        });
    });

    it('does not match the reverse direction', () => {
        const s = temporalResultToState({ ...RESULT, results: [{ source: 'b', target: 'a', events: 5 }] }, GRAPH);
        expect(s.findings[0].onCanvas).toBe(false);
    });
});

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisTemporalFindingsComponent],
    template: `<inspecto-link-analysis-temporal-findings
        [mappings]="mappings()"
        [timeColumn]="time()"
        [state]="state()"
        [error]="error()"
        (runRequested)="ran($event)"
        (highlight)="focused($event)"
    />`,
})
class HostComponent {
    readonly mappings = signal([{ value: '0', label: 'transfers: payer → payee' }]);
    readonly time = signal('ts');
    readonly state = signal<TemporalFindingsState | null>(null);
    readonly error = signal('');
    readonly ran = vi.fn<(r: TemporalRunRequest) => void>();
    readonly focused = vi.fn<(s: GraphSelection) => void>();
}

function setup() {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

describe('LinkAnalysisTemporalFindingsComponent', () => {
    it('asks for a time column before offering a scan', async () => {
        const { fixture, host, el } = setup();
        host.time.set('');
        fixture.detectChanges();
        expect(el.querySelector('[data-test=temporal-no-time]')).not.toBeNull();
        expect(el.querySelector('[data-test=temporal-run]')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('emits the run request with in-range numbers', () => {
        const { fixture, host, el } = setup();
        const win = el.querySelector('[data-test=temporal-window]') as HTMLInputElement;
        win.value = '999999';
        win.dispatchEvent(new Event('input'));
        (el.querySelector('[data-test=temporal-run]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(host.ran).toHaveBeenCalledWith({
            mapping: 0,
            mode: 'burst',
            windowSeconds: 86400,
            minEvents: 5,
            maxCv: 0.1,
        });
    });

    it('lists findings, says what is cut and not drawn, and focuses on click', async () => {
        const { fixture, host, el } = setup();
        host.state.set(temporalResultToState(RESULT, GRAPH));
        fixture.detectChanges();
        const text = el.textContent ?? '';
        expect(text).toContain('2 burst(s) found');
        expect(text).toContain('1 not drawn on this canvas');
        expect(text).toContain('2 row(s) skipped');
        expect(el.querySelector('[data-test=temporal-truncated]')!.textContent).toContain('row limit');
        const items = Array.from(el.querySelectorAll<HTMLButtonElement>('[data-test=temporal-finding]'));
        expect(items.map((b) => b.disabled)).toEqual([false, true]);
        items[0].click();
        expect(host.focused).toHaveBeenCalledWith({ nodeIds: [id('a', 'source'), id('b', 'target')], edgeIds: ['e1'] });
        await expectNoA11yViolations(el);
    });

    it('says so when nothing matched, and surfaces an error', async () => {
        const { fixture, host, el } = setup();
        host.state.set(temporalResultToState({ ...RESULT, results: [], truncated: false }, GRAPH));
        host.error.set('The scan failed.');
        fixture.detectChanges();
        expect(el.querySelector('[data-test=temporal-none]')).not.toBeNull();
        expect(el.textContent).toContain('The scan failed.');
        await expectNoA11yViolations(el);
    });
});
