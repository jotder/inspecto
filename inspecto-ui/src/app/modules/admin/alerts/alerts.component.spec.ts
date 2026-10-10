import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { convertToParamMap, provideRouter, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { AlertRule, AlertsService, FiredAlert, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { AlertsComponent, alertRuleSeed } from './alerts.component';

const FIRED: FiredAlert = {
    rule: 'failed_batches',
    severity: 'CRITICAL',
    pipeline: 'cdr_ingest',
    metric: 'failed_batches',
    value: 3,
    comparator: '>',
    threshold: 0,
    window: '15m',
    epochMillis: 1,
    message: 'failed_batches > 0 breached',
};

const STORED: FiredAlert = { ...FIRED, id: 'a-1', state: 'OPEN' };
const WORKED = { id: 'a-1', state: 'OPEN', title: 'failed_batches > 0 breached', severity: 'CRITICAL' };

const RULE: AlertRule = {
    name: 'failed_batches',
    metric: 'failed_batches',
    comparator: '>',
    threshold: 0,
    window: '15m',
    severity: 'CRITICAL',
};

async function create(
    overrides: Partial<Record<keyof AlertsService, unknown>> = {},
    { canAuthor = true, confirmed = true, canOperateRuns = true, canWork = true } = {},
) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        recent: () => of([FIRED]),
        rules: () => of([RULE]),
        pendingRules: () => of([]),
        evaluate: vi.fn(() => of([FIRED])),
        removeRule: vi.fn(() => of(void 0)),
        acknowledge: vi.fn(() => of({ ...WORKED, state: 'ACKNOWLEDGED' })),
        resolve: vi.fn(() => of({ ...WORKED, state: 'RESOLVED' })),
        ...overrides,
    } as unknown as AlertsService;
    TestBed.configureTestingModule({
        imports: [AlertsComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: AlertsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => confirmed) } },
            {
                provide: LensService,
                useValue: {
                    canAuthorAlertRules: () => canAuthor,
                    canOperateRuns: () => canOperateRuns,
                    canWorkIncidents: () => canWork,
                },
            },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(AlertsComponent);
    // A TestBed `{provide: MatDialog}` override would be shadowed here: DataTableComponent imports
    // MatDialogModule, so the component's *standalone injector* provides the real MatDialog closer
    // than the testing module. Spy on the instance the component actually got instead.
    const open = vi
        .spyOn((fixture.componentInstance as unknown as { dialog: MatDialog }).dialog, 'open')
        .mockReturnValue({ afterClosed: () => of({ saved: RULE }) } as never);
    fixture.detectChanges(); // ngOnInit → load()
    return { fixture, api, toastr, open };
}

