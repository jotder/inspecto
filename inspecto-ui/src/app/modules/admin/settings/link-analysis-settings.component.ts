import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import {
    AbstractControl,
    FormControl,
    FormGroup,
    ReactiveFormsModule,
    ValidationErrors,
    Validators,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';

import { LensService, apiErrorMessage } from 'app/inspecto/api';
import {
    LinkAnalysisLimits,
    LinkAnalysisSettingsService,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/** `SettingsRoutes.MAX_NODE_CAP` — every integer setting on this route is 1..100 000 or refused (422). */
const MAX = 100_000;
const intOrBlank = [Validators.min(1), Validators.max(MAX), Validators.pattern('\\d*')];

type DraftKey = 'maxOpen' | 'hibernateAfterMinutes' | 'expireAfterDays';
/** `drafts` keys: shipped default and accepted range (the server refuses outside it, 422). */
const DRAFT_FIELDS: { key: DraftKey; label: string; max: number; def: number; hint: string }[] = [
    {
        key: 'maxOpen',
        label: 'Drafts: open per Space',
        max: 1000,
        def: 50,
        hint: 'Most open Drafts one Space holds; a further fork is refused.',
    },
    {
        key: 'hibernateAfterMinutes',
        label: 'Drafts: hibernate after (minutes idle)',
        max: 10080,
        def: 60,
        hint: 'An idle Draft is parked on disk after this long.',
    },
    {
        key: 'expireAfterDays',
        label: 'Drafts: expire after (days idle)',
        max: 3650,
        def: 30,
        hint: 'An idle Draft is discarded after this long; must be longer than the hibernation.',
    },
];
/** `SettingsRoutes` `maxSetBytes`: 1 KiB..1 GiB (the PostgreSQL `text` ceiling), refused 422 outside; blank = 64 MiB. */
const SET_BYTES_MIN = 1024;
const SET_BYTES_MAX = 1_073_741_824;
const SET_BYTES_DEFAULT = 64 * 1024 * 1024;
/** `SettingsRoutes` `maxInvestigationBytes`: 1 MiB..1 TiB, refused 422 outside; blank = 4 GiB. */
const INV_BYTES_MIN = 1_048_576;
const INV_BYTES_MAX = 1_099_511_627_776;
const INV_BYTES_DEFAULT = 4 * 1024 * 1024 * 1024;
const invBytesValidators = [Validators.min(INV_BYTES_MIN), Validators.max(INV_BYTES_MAX), Validators.pattern('\\d*')];
const setBytesValidators = [Validators.min(SET_BYTES_MIN), Validators.max(SET_BYTES_MAX), Validators.pattern('\\d*')];
const draftValidators = (max: number) => [Validators.min(1), Validators.max(max), Validators.pattern('\\d*')];

type Key = 'fourEyesBudgetAbove' | 'fourEyesFanOutAbove' | 'mergedDistinctCap' | 'seedByDistinctCap';

/** Expiry must outlast hibernation (server 422 otherwise); a blank key counts as its value in force, else default. */
function expiryAfterHibernation(
    inForce: { hibernateAfterMinutes?: number | null; expireAfterDays?: number | null } | undefined,
) {
    return (g: AbstractControl): ValidationErrors | null => {
        const v = g.getRawValue() as Record<DraftKey, number | null | ''>;
        const pick = (x: number | null | '', f: number | null | undefined, d: number): number =>
            x === null || x === '' ? (f ?? d) : Number(x);
        const hib = pick(v.hibernateAfterMinutes, inForce?.hibernateAfterMinutes, 60);
        const exp = pick(v.expireAfterDays, inForce?.expireAfterDays, 30);
        return exp * 1440 > hib ? null : { expiryNotAfterHibernation: true };
    };
}

/**
 * Settings ▸ **Link Analysis** — the Investigation controls of `GET|PUT /settings/link-analysis` the SPA had no
 * surface for: the two four-eyes thresholds (D-U7), `mergedDistinctCap` (LA-17 merged traversal, with the value
 * in force) and — only when the server reports it — `seedByDistinctCap`. Blank = inherit the shipped default.
 *
 * <p>⚠ The PUT REPLACES the whole document, so a save sends back every other stated key exactly as read (the node
 * caps, masking mode, Entity Types) — this section edits four keys and must not erase the rest.
 */
@Component({
    selector: 'inspecto-link-analysis-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Link Analysis"
                subtitle="Four-eyes thresholds, traversal caps and Draft limits for Investigations in this space."
                [inset]="false"
            />
            @if (loading()) {
                <p class="text-secondary text-sm">Reading the Link Analysis settings…</p>
            } @else if (loadError()) {
                <inspecto-alert variant="error" title="Settings unavailable">{{ loadError() }}</inspecto-alert>
            } @else {
                <form [formGroup]="form" class="flex max-w-160 flex-col gap-3" (ngSubmit)="save()">
                    @for (f of fields(); track f.key) {
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>{{ f.label }}</mat-label>
                            <input
                                matInput
                                type="number"
                                min="1"
                                [formControlName]="f.key"
                                [readonly]="!canEdit()"
                                [placeholder]="f.placeholder"
                            />
                            <mat-hint>{{ f.hint }}</mat-hint>
                            @if (form.controls[f.key].invalid) {
                                <mat-error>A whole number from 1 to {{ max }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
                    }
                    @if (hasSetLimit()) {
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Working Set size limit (bytes)</mat-label>
                            <input
                                matInput
                                type="number"
                                [min]="setBytesMin"
                                [max]="setBytesMax"
                                formControlName="maxSetBytes"
                                [readonly]="!canEdit()"
                                [placeholder]="'default ' + setBytesInForce()"
                            />
                            <mat-hint
                                >In force: {{ setBytesInForce() / 1048576 }} MiB ({{ setBytesInForce() }} bytes). A
                                Working Set over the limit is refused with 413, so one step cannot grow an
                                Investigation's storage without bound; narrow the step or raise the limit.</mat-hint
                            >
                            @if (form.controls.maxSetBytes.invalid) {
                                <mat-error
                                    >A whole number of bytes from {{ setBytesMin }} (1 KiB) to {{ setBytesMax }} (1
                                    GiB), or blank for the default (64 MiB).</mat-error
                                >
                            }
                        </mat-form-field>
                    }
                    @if (hasInvestigationBudget()) {
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Investigation total size budget (bytes)</mat-label>
                            <input
                                matInput
                                type="number"
                                [min]="invBytesMin"
                                [max]="invBytesMax"
                                formControlName="maxInvestigationBytes"
                                [readonly]="!canEdit()"
                                [placeholder]="'default ' + invBytesInForce()"
                            />
                            <mat-hint
                                >In force: {{ invBytesInForce() / 1073741824 }} GiB ({{ invBytesInForce() }} bytes). A write
                                that would take one Investigation's sealed Working Sets (main plus open Drafts) over the
                                budget is refused with 413; fork a smaller Investigation or raise the budget.</mat-hint
                            >
                            @if (form.controls.maxInvestigationBytes.invalid) {
                                <mat-error
                                    >A whole number of bytes from {{ invBytesMin }} (1 MiB) to {{ invBytesMax }} (1 TiB),
                                    or blank for the default (4 GiB).</mat-error
                                >
                            }
                        </mat-form-field>
                    }
                    <fieldset formGroupName="drafts" class="flex flex-col gap-3">
                        <legend class="text-sm font-semibold">Drafts</legend>
                        @for (d of draftFields; track d.key) {
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>{{ d.label }}</mat-label>
                                <input
                                    matInput
                                    type="number"
                                    min="1"
                                    [formControlName]="d.key"
                                    [readonly]="!canEdit()"
                                    [placeholder]="'default ' + draftDefault(d)"
                                />
                                <mat-hint>{{ d.hint }} In force: {{ draftDefault(d) }}.</mat-hint>
                                @if (form.controls.drafts.controls[d.key].invalid) {
                                    <mat-error
                                        >A whole number from 1 to {{ d.max }}, or blank for the default.</mat-error
                                    >
                                }
                            </mat-form-field>
                        }
                        @if (form.controls.drafts.hasError('expiryNotAfterHibernation')) {
                            <p class="text-warn text-sm" role="alert">
                                Expiry must be longer than the hibernation period.
                            </p>
                        }
                    </fieldset>
                    @if (saveError()) {
                        <inspecto-alert variant="error" title="Not saved">{{ saveError() }}</inspecto-alert>
                    }
                    @if (writesDisabled()) {
                        <inspecto-alert variant="warning" title="Changes cannot be saved here">{{
                            writesDisabled()
                        }}</inspecto-alert>
                    }
                    @if (canEdit()) {
                        <div>
                            <button mat-flat-button color="primary" type="submit" [disabled]="saving()">
                                Save Link Analysis settings
                            </button>
                        </div>
                    } @else {
                        <inspecto-alert variant="info" title="Read only">
                            Changing these settings needs the Workbench authoring capability.
                        </inspecto-alert>
                    }
                </form>
            }
        </div>
    `,
})
export class LinkAnalysisSettingsComponent implements OnInit {
    private api = inject(LinkAnalysisSettingsService);
    private lens = inject(LensService);
    private toastr = inject(ToastrService);

    readonly max = MAX;
    readonly draftFields = DRAFT_FIELDS;
    readonly setBytesMin = SET_BYTES_MIN;
    readonly setBytesMax = SET_BYTES_MAX;
    readonly invBytesMin = INV_BYTES_MIN;
    readonly invBytesMax = INV_BYTES_MAX;
    readonly canEdit = computed(() => this.lens.canAuthorWorkbench());
    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly saveError = signal<string | null>(null);
    readonly writesDisabled = signal<string | null>(null);
    private readonly served = signal<LinkAnalysisLimits | null>(null);
    /** Shown only when the server reports the key (it always does since 2026-10-10). */
    readonly hasSetLimit = computed(() => this.served() !== null && 'maxSetBytes' in this.served()!);
    readonly setBytesInForce = computed(() => this.served()?.maxSetBytesInForce ?? SET_BYTES_DEFAULT);
    readonly hasInvestigationBudget = computed(() => this.served() !== null && 'maxInvestigationBytes' in this.served()!);
    readonly invBytesInForce = computed(() => this.served()?.maxInvestigationBytesInForce ?? INV_BYTES_DEFAULT);

    readonly form = new FormGroup({
        fourEyesBudgetAbove: new FormControl<number | null>(null, intOrBlank),
        fourEyesFanOutAbove: new FormControl<number | null>(null, intOrBlank),
        mergedDistinctCap: new FormControl<number | null>(null, intOrBlank),
        seedByDistinctCap: new FormControl<number | null>(null, intOrBlank),
        maxSetBytes: new FormControl<number | null>(null, setBytesValidators),
        maxInvestigationBytes: new FormControl<number | null>(null, invBytesValidators),
        drafts: new FormGroup(
            {
                maxOpen: new FormControl<number | null>(null, draftValidators(1000)),
                hibernateAfterMinutes: new FormControl<number | null>(null, draftValidators(10080)),
                expireAfterDays: new FormControl<number | null>(null, draftValidators(3650)),
            },
            expiryAfterHibernation(undefined),
        ),
    });

    /** The value in force for a Drafts key (server-computed), else the shipped default. */
    draftDefault(d: { key: DraftKey; def: number }): number {
        return this.served()?.draftsInForce?.[d.key] ?? d.def;
    }

    readonly fields = computed(() => {
        const s = this.served();
        const out: { key: Key; label: string; hint: string; placeholder: string }[] = [
            {
                key: 'fourEyesBudgetAbove',
                label: 'Four-eyes: expand budget above',
                hint: 'An expand asking for more than this needs a second approver. Blank = no threshold.',
                placeholder: 'no threshold',
            },
            {
                key: 'fourEyesFanOutAbove',
                label: 'Four-eyes: fan-out above',
                hint: 'An expand reaching more entities than this needs a second approver. Blank = no threshold.',
                placeholder: 'no threshold',
            },
            {
                key: 'mergedDistinctCap',
                label: 'Merged expand: distinct values per column',
                hint: `In force: ${s?.mergedDistinctCapInForce ?? '—'}. Above it a merged expand is refused, never sampled.`,
                placeholder: `default ${s?.mergedDistinctCapInForce ?? ''}`,
            },
        ];
        if (s && 'seedByDistinctCap' in s)
            out.push({
                key: 'seedByDistinctCap',
                label: 'Seed by Entity List: distinct values',
                hint: `In force: ${s.seedByDistinctCapInForce ?? '—'}.`,
                placeholder: `default ${s.seedByDistinctCapInForce ?? ''}`,
            });
        return out;
    });

    ngOnInit(): void {
        this.api.get().subscribe({
            next: (s) => {
                this.loading.set(false);
                this.apply(s);
            },
            error: (err) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(err, 'The Link Analysis settings route did not answer.'));
            },
        });
    }

    save(): void {
        const served = this.served();
        if (!served || this.saving()) return;
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        const num = (x: number | null): number | null => (x === null || (x as unknown) === '' ? null : Number(x));
        // Everything stated stays as read; the server-computed *InForce keys are not part of the document.
        const body: Record<string, unknown> = {};
        for (const [k, x] of Object.entries(served)) if (!k.endsWith('InForce')) body[k] = x;
        body['fourEyesBudgetAbove'] = num(v.fourEyesBudgetAbove);
        body['fourEyesFanOutAbove'] = num(v.fourEyesFanOutAbove);
        body['mergedDistinctCap'] = num(v.mergedDistinctCap);
        if ('seedByDistinctCap' in served) body['seedByDistinctCap'] = num(v.seedByDistinctCap);
        else delete body['seedByDistinctCap'];
        if ('maxSetBytes' in served) body['maxSetBytes'] = num(v.maxSetBytes);
        if ('maxInvestigationBytes' in served) body['maxInvestigationBytes'] = num(v.maxInvestigationBytes);
        // Blank keys are omitted (inherit); no stated key at all = no block.
        const drafts: Record<string, number> = {};
        for (const d of DRAFT_FIELDS) {
            const n = num(v.drafts[d.key]);
            if (n !== null) drafts[d.key] = n;
        }
        if (Object.keys(drafts).length) body['drafts'] = drafts;
        else delete body['drafts'];
        this.saving.set(true);
        this.saveError.set(null);
        this.api.save(body as unknown as LinkAnalysisLimits).subscribe({
            next: (s) => {
                this.saving.set(false);
                this.writesDisabled.set(null);
                this.apply(s);
                this.toastr.success('Link Analysis settings saved.');
            },
            error: (err) => {
                this.saving.set(false);
                const message = apiErrorMessage(err, 'Saving the Link Analysis settings failed.');
                if (err?.status === 422) this.saveError.set(message);
                else if (err?.status === 403)
                    this.saveError.set('You are not allowed to change these settings. Server: ' + message);
                else if (err?.status === 503) this.writesDisabled.set(message);
                else this.toastr.error(message);
            },
        });
    }

    private apply(s: LinkAnalysisLimits): void {
        this.served.set(s);
        this.form.reset({
            fourEyesBudgetAbove: s.fourEyesBudgetAbove ?? null,
            fourEyesFanOutAbove: s.fourEyesFanOutAbove ?? null,
            mergedDistinctCap: s.mergedDistinctCap ?? null,
            seedByDistinctCap: s.seedByDistinctCap ?? null,
            maxSetBytes: s.maxSetBytes ?? null,
            maxInvestigationBytes: s.maxInvestigationBytes ?? null,
            drafts: {
                maxOpen: s.drafts?.maxOpen ?? null,
                hibernateAfterMinutes: s.drafts?.hibernateAfterMinutes ?? null,
                expireAfterDays: s.drafts?.expireAfterDays ?? null,
            },
        });
        this.form.controls.drafts.setValidators(expiryAfterHibernation(s.draftsInForce));
        this.form.controls.drafts.updateValueAndValidity();
    }
}
