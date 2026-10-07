import { By } from '@angular/platform-browser';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { QueryConditionGroupComponent } from 'app/inspecto/query/query-condition-group.component';
import { ColumnMeta, Condition } from 'app/inspecto/query/query-types';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { factorDrafts, toRiskScoreContent } from 'app/inspecto/risk/risk-score-form';
import { RiskScoreFactorsComponent } from './risk-score-factors.component';

const COLS: ColumnMeta[] = [
    { name: 'cell', type: 'string' },
    { name: 'duration', type: 'number' },
];

const WHEN = {
    kind: 'group',
    op: 'OR',
    items: [{ kind: 'condition', field: 'cell', operator: 'startsWith', value: 'A', ignoreCase: true }],
};

const STORED = (withWhen: boolean): Record<string, unknown> => ({
    id: 'sim_box',
    entityType: 'subscriber',
    highThreshold: 70,
    factors: [
        {
            id: 'calls',
            dataset: 'cdr',
            key: 'cell',
            measure: 'count',
            weight: 3,
            ...(withWhen ? { when: JSON.parse(JSON.stringify(WHEN)) } : {}),
        },
    ],
});

const flush = async () => {
    for (let i = 0; i < 4; i++) await new Promise((r) => setTimeout(r, 0));
};

async function mount(body: Record<string, unknown>) {
    TestBed.configureTestingModule({ imports: [RiskScoreFactorsComponent], providers: [provideNoopAnimations()] });
    const f = TestBed.createComponent(RiskScoreFactorsComponent);
    f.componentInstance.columnMetaFor = async () => COLS;
    f.componentInstance.columnsFor = async () => COLS.map((c) => c.name);
    f.componentInstance.factors = factorDrafts(body);
    f.detectChanges();
    await flush();
    f.detectChanges();
    return f;
}

const toggle = (f: { nativeElement: HTMLElement }): HTMLButtonElement =>
    f.nativeElement.querySelector('[data-testid="when-toggle"]') as HTMLButtonElement;

describe('RiskScoreFactorsComponent advanced filter (when)', () => {
    it('round-trips an authored when through an unrelated edit (silent-loss trap)', async () => {
        const body = STORED(true);
        const f = await mount(body);
        f.componentInstance.rows.at(0).controls['label'].setValue('Renamed');
        const saved = toRiskScoreContent('sim_box', {}, f.componentInstance.value(), body);
        const row = (saved['factors'] as Record<string, unknown>[])[0];
        expect(row['label']).toBe('Renamed');
        expect(row['when']).toEqual(WHEN);
    });

    it('negative probe: a factor without a when saves none', async () => {
        const body = STORED(false);
        const f = await mount(body);
        const saved = toRiskScoreContent('sim_box', {}, f.componentInstance.value(), body);
        expect((saved['factors'] as Record<string, unknown>[])[0]).not.toHaveProperty('when');
    });

    it('is a disclosure: collapsed without a when, expanded with one, toggled by its button', async () => {
        const none = await mount(STORED(false));
        expect(toggle(none).tagName).toBe('BUTTON');
        expect(toggle(none).getAttribute('aria-expanded')).toBe('false');
        const region = none.nativeElement.querySelector(`#${toggle(none).getAttribute('aria-controls')}`);
        expect(region?.hasAttribute('hidden')).toBe(true);
        toggle(none).click();
        none.detectChanges();
        expect(toggle(none).getAttribute('aria-expanded')).toBe('true');
        expect(region?.hasAttribute('hidden')).toBe(false);
        expect(none.debugElement.query(By.directive(QueryConditionGroupComponent))).toBeTruthy();
    });

    it('opens by itself when a when is stored, and explains flat filters vs the tree', async () => {
        TestBed.resetTestingModule();
        const f = await mount(STORED(true));
        expect(toggle(f).getAttribute('aria-expanded')).toBe('true');
        expect(f.nativeElement.textContent).toContain('The flat filters above');
    });

    it('offers the factor dataset columns and saves an edited condition tree', async () => {
        const body = STORED(false);
        const f = await mount(body);
        toggle(f).click();
        f.detectChanges();
        const group = f.debugElement.query(By.directive(QueryConditionGroupComponent))
            .componentInstance as QueryConditionGroupComponent;
        expect(group.columns).toEqual(COLS);
        group.addCondition();
        const c = group.group.items[0] as Condition;
        group.onFieldChange(c, 'duration');
        group.onValue(c, '5');
        const saved = toRiskScoreContent('sim_box', {}, f.componentInstance.value(), body);
        const when = (saved['factors'] as Record<string, unknown>[])[0]['when'] as { items: Condition[] };
        expect(when.items[0]).toMatchObject({ field: 'duration', operator: '=', value: '5' });
    });

    it('shows and announces a refused condition, and opens the collapsed section on validate()', async () => {
        const body = STORED(false);
        const f = await mount(body);
        toggle(f).click();
        f.detectChanges();
        const group = f.debugElement.query(By.directive(QueryConditionGroupComponent))
            .componentInstance as QueryConditionGroupComponent;
        group.addCondition();
        const c = group.group.items[0] as Condition;
        group.onOperatorChange(c, 'matches');
        group.onValue(c, '(?=x)'); // lookahead: the SQL backend refuses it
        f.componentInstance.toggleWhen(f.componentInstance.rows.at(0)); // collapse it again
        f.detectChanges();
        expect(f.nativeElement.querySelector('[data-testid="when-error"]')).toBeTruthy(); // touched by the edit
        expect(f.componentInstance.validate()).toBe(false);
        f.detectChanges();
        expect(toggle(f).getAttribute('aria-expanded')).toBe('true');
        const err = f.nativeElement.querySelector('[data-testid="when-error"]') as HTMLElement;
        expect(err.getAttribute('role')).toBe('alert');
        expect(err.textContent).toContain('lookahead');
        expect(toggle(f).getAttribute('aria-describedby')).toBe(err.id);
    });

    it('shows no error before the author touches the tree', async () => {
        const f = await mount(STORED(false));
        expect(f.nativeElement.querySelector('[data-testid="when-error"]')).toBeNull();
        expect(toggle(f).hasAttribute('aria-describedby')).toBe(false);
    });

    it('keeps a when it cannot model, says so, and offers no editor over it', async () => {
        const body = STORED(false) as { factors: Record<string, unknown>[] };
        const opaque = { op: 'AND', conditions: [{ field: 'cell', operator: '=', value: 'x' }] };
        body.factors[0]['when'] = opaque;
        const f = await mount(body);
        toggle(f).click();
        f.detectChanges();
        expect(f.nativeElement.querySelector('[data-testid="when-opaque"]')).toBeTruthy();
        expect(f.debugElement.query(By.directive(QueryConditionGroupComponent))).toBeNull();
        const saved = toRiskScoreContent('sim_box', {}, f.componentInstance.value(), body);
        expect((saved['factors'] as Record<string, unknown>[])[0]['when']).toEqual(opaque);
    });

    it('has no axe violations, collapsed and expanded', async () => {
        const f = await mount(STORED(true));
        await expectNoA11yViolations(f.nativeElement);
        toggle(f).click();
        f.detectChanges();
        await expectNoA11yViolations(f.nativeElement);
    });
});
