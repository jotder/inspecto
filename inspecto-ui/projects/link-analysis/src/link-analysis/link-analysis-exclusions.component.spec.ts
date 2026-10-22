import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ExclusionListSummary } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit } from '@inspecto/core/testing/pen-field';
import { ListInForce, RemovedRow } from './grow-exclude';
import { LinkAnalysisExclusionsComponent } from './link-analysis-exclusions.component';

const IN_FORCE: ListInForce[] = [
    { listId: 'cc', title: 'Call centres', ownerKind: 'analyst', restricted: false, removed: 12, step: 2, steps: [2], switchOffWhyNot: 'Applied at step 2; only the latest step can be switched off (with Undo). Later steps depend on it.' },
    { listId: 'pol', title: 'Politicians', ownerKind: 'admin', restricted: true, removed: 5, step: 4, steps: [4], switchOffWhyNot: null },
];
const LISTS: ExclusionListSummary[] = [
    { id: 'cc', title: 'Call centres', entityType: 'msisdn', ownerKind: 'analyst', autoApply: false, visibility: 'open', size: 12 },
    { id: 'pol', title: 'Politicians', entityType: 'msisdn', ownerKind: 'admin', autoApply: true, visibility: 'restricted', size: 40 },
    { id: 'test', title: 'Test numbers', entityType: 'msisdn', ownerKind: 'analyst', autoApply: false, visibility: 'open', size: 3 },
];

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisExclusionsComponent],
    template: `<inspecto-link-analysis-exclusions
        [mode]="mode()"
        [inForce]="inForce()"
        line="17 numbers hidden by 2 lists"
        [removed]="removed()"
        [lists]="lists()"
        [basicHidden]="3"
        [terms]="{ node: 'number', nodes: 'numbers', link: 'call', links: 'calls' }"
        [whyNot]="whyNot()"
        [createWhyNot]="''"
        (switchOff)="events.push('off:' + $event)"
        (switchOn)="events.push('on:' + $event)"
        (showAgain)="events.push('again')"
        (createList)="events.push('create:' + $event)"
        (closed)="events.push('closed')"
    ></inspecto-link-analysis-exclusions>`,
})
class Host {
    readonly mode = signal<'investigation' | 'basic'>('investigation');
    readonly inForce = signal<ListInForce[]>(IN_FORCE);
    readonly removed = signal<{ rows: RemovedRow[]; restrictedCount: number }>({
        rows: [
            { id: '+4477001', why: 'List: Call centres', step: 2 },
            { id: '+4477002', why: 'busy number above 500 contacts', step: 5 },
        ],
        restrictedCount: 5,
    });
    readonly lists = signal<ExclusionListSummary[] | null>(LISTS);
    readonly whyNot = signal('');
    readonly events: string[] = [];
}

function create() {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, host: fixture.componentInstance };
}

const byLabel = (el: HTMLElement, label: string) => el.querySelector(`[aria-label="${label}"]`) as HTMLButtonElement;

beforeEach(() => localStorage.clear());

