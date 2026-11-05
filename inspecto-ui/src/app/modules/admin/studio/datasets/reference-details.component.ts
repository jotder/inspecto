import {
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    computed,
    effect,
    inject,
    input,
    signal,
    untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { BiQueryService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { Dataset } from './dataset-types';
import { DETAILS_LIMIT, detailsQuery, referenceOf } from './dataset-page';

/**
 * A Reference's **details** on the console Dataset page (ONBOARDING-FIRST-RUN-1 B5, C6a; A-5, every edition): its rows,
 * filtered by one detail ("region contains North"). Read over `POST /bi/query`, so masked like every BI read and capped at
 * {@link DETAILS_LIMIT} rows. Showing and filtering need no join, so Basic has them; adding the details to another
 * Dataset's rows is enrichment (Standard, the offers below).
 *
 * @example <app-reference-details [dataset]="d" />
 */
@Component({
    selector: 'app-reference-details',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
        DataTableComponent,
    ],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 border-t pt-3"
            [attr.aria-labelledby]="titleId"
            data-testid="reference-details"
        >
            <h4 class="m-0 text-sm font-semibold" [id]="titleId">
                Details<span class="sr-only"> - {{ dataset().id }}</span>
            </h4>
            @if (identifying(); as id) {
                <p class="text-secondary m-0 text-sm">
                    Each row describes one entity, identified by {{ id }}. Link Analysis shows these details on its
                    nodes.
                </p>
            }
            <form class="grid grid-cols-1 items-end gap-x-8 sm:grid-cols-3" (ngSubmit)="apply()">
                <inspecto-option-picker
                    label="Filter by"
                    [options]="columnOptions()"
                    [formControl]="column"
                    data-testid="details-filter-column"
                ></inspecto-option-picker>
                <inspecto-pen-field
                    label="Contains"
                    [formControl]="value"
                    (entered)="apply()"
                    data-testid="details-filter-value"
                ></inspecto-pen-field>
                <div class="flex gap-2">
                    <button mat-stroked-button type="submit" data-testid="details-filter-apply">Filter</button>
                    @if (filtered()) {
                        <button mat-button type="button" data-testid="details-filter-clear" (click)="clear()">
                            Clear
                        </button>
                    }
                </div>
            </form>
            @if (error()) {
                <inspecto-alert variant="error">{{ error() }}</inspecto-alert>
            } @else {
                <inspecto-data-table
                    tier="standard"
                    [rows]="rows()"
                    [loading]="loading()"
                    [exportName]="dataset().id"
                    noRowsTitle="No row matches"
                    noRowsHint="Clear the filter to see every row."
                ></inspecto-data-table>
                @if (truncated()) {
                    <p class="text-secondary m-0 text-xs" data-testid="details-truncated">
                        Showing the first {{ limit }} rows; filter to find the others.
                    </p>
                }
            }
        </section>
    `,
})
export class ReferenceDetailsComponent {
    private static nextId = 0;
    private readonly bi = inject(BiQueryService);
    private readonly destroyRef = inject(DestroyRef);

    readonly dataset = input.required<Dataset>();

    protected readonly titleId = `app-reference-details-${ReferenceDetailsComponent.nextId++}`;
    protected readonly limit = DETAILS_LIMIT;
    protected readonly identifying = computed(() => referenceOf(this.dataset())?.identifying ?? null);
    protected readonly columnOptions = computed<PickerOption[]>(() =>
        this.dataset().columns.map((c) => ({ value: c.name, label: c.label || c.name })),
    );
    protected readonly column = new FormControl<string>('', { nonNullable: true });
    protected readonly value = new FormControl<string>('', { nonNullable: true });
    protected readonly rows = signal<Record<string, unknown>[]>([]);
    protected readonly loading = signal(false);
    protected readonly truncated = signal(false);
    protected readonly error = signal('');
    /** The filter the rows on screen were read with. */
    protected readonly filtered = signal(false);
    private read?: Subscription;

    constructor() {
        effect(() => {
            const d = this.dataset();
            untracked(() => {
                if (!this.column.value) this.column.setValue(referenceOf(d)?.identifying ?? d.columns[0]?.name ?? '');
                this.load(null);
            });
        });
    }

    protected apply(): void {
        const v = this.value.value.trim();
        this.load(v && this.column.value ? { column: this.column.value, value: v } : null);
    }

    protected clear(): void {
        this.value.setValue('');
        this.load(null);
    }

    private load(filter: { column: string; value: string } | null): void {
        this.read?.unsubscribe();
        this.loading.set(true);
        this.error.set('');
        this.read = this.bi
            .run(detailsQuery(this.dataset(), filter))
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (r) => {
                    this.loading.set(false);
                    this.rows.set(r.rows);
                    this.truncated.set(r.statistics.truncated);
                    this.filtered.set(!!filter);
                },
                error: (e: unknown) => {
                    this.loading.set(false);
                    this.rows.set([]);
                    this.error.set(apiErrorMessage(e, 'The details could not be read.'));
                },
            });
    }
}
