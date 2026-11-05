import { ChangeDetectionStrategy, Component, computed, effect, input, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { NodeReference } from './reference-details';

/** A details filter to apply: keep the nodes whose `column` in `reference` contains `text`. */
export interface ReferenceFilter {
    reference: NodeReference;
    column: string;
    text: string;
}

/**
 * **Filter by details** (ONBOARDING-FIRST-RUN-1 C6a step 4; A-5, every edition): keep on the canvas the nodes whose
 * Reference row has a detail containing a text ("region contains North"); the others are hidden (undoable, nothing
 * recorded). Presentational: `(apply)` emits the filter, the host looks the drawn nodes up in the Reference and hides.
 * Offered only for the References that describe an Entity Type on the canvas.
 *
 * @example <inspecto-la-reference-filter [references]="refs" [busy]="b" (apply)="filterByDetails($event)" (clear)="showAll()" />
 */
@Component({
    selector: 'inspecto-la-reference-filter',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, InspectoOptionPickerComponent, PenFieldComponent],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2" aria-labelledby="la-reference-filter-title" data-testid="reference-filter">
            <h3 id="la-reference-filter-title" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Filter by details
            </h3>
            <form class="flex flex-col gap-1" (ngSubmit)="submit()">
                @if (references().length > 1) {
                    <inspecto-option-picker
                        label="Details from"
                        [options]="referenceOptions()"
                        [ngModel]="referenceId()"
                        (ngModelChange)="pickReference($event)"
                        [ngModelOptions]="{ standalone: true }"
                        data-testid="reference-filter-reference"
                    ></inspecto-option-picker>
                } @else if (reference(); as r) {
                    <p class="text-secondary m-0 text-xs">Details from {{ r.name }}</p>
                }
                <inspecto-option-picker
                    label="Detail"
                    [options]="columnOptions()"
                    [ngModel]="column()"
                    (ngModelChange)="column.set($event ?? '')"
                    [ngModelOptions]="{ standalone: true }"
                    data-testid="reference-filter-column"
                ></inspecto-option-picker>
                <inspecto-pen-field
                    label="Contains"
                    [ngModel]="text()"
                    (ngModelChange)="text.set($event ?? '')"
                    [ngModelOptions]="{ standalone: true }"
                    (entered)="submit()"
                    data-testid="reference-filter-text"
                ></inspecto-pen-field>
                <div class="flex gap-2">
                    <button
                        mat-stroked-button
                        type="submit"
                        data-testid="reference-filter-apply"
                        [disabled]="busy() || !column() || !text().trim()"
                    >
                        Keep matching nodes
                    </button>
                    @if (active()) {
                        <button mat-button type="button" data-testid="reference-filter-clear" (click)="clear.emit()">
                            Show all
                        </button>
                    }
                </div>
            </form>
        </section>
    `,
})
export class LinkAnalysisReferenceFilterComponent {
    /** The References that describe an Entity Type drawn on the canvas. */
    readonly references = input.required<readonly NodeReference[]>();
    readonly busy = input(false);
    /** A details filter is in force (nodes are hidden by it): offer *Show all*. */
    readonly active = input(false);
    readonly apply = output<ReferenceFilter>();
    readonly clear = output<void>();

    protected readonly referenceId = signal('');
    protected readonly column = signal('');
    protected readonly text = signal('');
    protected readonly reference = computed(
        () => this.references().find((r) => r.dataset === this.referenceId()) ?? this.references()[0] ?? null,
    );
    protected readonly referenceOptions = computed<PickerOption[]>(() =>
        this.references().map((r) => ({ value: r.dataset, label: r.name, hint: `describes ${r.entityType}` })),
    );
    protected readonly columnOptions = computed<PickerOption[]>(() =>
        (this.reference()?.columns ?? []).map((c) => ({ value: c, label: c })),
    );

    constructor() {
        // a Reference that left the canvas: fall back to the first one, and its first detail
        effect(() => {
            const r = this.reference();
            untracked(() => {
                if (r && this.referenceId() !== r.dataset) this.referenceId.set(r.dataset);
                if (r && !r.columns.includes(this.column())) this.column.set(r.columns[0] ?? '');
            });
        });
    }

    protected pickReference(id: string | null): void {
        this.referenceId.set(id ?? '');
    }

    protected submit(): void {
        const r = this.reference();
        if (!r || !this.column() || !this.text().trim() || this.busy()) return;
        this.apply.emit({ reference: r, column: this.column(), text: this.text().trim() });
    }
}
