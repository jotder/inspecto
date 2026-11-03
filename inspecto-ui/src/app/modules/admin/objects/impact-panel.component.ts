import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { ToastrService } from 'ngx-toastr';
import {
    apiErrorMessage,
    ImpactInput,
    LensService,
    ObjectImpact,
    ObjectsService,
    OperationalObject,
} from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { formatNumber } from 'app/inspecto/viz/number-format';

const AMOUNTS = ['suspected', 'confirmed', 'recovered', 'prevented'] as const;
type Amount = (typeof AMOUNTS)[number];

/** A non-negative decimal with at most 6 places — the server's rule, mirrored so the form can say so inline. */
const DECIMAL = /^\d+(\.\d{1,6})?$/;
const amount: ValidatorFn = (c) => (!c.value || DECIMAL.test(String(c.value).trim()) ? null : { decimal: true });

/**
 * The typed financial impact of an Incident or Case (WS-10, `ASSURE-IMPACT-LEDGER-1`): shows the four amounts
 * and the server-DERIVED outstanding, and — with `canWorkIncidents` — edits them through
 * `PUT /objects/{id}/impact`. The server owns validation (422) and the closed-books rule (409 on a terminal
 * object); this form only mirrors the shape rules so a mistake reads inline. The "outstanding" shown while
 * editing is a preview; what is stored and reported is the server's.
 */
@Component({
    selector: 'app-impact-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [ReactiveFormsModule, MatButtonModule, InspectoAlertComponent, PenFieldComponent],
    template: `
        <section aria-labelledby="impact-heading" class="flex flex-col gap-3">
            <div class="flex items-center justify-between">
                @if (headingLevel() === 2) {
                    <h2 id="impact-heading" class="m-0 text-sm font-semibold">Impact</h2>
                } @else {
                    <h3 id="impact-heading" class="m-0 text-sm font-semibold">Impact</h3>
                }
                @if (canEdit() && !editing()) {
                    <button mat-button type="button" (click)="startEdit()">
                        {{ impact() ? 'Edit' : 'Record impact' }}
                    </button>
                }
            </div>

            @if (!editing()) {
                @if (impact(); as i) {
                    <dl class="m-0 grid grid-cols-2 gap-x-6 gap-y-1 text-sm">
                        @for (row of readRows(); track row.label) {
                            <dt class="text-secondary">{{ row.label }}</dt>
                            <dd class="m-0 text-right font-medium tabular-nums">{{ row.value }}</dd>
                        }
                    </dl>
                    @if (i.period || i.basis) {
                        <p class="text-secondary m-0 text-xs">
                            {{ i.period ? 'Period ' + i.period : '' }}{{ i.period && i.basis ? ' · ' : ''
                            }}{{ i.basis }}
                        </p>
                    }
                } @else {
                    <p class="text-secondary m-0 text-sm">No impact recorded.</p>
                }
            } @else {
                <form [formGroup]="form" (ngSubmit)="save()" class="flex flex-col gap-2">
                    <div class="grid grid-cols-1 gap-x-8 gap-y-1 sm:grid-cols-2">
                        @for (a of amounts; track a) {
                            <inspecto-pen-field
                                [label]="labels[a]"
                                [formControlName]="a"
                                [error]="fieldError(a, 'decimal', 'A non-negative amount, up to 6 decimals.')"
                            ></inspecto-pen-field>
                        }
                        <inspecto-pen-field
                            label="Currency"
                            [maxlength]="3"
                            placeholder="EUR"
                            formControlName="currency"
                            [error]="fieldError('currency', 'pattern', 'A three-letter ISO 4217 code, e.g. EUR.')"
                        ></inspecto-pen-field>
                        <inspecto-pen-field
                            label="Period"
                            [maxlength]="64"
                            placeholder="2026-09"
                            formControlName="period"
                        ></inspecto-pen-field>
                    </div>
                    <inspecto-pen-field
                        label="Basis"
                        [multiline]="true"
                        [maxlength]="2000"
                        placeholder="how the amounts were worked out"
                        formControlName="basis"
                    ></inspecto-pen-field>
                    <!-- A GROUP error belongs to no one row, so the line is rendered here (the angular-ui
                         explicit-alert rule). -->
                    @if (form.hasError('currencyRequired') && form.controls.currency.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">A currency is required once an amount is set.</p>
                    }
                    @if (lateOnly()) {
                        <p class="text-secondary m-0 text-xs">
                            This {{ object().objectType === 'CASE' ? 'Case is closed' : 'Incident is resolved' }} — only
                            late recoveries (Recovered, Prevented) can still be recorded.
                        </p>
                    }
                    <p class="text-secondary m-0 text-sm">
                        Outstanding (confirmed − recovered):
                        <span class="font-medium tabular-nums">{{ previewOutstanding() }}</span>
                    </p>
                    @if (serverError()) {
                        <inspecto-alert variant="error">{{ serverError() }}</inspecto-alert>
                    }
                    <div class="flex justify-end gap-2">
                        <button mat-button type="button" (click)="editing.set(false)">Cancel</button>
                        <button mat-flat-button color="primary" type="submit" [disabled]="saving()">Save impact</button>
                    </div>
                </form>
            }
        </section>
    `,
})
export class ImpactPanelComponent {
    private api = inject(ObjectsService);
    private toastr = inject(ToastrService);
    private fb = inject(FormBuilder);
    /** `PUT /objects/{id}/impact` is `canWorkIncidents`, like the postmortem — never a dead button. */
    private canWork = inject(LensService).canWorkIncidents;

