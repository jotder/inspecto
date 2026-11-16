import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { convertToParamMap, provideRouter, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { Subject, of, throwError } from 'rxjs';
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
        expect(text).toContain('Pf high risk account'); // the id as words; the id itself is the tooltip
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

    it('holds the fired rows (and keeps the grid loading) until the armed rules answer (LA-DEMO-POLISH-1)', async () => {
        const rules = new Subject<(typeof RULE)[]>();
        const { fixture } = await create({ rules: () => rules });
        const c = fixture.componentInstance;
        expect(c.firedRows()).toEqual([]);
        expect(c.rulesSettled()).toBe(false);
        rules.next([RULE]);
        rules.complete();
        expect(c.firedRows()).toEqual([FIRED]);
    });

    it('still shows the fired rows when the rules route fails', async () => {
        const { fixture } = await create({ rules: () => throwError(() => new HttpErrorResponse({ status: 500 })) });
        expect(fixture.componentInstance.firedRows()).toEqual([FIRED]);
    });

    it('renders Message on one line with a tooltip, not autoHeight (LA-DEMO-POLISH-1)', async () => {
        const { fixture } = await create();
        const m = fixture.componentInstance.columnDefs.find((d) => d.field === 'message')!;
        expect(m.autoHeight).toBeFalsy();
        expect(m.wrapText).toBeFalsy();
        expect(m.tooltipField).toBe('message');
    });

    it('a manual sweep reports fired count and reloads', async () => {
        const { fixture, api, toastr } = await create();
        fixture.componentInstance.evaluate();
        expect(api.evaluate).toHaveBeenCalled();
        expect(toastr.info).toHaveBeenCalledWith('1 Alert fired');
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
        await expectNoA11yViolations(fixture.nativeElement); // axe, LA-A11Y-AUDIT-1: the failed-load state
    });

    it('axe: the grid held loading until the armed rules answer, and the empty list (LA-A11Y-AUDIT-1)', async () => {
        const rules = new Subject<(typeof RULE)[]>();
        const held = await create({ rules: () => rules });
        await expectNoA11yViolations(held.fixture.nativeElement);
        TestBed.resetTestingModule();
        const empty = await create({ recent: () => of([]) });
        expect(empty.fixture.componentInstance.alerts()).toEqual([]);
        await expectNoA11yViolations(empty.fixture.nativeElement);
    });

    it('authoring is capability-gated: rule actions and New Alert Rule vanish for the read-only lens', async () => {
        const { fixture } = await create({}, { canAuthor: false });
        const c = fixture.componentInstance;
        expect(c.ruleActions).toEqual([]);
        expect(fixture.nativeElement.textContent).not.toContain('New Alert Rule');
    });

    it('Evaluate now is hidden without canOperateRuns (UI-CAPABILITY-AFFORDANCE-1)', async () => {
        const { fixture } = await create({}, { canOperateRuns: false });
        expect(fixture.nativeElement.textContent).not.toContain('Evaluate now');
    });

    it('Evaluate now is shown with canOperateRuns', async () => {
        const { fixture } = await create({}, { canOperateRuns: true });
        expect(fixture.nativeElement.textContent).toContain('Evaluate now');
    });

    it('New Alert Rule opens the form dialog and a save reloads + toasts, naming the rule in words', async () => {
        const { fixture, open, toastr } = await create();
        fixture.componentInstance.newRule();
        expect(open).toHaveBeenCalled();
        expect(toastr.success).toHaveBeenCalledWith('Alert Rule “Failed batches” armed');
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

describe('Alert Rules grid text (ALERTS-PAGE-WORDS-1)', () => {
    it('words a Dataset-measure rule: no window, its Measure per key, its Dataset as the scope', async () => {
        const { ruleConditionText, ruleScopeText, ruleWatchText } = await import('./alert-words');
        const r = {
            name: 'r',
            comparator: 'gte',
            threshold: 6,
            severity: 'high',
            dataset: 'pf_sim_swap_payments',
            measure: 'max(risky_payments)',
            by: ['msisdn'],
        };
        expect(ruleConditionText(r)).toBe('at least 6');
        expect(ruleWatchText(r)).toBe('Maximum of risky_payments per msisdn');
        expect(ruleScopeText(r)).toBe('Dataset pf_sim_swap_payments');
    });

    it('words a ledger rule: its Metric, its window and every Pipeline when it names none', async () => {
        const { ruleConditionText, ruleScopeText, ruleWatchText } = await import('./alert-words');
        const ledger = {
            name: 'l',
            metric: 'failed_batches',
            comparator: 'gt',
            threshold: 5,
            window: '1h',
            severity: 'low',
        };
        expect(ruleConditionText(ledger)).toBe('above 5 over the last 1 hour');
        expect(ruleWatchText(ledger)).toBe('Failed Consignments');
        expect(ruleScopeText(ledger)).toBe('Every Pipeline');
        expect(ruleScopeText({ ...ledger, onPipeline: 'cdr_ingest' })).toBe('Pipeline cdr_ingest');
        expect(ruleConditionText({ ...ledger, window: '20b' })).toBe('above 5 over the last 20 Consignments');
    });

    it('words a value-measure Investigation rule: "at least 1", the Value Measure by its label, the Investigation', async () => {
        const { ruleConditionText, ruleScopeText, ruleWatchText, ruleName } = await import('./alert-words');
        const vm = {
            name: 'pass_through_watch',
            comparator: 'gte',
            threshold: 1,
            severity: 'WARNING',
            investigation: 'inv-7',
            valueMeasure: { name: 'passThrough', last: '7d' },
        };
        expect(ruleConditionText(vm)).toBe('at least 1');
        expect(ruleWatchText(vm)).toBe('Entities breaching Pass-through');
        expect(ruleScopeText(vm)).toBe('Investigation inv-7');
        expect(ruleName(vm, vm.name)).toBe('Pass through watch');
        expect(ruleName({ ...vm, description: 'Mule accounts' }, vm.name)).toBe('Mule accounts');
    });

    it('words a freshness rule by its age limit', async () => {
        const { ruleConditionText, ruleWatchText } = await import('./alert-words');
        const fresh = {
            name: 'f',
            comparator: 'gt',
            threshold: 0,
            severity: 'WARNING',
            dataset: 'd',
            maximumAge: '2h',
        };
        expect(ruleConditionText(fresh)).toBe('older than 2 hours');
        expect(ruleWatchText(fresh)).toBe('Time since last publish');
    });

    it("words what a fired Alert's metric label stored", async () => {
        const { firedWatchText } = await import('./alert-words');
        expect(firedWatchText('entities breaching passThrough')).toBe('Entities breaching Pass-through');
        expect(firedWatchText('entities count')).toBe('Number of entities');
        expect(firedWatchText('links sum(amount)')).toBe('Sum of amount of links');
        expect(firedWatchText('sum(exposure_sar)')).toBe('Sum of exposure_sar');
        expect(firedWatchText('count')).toBe('Row count');
        expect(firedWatchText('error_rate')).toBe('Error rate');
        expect(firedWatchText('file_problem')).toBe('File problem');
        expect(firedWatchText('freshness')).toBe('Time since last publish');
    });
});

describe('fired Alerts grid text (ALERTS-PAGE-WORDS-1)', () => {
    const INV_RULE = {
        name: 'pass_through_watch',
        comparator: 'gte',
        threshold: 1,
        severity: 'WARNING',
        investigation: 'inv-7',
        valueMeasure: { name: 'passThrough', last: '7d' },
    };
    const INV_FIRED = {
        ...FIRED,
        rule: 'pass_through_watch',
        pipeline: 'inv-7',
        metric: 'entities breaching passThrough',
        comparator: 'gte',
        threshold: 1,
    };
    const cell = (c: AlertsComponent, colId: string, a: typeof FIRED) => {
        const def = c.columnDefs.find((d) => d.colId === colId)!;
        return (def.valueGetter as (p: unknown) => unknown)({ data: a });
    };

    it('names the scope by what the rule watches, so an Investigation id is never under Pipeline', async () => {
        const { fixture } = await create({ rules: () => of([RULE, INV_RULE]), recent: () => of([FIRED, INV_FIRED]) });
        const c = fixture.componentInstance;
        expect(c.columnDefs.map((d) => d.headerName)).not.toContain('Pipeline');
        expect(cell(c, 'scope', INV_FIRED)).toBe('Investigation inv-7');
        expect(cell(c, 'scope', FIRED)).toBe(`Pipeline ${FIRED.pipeline}`);
        expect(cell(c, 'scope', { ...FIRED, rule: 'file_problem' })).toBe(`Pipeline ${FIRED.pipeline}`);
        expect(cell(c, 'scope', { ...FIRED, rule: 'deleted_rule' })).toBe(FIRED.pipeline);
    });

    it('shows the rule by name and what it watches in words, keeping the id in the tooltip', async () => {
        const { fixture } = await create({ rules: () => of([RULE, INV_RULE]), recent: () => of([INV_FIRED]) });
        const c = fixture.componentInstance;
        expect(cell(c, 'rule', INV_FIRED)).toBe('Pass through watch');
        expect(cell(c, 'watches', INV_FIRED)).toBe('Entities breaching Pass-through');
        const rule = c.columnDefs.find((d) => d.colId === 'rule')!;
        expect((rule.tooltipValueGetter as (p: unknown) => unknown)({ data: INV_FIRED })).toBe('pass_through_watch');
        expect(c.columnDefs.map((d) => d.headerName)).not.toContain('Metric');
        expect(c.ruleColumnDefs.map((d) => d.headerName)).toEqual([
            'Name',
            'Watches',
            'Condition',
            'Severity',
            'Scope',
        ]);
    });

    it('What happened reads live Pipeline state only for a Pipeline scope', async () => {
        const { fixture, open } = await create({ rules: () => of([RULE, INV_RULE]), recent: () => of([INV_FIRED]) });
        const c = fixture.componentInstance;
        const what = c.firedActions().find((x) => x.icon === 'heroicons_outline:information-circle')!;
        what.onClick(INV_FIRED);
        expect(open).toHaveBeenLastCalledWith(expect.anything(), { data: { label: 'Pass through watch' } });
        what.onClick(FIRED);
        expect(open).toHaveBeenLastCalledWith(expect.anything(), {
            data: { label: 'Failed batches', pipelineId: FIRED.pipeline },
        });
    });
});
