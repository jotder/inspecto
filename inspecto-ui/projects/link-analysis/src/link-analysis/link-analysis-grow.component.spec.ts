import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { GrowPreview, InvService } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GrowContext, GrowRequest } from './grow-exclude';
import { GrowExcludeStore } from './grow-exclude.store';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { LinkAnalysisGrowComponent } from './link-analysis-grow.component';

const TERMS = { node: 'number', nodes: 'numbers', link: 'call', links: 'calls' };
const CTX: GrowContext = {
    mode: 'investigation',
    frontier: ['a', 'b'],
    heldFrontier: ['hub'],
    from: 'the outer ring (degree 1): 3 numbers',
    blocked: null,
    linkKinds: ['voice', 'sms'],
    hubThreshold: 500,
    terms: TERMS,
    secondStepCap: 100,
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
    imports: [LinkAnalysisGrowComponent],
    template: `<inspecto-link-analysis-grow
        [ctx]="ctx()"
        [presets]="{ budget: 100 }"
        (grow)="grown.push($event)"
        (closed)="closed = closed + 1"
    ></inspecto-link-analysis-grow>`,
})
class Host {
    readonly ctx = signal<GrowContext>(CTX);
    readonly grown: GrowRequest[] = [];
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
    const basic = vi.fn(async (_frontier: string[], _req: GrowRequest) => opts.basic ?? null);
    store.basicAdapter = { preview: basic };
    if (opts.ctx) fixture.componentInstance.ctx.set({ ...CTX, ...opts.ctx });
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, inv, basic, host: fixture.componentInstance };
}

const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
const go = (el: HTMLElement) => q(el, 'la-grow-go') as HTMLButtonElement;

