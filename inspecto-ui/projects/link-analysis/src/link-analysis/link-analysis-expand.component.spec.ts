import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GrowPreview, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { pickerSet } from '@inspecto/core/testing/pen-field';
import { GrowContext } from './grow-exclude';
import { GrowExcludeStore } from './grow-exclude.store';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { ExpandRun, LinkAnalysisExpandComponent } from './link-analysis-expand.component';

const TERMS = { node: 'number', nodes: 'numbers', link: 'call', links: 'calls' };
const CTX: GrowContext = {
    mode: 'investigation',
    frontier: ['a', 'b'],
    heldFrontier: ['hub'],
    from: 'the outer ring (degree 1): 3 numbers',
    blocked: null,
    sources: [
        { value: 'outer', label: 'The outer ring (next degree)', frontier: ['a', 'b'], heldFrontier: ['hub'], from: 'the outer ring (degree 1): 3 numbers', blocked: null },
        { value: 'seeds', label: 'The seeds', frontier: ['s'], heldFrontier: [], from: 'the 1 number seeded', blocked: null },
        { value: 'all', label: 'The whole Working Set', frontier: [], heldFrontier: [], from: 'the whole Working Set: 0 numbers', blocked: 'Too many.' },
    ],
    linkKinds: ['voice', 'sms'],
    hubThreshold: 500,
    terms: TERMS,
    frontierCap: 100,
    maxDegree: 4,
    hasWindow: true,
};
const PREVIEW: GrowPreview = {
    addsNodes: 42,
    addsLinks: 120,
    held: [{ id: 'cc', contacts: 9000 }],
    heldCount: 1,
    excludedByLists: [{ listId: 'cc', title: 'Call centres', removed: 6 }],
    truncated: false,
    readPath: 'index',
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisExpandComponent],
    template: `<inspecto-link-analysis-expand
        [ctx]="ctx()"
        [embedded]="embedded()"
        [onScreen]="active()"
        [presets]="{ budget: 100 }"
        [limits]="limits()"
        (expand)="runs.push($event)"
        (closed)="closed = closed + 1"
    ></inspecto-link-analysis-expand>`,
})
class Host {
    readonly ctx = signal<GrowContext>(CTX);
    readonly embedded = signal(false);
    readonly active = signal(true);
    readonly limits = signal<{ budgetAbove: number | null; fanOutAbove: number | null } | null>(null);
    readonly runs: ExpandRun[] = [];
    closed = 0;
}

beforeEach(() => localStorage.clear());

const settle = async (fixture: { detectChanges(): void; whenStable(): Promise<unknown> }, ms = 0) => {
    if (ms) await new Promise((r) => setTimeout(r, ms));
    await fixture.whenStable();
    fixture.detectChanges();
};

