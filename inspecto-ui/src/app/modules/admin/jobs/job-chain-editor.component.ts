import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChainRow, chainToParams, parseChain, rowConfigError } from './job-chain';

/**
 * The `consignment.process` chain, authored as an ordered list (pipeline spec gap 12, decision D7).
 *
 * Replaces the two raw params an author otherwise keeps aligned **by counting**: `processor`
 * (`mask,rollup,report`) and `chain_config` (a JSON array positionally aligned with it). Here each step
 * is one row owning its own config, and {@link value} emits the pair aligned by construction.
 *
 * <p><b>Contract mirrors `<inspecto-schema-form>`</b> — the host seeds it once via {@link seed}, calls
 * {@link validate} before saving and reads {@link value}. It has **no write path**: the Job dialog saves
 * through its own route, like every other shared authoring surface.
 *
 * <p>⚠ Ordering is <b>accessible move up/down</b>, not drag-drop — the idiom
 * `menu-tree-node.component` already uses. A chain's order is its meaning, so it must be reachable by
 * keyboard, and a two-item list does not justify a drag dependency.
 *
 * <p>🔴 A row recovered from a <b>surplus</b> `chain_config` entry arrives with a blank id and is marked
 * <b>touched at seed time</b>. Without that the required-error is suppressed until the field is visited,
 * so save would refuse with nothing on screen to correct — the failure mode the schema-form's
 * open-the-collapsed-section fix exists to prevent.
 */
