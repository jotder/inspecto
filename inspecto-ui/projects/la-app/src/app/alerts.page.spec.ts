import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter, Router } from '@angular/router';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AlertRule, AlertsService, FiredAlert, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ALERTS_INVESTIGATION_ROUTE, AlertsComponent } from 'app/modules/admin/alerts/alerts.component';
import { ToastrService } from 'ngx-toastr';
import { laAppRoutes } from './app.routes';

const RULE = {
    name: 'smurfs',
    metric: 'count',
    comparator: '>',
    threshold: 0,
    window: '15m',
    severity: 'CRITICAL',
    investigation: 'case-v',
} as AlertRule;
const PLAIN = { ...RULE, name: 'plain', investigation: undefined } as AlertRule;
const fired = (rule: string): FiredAlert =>
    ({
        rule,
        severity: 'CRITICAL',
        pipeline: 'case-v',
        metric: 'count',
        value: 3,
        comparator: '>',
        threshold: 0,
        window: '15m',
        epochMillis: 1,
        message: `${rule} breached`,
    }) as FiredAlert;

async function create(api: Partial<Record<keyof AlertsService, unknown>> = {}) {
    TestBed.configureTestingModule({
        imports: [AlertsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ALERTS_INVESTIGATION_ROUTE, useValue: '/link-analysis' },
            {
                provide: AlertsService,
                useValue: {
                    recent: () => of([fired('smurfs'), fired('plain')]),
                    rules: () => of([RULE, PLAIN]),
                    pendingRules: () => of([]),
                    ...api,
                },
            },
            { provide: ToastrService, useValue: { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => true) } },
            {
                provide: LensService,
                useValue: { canAuthorAlertRules: () => false, canOperateRuns: () => false, canWorkIncidents: () => false },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(AlertsComponent);
    fixture.detectChanges();
    return fixture;
}

describe('la-app Alerts page', () => {
    it('is routed in the shell, reusing the console Alerts component with the la-app investigation target', async () => {
        const shell = laAppRoutes.find((r) => r.path === '' && r.children)!;
        const r = shell.children!.find((c) => c.path === 'alerts')!;
        expect(await r.loadComponent!()).toBe(AlertsComponent);
        expect(r.providers).toEqual([{ provide: ALERTS_INVESTIGATION_ROUTE, useValue: '/link-analysis' }]);
    }, 60_000);

    it('renders the fired rows', async () => {
        const f = await create();
        expect(f.componentInstance.firedRows().map((a) => a.rule)).toEqual(['smurfs', 'plain']);
        await expectNoA11yViolations(f.nativeElement);
    });

    it('Open Investigation navigates within la-app with the id, and only for an Investigation rule', async () => {
        const f = await create();
        const open = f.componentInstance.firedActions().find((x) => x.icon === 'heroicons_outline:share')!;
        expect(open.visible!(fired('smurfs'))).toBe(true);
        expect(open.visible!(fired('plain'))).toBe(false);
        const nav = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
        open.onClick(fired('smurfs'));
        expect(nav).toHaveBeenCalledWith(['/link-analysis'], { queryParams: { investigation: 'case-v' } });
    });

    it('shows the empty state when nothing fired', async () => {
        const f = await create({ recent: () => of([]) });
        expect(f.componentInstance.firedRows()).toEqual([]);
        expect(f.componentInstance.loadError()).toBe(false);
        await expectNoA11yViolations(f.nativeElement);
    });

    it('shows the failed-load state', async () => {
        const f = await create({ recent: () => throwError(() => ({ status: 500 })) });
        expect(f.nativeElement.textContent).toContain("Couldn't load alerts");
        await expectNoA11yViolations(f.nativeElement);
    });
});
