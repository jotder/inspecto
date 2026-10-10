import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
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
    });

    it('states a failed read instead of an empty list', () => {
        const { el, open } = make(() => throwError(() => new Error('x')));
        open();
        expect(el.querySelector('[data-testid=modules-error]')).not.toBeNull();
        expect(el.querySelector('[data-testid=modules-list]')).toBeNull();
    });
});