describe('LinkAnalysisGrowComponent (LA-GROW-EXCLUDE-1 §3.1)', () => {
    it('opens on the defaults — Both ways, 1 step, busy numbers held — with the first-time hint, and is axe-clean', async () => {
        const { fixture, el } = create();
        await settle(fixture);
        const pressed = Array.from(el.querySelectorAll('mat-button-toggle.mat-button-toggle-checked')).map((t) =>
            t.textContent?.trim(),
        );
        expect(pressed).toEqual(['Both ways', '1 step']);
        // every direction is an icon AND a word
        expect(Array.from(el.querySelectorAll('mat-button-toggle mat-icon')).length).toBe(3);
        expect(q(el, 'la-grow-hold')?.textContent).toContain('Hold busy numbers with more than 500 contacts');
        expect(q(el, 'la-grow-from')?.textContent).toContain('the outer ring (degree 1): 3 numbers');
        expect(q(el, 'la-grow-hint')?.textContent).toContain('one ring at a time');
        expect(q(el, 'la-grow-hint')?.querySelector('[data-testid="la-hint-guide"]')?.textContent).toContain(
            'Grow and clean a graph',
        );
        await expectNoA11yViolations(el);
        (q(el, 'la-grow-hint')!.querySelector('button') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(q(el, 'la-grow-hint')).toBeNull();
        expect(localStorage.getItem('inspecto.la.hint.grow')).toBe('1');
    });

    it('previews the step before anything changes — the expand body and the pack-worded numbers, list chips with counts', async () => {
        const { fixture, el, inv } = create();
        await settle(fixture);
        expect(inv.previewExpand).toHaveBeenCalledWith('inv-1', {
            op: 'expand',
            ids: ['a', 'b'],
            budget: 100,
            direction: 'either',
            hubThreshold: 500,
        });
        expect(q(el, 'la-grow-preview')?.textContent).toContain(
            'This step adds about 42 numbers and 120 calls; 1 busy number will be held; 6 numbers are removed by your exclusion lists.',
        );
        expect(q(el, 'la-grow-lists')?.textContent).toContain('Call centres · 6');
        expect(q(el, 'la-grow-preview')?.getAttribute('role')).toBe('status');
    });

    it('Grow hands the choice to the screen; Cancel and Escape close', async () => {
        const { fixture, el, host } = create();
        await settle(fixture);
        go(el).click();
        expect(host.grown).toEqual([
            { direction: 'either', steps: 1, linkKinds: null, window: 'inherit', hold: true, threshold: 500 },
        ]);
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Cancel') as HTMLButtonElement).click();
        q(el, 'la-grow-panel')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
        expect(host.closed).toBe(2);
    });

    it('a direction, a link kind and Hold off change the previewed body; Hold off says it is a recorded override', async () => {
        const { fixture, el, inv } = create();
        await settle(fixture);
        const outgoing = Array.from(el.querySelectorAll('mat-button-toggle button')).find((b) =>
            b.textContent?.includes('Outgoing'),
        ) as HTMLButtonElement;
        outgoing.click();
        (q(el, 'la-grow-hold')!.querySelector('input') as HTMLInputElement).click();
        await settle(fixture, 350);
        expect(inv.previewExpand).toHaveBeenLastCalledWith('inv-1', {
            op: 'expand',
            ids: ['a', 'b', 'hub'],
            budget: 100,
            direction: 'out',
            includeHubs: true,
        });
        expect(q(el, 'la-grow-no-hold')?.textContent).toContain('recorded as an override');
        // More options: link kinds (pack-worded) and the window choice
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('More options')) as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(el.textContent).toContain('Calls to follow');
        const sms = Array.from(el.querySelectorAll('#la-grow-more mat-checkbox')).find((c) => c.textContent?.includes('sms'));
        (sms!.querySelector('input') as HTMLInputElement).click();
        await settle(fixture);
        expect(inv.previewExpand.mock.lastCall?.[1]).toMatchObject({ linkKinds: ['voice'] });
        expect(el.textContent).toContain('Time window');
    });

    it('2 steps is greyed, with the reason, until the previewed first step is under the cap', async () => {
        const big = create({ preview: () => of({ ...PREVIEW, addsNodes: 3400 }) });
        await settle(big.fixture);
        const two = q(big.el, 'la-grow-two-steps')!.querySelector('button') as HTMLButtonElement;
        expect(two.getAttribute('aria-disabled')).toBe('true');
        expect(q(big.el, 'la-grow-steps-why')?.textContent).toContain(
            '2 steps is offered only when the first step adds at most 100 numbers; this one adds about 3,400.',
        );
    });

    it('a preview that arrives after "2 steps" was chosen and forbids it falls back to 1 step — 2 never travels', async () => {
        let adds = 10;
        const { fixture, el, host } = create({ preview: () => of({ ...PREVIEW, addsNodes: adds }) });
        await settle(fixture);
        (q(el, 'la-grow-two-steps')!.querySelector('button') as HTMLButtonElement).click();
        fixture.detectChanges();
        adds = 5000; // a new choice re-previews: the first step is now too big for a second
        const outgoing = Array.from(el.querySelectorAll('mat-button-toggle button')).find((b) =>
            b.textContent?.includes('Outgoing'),
        ) as HTMLButtonElement;
        outgoing.click();
        await settle(fixture, 350);
        await settle(fixture);
        expect(q(el, 'la-grow-steps-why')?.textContent).toContain('this one adds about 5,000');
        go(el).click();
        expect(host.grown[0].steps).toBe(1);
    });

    it('2 steps is offered under the cap, and Grow then asks for two steps', async () => {
        const { fixture, el, host } = create();
        await settle(fixture);
        expect(q(el, 'la-grow-steps-why')).toBeNull();
        (q(el, 'la-grow-two-steps')!.querySelector('button') as HTMLButtonElement).click();
        fixture.detectChanges();
        go(el).click();
        expect(host.grown[0].steps).toBe(2);
    });

    it.each([404, 503])('degrades without the preview route (%i): no numbers, a quiet line, Grow still runs', async (status) => {
        {
            const { fixture, el, host } = create({ preview: () => throwError(() => new HttpErrorResponse({ status })) });
            await settle(fixture);
            expect(q(el, 'la-grow-no-preview')?.textContent).toContain('No preview on this server');
            expect(q(el, 'la-grow-preview')?.textContent).not.toContain('This step adds');
            expect(el.querySelector('inspecto-alert[variant="warning"]')).toBeNull();
            expect(q(el, 'la-grow-steps-why')?.textContent).toContain('none is available here');
            go(el).click();
            expect(host.grown).toHaveLength(1);
        }
    });

    it('a refused preview (422) says why, in place', async () => {
        const { fixture, el } = create({
            preview: () =>
                throwError(() => new HttpErrorResponse({ status: 422, error: { error: { message: 'budget above the Space cap' } } })),
        });
        await settle(fixture);
        expect(el.textContent).toContain('The preview was refused');
        expect(el.textContent).toContain('budget above the Space cap');
    });

    it('says why Grow is not possible instead of a silent grey button — and a click does nothing', async () => {
        const { fixture, el, host, inv } = create({ ctx: { blocked: 'This changes the Investigation log and needs the Incident management capability.' } });
        await settle(fixture);
        expect(q(el, 'la-grow-blocked')?.textContent).toContain('needs the Incident management capability');
        expect(go(el).getAttribute('aria-disabled')).toBe('true');
        expect(el.querySelector('#la-grow-why')?.textContent).toContain('Incident management');
        go(el).click();
        expect(host.grown).toEqual([]);
        expect(inv.previewExpand).not.toHaveBeenCalled();
    });

    it('every edge item held: Grow says untick Hold, and unticking it allows the override', async () => {
        const { fixture, el, host } = create({ ctx: { frontier: [], heldFrontier: ['hub'] } });
        await settle(fixture);
        expect(el.querySelector('#la-grow-why')?.textContent).toContain('Untick “Hold busy numbers”');
        (q(el, 'la-grow-hold')!.querySelector('input') as HTMLInputElement).click();
        await settle(fixture, 350);
        go(el).click();
        expect(host.grown[0].hold).toBe(false);
    });

    it('Basic: previews through the explorer adapter, says "not recorded" and offers no window', async () => {
        const { fixture, el, inv, basic } = create({
            ctx: { mode: 'basic', hasWindow: false, from: 'the 2 numbers not grown yet' },
            basic: { ...PREVIEW, excludedByLists: [] },
        });
        await settle(fixture);
        expect(basic).toHaveBeenCalledWith(['a', 'b'], expect.objectContaining({ direction: 'either', hold: true }));
        expect(inv.previewExpand).not.toHaveBeenCalled();
        expect(el.textContent).toContain('(not recorded)');
        expect(q(el, 'la-grow-preview')?.textContent).toContain('42 numbers');
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('More options')) as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(el.textContent).not.toContain('Time window');
        await expectNoA11yViolations(el);
    });

    it('Basic without the preview route: the adapter answers nothing → no numbers, still growable', async () => {
        const { fixture, el, host } = create({ ctx: { mode: 'basic', hasWindow: false }, basic: null });
        await settle(fixture);
        expect(q(el, 'la-grow-no-preview')).not.toBeNull();
        go(el).click();
        expect(host.grown).toHaveLength(1);
    });
});
