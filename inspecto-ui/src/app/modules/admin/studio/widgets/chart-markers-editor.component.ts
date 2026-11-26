import { ChangeDetectionStrategy, Component, Input, OnInit, signal } from '@angular/core';
import { FormArray, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import {
    ChartMarker,
    ChartMarkerKind,
    MARKER_KIND_LABEL,
    MARKER_TONE_LABEL,
    MarkerTone,
} from 'app/inspecto/viz/chart-markers';

type MarkerRow = FormGroup<{
    kind: FormControl<ChartMarkerKind>;
    value: FormControl<number | string | null>;
    p: FormControl<number | string | null>;
    from: FormControl<number | string | null>;
    to: FormControl<number | string | null>;
    tone: FormControl<MarkerTone>;
    label: FormControl<string | null>;
}>;

const KIND_HINT: Record<ChartMarkerKind, string> = {
    constant: 'A dashed line at a value you give',
    target: "A solid line at the target: the value you give, else the KPI's, else this Widget's Target",
    average: 'A dashed line at the average of the values drawn',
    percentile: 'A dashed line at a percentile of the values drawn',
    band: 'A shaded range in a status colour, with its words',
    rag: "The KPI's red, amber and green bands (choose the KPI above)",
};

/** A number from a pen field's value (a number, or the text typed), or null when blank or not a number. */
function num(v: unknown): number | null {
    if (v === null || v === undefined || v === '') return null;
    const n = typeof v === 'number' ? v : Number(v);
    return Number.isFinite(n) ? n : null;
}

/**
 * The Widget editor's Chart Markers list (BI-INSIGHTS-1 I-2) — add, change and remove reference lines and bands. A
 * bespoke section (a list of shaped rows, which the schema form cannot express) built from the shared row controls:
 * an option picker for the kind and tone, pen fields for numbers and the label. The host reads {@link markers} on
 * save; `null` means a row needs a fix, and its error line is then on screen.
 */
@Component({
    selector: 'app-chart-markers-editor',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        PenFieldComponent,
        InspectoOptionPickerComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <section aria-labelledby="chart-markers-heading" class="mt-4 border-t pt-4" data-testid="chart-markers">
            <h3 id="chart-markers-heading" class="text-sm font-semibold">Chart markers</h3>
            <p class="text-secondary mb-2 text-xs">
                Reference lines and target bands on the value axis. Each one is drawn with its words and value.
            </p>
            <ol class="flex flex-col gap-3" [formGroup]="form">
                @for (row of rows.controls; track row; let i = $index) {
                    <li
                        class="rounded-lg border p-3"
                        [formGroup]="row"
                        data-testid="marker-row"
                        [attr.aria-label]="'Marker ' + (i + 1)"
                    >
                        <div class="flex items-start gap-2">
                            <div class="grid min-w-0 flex-1 grid-cols-1">
                                <inspecto-option-picker label="Kind" formControlName="kind" [options]="kindOptions" />
                                @switch (row.controls.kind.value) {
                                    @case ('constant') {
                                        <inspecto-pen-field
                                            label="Value"
                                            type="number"
                                            formControlName="value"
                                            [error]="errorFor(row, 'value')"
                                        />
                                    }
                                    @case ('target') {
                                        <inspecto-pen-field
                                            label="Value"
                                            type="number"
                                            formControlName="value"
                                            placeholder="The KPI's or the Widget's target"
                                        />
                                    }
                                    @case ('percentile') {
                                        <inspecto-pen-field
                                            label="Percentile"
                                            type="number"
                                            formControlName="p"
                                            [min]="1"
                                            [max]="99"
                                            [error]="errorFor(row, 'p')"
                                        />
                                    }
                                    @case ('band') {
                                        <inspecto-pen-field
                                            label="From"
                                            type="number"
                                            formControlName="from"
                                            placeholder="The bottom of the chart"
                                            [error]="errorFor(row, 'from')"
                                        />
                                        <inspecto-pen-field
                                            label="To"
                                            type="number"
                                            formControlName="to"
                                            placeholder="The top of the chart"
                                        />
                                        <inspecto-option-picker
                                            label="Tone"
                                            formControlName="tone"
                                            [options]="toneOptions"
                                        />
                                    }
                                }
                                <inspecto-pen-field
                                    label="Label"
                                    formControlName="label"
                                    [placeholder]="defaultLabel(row.controls.kind.value)"
                                    [maxlength]="60"
                                />
                            </div>
                            <button
                                type="button"
                                mat-icon-button
                                (click)="remove(i)"
                                [attr.aria-label]="'Remove marker ' + (i + 1)"
                                matTooltip="Remove marker"
                                data-testid="remove-marker"
                            >
                                <mat-icon svgIcon="heroicons_outline:trash"></mat-icon>
                            </button>
                        </div>
                        @if (row.controls.kind.value === 'rag' && !kpiBound) {
                            <p class="text-warn mt-1 text-xs" role="alert" data-testid="marker-rag-unbound">
                                Choose the KPI above — its bands are what this marker draws.
                            </p>
                        }
                    </li>
                }
            </ol>
            <button type="button" mat-stroked-button class="mt-3" (click)="add()" data-testid="add-marker">
                <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                Add marker
            </button>
        </section>
    `,
})
export class ChartMarkersEditorComponent implements OnInit {
    /** The Widget's stored markers. */
    @Input() initial: readonly ChartMarker[] = [];
    /** Whether the Widget names a KPI (`options.kpi.kpiId`) — a RAG marker without one draws nothing. */
    @Input() kpiBound = false;

    readonly rows = new FormArray<MarkerRow>([]);
    readonly form = new FormGroup({ rows: this.rows });
    /** Error lines show once the host tried to save (or the row was touched). */
    private readonly submitted = signal(false);

    readonly kindOptions: PickerOption[] = (Object.keys(MARKER_KIND_LABEL) as ChartMarkerKind[]).map((k) => ({
        value: k,
        label: MARKER_KIND_LABEL[k],
        hint: KIND_HINT[k],
    }));
    readonly toneOptions: PickerOption[] = (Object.keys(MARKER_TONE_LABEL) as MarkerTone[]).map((t) => ({
        value: t,
        label: MARKER_TONE_LABEL[t],
    }));

    ngOnInit(): void {
        for (const m of this.initial) this.rows.push(this.row(m));
        this.form.markAsPristine();
    }

    add(): void {
        this.rows.push(this.row({ kind: 'target' }));
        this.rows.markAsDirty();
    }

    /** Add a marker of this kind (a Show-Me suggestion taken in the cog dialog), unless one is there already. */
    addKind(kind: ChartMarkerKind): void {
        if (this.rows.controls.some((r) => r.value.kind === kind)) return;
        this.rows.push(this.row({ kind }));
        this.rows.markAsDirty();
    }

    remove(i: number): void {
        this.rows.removeAt(i);
        this.rows.markAsDirty();
    }

    isDirty(): boolean {
        return this.form.dirty;
    }

    defaultLabel(kind: ChartMarkerKind): string {
        return {
            constant: 'Reference',
            target: 'Target',
            average: 'Average',
            percentile: 'Percentile',
            band: 'Band',
            rag: 'Red, Amber, Green',
        }[kind];
    }

    /** The row's error for `field`, once the host has tried to save or the control was touched. */
    errorFor(row: MarkerRow, field: 'value' | 'p' | 'from'): string {
        const c = row.controls[field];
        if (!this.submitted() && !c.touched) return '';
        return rowError(row)?.field === field ? rowError(row)!.message : '';
    }

    /** The rows as stored markers, or `null` (and the error lines shown) when a row needs a fix. */
    markers(): ChartMarker[] | null {
        this.submitted.set(true);
        this.rows.markAllAsTouched();
        if (this.rows.controls.some((r) => rowError(r))) return null;
        return this.rows.controls.map(toMarker);
    }

    private row(m: Partial<ChartMarker> & { kind: ChartMarkerKind }): MarkerRow {
        const a = m as Record<string, unknown>;
        return new FormGroup({
            kind: new FormControl<ChartMarkerKind>(m.kind, { nonNullable: true }),
            value: new FormControl<number | string | null>(num(a['value'])),
            p: new FormControl<number | string | null>(num(a['p']) ?? 90),
            from: new FormControl<number | string | null>(num(a['from'])),
            to: new FormControl<number | string | null>(num(a['to'])),
            tone: new FormControl<MarkerTone>((a['tone'] as MarkerTone) ?? 'success', { nonNullable: true }),
            label: new FormControl<string | null>((a['label'] as string) ?? null),
        });
    }
}

/** What is wrong with a row, if anything — one message, on the field to fix. */
function rowError(row: MarkerRow): { field: 'value' | 'p' | 'from'; message: string } | null {
    const v = row.getRawValue();
    if (v.kind === 'constant' && num(v.value) == null) return { field: 'value', message: 'Enter the line’s value.' };
    if (v.kind === 'percentile') {
        const p = num(v.p);
        if (p == null || !Number.isInteger(p) || p < 1 || p > 99)
            return { field: 'p', message: 'Enter a whole percentile from 1 to 99.' };
    }
    if (v.kind === 'band' && num(v.from) == null && num(v.to) == null)
        return { field: 'from', message: 'Enter where the band starts, ends, or both.' };
    return null;
}

/** A row as the stored marker — only the keys its kind reads, so the saved Widget stays minimal. */
function toMarker(row: MarkerRow): ChartMarker {
    const v = row.getRawValue();
    const label = v.label?.trim() ? { label: v.label.trim() } : {};
    switch (v.kind) {
        case 'constant':
            return { kind: 'constant', value: num(v.value)!, ...label };
        case 'target': {
            const value = num(v.value);
            return { kind: 'target', ...(value == null ? {} : { value }), ...label };
        }
        case 'percentile':
            return { kind: 'percentile', p: num(v.p)!, ...label };
        case 'band': {
            const from = num(v.from);
            const to = num(v.to);
            return {
                kind: 'band',
                ...(from == null ? {} : { from }),
                ...(to == null ? {} : { to }),
                tone: v.tone,
                ...label,
            };
        }
        case 'average':
        case 'rag':
            return { kind: v.kind, ...label };
    }
}
