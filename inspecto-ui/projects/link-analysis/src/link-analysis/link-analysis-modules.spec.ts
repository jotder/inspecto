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
    const el = fixture.nativeElement as HTMLElement;
    /** The page creates the section only when ⚙ Settings shows it: its first render is the opening. */
    const open = () => fixture.detectChanges();
    return { el, open, spy };
}

describe('LinkAnalysisModulesComponent (DR-U12)', () => {
    it('recognises exactly the Link Analysis modules', () => {
        expect(['geo-link', 'la-api', 'la-graph'].every(isLinkAnalysisModule)).toBe(true);
        expect(['storage', 'agent', 'lake'].some(isLinkAnalysisModule)).toBe(false);
    });

    it('loads once when shown, lists only Link Analysis modules, and says why one is not active', () => {
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

    it('is a ⚙ Settings section: the list in the page flow, no toggle and no popover (operator 2026-10-29)', () => {
        const { el, open, spy } = make(() => of({ hostBuildId: 'h', modules: [m('geo-link')] }));
        open();
        open();
        expect(spy).toHaveBeenCalledTimes(1);
        expect(el.querySelector('[data-testid=modules-toggle]')).toBeNull();
        expect(el.querySelector('button')).toBeNull();
        const card = el.querySelector<HTMLElement>('[data-testid=modules-list]')!.parentElement!;
        expect(card.className).not.toContain('absolute');
    });

    it('states a failed read instead of an empty list', () => {
        const { el, open } = make(() => throwError(() => new Error('x')));
        open();
        expect(el.querySelector('[data-testid=modules-error]')).not.toBeNull();
        expect(el.querySelector('[data-testid=modules-list]')).toBeNull();
    });

    describe('accessibility (axe, LA-A11Y-AUDIT-1)', () => {
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
