import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { QueryConditionGroupComponent } from './query-condition-group.component';
import { Condition, ColumnMeta, emptyGroup } from './query-types';

const COLS: ColumnMeta[] = [
    { name: 'a', type: 'number' },
    { name: 'b', type: 'string' },
];

function create() {
    TestBed.configureTestingModule({
        imports: [QueryConditionGroupComponent],
        providers: [provideNoopAnimations()],
    });
    const f = TestBed.createComponent(QueryConditionGroupComponent);
    f.componentInstance.group = emptyGroup('AND');
    f.componentInstance.columns = COLS;
    f.componentInstance.root = true;
    f.detectChanges();
    return f;
}

describe('QueryConditionGroupComponent', () => {
    it('adds a condition seeded with the first column', () => {
        const c = create().componentInstance;
        c.addCondition();
        expect(c.group.items.length).toBe(1);
        expect((c.group.items[0] as Condition).field).toBe('a');
    });

    it('adds and removes a nested group', () => {
        const c = create().componentInstance;
        c.addGroup();
        expect(c.group.items[0].kind).toBe('group');
        c.removeAt(0);
        expect(c.group.items.length).toBe(0);
    });

    it('resets the operator + values when the field type no longer supports them', () => {
        const c = create().componentInstance;
        c.addCondition();
        const cond = c.group.items[0] as Condition;
        cond.operator = 'between';
        cond.value = '1';
        cond.value2 = '2';
        c.onFieldChange(cond, 'b'); // string has no 'between'
        expect(cond.operator).toBe('=');
        expect(cond.value).toBe('');
        expect(cond.value2).toBe('');
    });

    it('offers matches for strings only', () => {
        const c = create().componentInstance;
        c.addCondition();
        const cond = c.group.items[0] as Condition;
        expect(c.operatorsForCondition(cond).some((o) => o.op === 'matches')).toBe(false); // field a = number
        cond.field = 'b';
        expect(c.operatorsForCondition(cond).some((o) => o.op === 'matches')).toBe(true);
    });

    it('toggles NOT on the group and only stores true', () => {
        const f = create();
        const c = f.componentInstance;
        c.setNegate(true);
        expect(c.group.negate).toBe(true);
        expect(c.summary()).toBe('no conditions');
        c.setNegate(false);
        expect('negate' in c.group).toBe(false);
    });

    it('compare-to-field clears value, and an unsupported operator drops valueField/ignoreCase', () => {
        const c = create().componentInstance;
        c.addCondition();
        const cond = c.group.items[0] as Condition;
        cond.field = 'b';
        cond.value = 'x';
        c.setCompareField(cond, true);
        expect(cond.valueField).toBe('');
        expect(cond.value).toBe('');
        c.onValueField(cond, 'a');
        c.setIgnoreCase(cond, true);
        c.onOperatorChange(cond, 'in');
        expect(cond.valueField).toBeUndefined();
        expect(cond.ignoreCase).toBe(true); // `in` accepts ignoreCase
        c.onOperatorChange(cond, 'isNull');
        expect(cond.ignoreCase).toBeUndefined();
        expect(c.canCompareField(cond)).toBe(false);
    });

    it('renders the new controls only where valid, with accessible names and a described error', async () => {
        const f = create();
        const g = emptyGroup('AND');
        g.negate = true;
        g.items.push({ kind: 'condition', field: 'b', operator: 'matches', value: '(?=x)', ignoreCase: true });
        g.items.push({ kind: 'condition', field: 'a', operator: '>', value: '1' });
        f.componentRef.setInput('group', g);
        f.detectChanges();
        const el: HTMLElement = f.nativeElement;
        const text = el.textContent ?? '';
        expect(el.querySelector('button[role="switch"][aria-label^="NOT"]')).toBeTruthy();
        expect(text).toContain('NOT (match all of)');
        expect(text).toContain('Ignore case'); // matches + the string leaf only, not `>`
        expect(el.querySelectorAll('mat-slide-toggle').length).toBe(3); // NOT, ignore case (matches), compare (>)
        const note = el.querySelector('p[role="alert"]');
        expect(note?.textContent).toContain('lookahead');
        const input = Array.from(el.querySelectorAll('input')).find((i) => i.value === '(?=x)');
        expect(input?.getAttribute('aria-describedby')).toBe(note?.id);
        expect(el.querySelector('[role="group"]')?.getAttribute('aria-label')).toContain('NOT (');
        await expectNoA11yViolations(el);
    });

    it('round-trips negate / valueField / ignoreCase through an unrelated edit unchanged', () => {
        const f = create();
        const c = f.componentInstance;
        const tree = {
            kind: 'group',
            op: 'OR',
            negate: true,
            items: [
                { kind: 'condition', field: 'b', operator: 'contains', valueField: 'b', ignoreCase: true },
                { kind: 'condition', field: 'a', operator: '>', value: '1' },
                {
                    kind: 'group',
                    op: 'AND',
                    negate: true,
                    items: [{ kind: 'condition', field: 'b', operator: 'matches', value: '^x', ignoreCase: true }],
                },
            ],
        };
        const before = JSON.stringify(tree);
        f.componentRef.setInput('group', structuredClone(tree));
        f.detectChanges();
        c.onValue(c.group.items[1] as Condition, '1'); // an unrelated edit (same value re-entered)
        expect(JSON.stringify(c.group)).toBe(before);
    });

    it('has no a11y violations', async () => {
        const f = create();
        f.componentInstance.addCondition();
        f.detectChanges();
        await expectNoA11yViolations(f.nativeElement);
    });
});
