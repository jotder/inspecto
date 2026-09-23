import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Observable, of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ComponentsService, SqlAstResponse } from 'app/inspecto/api';
import { Condition, SqlAstNode } from 'app/inspecto/query';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineFilterPredicateComponent } from './pipeline-filter-predicate.component';

// DuckDB's tree for STATUS = 'SHIPPED' (shape as json_serialize_sql emits it; offsets vary with spelling).
const statusIs = (value: string, loc = 1): SqlAstNode => ({
    class: 'COMPARISON',
    type: 'COMPARE_EQUAL',
    alias: '',
    query_location: loc,
    left: { class: 'COLUMN_REF', type: 'COLUMN_REF', alias: '', query_location: loc, column_names: ['STATUS'] },
    right: {
        class: 'CONSTANT',
        type: 'VALUE_CONSTANT',
        alias: '',
        query_location: loc,
        value: { type: { id: 'VARCHAR', type_info: null }, is_null: false, value },
    },
});

@Component({
    standalone: true,
    imports: [PipelineFilterPredicateComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<app-pipeline-filter-predicate
        [where]="where"
        [readOnly]="readOnly"
        [extraColumns]="['GROSS']"
        (rewrite)="rewritten.push($event)"
    />`,
})
class HostComponent {
    where = '';
    readOnly = false;
    rewritten: string[] = [];
}

describe('PipelineFilterPredicateComponent — "What gets kept" on a Filter Step', () => {
    let answers: Record<string, () => Observable<SqlAstResponse>>;
    let calls: [string, string][];

    beforeEach(() => {
        vi.useFakeTimers();
        calls = [];
        answers = {
            "STATUS = 'SHIPPED'": () => of({ ok: true, ast: statusIs('SHIPPED', 26) }),
            // The Query Core's own spelling of the same predicate: a different TEXT, the same tree.
            '"STATUS" = \'SHIPPED\'': () => of({ ok: true, ast: statusIs('SHIPPED', 40) }),
        };
    });
    afterEach(() => vi.useRealTimers());

    function render(where: string, readOnly = false) {
        TestBed.configureTestingModule({
            imports: [HostComponent],
            providers: [
                provideNoopAnimations(),
                {
                    provide: ComponentsService,
                    useValue: {
                        sqlAst: (sql: string, fragment: string) => {
                            calls.push([sql, fragment]);
                            const a = answers[sql];
                            return a ? a() : throwError(() => new Error('unexpected ' + sql));
                        },
                    },
                },
            ],
        });
        const f = TestBed.createComponent(HostComponent);
        f.componentInstance.where = where;
        f.componentInstance.readOnly = readOnly;
        f.detectChanges();
        return f;
    }
    const settle = (f: ReturnType<typeof render>) => {
        vi.advanceTimersByTime(300);
        f.detectChanges();
    };
    const el = (f: ReturnType<typeof render>) => f.nativeElement as HTMLElement;
    const pane = (f: ReturnType<typeof render>) =>
        f.debugElement.children[0].componentInstance as PipelineFilterPredicateComponent;
    const button = (f: ReturnType<typeof render>, label: string) =>
        Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === label) as
            | HTMLButtonElement
            | undefined;

    it('says every row is kept when there is no predicate, and asks the server nothing', () => {
        const f = render('');
        settle(f);
        expect(el(f).textContent).toContain('No row predicate');
        expect(calls).toEqual([]);
    });

    it('reads the stored predicate once, debounced, as a predicate fragment, and renders the table', () => {
        const f = render("STATUS = 'SHIPPED'");
        expect(calls).toEqual([]);
        settle(f);
        expect(calls).toEqual([["STATUS = 'SHIPPED'", 'predicate']]);
        expect(el(f).querySelector('inspecto-sql-ast-table tbody tr')?.textContent).toContain('STATUS');
    });

    it('tier 1: a predicate that does not parse is a warning with DuckDB’s own words — nothing is blocked', () => {
        answers['amount > > 1'] = () =>
            of({ ok: false, error: { message: 'syntax error at or near ">"', position: 9, subtype: 'SYNTAX_ERROR' } });
        const f = render('amount > > 1');
        settle(f);
        expect(el(f).textContent).toContain('syntax error at or near ">" (at character 10)');
        expect(el(f).textContent).toContain('It can still be saved');
        expect(el(f).querySelector('inspecto-sql-ast-table')).toBeNull();
    });

    it('an unreachable route is a quiet status line, never an error', () => {
        answers['a > 1'] = () => throwError(() => ({ status: 503 }));
        const f = render('a > 1');
        settle(f);
        expect(el(f).querySelector('[role="status"]')?.textContent).toContain('could not be read right now');
        expect(el(f).querySelector('[role="alert"]')).toBeNull();
    });

    it('Q2: a predicate carrying a comment is never offered for structured editing', () => {
        answers["STATUS = 'SHIPPED' -- only shipped"] = () => of({ ok: true, ast: statusIs('SHIPPED') });
        const f = render("STATUS = 'SHIPPED' -- only shipped");
        settle(f);
        expect(el(f).textContent).toContain('carries a comment');
        expect(button(f, 'Edit as conditions')).toBeUndefined();
    });

    it('tier 2: a predicate the editor cannot represent says why, and offers no editor', () => {
        const fn: SqlAstNode = {
            ...statusIs('x'),
            left: { class: 'FUNCTION', type: 'FUNCTION', function_name: 'lower', children: [] },
        };
        answers["lower(STATUS) = 'x'"] = () => of({ ok: true, ast: fn });
        const f = render("lower(STATUS) = 'x'");
        settle(f);
        expect(el(f).textContent).toContain('cannot represent');
        expect(button(f, 'Edit as conditions')).toBeUndefined();
    });

    it('the admission test refuses when the Query Core’s spelling would parse to a DIFFERENT tree', () => {
        answers['"STATUS" = \'SHIPPED\''] = () => of({ ok: true, ast: statusIs('OTHER') });
        const f = render("STATUS = 'SHIPPED'");
        settle(f);
        button(f, 'Edit as conditions')!.click();
        f.detectChanges();
        expect(el(f).querySelector('[role="alert"]')?.textContent).toContain('offered as text only');
        expect(el(f).querySelector('inspecto-query-condition-group')).toBeNull();
    });

    it('step 4: edit, review the exact before/after, and only an ACCEPT writes — via compileWhere', () => {
        const f = render("STATUS = 'SHIPPED'");
        settle(f);
        button(f, 'Edit as conditions')!.click();
        f.detectChanges();
        expect(calls[1]).toEqual(['"STATUS" = \'SHIPPED\'', 'predicate']);
        expect(el(f).querySelector('inspecto-query-condition-group')).not.toBeNull();

        // Reviewing an untouched draft writes nothing.
        button(f, 'Review change')!.click();
        f.detectChanges();
        expect(el(f).textContent).toContain('No change yet');
        expect(f.componentInstance.rewritten).toEqual([]);

        // The editor mutates its (deep-cloned) group in place; the recognised group is untouched.
        (pane(f).editing()!.group.items[0] as Condition).value = 'OPEN';
        button(f, 'Review change')!.click();
        f.detectChanges();
        expect(el(f).textContent).toContain("STATUS = 'SHIPPED'");
        expect(el(f).textContent).toContain('"STATUS" = \'OPEN\'');
        expect(f.componentInstance.rewritten).toEqual([]);

        button(f, 'Use this condition')!.click();
        expect(f.componentInstance.rewritten).toEqual(['"STATUS" = \'OPEN\'']);
        const r = pane(f).recognition();
        expect(r && 'group' in r ? (r.group.items[0] as Condition).value : null).toBe('SHIPPED');
    });

    it('offers the upstream columns to the editor, typed as text, after the recognised ones', () => {
        const f = render("STATUS = 'SHIPPED'");
        settle(f);
        button(f, 'Edit as conditions')!.click();
        f.detectChanges();
        expect(pane(f).editing()!.columns).toEqual([
            { name: 'STATUS', type: 'string' },
            { name: 'GROSS', type: 'string' },
        ]);
    });

    it('a read-only lens sees the table and no editing affordance', () => {
        const f = render("STATUS = 'SHIPPED'", true);
        settle(f);
        expect(el(f).querySelector('inspecto-sql-ast-table')).not.toBeNull();
        expect(button(f, 'Edit as conditions')).toBeUndefined();
    });

    it('has no a11y violations, reading and editing', async () => {
        const f = render("STATUS = 'SHIPPED'");
        settle(f);
        vi.useRealTimers();
        await expectNoA11yViolations(el(f));
        button(f, 'Edit as conditions')!.click();
        f.detectChanges();
        await expectNoA11yViolations(el(f));
    });
});
