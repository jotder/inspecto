import { ChangeDetectionStrategy, Component, inject, input, output } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { NODE_RULE_OPS, NodeRule, NodeRuleOp, describeNodeRule } from '@inspecto/core/graph';

/**
 * **Conditional formatting** editor for the canvas Display menu: an ordered list of rules (an attribute predicate →
 * a node colour, first match wins) plus the form that adds one. Presentational — the host owns the rules signal,
 * the undo history and the saved view; this only emits the next list. The colours are the shared chart-token swatches.
 */
@Component({
    selector: 'inspecto-link-analysis-display-rules',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule],
    template: `
        <span class="text-secondary mt-1 text-xs font-medium uppercase tracking-wide">Conditional formatting</span>
        @for (r of rules(); track $index) {
            <div class="flex items-center gap-1.5">
                <span class="h-4 w-4 shrink-0 rounded-full border" [style.background]="r.color"></span>
                <span class="min-w-0 flex-1 truncate text-sm" [title]="describe(r)">{{ describe(r) }}</span>
                <button
                    mat-icon-button
                    type="button"
                    class="!h-6 !w-6"
                    (click)="remove($index)"
                    [attr.aria-label]="'Remove the rule ' + describe(r)"
                >
                    <mat-icon class="icon-size-3" svgIcon="heroicons_outline:x-mark"></mat-icon>
                </button>
            </div>
        } @empty {
            <span class="text-secondary text-xs">
                No rules. Colour the nodes whose attribute meets a condition; the first matching rule wins and
                outranks the per-type colour.
            </span>
        }
        @if (attributes().length === 0) {
            <span class="text-secondary text-xs">The loaded nodes carry no attributes to build a rule on.</span>
        } @else {
            <form [formGroup]="form" (ngSubmit)="add()" class="flex flex-col gap-1">
                <div class="flex flex-wrap items-center gap-1.5">
                    <label class="sr-only" for="la-rule-kind">Entity Type</label>
                    <select id="la-rule-kind" formControlName="kind" class="rounded border px-1 text-sm">
                        <option value="">any type</option>
                        @for (k of kinds(); track k) {
                            <option [value]="k">{{ k }}</option>
                        }
                    </select>
                    <label class="sr-only" for="la-rule-attr">Attribute</label>
                    <select id="la-rule-attr" formControlName="attr" class="rounded border px-1 text-sm">
                        <option value="" disabled>attribute</option>
                        @for (a of attributes(); track a) {
                            <option [value]="a">{{ a }}</option>
                        }
                    </select>
                    <label class="sr-only" for="la-rule-op">Comparison</label>
                    <select id="la-rule-op" formControlName="op" class="rounded border px-1 text-sm">
                        @for (o of ops; track o.value) {
                            <option [value]="o.value">{{ o.label }}</option>
                        }
                    </select>
                    <label class="sr-only" for="la-rule-value">Value</label>
                    <input
                        id="la-rule-value"
                        formControlName="value"
                        placeholder="value"
                        class="w-24 rounded border px-1 text-sm"
                    />
                </div>
                <div class="flex items-center gap-1.5">
                    @for (s of swatches(); track s) {
                        <button
                            type="button"
                            class="h-4 w-4 rounded-full border"
                            [style.background]="s"
                            [class.ring-2]="form.controls.color.value === s"
                            [attr.aria-pressed]="form.controls.color.value === s"
                            (click)="form.controls.color.setValue(s)"
                            aria-label="Colour matching nodes with this swatch"
                        ></button>
                    }
                    <button mat-stroked-button type="submit" [disabled]="form.invalid">Add rule</button>
                </div>
            </form>
        }
    `,
})
export class LinkAnalysisDisplayRulesComponent {
    private readonly fb = inject(FormBuilder);

    readonly rules = input<readonly NodeRule[]>([]);
    /** The node `attrs` keys present in the loaded graph. */
    readonly attributes = input<readonly string[]>([]);
    readonly kinds = input<readonly string[]>([]);
    readonly swatches = input<readonly string[]>([]);
    readonly rulesChange = output<NodeRule[]>();

    readonly ops = NODE_RULE_OPS;
    readonly form = this.fb.nonNullable.group({
        kind: [''],
        attr: ['', Validators.required],
        op: ['eq' as NodeRuleOp],
        value: ['', Validators.required],
        color: ['', Validators.required],
    });

    describe(r: NodeRule): string {
        return describeNodeRule(r);
    }

    add(): void {
        if (this.form.invalid) return;
        const { kind, attr, op, value, color } = this.form.getRawValue();
        this.rulesChange.emit([...this.rules(), { ...(kind ? { kind } : {}), attr, op, value, color }]);
        this.form.patchValue({ value: '' });
    }

    remove(index: number): void {
        this.rulesChange.emit(this.rules().filter((_, i) => i !== index));
    }
}
