import { ChangeDetectionStrategy, Component, computed, inject, input, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import {
    AlertSeverity,
    InvService,
    InvestigationHeader,
    ValueMeasureAlertRuleResult,
    ValueMeasureResult,
} from '@inspecto/link-analysis/api/inv.service';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { columnOptionLoader, datasetOptionLoader } from '@inspecto/core/components/entity-option-loaders';
import { InspectoOptionPickerComponent } from '@inspecto/core/components/option-picker.component';
import { InspectoSchemaFormComponent } from '@inspecto/core/components/schema-form.component';
import { DataTableComponent } from '@inspecto/core/data-table';
import { isUnavailable } from './link-analysis-template.dialogs';
import {
    VALUE_MEASURES,
    VALUE_MEASURE_THRESHOLDS,
    valueMeasureAttributes,
    valueMeasureColumns,
    valueMeasureQuery,
} from './value-measures';

/** A value-measure failure in the analyst's words; 422 carries the server's own sentence verbatim. */
export function valueMeasureErrorMessage(err: unknown, fallback: string): string {
    const status = (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 403:
            return 'You do not have the capability this needs. Server: ' + server;
        case 404:
            return 'That Dataset (or Investigation) is not available to you. Server: ' + server;
        case 409:
            return 'Refused — ' + server;
        case 422:
            return 'The server refused this: ' + server;
        case 503:
            return (
                'Value Measures need the link-analysis module, a write root and (to watch) the alert engine. Server: ' +
                server
            );
        default:
            return server;
    }
}

/**
 * **Link Analysis — Value Measures** (LA-18, SPA half): one named value Measure over a WHOLE Dataset
 * (`GET /inv/value-measures`) — the entities breaching its thresholds in a `[from, to)` window, the thresholds in
 * force stated in words and editable, and the `truncated` / `unvalued` counts shown as they are. No view filter is
 * ever sent (the §2.6 ≥ 5 000 trap). "Watch" binds a value-measure Alert Rule to the open Investigation
 * (`POST …/alert-rules` with `valueMeasure`): it fires on the COUNT of breaching entities — one Alert per rule.
 */
@Component({
    selector: 'inspecto-link-analysis-value-measures',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        DataTableComponent,
        InspectoOptionPickerComponent,
        InspectoSchemaFormComponent,
    ],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-xs" aria-labelledby="la-value-measures-heading">
            <h3 id="la-value-measures-heading" class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                Value Measures
            </h3>
            <p class="text-secondary m-0">Reads the whole Dataset in the window — never the filtered view on screen.</p>
            <inspecto-schema-form
                [specs]="specs"
                [initial]="seed()"
                [optionLoaders]="loaders"
                (submitted)="run()"
            ></inspecto-schema-form>
            <div>
                <button mat-stroked-button type="button" [disabled]="busy()" (click)="run()">Run Measure</button>
            </div>

            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Value Measure">{{
                    error()
                }}</inspecto-alert>
            }

            @if (result(); as r) {
                <p class="m-0" data-test="threshold">
                    <span class="font-semibold">Threshold in force:</span> {{ r.measure.name }} — {{ r.threshold }}
                </p>
                <p class="text-secondary m-0" data-test="counts">
                    {{ r.count }} entities breach · {{ r.rowsInWindow }} rows in the window · {{ r.unvalued }}
                    rows without a numeric value (skipped)
                </p>
                @if (r.truncated) {
                    <inspecto-alert variant="warning" title="Result cut">
                        More entities breach than are listed — only the first {{ r.fences?.maxEntities ?? r.count }}
                        are shown. Narrow the window or raise a threshold.
                    </inspecto-alert>
                }
                <inspecto-data-table
                    [rows]="r.entities"
                    [columns]="columns()"
                    noRowsTitle="No entity breaches these thresholds"
                    exportName="value-measure"
                ></inspecto-data-table>

                @if (!alertable()) {
                    <p class="text-secondary m-0">This Measure is read-only — it cannot be watched by an Alert Rule.</p>
                } @else if (!investigation()) {
                    <p class="text-secondary m-0">Open an Investigation over this Dataset to watch this Measure.</p>
                } @else if (!matchesInvestigation()) {
                    <p class="text-secondary m-0">
                        Watching binds over the open Investigation's Dataset and roles ({{ investigation()!.dataset }});
                        run the Measure over those to watch it.
                    </p>
                } @else {
                    <form
                        [formGroup]="watchForm"
                        class="flex flex-col gap-2"
                        aria-label="Watch this value Measure"
                        (ngSubmit)="watch()"
                    >
                        <p class="m-0" data-test="fires-on">
                            Fires ONE Alert when at least 1 entity breaches — on the count of breaching entities, not
                            per entity. Now: {{ r.count }}.
                        </p>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Alert Rule name</mat-label>
                            <input matInput formControlName="name" autocomplete="off" />
                            @if (watchForm.controls.name.hasError('required')) {
                                <mat-error>A name is required.</mat-error>
                            } @else if (watchForm.controls.name.hasError('pattern')) {
                                <mat-error>Letters, digits, dot, dash and underscore only.</mat-error>
                            }
                        </mat-form-field>
                        <inspecto-option-picker
                            label="Severity"
                            formControlName="severity"
                            [options]="severities"
                        ></inspecto-option-picker>
                        <div>
                            <button mat-flat-button color="primary" type="submit" [disabled]="binding() || !!bound()">
                                Watch
                            </button>
                        </div>
                    </form>
                    @if (bound(); as b) {
                        <inspecto-alert [variant]="b.wouldFire ? 'warning' : 'success'" title="Alert Rule armed">
                            {{ b.wouldFire ? 'It would fire now' : 'It would not fire now' }} ({{
                                b.current ?? '—'
                            }}
                            breaching). {{ b.disclosure }}
                        </inspecto-alert>
                    }
                }
            }
        </section>
    `,
})
export class LinkAnalysisValueMeasuresComponent {
    private inv = inject(InvService);

    /** The open Investigation, if any — seeds the Dataset and roles, and is what "Watch" binds to. */
    readonly investigation = input<InvestigationHeader | null>(null);

    readonly specs = valueMeasureAttributes();
    readonly loaders = {
        dataset: datasetOptionLoader(),
        sourceCol: columnOptionLoader('dataset'),
        targetCol: columnOptionLoader('dataset'),
        linkKindCol: columnOptionLoader('dataset'),
        valueCol: columnOptionLoader('dataset'),
        timeCol: columnOptionLoader('dataset'),
    };
    readonly severities: { value: AlertSeverity; label: string }[] = [
        { value: 'INFO', label: 'Info' },
        { value: 'WARNING', label: 'Warning' },
        { value: 'CRITICAL', label: 'Critical (also opens an Incident)' },
    ];

    readonly seed = computed(() => {
        const h = this.investigation();
        return {
            name: 'passThrough',
            ...(h
                ? {
                      dataset: h.dataset,
                      sourceCol: h.sourceCol,
                      targetCol: h.targetCol,
                      linkKindCol: h.linkKindCol ?? '',
                  }
                : {}),
        };
    });

    readonly form = viewChild.required(InspectoSchemaFormComponent);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly result = signal<ValueMeasureResult | null>(null);
    /** The Dataset + roles the result was computed over. */
    private readonly ranOver = signal<{ dataset: string; sourceCol: string; targetCol: string; linkKindCol: string }>({
        dataset: '',
        sourceCol: '',
        targetCol: '',
        linkKindCol: '',
    });
    readonly columns = computed(() => valueMeasureColumns(this.result()?.entities ?? []));
    readonly alertable = computed(
        () => VALUE_MEASURES.find((m) => m.value === this.result()?.measure.name)?.alertable ?? false,
    );
    readonly matchesInvestigation = computed(() => {
        const h = this.investigation();
        const o = this.ranOver();
        return (
            !!h &&
            h.dataset === o.dataset &&
            h.sourceCol === o.sourceCol &&
            h.targetCol === o.targetCol &&
            (h.linkKindCol ?? '') === o.linkKindCol
        );
    });

    readonly watchForm = new FormGroup({
        name: new FormControl('', {
            nonNullable: true,
            validators: [Validators.required, Validators.pattern('[A-Za-z0-9._-]+')],
        }),
        severity: new FormControl<AlertSeverity>('WARNING', { nonNullable: true }),
    });
    readonly binding = signal(false);
    readonly bound = signal<ValueMeasureAlertRuleResult | null>(null);

    async run(): Promise<void> {
        const form = this.form();
        if (this.busy() || !form.validate()) return;
        const q = valueMeasureQuery(form.value());
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        this.bound.set(null);
        try {
            const r = await firstValueFrom(this.inv.valueMeasures(q));
            this.result.set(r);
            this.ranOver.set({
                dataset: q.dataset,
                sourceCol: q.sourceCol,
                targetCol: q.targetCol,
                linkKindCol: q.linkKindCol ?? '',
            });
            // State the thresholds in force in the fields themselves, so the next run edits what is shown.
            const inForce: Record<string, unknown> = {};
            for (const k of VALUE_MEASURE_THRESHOLDS[r.measure.name] ?? []) inForce[k] = r.measure[k];
            form.form.patchValue(inForce, { emitEvent: false });
            const id = this.investigation()?.id;
            if (!this.watchForm.controls.name.dirty)
                this.watchForm.controls.name.setValue(
                    `${id ?? 'inv'}-${r.measure.name}`.replace(/[^A-Za-z0-9._-]/g, '-'),
                );
        } catch (err) {
            this.result.set(null);
            this.unavailable.set(isUnavailable(err));
            this.error.set(valueMeasureErrorMessage(err, 'Could not compute the value Measure.'));
        } finally {
            this.busy.set(false);
        }
    }

    async watch(): Promise<void> {
        const h = this.investigation();
        const r = this.result();
        if (!h || !r || this.binding()) return;
        if (this.watchForm.invalid) {
            this.watchForm.markAllAsTouched();
            return;
        }
        const f = this.watchForm.getRawValue();
        this.binding.set(true);
        this.error.set('');
        this.unavailable.set(false);
        try {
            // The block exactly as the server answered it — what is watched is what was shown.
            this.bound.set(
                await firstValueFrom(
                    this.inv.bindValueMeasureAlertRule(h.id, {
                        name: f.name.trim(),
                        valueMeasure: r.measure,
                        severity: f.severity,
                    }),
                ),
            );
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(valueMeasureErrorMessage(err, 'Could not create the Alert Rule.'));
        } finally {
            this.binding.set(false);
        }
    }
}
