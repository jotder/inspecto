import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GraphRunsService, ModuleView } from '@inspecto/link-analysis/api/graph-runs.service';
import { LinkAnalysisModulesComponent, isLinkAnalysisModule } from './link-analysis-modules.component';

const m = (id: string, over: Partial<ModuleView> = {}): ModuleView => ({
    id,
    title: id + ' title',
    state: 'ACTIVE',
    buildId: 'b',
    enabledInSpace: true,
    ...over,
});

function make(modules: () => ReturnType<GraphRunsService['modules']>) {
    const spy = vi.fn(modules);
    TestBed.configureTestingModule({
        imports: [LinkAnalysisModulesComponent],
        providers: [provideNoopAnimations(), { provide: GraphRunsService, useValue: { modules: spy } }],
    });
    const fixture = TestBed.createComponent(LinkAnalysisModulesComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const open = () => {
        el.querySelector<HTMLButtonElement>('[data-testid=modules-toggle]')!.click();
        fixture.detectChanges();
    };
    return { el, open, spy };
}

describe('LinkAnalysisModulesComponent (DR-U12)', () => {
    it('recognises exactly the Link Analysis modules', () => {
        expect(['geo-link', 'la-api', 'la-graph'].every(isLinkAnalysisModule)).toBe(true);
        expect(['storage', 'agent', 'lake'].some(isLinkAnalysisModule)).toBe(false);
    });

    it('loads only when opened, lists only Link Analysis modules, and says why one is not active', () => {
        const { el, open, spy } = make(() =>
            of({
                hostBuildId: 'h',
                modules: [
                    m('geo-link'),
                    m('la-graph', { state: 'INERT', reasons: ['requires la-core'] }),
                    m('la-store-pg', { state: 'not-installed', enabledInSpace: false }),
                    m('agent'),
                ],
            }),
        );
        expect(spy).not.toHaveBeenCalled();
        open();
        expect(spy).toHaveBeenCalledTimes(1);
        expect(el.querySelector('[data-testid=module-agent]')).toBeNull();
        expect(el.querySelector('[data-testid=module-geo-link]')!.textContent).toContain('Active');
        expect(el.querySelector('[data-testid=module-la-graph]')!.textContent).toContain('Inert');
        expect(el.textContent).toContain('requires la-core');
        expect(el.querySelector('[data-testid=module-la-store-pg]')!.textContent).toContain('Not installed');
        // T-083: the title is the text; the module id is a tooltip only
        const name = el.querySelector<HTMLElement>('[data-testid=module-name]')!;
        expect(name.textContent!.trim()).toBe('geo-link title');
        expect(name.title).toBe('geo-link');
        expect(el.querySelector('[data-testid=modules-list]')!.textContent).not.toMatch(/geo-link(?! title)/);
    });

    it('T-083: a reason naming another module says its plain name; an unlisted id or a longer word stays as written', () => {
        const { el, open } = make(() =>
            of({
                hostBuildId: 'h',
                modules: [
                    m('la-core', { title: 'Link analysis core and ports' }),
                    m('la-graph', { state: 'INERT', reasons: ['requires la-core (and la-core-x, not-listed)'] }),
                ],
            }),
        );
        open();
        expect(el.querySelector('[data-testid=module-reason]')!.textContent!.trim()).toBe(
            'requires Link analysis core and ports (and la-core-x, not-listed)',
        );
    });

    it('draws the list as an opaque card above the canvas, so graph labels do not bleed through it', () => {
        const { el, open } = make(() => of({ hostBuildId: 'h', modules: [m('geo-link')] }));
        open();
        const card = el.querySelector<HTMLElement>('[data-testid=modules-list]')!.parentElement!; // the popover card
        expect(card.getAttribute('style')).toContain('--gamma-bg-card');
        expect(card.classList.contains('z-30')).toBe(true);
        expect(card.className).toContain('bottom-full'); // opens upward from the footer
        expect(card.className).toContain('right-0');
    });

    it('states a failed read instead of an empty list', () => {
        const { el, open } = make(() => throwError(() => new Error('x')));
        open();
        expect(el.querySelector('[data-testid=modules-error]')).not.toBeNull();
        expect(el.querySelector('[data-testid=modules-list]')).toBeNull();
    });

    describe('accessibility (axe, LA-A11Y-AUDIT-1)', () => {
        it('closed: a named toggle that says it is collapsed', async () => {
            const { el } = make(() => of({ hostBuildId: 'h', modules: [] }));
            expect(el.querySelector('[data-testid=modules-toggle]')!.getAttribute('aria-expanded')).toBe('false');
            await expectNoA11yViolations(el);
        });

        it('open with an active, an inert, a switched-off and a not-installed module', async () => {
            const { el, open } = make(() =>
                of({
                    hostBuildId: 'h',
                    modules: [
                        m('geo-link'),
                        m('la-graph', { state: 'INERT', reasons: ['requires la-core'] }),
                        m('la-api', { enabledInSpace: false }),
                        m('la-store-pg', { state: 'not-installed', enabledInSpace: false }),
                    ],
                }),
            );
            open();
            await expectNoA11yViolations(el);
        });

        it('open with no Link Analysis module listed', async () => {
            const { el, open } = make(() => of({ hostBuildId: 'h', modules: [] }));
            open();
            await expectNoA11yViolations(el);
        });

        it('open after a failed read: the notice is a status', async () => {
            const { el, open } = make(() => throwError(() => new Error('x')));
            open();
            expect(el.querySelector('[data-testid=modules-error]')!.getAttribute('role')).toBe('status');
            await expectNoA11yViolations(el);
        });
    });
});
