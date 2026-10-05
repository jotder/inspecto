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
    series: 'link',
    results: [
        { source: 'a', target: 'b', events: 7, start: '2026-01-01T00:00:00', end: '2026-01-01T00:00:30' },
        { source: 'c', target: 'b', events: 5, start: '2026-01-02T00:00:00', end: '2026-01-02T00:00:10' },
    ],
    truncated: true,
    rowCapped: true,
    skippedNoTime: 2,
    timeNote: 'Times are the Dataset own wall-clock values.',
    fences: { maxRows: 200000, maxResults: 1000, timeoutMs: 5000 },
    source: { kind: 'dataset', reason: 'no_index' },
};

describe('temporalResultToState', () => {
    it('places an entity finding on its node and counts an undrawn entity', () => {
        const res: TemporalPatternResult = {
            ...RESULT,
            series: 'entity',
            results: [
                { entity: 'a', events: 6, start: '2026-01-01T00:00:00', end: '2026-01-01T00:00:25' },
                { entity: 'zzz', events: 5, start: '2026-01-02T00:00:00', end: '2026-01-02T00:00:10' },
            ],
        };
        const s = temporalResultToState(res, GRAPH);
        expect(s.series).toBe('entity');
        expect(s.findings.map((f) => f.onCanvas)).toEqual([true, false]);
        expect(s.findings[0].selection).toEqual({ nodeIds: [id('a', 'source')], edgeIds: [] });
        expect(s.offCanvas).toBe(1);
        expect(allTemporalSelection(s)).toEqual({ nodeIds: [id('a', 'source')], edgeIds: [] });
    });

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
        [canSeal]="canSeal()"
        [sealBusy]="sealBusy()"
        [sealed]="sealed()"
        [sealError]="sealError()"
        (runRequested)="ran($event)"
        (sealRequested)="sealRequested()"
        (highlight)="focused($event)"
    />`,
})
class HostComponent {
    readonly mappings = signal([{ value: '0', label: 'transfers: payer → payee' }]);
    readonly time = signal('ts');
    readonly state = signal<TemporalFindingsState | null>(null);
    readonly error = signal('');
    readonly canSeal = signal(false);
    readonly sealBusy = signal(false);
    readonly sealed = signal<{ step: number; count: number; outsideWorkingSet: number; fingerprint: string } | null>(
        null,
    );
    readonly sealError = signal('');
    readonly sealRequested = vi.fn<() => void>();
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
            series: 'link',
            windowSeconds: 86400,
            minEvents: 5,
            maxCv: 0.1,
        });
    });

    it('asks for the entity series when the analyst picks it', () => {
        const { fixture, host, el } = setup();
        const cmp = fixture.debugElement.children[0].componentInstance as LinkAnalysisTemporalFindingsComponent;
        cmp.series.set('entity');
        (el.querySelector('[data-test=temporal-run]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(host.ran).toHaveBeenCalledWith(expect.objectContaining({ series: 'entity' }));
    });

    it('lists an entity finding by its name', async () => {
        const { fixture, host, el } = setup();
        host.state.set(
            temporalResultToState(
                { ...RESULT, series: 'entity', results: [{ entity: 'a', events: 6, start: 's', end: 'e' }] },
                GRAPH,
            ),
        );
        fixture.detectChanges();
        expect(el.textContent).toContain('1 burst(s) found');
        expect(el.querySelector('[data-test=temporal-finding]')!.textContent).toContain('a');
        expect(el.querySelector('[data-test=temporal-finding]')!.textContent).not.toContain('→');
        await expectNoA11yViolations(el);
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

    it('offers to seal the findings only into an open Investigation', async () => {
        const { fixture, host, el } = setup();
        expect(el.querySelector('[data-test=temporal-seal]')).toBeNull();
        host.state.set(temporalResultToState(RESULT, GRAPH));
        fixture.detectChanges();
        const button = el.querySelector<HTMLButtonElement>('[data-test=temporal-seal-button]')!;
        expect(button.textContent).toContain('Seal these findings');
        expect(button.disabled).toBe(true);
        expect(el.querySelector('[data-test=temporal-seal-note]')!.textContent).toContain('Open an Investigation');
        button.click();
        expect(host.sealRequested).not.toHaveBeenCalled();

        host.canSeal.set(true);
        fixture.detectChanges();
        expect(button.disabled).toBe(false);
        expect(el.querySelector('[data-test=temporal-seal-note]')!.textContent).toContain(
            'Working Set does not change',
        );
        button.click();
        expect(host.sealRequested).toHaveBeenCalledTimes(1);
        await expectNoA11yViolations(el);
    });

    it('reports what was sealed, or why it was refused', async () => {
        const { fixture, host, el } = setup();
        host.state.set(temporalResultToState(RESULT, GRAPH));
        host.canSeal.set(true);
        host.sealed.set({ step: 4, count: 1, outsideWorkingSet: 2, fingerprint: 'abcdef0123456789' });
        fixture.detectChanges();
        const sealed = el.querySelector('[data-test=temporal-sealed]')!.textContent ?? '';
        expect(sealed).toContain('Sealed at step 4: 1 finding(s)');
        expect(sealed).toContain('2 outside it');
        expect(sealed).toContain('abcdef012345');
        host.sealed.set(null);
        host.sealError.set('this Investigation has no time column');
        fixture.detectChanges();
        expect(el.querySelector('[data-test=temporal-sealed]')).toBeNull();
        expect(el.textContent).toContain('this Investigation has no time column');
        host.sealBusy.set(true);
        fixture.detectChanges();
        expect(el.querySelector<HTMLButtonElement>('[data-test=temporal-seal-button]')!.disabled).toBe(true);
        await expectNoA11yViolations(el);
    });
});
