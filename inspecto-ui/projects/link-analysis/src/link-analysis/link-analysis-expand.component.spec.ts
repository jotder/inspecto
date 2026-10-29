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
        [presets]="{ budget: 100 }"
        [limits]="limits()"
        (expand)="runs.push($event)"
        (closed)="closed = closed + 1"
    ></inspecto-link-analysis-expand>`,
})
class Host {
    readonly ctx = signal<GrowContext>(CTX);
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

function create(opts: { preview?: () => Observable<GrowPreview>; ctx?: Partial<GrowContext>; basic?: GrowPreview | null } = {}) {
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
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const cmp = fixture.debugElement.query(By.directive(LinkAnalysisExpandComponent)).componentInstance as LinkAnalysisExpandComponent;
    return { fixture, el, inv, basic, host: fixture.componentInstance, cmp };
}

const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
const go = (el: HTMLElement) => q(el, 'la-expand-go') as HTMLButtonElement;
const toggle = (el: HTMLElement, text: string) =>
    (Array.from(el.querySelectorAll('mat-button-toggle button')).find((b) => b.textContent?.trim() === text) as HTMLButtonElement).click();

describe('LinkAnalysisExpandComponent (LA-STUDIO-UI-REORG-1 §5.4)', () => {
    it('opens on the defaults: the first usable From, Both ways, 1 degree, supernodes held; degrees run 1…maxDegree; axe-clean', async () => {
        const { fixture, el } = create();
        await settle(fixture);
        const pressed = Array.from(el.querySelectorAll('mat-button-toggle.mat-button-toggle-checked')).map((t) => t.textContent?.trim());
        expect(pressed).toEqual(['Both ways', '1']);
        expect(Array.from(q(el, 'la-expand-degrees')!.querySelectorAll('mat-button-toggle')).map((t) => t.textContent?.trim())).toEqual([
            '1',
            '2',
            '3',
            '4',
        ]);
        // an Investigation also offers Reciprocal
        expect(el.textContent).toContain('Reciprocal');
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
        toggle(el, '3');
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
        toggle(el, '3');
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
        q(el, 'la-expand-dialog')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
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
        q(el, 'la-expand-advanced-toggle')!.click();
        fixture.detectChanges();
        const sms = Array.from(el.querySelectorAll('#la-expand-advanced mat-checkbox')).find((c) => c.textContent?.includes('sms'));
        (sms!.querySelector('input') as HTMLInputElement).click();
        await settle(fixture, 350);
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', expect.objectContaining({ linkKinds: ['voice'] }));
        go(el).click();
        expect(host.runs[0].req).toMatchObject({ hold: false, linkKinds: ['voice'] });
    });

    it('an out-of-bounds Advanced field blocks Expand and opens the fold; four-eyes limits warn per degree', async () => {
        const { fixture, el, host, cmp } = create();
        host.limits.set({ budgetAbove: 50, fanOutAbove: null });
        await settle(fixture);
        expect(q(el, 'la-expand-needs-approval')?.textContent).toContain('The walk stops at the first degree held for approval');
        cmp.rung.controls.minEvents.setValue('0');
        await settle(fixture, 350);
        expect(el.querySelector('#la-expand-why')?.textContent).toContain('Fix the Advanced fields first.');
        go(el).click();
        expect(host.runs).toHaveLength(0);
        expect(cmp.moreOpen()).toBe(true);
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
        blocked.el.querySelector<HTMLButtonElement>('[data-testid="la-expand-go"]')!.click();
        expect(blocked.host.runs).toHaveLength(0);
    });

    it('Basic: previews through the explorer adapter, not recorded, no Reciprocal and no Investigation read settings', async () => {
        const { fixture, el, inv, basic, host } = create({
            ctx: { mode: 'basic', hasWindow: false, sources: undefined },
            basic: { ...PREVIEW, excludedByLists: [] },
        });
        await settle(fixture);
        expect(basic).toHaveBeenCalledWith(['a', 'b'], expect.objectContaining({ direction: 'either', hold: true }));
        expect(inv.previewExpand).not.toHaveBeenCalled();
        expect(el.textContent).toContain('(not recorded)');
        expect(el.textContent).not.toContain('Reciprocal');
        q(el, 'la-expand-advanced-toggle')!.click();
        fixture.detectChanges();
        expect(q(el, 'la-expand-rung')).toBeNull();
        expect(q(el, 'la-expand-basic-note')).not.toBeNull();
        go(el).click();
        expect(host.runs[0].req.rung).toBeUndefined();
        await expectNoA11yViolations(el);
    });
});