    readonly object = input.required<OperationalObject>();
    /** 3 inside a panel that already has section headings; 2 directly under a page's h1. */
    readonly headingLevel = input<2 | 3>(3);
    /** The server returned the updated object. */
    readonly saved = output<OperationalObject>();

    readonly amounts = AMOUNTS;
    readonly labels: Record<Amount, string> = {
        suspected: 'Suspected',
        confirmed: 'Confirmed',
        recovered: 'Recovered',
        prevented: 'Prevented',
    };

    readonly editing = signal(false);
    readonly saving = signal(false);
    readonly serverError = signal('');

    readonly impact = computed<ObjectImpact | null>(() => this.object().impact ?? null);
    private readonly status = computed(() => (this.object().status ?? '').toUpperCase());
    /** An ARCHIVED Incident's impact is closed server-side (409) — offer no edit there. */
    readonly canEdit = computed(() => this.canWork() && this.status() !== 'ARCHIVED');
    /**
     * Late recoveries (operator 2026-09-26): on a RESOLVED Incident or a CLOSED Case only `recovered` and
     * `prevented` may still change — the server 409s anything else, so the other controls are disabled.
     */
    readonly lateOnly = computed(() =>
        this.object().objectType === 'INCIDENT' ? this.status() === 'RESOLVED' : this.status() === 'CLOSED',
    );

    readonly form = this.fb.group(
        {
            suspected: ['', amount],
            confirmed: ['', amount],
            recovered: ['', amount],
            prevented: ['', amount],
            currency: ['', Validators.pattern(/^[A-Za-z]{3}$/)],
            period: [''],
            basis: [''],
        },
        {
            validators: (g) => {
                // RAW value: on a late recovery the currency control is disabled, and `value` omits disabled
                // controls, so every late-recovery save read as "no currency" and could never be submitted.
                const v = (g as FormGroup).getRawValue() as Record<string, string>;
                const any = AMOUNTS.some((a) => (v[a] ?? '').trim());
                return any && !(v['currency'] ?? '').trim() ? { currencyRequired: true } : null;
            },
        },
    );

    private readonly formValue = signal<Record<string, string>>({});

    readonly readRows = computed(() => {
        const i = this.impact();
        if (!i) return [];
        // Display only: the exact string stays the value an edit starts from (startEdit).
        const fmt = (n: string | null) => (n == null ? '—' : this.money(Number(n), i.currency));
        return [
            ...AMOUNTS.map((a) => ({ label: this.labels[a], value: fmt(i[a]) })),
            { label: 'Outstanding', value: fmt(i.outstanding) },
        ];
    });

    readonly previewOutstanding = computed(() => {
        const v = this.formValue();
        const confirmed = (v['confirmed'] ?? '').trim();
        if (!confirmed || !DECIMAL.test(confirmed)) return '—';
        const recovered = (v['recovered'] ?? '').trim();
        const out = Number(confirmed) - (recovered && DECIMAL.test(recovered) ? Number(recovered) : 0);
        return this.money(out, (v['currency'] ?? '').trim().toUpperCase() || null);
    });

    constructor() {
        // RAW value, so the preview still sees a disabled (late-recovery) confirmed and currency.
        this.form.valueChanges.subscribe(() => this.formValue.set(this.form.getRawValue() as Record<string, string>));
        // A different object (or a reload) ends an edit in progress on the old one.
        effect(() => {
            this.object();
            this.editing.set(false);
        });
    }

    /** A row's error line: `message` once the row was left (or Save pressed) while it fails `code`. */
    fieldError(name: Amount | 'currency', code: string, message: string): string {
        const c = this.form.controls[name];
        return c.touched && c.hasError(code) ? message : '';
    }

    startEdit(): void {
        const i = this.impact();
        this.form.reset({
            suspected: i?.suspected ?? '',
            confirmed: i?.confirmed ?? '',
            recovered: i?.recovered ?? '',
            prevented: i?.prevented ?? '',
            currency: i?.currency ?? '',
            period: i?.period ?? '',
            basis: i?.basis ?? '',
        });
        for (const k of ['suspected', 'confirmed', 'currency', 'period', 'basis'] as const) {
            if (this.lateOnly()) this.form.controls[k].disable();
            else this.form.controls[k].enable();
        }
        this.formValue.set(this.form.getRawValue() as Record<string, string>);
        this.serverError.set('');
        this.editing.set(true);
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const body: ImpactInput = {};
        for (const [k, v] of Object.entries(this.form.getRawValue())) {
            const t = (v ?? '').trim();
            if (t) body[k as keyof ImpactInput] = k === 'currency' ? t.toUpperCase() : t;
        }
        this.saving.set(true);
        this.serverError.set('');
        this.api.saveImpact(this.object().id, body).subscribe({
            next: (o) => {
                this.saving.set(false);
                this.editing.set(false);
                this.toastr.success('Impact saved');
                this.saved.emit(o);
            },
            error: (e: HttpErrorResponse) => {
                this.saving.set(false);
                if (e.status === 422 || e.status === 409)
                    this.serverError.set(apiErrorMessage(e, 'The server refused the impact.'));
                else this.toastr.error(apiErrorMessage(e, 'Saving the impact failed'));
            },
        });
    }

    private money(n: number, currency: string | null): string {
        return currency ? formatNumber(n, { style: 'currency', currency }) : formatNumber(n);
    }
}
