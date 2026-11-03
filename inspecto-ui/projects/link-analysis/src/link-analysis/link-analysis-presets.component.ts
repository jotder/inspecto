import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormControl, FormRecord, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Subscription } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import { InvService, PresetList, PresetQuery, PresetRunResult } from '@inspecto/link-analysis/api/inv.service';
import { PRESET_PATTERNS, presetColumns, presetEntityValues, presetParamValues, presetSourceLine } from './presets';

/** What *Draw these* asks the host to start: the Dataset, the entities, and what they are in words. */
export interface PresetDraw {
    datasetId: string;
    values: string[];
    what: string;
}

/**
 * LA-PRESET-QUERIES-1 **Preset queries**: pick a Dataset and one of the questions the Space's Industry pack asks of it,
 * set its few parameters, read the answer as a table, and *Draw these* - the Start box's path over the listed entities.
 * A preset the Dataset cannot answer (a role it does not map) is not offered. Presentational apart from its two reads.
 */
@Component({
    selector: 'inspecto-link-analysis-presets',
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
        <section class="flex flex-col gap-2 text-sm" aria-labelledby="la-presets-title" data-testid="la-presets">
            <h2 id="la-presets-title" class="m-0 text-base font-semibold">Preset queries</h2>
            <p class="text-secondary m-0 text-xs">Questions your industry already asks of a Dataset. Draw the answer as a graph.</p>
            <div class="flex flex-wrap items-end gap-3">
                <inspecto-option-picker
                    label="Dataset"
                    [outlined]="true"
                    [options]="datasetOptions()"
                    [formControl]="dataset"
                    data-testid="la-presets-dataset"
                />
                @if (list(); as l) {
                    <inspecto-option-picker
                        label="Question"
                        [outlined]="true"
                        [options]="presetOptions()"
                        [formControl]="preset"
                        data-testid="la-presets-preset"
                    />
                }
            </div>
            @if (loadError()) {
                <inspecto-alert variant="error">{{ loadError() }}</inspecto-alert>
            } @else if (list(); as l) {
                @if (!l.presets.length) {
                    <p class="text-secondary m-0 text-xs" data-testid="la-presets-none">
                        No preset query fits this Dataset: it does not say which columns link one entity to another.
                        Name them on the Data page (a Mapping), then come back.
                    </p>
                }
            }
            @if (chosen(); as p) {
                <p class="text-secondary m-0 text-xs">{{ p.description }}</p>
                <form class="flex flex-wrap items-start gap-x-4 gap-y-2" [formGroup]="params" (ngSubmit)="run()" data-testid="la-presets-form">
                    @for (q of shownParams(); track q.name) {
                        <inspecto-pen-field
                            class="w-64"
                            [label]="q.label"
                            [formControlName]="q.name"
                            [attr.data-testid]="'la-presets-param-' + q.name"
                            [help]="q.type === 'window' ? 'Hours or days, e.g. 24h or 30d' : ''"
                            [error]="paramError(q.name, q.type)"
                        ></inspecto-pen-field>
                    }
                    <button mat-flat-button color="primary" type="submit" class="mt-1" [disabled]="busy()" data-testid="la-presets-run">
                        Ask
                    </button>
                </form>
            }
            @if (runError()) {
                <inspecto-alert variant="error">{{ runError() }}</inspecto-alert>
            }
            @if (result(); as r) {
                @if (r.truncated) {
                    <inspecto-alert variant="warning" title="Answer cut short">
                        Only the first {{ r.count }} rows are shown. Raise a minimum or ask for fewer rows.
                    </inspecto-alert>
                }
                @if (sourceLine(); as line) {
                    <p class="text-secondary m-0 text-xs" data-testid="la-presets-source">{{ line }}</p>
                }
                <inspecto-data-table
                    [rows]="r.rows"
                    [columns]="columns()"
                    noRowsTitle="Nothing matches this question"
                    exportName="preset-query"
                ></inspecto-data-table>
                <button
                    mat-stroked-button
                    type="button"
                    class="self-start"
                    data-testid="la-presets-draw"
                    [disabled]="!drawValues().length || !!drawWhyNot()"
                    [disabledInteractive]="true"
                    [attr.aria-describedby]="drawWhyNot() ? 'la-presets-draw-why' : null"
                    (click)="drawThese()"
                >
                    Draw these
                </button>
                @if (drawWhyNot()) {
                    <p id="la-presets-draw-why" class="text-secondary m-0 text-xs">{{ drawWhyNot() }}</p>
                }
            }
        </section>
    `,
})
export class LinkAnalysisPresetsComponent {
    private readonly inv = inject(InvService);
    private readonly destroyRef = inject(DestroyRef);

    /** The Datasets a graph can start from (the Start box's catalogue). */
    readonly datasets = input<{ id: string; name: string }[]>([]);
    /** The Dataset to preselect (the Start box's). */
    readonly initialDataset = input<string | null>(null);
    /** Why *Draw these* cannot run here; '' = it can. */
    readonly drawWhyNot = input('');

    readonly draw = output<PresetDraw>();

    readonly dataset = new FormControl<string | null>(null);
    readonly preset = new FormControl<string | null>(null);
    params = new FormRecord<FormControl<string | null>>({});

    readonly list = signal<PresetList | null>(null);
    readonly loadError = signal('');
    readonly runError = signal('');
    readonly busy = signal(false);
    readonly result = signal<PresetRunResult | null>(null);

    private readonly presetId = toSignal(this.preset.valueChanges, { initialValue: null });
    readonly datasetOptions = computed<PickerOption[]>(() => this.datasets().map((d) => ({ value: d.id, label: d.name })));
    readonly presetOptions = computed<PickerOption[]>(() =>
        (this.list()?.presets ?? []).map((p) => ({ value: p.id, label: p.label, description: p.description })),
    );
    readonly chosen = computed<PresetQuery | null>(() => this.list()?.presets.find((p) => p.id === this.presetId()) ?? null);
    /** The chosen preset's parameters, less a kinds filter the Dataset cannot apply (it maps no kind of link). */
    readonly shownParams = computed(() =>
        (this.chosen()?.params ?? []).filter((q) => q.type !== 'kinds' || !!this.list()?.roles.kind),
    );
    readonly columns = computed(() => presetColumns(this.result()));
    readonly sourceLine = computed(() => presetSourceLine(this.result()));
    readonly drawValues = computed(() => presetEntityValues(this.result()));

    private loading?: Subscription;

    constructor() {
        // The Start box's rule: the page's Dataset when it can start a graph, else keep a valid pick, else the first.
        effect(() => {
            const ids = this.datasets().map((d) => d.id);
            const wanted = this.initialDataset();
            const current = this.dataset.value;
            const next = wanted && ids.includes(wanted) ? wanted : current && ids.includes(current) ? current : (ids[0] ?? null);
            if (next !== current) this.dataset.setValue(next);
        });
        this.dataset.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((id) => this.load(id));
        this.preset.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.buildParams());
    }

    private load(id: string | null): void {
        this.loading?.unsubscribe();
        this.list.set(null);
        this.loadError.set('');
        this.result.set(null);
        this.preset.setValue(null);
        if (!id) return;
        this.loading = this.inv.presets(id).subscribe({
            next: (l) => {
                this.list.set(l);
                if (l.presets.length) this.preset.setValue(l.presets[0].id);
            },
            error: (e) => this.loadError.set(apiErrorMessage(e, 'Could not read the preset queries for this Dataset.')),
        });
    }

    private buildParams(): void {
        this.result.set(null);
        this.runError.set('');
        const controls: Record<string, FormControl<string | null>> = {};
        for (const q of this.shownParams()) {
            const v = [];
            if (q.type === 'entity') v.push(Validators.required);
            const pattern = PRESET_PATTERNS[q.type];
            if (pattern) v.push(Validators.pattern(pattern));
            controls[q.name] = new FormControl<string | null>(q.default || null, v);
        }
        this.params = new FormRecord(controls);
    }

    /** A parameter row's error line, once the row was left or Ask was pressed. */
    protected paramError(name: string, type: string): string {
        const c = this.params.controls[name];
        if (!c?.touched) return '';
        if (c.hasError('required')) return 'Required.';
        if (c.hasError('pattern')) return type === 'window' ? 'A number of hours or days, e.g. 24h or 30d.' : 'A number of 0 or more.';
        return '';
    }

    run(): void {
        const p = this.chosen();
        const ds = this.dataset.value;
        if (!p || !ds) return;
        if (this.params.invalid) {
            this.params.markAllAsTouched();
            return;
        }
        this.busy.set(true);
        this.runError.set('');
        this.inv.runPreset(p.id, ds, presetParamValues(this.params.getRawValue())).subscribe({
            next: (r) => {
                this.busy.set(false);
                this.result.set(r);
            },
            error: (e) => {
                this.busy.set(false);
                this.result.set(null);
                this.runError.set(apiErrorMessage(e, 'The question could not be answered.'));
            },
        });
    }

    drawThese(): void {
        const p = this.chosen();
        const ds = this.dataset.value;
        const values = this.drawValues();
        if (!p || !ds || !values.length || this.drawWhyNot()) return;
        this.draw.emit({ datasetId: ds, values, what: `the answer to "${p.label}"` });
    }
}