describe('LinkAnalysisExclusionsComponent (LA-GROW-EXCLUDE-1 §3.3)', () => {
    afterEach(() => vi.restoreAllMocks());

    it('teaches once (first-time hint, pack-worded, naming the user guide chapter), remembered per browser; axe-clean', async () => {
        const { fixture, el, host } = create();
        const hint = () => el.querySelector('[data-testid="la-excl-hint"]') as HTMLElement | null;
        expect(hint()?.textContent).toContain('keeps numbers out of every step');
        expect(hint()?.textContent).toContain('Category lists come from your');
        expect(hint()?.querySelector('[data-testid="la-hint-guide"]')?.textContent).toContain('Grow and clean a graph');
        await expectNoA11yViolations(el);
        host.mode.set('basic');
        fixture.detectChanges();
        expect(hint()?.textContent).toContain('hides numbers from this graph only (not recorded)');
        (Array.from(hint()!.querySelectorAll('button')).find((b) => b.textContent?.includes('Got it')) as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(hint()).toBeNull();
        expect(localStorage.getItem('inspecto.la.hint.exclusions')).toBe('1');
        fixture.destroy();
        TestBed.resetTestingModule();
        expect(create().el.querySelector('[data-testid="la-excl-hint"]')).toBeNull(); // seen: not shown again
    });

    it('blocked storage (private window): the hint shows, and Got it still hides it — never an error', () => {
        vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
            throw new Error('SecurityError');
        });
        vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new Error('SecurityError');
        });
        const { fixture, el } = create();
        const hint = () => el.querySelector('[data-testid="la-excl-hint"]');
        expect(hint()).not.toBeNull();
        (Array.from(hint()!.querySelectorAll('button')).find((b) => b.textContent?.includes('Got it')) as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(hint()).toBeNull();
    });

    it('reads in plain words: the line, the lists in force with who keeps them and what they removed; axe-clean', async () => {
        const { el } = create();
        expect(el.querySelector('[data-testid="la-excl-line"]')?.textContent).toContain('17 numbers hidden by 2 lists');
        const chips = el.querySelector('[data-testid="la-excl-in-force"]')!.textContent!;
        expect(chips).toContain('Call centres');
        expect(chips).toContain('Analyst');
        expect(chips).toContain('12 removed');
        expect(chips).toContain('Category');
        expect(chips).toContain('about 5 removed'); // restricted: rounded, said so
        await expectNoA11yViolations(el);
    });

    it('switches a list off (the latest step) or says why not; switches another on', () => {
        const { el, host } = create();
        const off = byLabel(el, 'Switch off Call centres');
        expect(off.getAttribute('aria-disabled')).toBe('true');
        off.click();
        byLabel(el, 'Switch off Politicians').click();
        byLabel(el, 'Switch on Test numbers').click();
        expect(host.events).toEqual(['off:pol', 'on:test']);
    });

    it('Show what was removed: a table, with the restricted lists only counted', async () => {
        const { fixture, el } = create();
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Show what was removed')) as HTMLButtonElement).click();
        fixture.detectChanges();
        const table = el.querySelector('[data-testid="la-excl-table"]') as HTMLTableElement;
        expect(Array.from(table.querySelectorAll('th')).map((h) => h.getAttribute('scope'))).toEqual(['col', 'col', 'col']);
        expect(table.textContent).toContain('+4477002');
        expect(table.textContent).toContain('busy number above 500 contacts');
        expect(el.querySelector('[data-testid="la-excl-restricted"]')?.textContent).toContain(
            'About 5 more were removed by restricted lists',
        );
        await expectNoA11yViolations(el);
    });

    it('without the capability the switches say why and do nothing', () => {
        const { fixture, el, host } = create();
        host.whyNot.set('This changes the Investigation log and needs the Incident management capability.');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-excl-why"]')?.textContent).toContain('Incident management');
        byLabel(el, 'Switch off Politicians').click();
        byLabel(el, 'Switch on Test numbers').click();
        expect(host.events).toEqual([]);
    });

    it('with no list yet it teaches: "Create your first list"', () => {
        const { fixture, el, host } = create();
        host.lists.set([]);
        host.inForce.set([]);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-excl-first"]')?.textContent).toContain('No exclusion list yet');
        penEdit(fixture, penByLabel(el, 'List name')!, 'Call centres');
        fixture.detectChanges();
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Create your first list')) as HTMLButtonElement).click();
        expect(host.events).toEqual(['create:Call centres']);
    });

    it('names the category lists the administrator set up; a list index that cannot be read shows no list section', () => {
        const { fixture, el, host } = create();
        host.inForce.set([]);
        host.lists.set([LISTS[1]]);
        fixture.detectChanges();
        // the admin list not in force is offered to switch on, and named as a category list
        expect(el.querySelector('[data-testid="la-excl-categories"]')?.textContent).toContain('Politicians');
        host.lists.set(null);
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-excl-categories"]')).toBeNull();
        expect(el.querySelector('[data-testid="la-excl-first"]')).toBeNull();
        expect(el.textContent).not.toContain('Not in force');
    });

    it('Basic: says how many are hidden on this graph (not recorded) and brings them back', async () => {
        const { fixture, el, host } = create();
        host.mode.set('basic');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-excl-basic-line"]')?.textContent).toContain('3 numbers hidden on this graph (not recorded)');
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Show them again')) as HTMLButtonElement).click();
        byLabel(el, 'Close Hidden by lists').click();
        expect(host.events).toEqual(['again', 'closed']);
        await expectNoA11yViolations(el);
    });
});