describe('AlertsComponent', () => {
    const pendingSection = (el: HTMLElement) => el.querySelector('#pending-rules-heading')?.closest('section') ?? null;

    it('lists pending Template Alert Rules read-only with the score model each waits on', async () => {
        const { fixture } = await create({
            pendingRules: () =>
                of([
                    {
                        name: 'pf_high_risk_account',
                        afterScore: { kind: 'risk-score', model: 'payment_account' },
                        dataset: 'pf_risk',
                    },
                ]),
        });
        await fixture.whenStable();
        fixture.detectChanges();
        expect(fixture.componentInstance.pendingState()).toBe('ready');
        const text = pendingSection(fixture.nativeElement)!.textContent!;
        expect(text).toContain('pf_high_risk_account');
        expect(text).toContain('Risk Score payment_account');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the latest refusal reason and drops the audit-log hint when a rule was refused', async () => {
        const { fixture } = await create({
            pendingRules: () =>
                of([
                    {
                        name: 'pf_high_risk_account',
                        afterScore: { kind: 'risk-score', model: 'payment_account' },
                        dataset: 'pf_risk',
                        lastRefusal: {
                            reason: "risk-score 'payment_account' has not written it yet",
                            at: '2026-10-06T08:00:00Z',
                        },
                    },
                ]),
        });
        await fixture.whenStable();
        fixture.detectChanges();
        const text = pendingSection(fixture.nativeElement)!.textContent!;
        expect(text).toContain('has not written it yet');
        expect(text).toContain('shown per rule');
        expect(text).not.toContain('audit log');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('keeps the audit-log hint while no rule has been refused', async () => {
        const { fixture } = await create({
            pendingRules: () => of([{ name: 'r1', afterScore: { kind: 'anomaly-model', model: 'm' }, dataset: 'd' }]),
        });
        await fixture.whenStable();
        fixture.detectChanges();
        expect(pendingSection(fixture.nativeElement)!.textContent).toContain('audit log');
    });

    it('shows an empty state when nothing is pending', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        expect(pendingSection(fixture.nativeElement)!.textContent).toContain('No pending Alert Rules');
    });

    it('shows an error state with retry when the pending route fails', async () => {
        const { fixture } = await create({
            pendingRules: () => throwError(() => new HttpErrorResponse({ status: 500 })),
        });
        fixture.detectChanges();
        expect(fixture.componentInstance.pendingState()).toBe('error');
        expect(pendingSection(fixture.nativeElement)!.textContent).toContain("Couldn't load pending Alert Rules");
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('hides the section when the pending route is absent (404)', async () => {
        const { fixture } = await create({
            pendingRules: () => throwError(() => new HttpErrorResponse({ status: 404 })),
        });
        fixture.detectChanges();
        expect(fixture.componentInstance.pendingState()).toBe('hidden');
        expect(pendingSection(fixture.nativeElement)).toBeNull();
    });

    it('loads fired alerts and the armed rules on init', async () => {
        const { fixture } = await create();
        const c = fixture.componentInstance;
        expect(c.alerts()).toEqual([FIRED]);
        expect(c.rules()).toEqual([RULE]);
        expect(c.loading()).toBe(false);
        expect(fixture.nativeElement.textContent).toContain('Alert Rules');
    });

    it('a manual sweep reports fired count and reloads', async () => {
        const { fixture, api, toastr } = await create();
        fixture.componentInstance.evaluate();
        expect(api.evaluate).toHaveBeenCalled();
        expect(toastr.info).toHaveBeenCalledWith('1 alert(s) fired');
        expect(fixture.componentInstance.evaluating()).toBe(false);
    });

    it('surfaces an inline error state (not a toast) when the load fails', async () => {
        const { fixture, toastr } = await create({ recent: () => throwError(() => ({ status: 500 })) });
        const c = fixture.componentInstance;
        expect(c.alerts()).toEqual([]);
        expect(c.loading()).toBe(false);
        expect(c.loadError()).toBe(true);
        expect(toastr.error).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain("Couldn't load alerts");
    });

    it('authoring is capability-gated: rule actions and New rule vanish for the read-only lens', async () => {
        const { fixture } = await create({}, { canAuthor: false });
        const c = fixture.componentInstance;
        expect(c.ruleActions).toEqual([]);
        expect(fixture.nativeElement.textContent).not.toContain('New rule');
    });

    it('Evaluate now is hidden without canOperateRuns (UI-CAPABILITY-AFFORDANCE-1)', async () => {
        const { fixture } = await create({}, { canOperateRuns: false });
        expect(fixture.nativeElement.textContent).not.toContain('Evaluate now');
    });

    it('Evaluate now is shown with canOperateRuns', async () => {
        const { fixture } = await create({}, { canOperateRuns: true });
        expect(fixture.nativeElement.textContent).toContain('Evaluate now');
    });

    it('New rule opens the form dialog and a save reloads + toasts', async () => {
        const { fixture, open, toastr } = await create();
        fixture.componentInstance.newRule();
        expect(open).toHaveBeenCalled();
        expect(toastr.success).toHaveBeenCalledWith('Alert rule "failed_batches" armed');
    });

    it('delete asks for confirmation, calls the API, and drops the row', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        await c.removeRule(RULE);
        expect(api.removeRule).toHaveBeenCalledWith('failed_batches');
        expect(c.rules()).toEqual([]);
    });

    it('a declined confirmation leaves the rule untouched', async () => {
        const { fixture, api } = await create({}, { confirmed: false });
        const c = fixture.componentInstance;
        await c.removeRule(RULE);
        expect(api.removeRule).not.toHaveBeenCalled();
        expect(c.rules()).toEqual([RULE]);
    });

    it('renders the loaded state with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('AlertsComponent ack / resolve', () => {
    const settle = async (f: { detectChanges(): void }) => {
        await new Promise((r) => setTimeout(r));
        f.detectChanges();
    };
    const region = (el: HTMLElement) => el.querySelector('[role="status"]') as HTMLElement;
    const names = (c: AlertsComponent, a: FiredAlert) =>
        c
            .firedActions()
            .filter((x) => !x.visible || x.visible(a))
            .map((x) => (typeof x.hint === 'function' ? x.hint(a) : x.hint));

    it('offers Acknowledge + Resolve for OPEN, Resolve only for ACKNOWLEDGED, none once RESOLVED', async () => {
        const { fixture } = await create({ recent: () => of([STORED]) });
        const c = fixture.componentInstance;
        const title = 'failed_batches > 0 breached';
        expect(names(c, STORED)).toEqual(['What happened', `Acknowledge alert ${title}`, `Resolve alert ${title}`]);
        expect(names(c, { ...STORED, state: 'ACKNOWLEDGED' })).toEqual(['What happened', `Resolve alert ${title}`]);
        expect(names(c, { ...STORED, state: 'RESOLVED' })).toEqual(['What happened']);
    });

    it('offers Open Investigation only for a rule that watches one, deep-linking to it (DR-U9)', async () => {
        const invRule = { ...RULE, name: 'smurfs', investigation: 'case-v', relation: 'entities', measure: 'count' };
        const invFired = { ...FIRED, rule: 'smurfs', pipeline: 'case-v' };
        const { fixture } = await create({ rules: () => of([RULE, invRule]), recent: () => of([FIRED, invFired]) });
        const c = fixture.componentInstance;
        expect(names(c, FIRED)).toEqual(['What happened']);
        expect(names(c, invFired)).toEqual([
            'What happened',
            'Open Investigation case-v — it lists the breaching entities',
        ]);
        const nav = vi.spyOn((c as unknown as { router: Router }).router, 'navigate').mockResolvedValue(true);
        const open = c.firedActions().find((x) => x.visible?.(invFired) && x.icon === 'heroicons_outline:share')!;
        open.onClick(invFired);
        expect(nav).toHaveBeenCalledWith(['/studio/link-analysis'], { queryParams: { investigation: 'case-v' } });
    });

    it('offers no work actions for an entry without an id', async () => {
        const { fixture } = await create();
        expect(names(fixture.componentInstance, FIRED)).toEqual(['What happened']);
    });

    it('acknowledge posts, updates the state and announces it in the live region', async () => {
        const { fixture, api } = await create({ recent: () => of([STORED]) });
        const c = fixture.componentInstance;
        c.work(STORED, 'acknowledged');
        expect(api.acknowledge).toHaveBeenCalledWith('a-1');
        expect(c.alerts()[0].state).toBe('ACKNOWLEDGED');
        await settle(fixture);
        expect(region(fixture.nativeElement).textContent).toContain('failed_batches > 0 breached acknowledged');
        expect(document.activeElement).toBe(region(fixture.nativeElement));
    });

    it('resolve works straight from OPEN', async () => {
        const { fixture, api } = await create({ recent: () => of([STORED]) });
        fixture.componentInstance.work(STORED, 'resolved');
        expect(api.resolve).toHaveBeenCalledWith('a-1');
        expect(fixture.componentInstance.alerts()[0].state).toBe('RESOLVED');
    });

    it('a 422 rolls the state back and says so in plain language', async () => {
        const { fixture } = await create({
            recent: () => of([STORED]),
            resolve: () => throwError(() => new HttpErrorResponse({ status: 422 })),
        });
        const c = fixture.componentInstance;
        c.work(STORED, 'resolved');
        expect(c.alerts()[0].state).toBe('OPEN');
        await settle(fixture);
        expect(region(fixture.nativeElement).textContent).toContain('already resolved or acknowledged');
    });

    it('a 404 asks for a refresh', async () => {
        const { fixture } = await create({
            recent: () => of([STORED]),
            acknowledge: () => throwError(() => new HttpErrorResponse({ status: 404 })),
        });
        fixture.componentInstance.work(STORED, 'acknowledged');
        await settle(fixture);
        expect(region(fixture.nativeElement).textContent).toContain('no longer exists');
    });

    it('a 403 drops to read-only: the actions vanish and the permission note shows', async () => {
        const { fixture } = await create({
            recent: () => of([STORED]),
            acknowledge: () => throwError(() => new HttpErrorResponse({ status: 403 })),
        });
        const c = fixture.componentInstance;
        c.work(STORED, 'acknowledged');
        await settle(fixture);
        expect(c.alerts()[0].state).toBe('OPEN');
        expect(names(c, STORED)).toEqual(['What happened']);
        expect(region(fixture.nativeElement).textContent).toContain('do not have permission');
        expect(fixture.nativeElement.textContent).toContain('needs the Work Incidents permission');
    });

    it('without canWorkIncidents the actions are absent and the read-only note shows', async () => {
        const { fixture } = await create({ recent: () => of([STORED]) }, { canWork: false });
        expect(names(fixture.componentInstance, STORED)).toEqual(['What happened']);
        expect(fixture.nativeElement.textContent).toContain('needs the Work Incidents permission');
    });

    it('renders the stateful grid and live region with no a11y violations', async () => {
        const { fixture } = await create({ recent: () => of([STORED]) });
        fixture.componentInstance.work(STORED, 'acknowledged');
        await settle(fixture);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});

describe('alertRuleSeed', () => {
    it('reads only the keys a ?newRule link names, by as a list and threshold as a number', () => {
        expect(
            alertRuleSeed(
                convertToParamMap({
                    newRule: '1',
                    dataset: 'risk_scores_x_latest',
                    measure: 'max(score)',
                    by: 'model, entity_key',
                    comparator: 'gte',
                    threshold: '70',
                    severity: 'CRITICAL',
                }),
            ),
        ).toEqual({
            dataset: 'risk_scores_x_latest',
            measure: 'max(score)',
            by: ['model', 'entity_key'],
            comparator: 'gte',
            threshold: 70,
        });
    });

    it('drops a non-numeric threshold rather than seeding text', () => {
        expect(alertRuleSeed(convertToParamMap({ threshold: 'high' }))).toEqual({});
    });
});

describe('Alert Rules grid text', () => {
    it('omits the window for a Dataset-measure rule and names its Measure on its Dataset', async () => {
        const { ruleConditionText, ruleMetricText } = await import('./alerts.component');
        const r = {
            name: 'r',
            comparator: 'gte',
            threshold: 6,
            severity: 'high',
            dataset: 'pf_sim_swap_payments',
            measure: 'max(risky_payments)',
            by: ['msisdn'],
        };
        expect(ruleConditionText(r)).toBe('gte 6');
        expect(ruleMetricText(r)).toBe('max(risky_payments) on pf_sim_swap_payments by msisdn');
        const ledger = { name: 'l', metric: 'rejects', comparator: 'gt', threshold: 5, window: '1h', severity: 'low' };
        expect(ruleConditionText(ledger)).toBe('gt 5 / 1h');
        expect(ruleMetricText(ledger)).toBe('rejects');
    });
});
