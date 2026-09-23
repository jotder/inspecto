import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SqlAstTableComponent } from './sql-ast-table.component';
import { SqlAstNode } from './sql-ast';

const col = (n: string): SqlAstNode => ({ class: 'COLUMN_REF', type: 'COLUMN_REF', column_names: [n] });
const int = (v: number): SqlAstNode => ({
    class: 'CONSTANT',
    type: 'VALUE_CONSTANT',
    value: { type: { id: 'INTEGER', type_info: null }, is_null: false, value: v },
});
const str = (v: string): SqlAstNode => ({
    class: 'CONSTANT',
    type: 'VALUE_CONSTANT',
    value: { type: { id: 'VARCHAR', type_info: null }, is_null: false, value: v },
});
const cmp = (type: string, left: SqlAstNode, right: SqlAstNode): SqlAstNode => ({
    class: 'COMPARISON',
    type,
    left,
    right,
});
const conj = (type: string, ...children: SqlAstNode[]): SqlAstNode => ({ class: 'CONJUNCTION', type, children });

@Component({
    standalone: true,
    imports: [SqlAstTableComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-sql-ast-table [ast]="ast" />`,
})
class HostComponent {
    ast: SqlAstNode = conj(
        'CONJUNCTION_AND',
        cmp('COMPARE_EQUAL', col('STATUS'), str('SHIPPED')),
        conj('CONJUNCTION_OR', cmp('COMPARE_GREATERTHANOREQUALTO', col('GROSS'), int(30)), {
            class: 'FUNCTION',
            type: 'FUNCTION',
            function_name: 'lower',
            children: [col('x')],
        }),
    );
}

function render() {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    const f = TestBed.createComponent(HostComponent);
    f.detectChanges();
    return f;
}

describe('SqlAstTableComponent — the read-only predicate table', () => {
    it('renders one row per condition and group, nesting announced in text, not by indentation alone', () => {
        const f = render();
        const rows = Array.from((f.nativeElement as HTMLElement).querySelectorAll('tbody tr'));
        // Each LEAF element's text, space-joined (Angular strips the whitespace between sibling nodes).
        const cells = (r: Element) =>
            Array.from(r.querySelectorAll('td, span'))
                .filter((e) => e.children.length === 0)
                .map((e) => e.textContent?.replace(/\s+/g, ' ').trim())
                .filter(Boolean)
                .join(' ');
        expect(rows.map(cells)).toEqual([
            "STATUS = 'SHIPPED'",
            'and any of the rows below',
            'Level 2 GROSS ≥ 30',
            'Level 2 or A part this view cannot show — read it in the text above',
        ]);
        // Every column header is scoped; no editable control exists anywhere in a READ-ONLY table.
        const heads = Array.from((f.nativeElement as HTMLElement).querySelectorAll('thead th'));
        expect(heads.every((h) => h.getAttribute('scope') === 'col')).toBe(true);
        expect((f.nativeElement as HTMLElement).querySelector('input, select, button, textarea')).toBeNull();
    });

    it('says how many parts it could not show', () => {
        const f = render();
        expect((f.nativeElement as HTMLElement).textContent).toContain('1 part of this condition is not shown');
    });

    it('has no a11y violations', async () => {
        await expectNoA11yViolations(render().nativeElement);
    });
});
