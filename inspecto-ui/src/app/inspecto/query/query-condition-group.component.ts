import { ChangeDetectionStrategy, Component, EventEmitter, Input, Output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import {
    describeGroup,
    MAX_PATTERN_LENGTH,
    supportsIgnoreCase,
    supportsValueField,
    validateCondition,
} from './condition-rules';
import { columnType, operatorDef, operatorsFor, OperatorDef } from './query-columns';
import { ColumnMeta, Condition, ConditionGroup, Operator, emptyGroup, newCondition } from './query-types';

let nextId = 0;

/**
 * Recursive editor for one {@link ConditionGroup} (AND/OR over conditions + nested groups). Mutates the
 * bound group object in place and emits {@link changed} after every edit; the host ({@link QueryPanelComponent})
 * re-derives SQL + preview from that. Renders itself recursively via its own selector (a standalone
 * component may reference its own selector without listing itself in `imports`).
 */
@Component({
    selector: 'inspecto-query-condition-group',
    standalone: true,
    imports: [
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatSlideToggleModule,
        MatTooltipModule,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './query-condition-group.component.html',
})
export class QueryConditionGroupComponent {
    @Input({ required: true }) group!: ConditionGroup;
    @Input() columns: ColumnMeta[] = [];
    @Input() root = false;
    @Output() changed = new EventEmitter<void>();

    setOp(op: 'AND' | 'OR'): void {
        this.group.op = op;
        this.changed.emit();
    }

    addCondition(): void {
        this.group.items.push(newCondition(this.columns[0]?.name ?? ''));
        this.changed.emit();
    }

    addGroup(): void {
        this.group.items.push(emptyGroup('AND'));
        this.changed.emit();
    }

    removeAt(i: number): void {
        this.group.items.splice(i, 1);
        this.changed.emit();
    }

    asGroup(it: Condition | ConditionGroup): ConditionGroup {
        return it as ConditionGroup;
    }
    asCondition(it: Condition | ConditionGroup): Condition {
        return it as Condition;
    }

    operatorsForCondition(c: Condition): OperatorDef[] {
        return operatorsFor(columnType(this.columns, c.field));
    }
    arity(c: Condition): 0 | 1 | 2 | 'list' {
        return operatorDef(columnType(this.columns, c.field), c.operator)?.arity ?? 1;
    }
    inputType(c: Condition): string {
        return columnType(this.columns, c.field) === 'number' ? 'number' : 'text';
    }

    onFieldChange(c: Condition, field: string): void {
        c.field = field;
        const ops = operatorsFor(columnType(this.columns, field));
        if (!ops.some((o) => o.op === c.operator)) c.operator = ops[0]?.op ?? '=';
        c.value = '';
        c.value2 = '';
        this.dropUnsupported(c);
        this.changed.emit();
    }
    onOperatorChange(c: Condition, op: Operator): void {
        c.operator = op;
        this.dropUnsupported(c);
        this.changed.emit();
    }

    /** Remove modifiers the (new) operator does not accept, so the tree never holds a refused combination. */
    private dropUnsupported(c: Condition): void {
        if (c.valueField !== undefined && !supportsValueField(c.operator)) delete c.valueField;
        if (c.ignoreCase && !supportsIgnoreCase(c.operator)) delete c.ignoreCase;
    }

    // ── group NOT ────────────────────────────────────────────────────────────────
    setNegate(on: boolean): void {
        if (on) this.group.negate = true;
        else delete this.group.negate;
        this.changed.emit();
    }
    /** Readable summary for assistive tech, e.g. `NOT (a equals 1 and b contains x)`. */
    summary(): string {
        return describeGroup(this.group);
    }

    // ── field-to-field + ignore case ─────────────────────────────────────────────
    canCompareField(c: Condition): boolean {
        return supportsValueField(c.operator);
    }
    canIgnoreCase(c: Condition): boolean {
        return supportsIgnoreCase(c.operator);
    }
    comparesField(c: Condition): boolean {
        return c.valueField !== undefined;
    }
    setCompareField(c: Condition, on: boolean): void {
        if (on) {
            c.valueField = '';
            c.value = '';
            c.value2 = '';
        } else {
            delete c.valueField;
        }
        this.changed.emit();
    }
    onValueField(c: Condition, field: string): void {
        c.valueField = field;
        this.changed.emit();
    }
    setIgnoreCase(c: Condition, on: boolean): void {
        if (on) c.ignoreCase = true;
        else delete c.ignoreCase;
        this.changed.emit();
    }

    /** Refusal reason shown beside the condition (mirrors the server's validation), or `null`. */
    problem(c: Condition): string | null {
        return validateCondition(c);
    }
    /** Hint for the one operator whose syntax needs explaining. */
    hint(c: Condition): string {
        return c.operator === 'matches'
            ? `Finds the pattern anywhere in the value. No lookahead, lookbehind, back-references or atomic/possessive groups; at most ${MAX_PATTERN_LENGTH} characters.`
            : '';
    }
    /** The id a control points `aria-describedby` at — only while that note is actually rendered. */
    noteId(c: Condition): string | null {
        return this.problem(c) || this.hint(c) ? this.uid(c) : null;
    }
    /** A stable per-condition id for aria-describedby. */
    uid(c: Condition): string {
        let n = this.ids.get(c);
        if (n === undefined) {
            n = ++nextId;
            this.ids.set(c, n);
        }
        return 'qc-note-' + n;
    }
    private readonly ids = new WeakMap<Condition, number>();
    onValue(c: Condition, v: string): void {
        c.value = v;
        this.changed.emit();
    }
    onValue2(c: Condition, v: string): void {
        c.value2 = v;
        this.changed.emit();
    }
}