@Component({
    selector: 'app-job-chain-editor',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        InspectoAlertComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="flex flex-col gap-2">
            <div class="flex items-center justify-between">
                <div>
                    <div class="text-sm font-semibold">Processor chain</div>
                    <p class="text-secondary text-xs">
                        Each step runs in this order over the same Consignment, seeing it as the previous step left it.
                    </p>
                </div>
                <button mat-stroked-button type="button" (click)="addRow()">
                    <mat-icon class="icon-size-4" svgIcon="heroicons_outline:plus"></mat-icon>
                    Add step
                </button>
            </div>

            @if (recovered() > 0) {
                <inspecto-alert variant="warning" title="A config entry had no step">
                    {{ recovered() }} entry in 'chain_config' had no matching processor — it was kept as a step with no
                    id rather than discarded. Name its processor, or remove the step.
                </inspecto-alert>
            }

            @if (rows.length === 0) {
                <p class="text-secondary text-sm">No steps yet — add one to build the chain.</p>
            }

            <ol class="flex flex-col gap-2" [formGroup]="form">
                @for (row of rows.controls; track row; let i = $index; let first = $first; let last = $last) {
                    <li
                        class="flex flex-col gap-1 rounded border p-2"
                        style="border-color: var(--gamma-border); background: var(--gamma-bg-card)"
                        [formGroup]="asGroup(row)"
                    >
                        <div class="flex items-center gap-2">
                            <span class="text-secondary text-xs font-semibold">Step {{ i + 1 }}</span>
                            <mat-form-field class="flex-auto" subscriptSizing="dynamic">
                                <mat-label>Processor id</mat-label>
                                <input matInput formControlName="id" autocomplete="off" />
                                @if (asGroup(row).controls.id.hasError('required')) {
                                    <mat-error>A step needs the id of a ConsignmentProcessor.</mat-error>
                                }
                                @if (asGroup(row).controls.id.hasError('comma')) {
                                    <mat-error>An id cannot contain a comma — it separates steps.</mat-error>
                                }
                            </mat-form-field>
                            <button
                                mat-icon-button
                                type="button"
                                [disabled]="first"
                                (click)="move(i, -1)"
                                matTooltip="Move earlier"
                                [attr.aria-label]="'Move step ' + (i + 1) + ' earlier'"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-up"></mat-icon>
                            </button>
                            <button
                                mat-icon-button
                                type="button"
                                [disabled]="last"
                                (click)="move(i, 1)"
                                matTooltip="Move later"
                                [attr.aria-label]="'Move step ' + (i + 1) + ' later'"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-down"></mat-icon>
                            </button>
                            <button
                                mat-icon-button
                                type="button"
                                (click)="remove(i)"
                                matTooltip="Remove step"
                                [attr.aria-label]="'Remove step ' + (i + 1)"
                            >
                                <mat-icon class="icon-size-4" svgIcon="heroicons_outline:trash"></mat-icon>
                            </button>
                        </div>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Config (JSON)</mat-label>
                            <textarea matInput formControlName="configText" rows="3" spellcheck="false"></textarea>
                            @if (asGroup(row).controls.configText.hasError('json')) {
                                <mat-error>{{ asGroup(row).controls.configText.getError('json') }}</mat-error>
                            }
                        </mat-form-field>
                    </li>
                }
            </ol>
        </div>
    `,
})
export class JobChainEditorComponent {
    private fb = inject(FormBuilder);

    readonly form = this.fb.group({ rows: this.fb.array<RowGroup>([]) });

    /** How many rows came from a `chain_config` entry with no processor of its own. */
    private readonly recoveredCount = signal(0);
    readonly recovered = computed(() => this.recoveredCount());

    get rows(): FormArray<RowGroup> {
        return this.form.controls.rows;
    }

    /** Narrow for the template — a `FormArray<FormGroup>` control is typed as `AbstractControl` there. */
    asGroup(c: unknown): RowGroup {
        return c as RowGroup;
    }

    /**
     * Seed from the two raw params. Returns `false` when `chain_config` is not an array of objects —
     * the host must then leave the raw fields in place, because this editor cannot represent the value
     * and rewriting it would destroy an authored config it does not understand.
     *
     * ⚠ One-shot, like every other seeded shared surface: re-seeding while the author edits clobbers
     * the form.
     */
    seed(processor: unknown, chainConfig: unknown): boolean {
        const parsed = parseChain(processor, chainConfig);
        if (parsed === null) return false;
        this.rows.clear();
        let recovered = 0;
        for (const row of parsed) {
            const g = this.newRow(row);
            // A blank id here came from a surplus config entry — show its error at once (see the class
            // doc): an untouched control renders no <mat-error>, so the refusal would be invisible.
            if (!row.id.trim()) {
                g.controls.id.markAsTouched();
                recovered++;
            }
            this.rows.push(g);
        }
        this.recoveredCount.set(recovered);
        return true;
    }

    /** True when every row is complete and its config parses. Marks touched so the errors render. */
    validate(): boolean {
        this.form.markAllAsTouched();
        return this.form.valid;
    }

    /** The two params, aligned by construction. Call only after {@link validate}. */
    value(): { processor: string; chain_config: unknown[] } {
        return chainToParams(
            this.rows.controls.map((g) => ({
                id: String(this.asGroup(g).controls.id.value ?? ''),
                configText: String(this.asGroup(g).controls.configText.value ?? ''),
                element: (g as WithElement)._element,
            })),
        );
    }

    addRow(): void {
        this.rows.push(this.newRow({ id: '', configText: '{}' }));
    }

    remove(i: number): void {
        this.rows.removeAt(i);
        if (this.recoveredCount() > 0) this.recoveredCount.set(0);
    }

    /** Reorder by one place. Order is the chain's meaning, so this is the only thing that changes. */
    move(i: number, delta: number): void {
        const to = i + delta;
        if (to < 0 || to >= this.rows.length) return;
        const g = this.rows.at(i);
        this.rows.removeAt(i);
        this.rows.insert(to, g);
        this.form.markAsDirty();
    }

    private newRow(row: ChainRow): RowGroup {
        const g: RowGroup = this.fb.group({
            id: this.fb.control(row.id, [Validators.required, noComma]),
            configText: this.fb.control(row.configText, [jsonObject]),
        });
        // The element travels on the group so an unmodelled key survives the round trip (job-chain.ts).
        (g as WithElement)._element = row.element;
        return g;
    }
}

/** One chain row's form group. */
type RowGroup = FormGroup<{ id: FormControl<string>; configText: FormControl<string> }>;
/** The verbatim `chain_config` element, carried on the row's group rather than in the form value. */
type WithElement = RowGroup & { _element?: Record<string, unknown> };

/** A comma in an id would silently split one step into two on the way out. */
function noComma(c: { value: unknown }): Record<string, boolean> | null {
    return String(c.value ?? '').includes(',') ? { comma: true } : null;
}

/** `config` must be a JSON object; the message is rendered verbatim. */
function jsonObject(c: { value: unknown }): Record<string, string> | null {
    const err = rowConfigError({ id: 'x', configText: String(c.value ?? '') });
    return err ? { json: `Config ${err}.` } : null;
}
