import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
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
    imports: [ReactiveFormsModule, MatButtonModule, MatFormFieldModule, MatInputModule, InspectoAlertComponent],
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
                    <div class="grid grid-cols-1 gap-x-3 sm:grid-cols-2">
                        @for (a of amounts; track a) {
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>{{ labels[a] }}</mat-label>
                                <input matInput inputmode="decimal" [formControlName]="a" />
                                @if (form.controls[a].hasError('decimal')) {
                                    <mat-error>A non-negative amount, up to 6 decimals.</mat-error>
                                }
                            </mat-form-field>
                        }
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Currency</mat-label>
                            <input matInput formControlName="currency" maxlength="3" placeholder="EUR" />
                            @if (form.controls.currency.hasError('pattern')) {
                                <mat-error>A three-letter ISO 4217 code, e.g. EUR.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Period</mat-label>
                            <input matInput formControlName="period" maxlength="64" placeholder="2026-09" />
                        </mat-form-field>
                    </div>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Basis</mat-label>
                        <textarea
                            matInput
                            rows="2"
                            formControlName="basis"
                            maxlength="2000"
                            placeholder="how the amounts were worked out"
                        ></textarea>
                    </mat-form-field>
                    <!-- A GROUP error never puts the currency field in an error state, so a <mat-error> there could
                         not fire — render the line explicitly (the angular-ui explicit-alert rule). -->
                    @if (form.hasError('currencyRequired') && form.controls.currency.touched) {
                        <p class="text-warn m-0 text-xs" role="alert">A currency is required once an amount is set.</p>
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
    /** A terminal object's impact is closed server-side (409) — offer no edit there. */
    readonly canEdit = computed(
        () => this.canWork() && !['ARCHIVED', 'CLOSED'].includes((this.object().status ?? '').toUpperCase()),
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
                const v = g.value as Record<string, string>;
                const any = AMOUNTS.some((a) => (v[a] ?? '').trim());
                return any && !(v['currency'] ?? '').trim() ? { currencyRequired: true } : null;
            },
        },
    );

    private readonly formValue = signal<Record<string, string>>({});

    readonly readRows = computed(() => {
        const i = this.impact();
        if (!i) return [];
        const fmt = (n: number | null) => (n == null ? '—' : this.money(n, i.currency));
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
        this.form.valueChanges.subscribe((v) => this.formValue.set(v as Record<string, string>));
        // A different object (or a reload) ends an edit in progress on the old one.
        effect(() => {
            this.object();
            this.editing.set(false);
        });
    }

    startEdit(): void {
        const i = this.impact();
        const str = (n: number | null | undefined) => (n == null ? '' : String(n));
        this.form.reset({
            suspected: str(i?.suspected),
            confirmed: str(i?.confirmed),
            recovered: str(i?.recovered),
            prevented: str(i?.prevented),
            currency: i?.currency ?? '',
            period: i?.period ?? '',
            basis: i?.basis ?? '',
        });
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