function create(
    opts: { preview?: () => Observable<GrowPreview>; ctx?: Partial<GrowContext>; basic?: GrowPreview | null; embedded?: boolean } = {},
) {
    const answer = opts.preview ?? (() => of(PREVIEW));
    const inv = { previewExpand: vi.fn((_id: string, _body: unknown) => answer()) };
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            GrowExcludeStore,
            { provide: InvService, useValue: inv },
            {
                provide: LensService,
                useValue: {
                    canManageIncidents: signal(true),
                    canManageExclusionCategories: signal(false),
                    canRevealLinkEntities: signal(false),
                },
            },
            {
                provide: InvestigationSessionStore,
                useValue: {
                    activeId: signal('inv-1'),
                    log: signal(null),
                    workingSet: signal(null),
                    withPresets: (p: object, r: object) => ({ ...p, ...r }),
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    const store = TestBed.inject(GrowExcludeStore);
    const basic = vi.fn(async (_frontier: string[], _req: unknown) => opts.basic ?? null);
    store.basicAdapter = { preview: basic };
    if (opts.ctx) fixture.componentInstance.ctx.set({ ...CTX, ...opts.ctx });
    if (opts.embedded) fixture.componentInstance.embedded.set(true);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const cmp = fixture.debugElement.query(By.directive(LinkAnalysisExpandComponent)).componentInstance as LinkAnalysisExpandComponent;
    return { fixture, el, inv, basic, host: fixture.componentInstance, cmp };
}

const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
const go = (el: HTMLElement) => q(el, 'la-expand-go') as HTMLButtonElement;
const spin = (el: HTMLElement) => q(el, 'la-expand-degrees')!.querySelector('[role="spinbutton"]') as HTMLElement;
/** Step the Degrees stepper to `n` with its + / − buttons, as a person would. */
const degreesTo = (el: HTMLElement, n: number) => {
    const now = Number(spin(el).getAttribute('aria-valuenow'));
    const id = n > now ? 'stepper-increment' : 'stepper-decrement';
    for (let i = 0; i < Math.abs(n - now); i++) q(q(el, 'la-expand-degrees')!, id)!.click();
};
const fold = (el: HTMLElement, id: 'links' | 'time' | 'limits') => q(el, 'la-expand-fold-' + id)!.querySelector('details')!;
const foldHint = (el: HTMLElement, id: 'links' | 'time' | 'limits') =>
    fold(el, id).querySelector('summary')!.textContent!.replace(/\s+/g, ' ').trim();

describe('LinkAnalysisExpandComponent (LA-STUDIO-UI-REORG-1 §5.4)', () => {
    it('opens on the defaults: the first usable From, Both ways, 1 degree, supernodes held; degrees run 1…maxDegree; axe-clean', async () => {
        const { fixture, el, cmp } = create();
        await settle(fixture);
        // Direction is a popup choice showing Both ways; Degrees a − 1 + stepper bounded 1…maxDegree
        expect(q(el, 'la-expand-direction')!.textContent).toContain('Both ways');
        expect(spin(el).getAttribute('aria-valuenow')).toBe('1');
        expect(spin(el).getAttribute('aria-valuemin')).toBe('1');
        expect(spin(el).getAttribute('aria-valuemax')).toBe('4');
        // an Investigation also offers Reciprocal
        expect(cmp.directionOptions().map((o) => o.label)).toEqual(['Both ways', 'Outgoing', 'Incoming', 'Reciprocal']);
        // order of importance: From, Degrees, Direction, the Expand button, then the filters
        const order = ['la-expand-from', 'la-expand-degrees', 'la-expand-direction', 'la-expand-go', 'la-expand-filters'].map((id) => q(el, id)!);
        for (let i = 1; i < order.length; i++)
            expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING, order[i].dataset['testid']).toBeTruthy();
        // the filters are closed folds, each saying what is set
        expect([fold(el, 'links').open, fold(el, 'time').open, fold(el, 'limits').open]).toEqual([false, false, false]);
        expect(foldHint(el, 'links')).toContain('All link kinds');
        expect(foldHint(el, 'time')).toContain('Investigation window');
        expect(foldHint(el, 'limits')).toContain('Busy over 500 links');
        expect(q(el, 'la-expand-hold')?.textContent).toContain('Hold busy entities (more than 500 linked)');
        expect(q(el, 'la-expand-from-line')?.textContent).toContain('the outer ring (degree 1): 3 numbers');
        expect(q(el, 'la-expand-hint')?.textContent).toContain('one Undo (Ctrl+Z) reverses it');
        await expectNoA11yViolations(el);
    });

    it('previews degree 1 before anything changes, and the line says later degrees are estimated after it', async () => {
        const { fixture, el, inv } = create();
        await settle(fixture);
        expect(inv.previewExpand).toHaveBeenCalledWith('inv-1', {
            op: 'expand',
            ids: ['a', 'b'],
            budget: 100,
            direction: 'either',
            hubThreshold: 500,
        });
        expect(q(el, 'la-expand-preview')?.textContent).toContain('This step adds about 42 numbers and 120 calls; 1 busy number will be held');
        degreesTo(el, 3);
        await settle(fixture, 350);
        expect(q(el, 'la-expand-preview')?.textContent).toContain(
            'Degree 1 adds about 42 numbers and 120 calls; 1 busy number will be held; 6 numbers are removed by your exclusion lists; degrees 2–3 are estimated after degree 1 runs.',
        );
        // Degrees alone never re-read the preview
        expect(inv.previewExpand).toHaveBeenCalledTimes(1);
        expect(go(el).textContent).toContain('Expand 3 degrees');
    });

    it('Expand hands the start and the choice (degrees, Advanced rung) to the screen; Cancel and Escape close', async () => {
        const { fixture, el, host, cmp } = create();
        await settle(fixture);
        degreesTo(el, 3);
        cmp.rung.controls.minEvents.setValue('2');
        cmp.rung.controls.window.setValue('full');
        await settle(fixture, 350);
        go(el).click();
        expect(host.runs).toHaveLength(1);
        expect(host.runs[0].req).toEqual({
            direction: 'either',
            degrees: 3,
            linkKinds: null,
            hold: true,
            threshold: 500,
            rung: { minEvents: 2, window: 'full' },
        });
        expect(host.runs[0].ctx.frontier).toEqual(['a', 'b']);
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Cancel') as HTMLButtonElement).click();
        q(el, 'la-expand-dialog')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        expect(host.closed).toBe(2);
    });

    it('From switches the frontier the preview and the run use; a blocked From says why', async () => {
        const { fixture, el, inv, host, cmp } = create();
        await settle(fixture);
        cmp.form.controls.from.setValue('seeds');
        await settle(fixture, 350);
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ ids: ['s'] }));
        expect(q(el, 'la-expand-from-line')?.textContent).toContain('the 1 number seeded');
        go(el).click();
        expect(host.runs[0].ctx.frontier).toEqual(['s']);
        cmp.form.controls.from.setValue('all');
        await settle(fixture, 350);
        expect(q(el, 'la-expand-blocked')?.textContent).toContain('Too many.');
        go(el).click();
        expect(host.runs).toHaveLength(1);
    });

    it('Hold off names the held hubs too and says it is a recorded override; a link kind narrows the body', async () => {
        const { fixture, el, inv, host } = create();
        await settle(fixture);
        (q(el, 'la-expand-hold')!.querySelector('input') as HTMLInputElement).click();
        await settle(fixture, 350);
        expect(q(el, 'la-expand-no-hold')?.textContent).toContain('recorded as an override');
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ ids: ['a', 'b', 'hub'], includeHubs: true }));
        expect(foldHint(el, 'limits')).toContain('Busy expanded through');
        const sms = Array.from(q(el, 'la-expand-fold-links')!.querySelectorAll('mat-checkbox')).find((c) => c.textContent?.includes('sms'));
        (sms!.querySelector('input') as HTMLInputElement).click();
        await settle(fixture, 350);
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ linkKinds: ['voice'] }));
        expect(foldHint(el, 'links')).toContain('1 of 2 link kinds');
        go(el).click();
        expect(host.runs[0].req).toMatchObject({ hold: false, linkKinds: ['voice'] });
    });

    it('an out-of-bounds filter blocks Expand and opens its fold; four-eyes limits warn per degree', async () => {
        const { fixture, el, host, cmp } = create();
        host.limits.set({ budgetAbove: 50, fanOutAbove: null });
        await settle(fixture);
        expect(q(el, 'la-expand-needs-approval')?.textContent).toContain('The walk stops at the first degree held for approval');
        cmp.rung.controls.minEvents.setValue('0');
        await settle(fixture, 350);
        expect(el.querySelector('#la-expand-why')?.textContent).toContain('Fix the filters first.');
        expect(fold(el, 'links').open).toBe(false);
        go(el).click();
        fixture.detectChanges();
        expect(host.runs).toHaveLength(0);
        // the error is behind the Links fold: it opens, the Limits fold (nothing wrong there) stays closed
        expect(fold(el, 'links').open).toBe(true);
        expect(fold(el, 'limits').open).toBe(false);
    });

    it('the fold summaries say what each filter group has set', async () => {
        const { fixture, el, cmp } = create();
        await settle(fixture);
        cmp.rung.controls.minEvents.setValue('3');
        cmp.rung.controls.minDistinctDays.setValue('2');
        cmp.rung.controls.window.setValue('full');
        cmp.rung.controls.candidateDegreeMin.setValue('1');
        cmp.rung.controls.candidateDegreeMax.setValue('40');
        cmp.rung.controls.maxFanOut.setValue('20');
        cmp.rung.controls.budget.setValue('5000');
        cmp.form.controls.threshold.setValue(50);
        await settle(fixture, 350);
        expect(foldHint(el, 'links')).toContain('All link kinds · at least 3 events · on 2+ days');
        expect(foldHint(el, 'time')).toContain('All available data');
        expect(foldHint(el, 'limits')).toContain('Busy over 50 links · neighbours with 1–40 links · 20 per entity · 5,000 rows');
    });

    it('Direction is a popup choice: picking Outgoing re-reads the preview and is what Expand sends', async () => {
        const { fixture, el, inv, host } = create();
        await settle(fixture);
        expect(q(el, 'la-expand-direction')!.querySelector('button')!.textContent).toContain('Both ways');
        pickerSet(fixture, '[data-testid="la-expand-direction"]', 'out');
        await settle(fixture, 350);
        expect(q(el, 'la-expand-direction')!.querySelector('button')!.textContent).toContain('Outgoing');
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ direction: 'out' }));
        go(el).click();
        expect(host.runs[0].req.direction).toBe('out');
    });

    it('Degrees is a stepper bounded 1…the profile maximum: keys step it, the bounds say why, Expand sends it', async () => {
        const { fixture, el, host } = create();
        await settle(fixture);
        const s = spin(el);
        const dec = q(q(el, 'la-expand-degrees')!, 'stepper-decrement') as HTMLButtonElement;
        const inc = q(q(el, 'la-expand-degrees')!, 'stepper-increment') as HTMLButtonElement;
        expect(dec.getAttribute('aria-label')).toBe('Fewer degrees');
        expect(inc.getAttribute('aria-label')).toBe('More degrees');
        expect(dec.getAttribute('aria-disabled')).toBe('true');
        dec.click();
        fixture.detectChanges();
        expect(s.getAttribute('aria-valuenow')).toBe('1');
        expect(q(el, 'stepper-reason')!.textContent).toContain('One degree is the fewest.');
        s.dispatchEvent(new KeyboardEvent('keydown', { key: 'End' }));
        fixture.detectChanges();
        expect(s.getAttribute('aria-valuenow')).toBe('4');
        expect(inc.getAttribute('aria-disabled')).toBe('true');
        s.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowUp' }));
        fixture.detectChanges();
        expect(q(el, 'stepper-reason')!.textContent).toContain('4 degrees is the most this Industry pack allows.');
        s.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
        fixture.detectChanges();
        expect(s.getAttribute('aria-valuenow')).toBe('3');
        expect(go(el).textContent).toContain('Expand 3 degrees');
        go(el).click();
        expect(host.runs[0].req.degrees).toBe(3);
        await expectNoA11yViolations(el);
    });

    it('embedded in the Investigation tab: no dialog chrome, no Cancel, no focus grab; focus() lands on From', async () => {
        const { fixture, el, cmp, host } = create({ embedded: true });
        await settle(fixture, 350); // the tab section reads its preview after a 300 ms settle
        expect(q(el, 'la-expand-dialog')).toBeNull();
        const section = q(el, 'la-expand-section')!;
        expect(section.getAttribute('role')).toBeNull();
        expect(section.getAttribute('aria-label')).toBe('Expand');
        expect(Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.trim() === 'Cancel')).toBe(false);
        expect(el.querySelector('[aria-label="Close Expand"]')).toBeNull();
        expect(el.contains(document.activeElement)).toBe(false);
        section.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        expect(host.closed).toBe(0);
        cmp.focus();
        expect(document.activeElement).toBe(q(el, 'la-expand-from')!.querySelector('button'));
        // the preview and four-eyes line still show in the tab
        host.limits.set({ budgetAbove: 50, fanOutAbove: null });
        await settle(fixture);
        expect(q(el, 'la-expand-preview')?.textContent).toContain('This step adds about 42 numbers');
        expect(q(el, 'la-expand-needs-approval')).not.toBeNull();
        await expectNoA11yViolations(el);
    });

    it('embedded: previews are read once things settle (300 ms), never per selection click, and never while off screen', async () => {
        const { fixture, inv, host } = create({ embedded: true });
        // real time only: whenStable would wait out the debounce itself
        // the inputs reach the component on detectChanges, so render first, then let real time pass
        const wait = async (ms: number) => {
            fixture.detectChanges();
            await new Promise((r) => setTimeout(r, ms));
            fixture.detectChanges();
        };
        fixture.detectChanges();
        expect(inv.previewExpand).toHaveBeenCalledTimes(0); // not on mount
        await wait(400);
        expect(inv.previewExpand).toHaveBeenCalledTimes(1);
        // three selections in quick succession: one read, for the last one
        for (const ids of [['x'], ['x', 'y'], ['y']]) {
            const sel = { value: 'selection' as const, label: 'The selection', frontier: ids, heldFrontier: [], from: ids.join(','), blocked: null };
            host.ctx.set({ ...CTX, sources: [sel, ...CTX.sources!] });
            await wait(50);
        }
        expect(inv.previewExpand).toHaveBeenCalledTimes(1);
        await wait(400);
        expect(inv.previewExpand).toHaveBeenCalledTimes(2);
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ ids: ['y'] }));
        // off screen (the toolbox collapsed): a change reads nothing; back on screen, one read
        host.active.set(false);
        host.ctx.set({ ...CTX });
        await wait(400);
        expect(inv.previewExpand).toHaveBeenCalledTimes(2);
        host.active.set(true);
        await wait(400);
        expect(inv.previewExpand).toHaveBeenCalledTimes(3);
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ ids: ['a', 'b'] }));
    });

    it('embedded: a new selection makes it the From again (a right-click Expand starts from the node)', async () => {
        const { fixture, cmp, host } = create({ embedded: true });
        await settle(fixture);
        cmp.form.controls.from.setValue('seeds');
        const sel = { value: 'selection' as const, label: 'The selection', frontier: ['x'], heldFrontier: [], from: 'the 1 number you selected', blocked: null };
        host.ctx.set({ ...CTX, sources: [sel, ...CTX.sources!] });
        await settle(fixture);
        expect(cmp.form.controls.from.value).toBe('selection');
    });

    it('a refused preview (422) says why, in place', async () => {
        const { fixture, el } = create({
            preview: () => throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: 'budget above the cap' } } })),
        });
        await settle(fixture);
        expect(q(el, 'la-expand-preview')?.textContent).toContain('budget above the cap');
    });

    it('every start item held: Expand says untick Hold; a blocked context says why beside the button', async () => {
        const held = create({ ctx: { frontier: [], sources: undefined } });
        await settle(held.fixture);
        expect(held.el.querySelector('#la-expand-why')?.textContent).toContain('Untick “Hold busy entities”');
        TestBed.resetTestingModule();
        const blocked = create({ ctx: { blocked: 'This changes the Investigation log and needs the Incident management capability.' } });
        await settle(blocked.fixture);
        expect(q(blocked.el, 'la-expand-blocked')?.textContent).toContain('Incident management');
        // T-193: shown once; the button points at the alert instead of repeating it in a second line
        expect(blocked.el.querySelector('#la-expand-why')).toBeNull();
        expect(blocked.el.querySelector('[data-testid="la-expand-go"]')!.getAttribute('aria-describedby')).toContain('la-expand-blocked-why');
        blocked.el.querySelector<HTMLButtonElement>('[data-testid="la-expand-go"]')!.click();
        expect(blocked.host.runs).toHaveLength(0);
    });

    it('Basic: previews through the explorer adapter, not recorded, no Reciprocal and no Investigation read settings', async () => {
        const { fixture, el, inv, basic, host, cmp } = create({
            ctx: { mode: 'basic', hasWindow: false, sources: undefined },
            basic: { ...PREVIEW, excludedByLists: [] },
        });
        await settle(fixture);
        expect(basic).toHaveBeenCalledWith(['a', 'b'], expect.objectContaining({ direction: 'either', hold: true }));
        expect(inv.previewExpand).not.toHaveBeenCalled();
        expect(el.textContent).toContain('(not recorded)');
        expect(cmp.directionOptions().map((o) => o.value)).not.toContain('reciprocal');
        expect(q(el, 'la-expand-fold-time')).toBeNull();
        expect(q(el, 'la-expand-rung')).toBeNull();
        expect(q(el, 'la-expand-basic-note')).not.toBeNull();
        go(el).click();
        expect(host.runs[0].req.rung).toBeUndefined();
        await expectNoA11yViolations(el);
    });
});
