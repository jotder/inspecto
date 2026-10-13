import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import type { InvestigationStepResult } from '@inspecto/link-analysis/api/inv.service';
import { InvestigationConnectOpComponent } from './investigation-connect-op.component';
import { InvestigationSessionStore } from './link-analysis-investigation.store';

const STEP = {
    step: 2,
    op: 'connect',
    truncated: false,
    read: {
        pairs: [
            { a: '5551110001', b: '5551110002', connected: true, hops: 2, routes: 2, route: ['5551110001', 'm1', '5551110002'] },
            { a: '5551110001', b: '5551110003', connected: false, notWalked: ['hub1'] },
        ],
        noLinks: ['5551110003'],
    },
} as unknown as InvestigationStepResult;

function setup(applyOk = true, step: InvestigationStepResult | null = null) {
    const store = {
        busy: signal(false),
        error: signal('boom'),
        lastStep: signal<InvestigationStepResult | null>(step),
        apply: vi.fn(async () => applyOk),
    };
    TestBed.configureTestingModule({
        imports: [InvestigationConnectOpComponent],
        providers: [provideNoopAnimations(), { provide: InvestigationSessionStore, useValue: store }],
    });
    const fixture = TestBed.createComponent(InvestigationConnectOpComponent);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, store, el: fixture.nativeElement as HTMLElement };
}

describe('InvestigationConnectOpComponent (LA-SEED-CONNECT-1)', () => {
    it('sends ONE connect op with the pasted numbers, normalised, and the hop limit of 3', async () => {
        const { fixture, cmp, store, el } = setup();
        cmp.text.set('555-111-0001\n5551110002, 5551110002 hello');
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="seed-summary"]')?.textContent).toContain('2 numbers to connect');
        expect(el.textContent).toContain('hello'); // the unusable entry is listed, not dropped
        await expectNoA11yViolations(el);
        await cmp.connect();
        expect(store.apply).toHaveBeenCalledTimes(1);
        expect(store.apply).toHaveBeenLastCalledWith({ op: 'connect', ids: ['5551110001', '5551110002'], maxHops: 3 });
    });

    it('needs two numbers: Connect seeds is disabled for one and nothing is sent', async () => {
        const { fixture, cmp, store, el } = setup();
        cmp.text.set('5551110001');
        fixture.detectChanges();
        const button = Array.from(el.querySelectorAll('button')).find((b) =>
            b.textContent?.includes('Connect seeds'),
        ) as HTMLButtonElement;
        expect(button.disabled).toBe(true);
        await cmp.connect();
        expect(store.apply).not.toHaveBeenCalled();
    });

    it("cancels the native form submit (no page navigation)", async () => {
        const { fixture, cmp, el } = setup();
        cmp.text.set('5551110001 5551110002');
        fixture.detectChanges();
        const seen: Event[] = [];
        const onSubmit = (e: Event) => seen.push(e);
        el.ownerDocument.addEventListener('submit', onSubmit);
        try {
            (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Connect seeds')) as HTMLButtonElement).click();
            await fixture.whenStable();
        } finally {
            el.ownerDocument.removeEventListener('submit', onSubmit);
        }
        expect(seen.length).toBe(1);
        expect(seen[0].defaultPrevented).toBe(true);
    });

    it('shows the server refusal verbatim', async () => {
        const { fixture, cmp, el } = setup(false);
        cmp.text.set('5551110001 5551110002');
        await cmp.connect();
        fixture.detectChanges();
        expect(el.textContent).toContain('boom');
    });

    it('renders every pair: the connected route, and a disconnected pair with the hub it stopped at', async () => {
        const { fixture, el } = setup(true, STEP);
        fixture.detectChanges();
        const rows = Array.from(el.querySelectorAll('tbody tr')).map((r) => r.textContent?.replace(/\s+/g, ' ').trim());
        expect(rows[0]).toContain('2 hops · 2 routes');
        expect(rows[0]).toContain('5551110001 → m1 → 5551110002');
        expect(rows[1]).toContain('Not connected within 3 hops (stopped at high-connectivity hub1)');
        expect(el.querySelector('[data-testid="connect-summary"]')?.textContent).toContain('1 of 2 pairs connected');
        expect(el.textContent).toContain('No links found for');
        await expectNoA11yViolations(el);
    });

    it('Expand from these nodes sends one expand over the nodes on the routes', async () => {
        const { fixture, store, el } = setup(true, STEP);
        fixture.detectChanges();
        (Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Expand from these nodes')) as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(store.apply).toHaveBeenLastCalledWith({ op: 'expand', ids: ['5551110001', 'm1', '5551110002'] });
    });

    it('shows no table before a connect, and none for a step of another op', () => {
        expect(setup().el.querySelector('table')).toBeNull();
        TestBed.resetTestingModule();
        const other = { ...STEP, op: 'expand' } as InvestigationStepResult;
        expect(setup(true, other).el.querySelector('table')).toBeNull();
    });
});
