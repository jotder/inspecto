import { ChangeDetectionStrategy, Component, OnInit, computed, inject, input, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatExpansionModule } from '@angular/material/expansion';
import { ToastrService } from 'ngx-toastr';

import { LensService, apiErrorMessage } from 'app/inspecto/api';
import {
    LinkAnalysisLimits,
    LinkAnalysisSettingsService,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { LinkAnalysisLimitsComponent } from './link-analysis-limits.component';
import { LinkAnalysisDomainProfileComponent } from '@inspecto/link-analysis/link-analysis/link-analysis-domain-profile.component';
import { ExclusionCategoriesComponent } from './exclusion-categories.component';

/** `ConfigSpecs.LINK_ANALYSIS_MASKING_MODES`; blank = inherit the edition's default (`typed`; `none` in Basic), read back as `maskingModeInForce`. */
const MASKING_MODES: { value: string; label: string; hint: string }[] = [
    {
        value: 'typed',
        label: 'typed',
        hint: 'Only values of Entity Types marked masked are shown as tokens; the rest are shown as they are.',
    },
    { value: 'all', label: 'all', hint: 'Every entity value is shown as a token; the raw value stays on the server.' },
    { value: 'none', label: 'none', hint: 'No entity value is masked; every value is shown as it is.' },
];
/** SettingsRoutes graphRun.propagatedRiskWeights: 1..6 numbers in [0,1], comma separated; blank = inherit. */
const RISK_WEIGHTS_MAX = 6;

/** The risk weights as typed: comma separated numbers; null when blank (NaN entries kept so the validator refuses them). */
function parseWeights(text: string | null): number[] | null {
    const parts = (text ?? '')
        .split(',')
        .map((x) => x.trim())
        .filter((x) => x.length > 0);
    return parts.length ? parts.map(Number) : null;
}

function riskWeightsValid(c: AbstractControl): ValidationErrors | null {
    const w = parseWeights(c.value as string | null);
    if (w === null) return null;
    return w.length <= RISK_WEIGHTS_MAX && w.every((n) => Number.isFinite(n) && n >= 0 && n <= 1)
        ? null
        : { riskWeights: true };
}
/** `SettingsRoutes` `embed.*`: at most 20 refused classifications of at most 64 characters, at most 20 framing origins. */
const EMBED_REFUSE_MAX = 20;
const EMBED_NAME_MAX = 64;

/** The refused classifications as typed: comma separated, blanks dropped. */
function parseClasses(text: string | null): string[] {
    return (text ?? '')
        .split(',')
        .map((s) => s.trim())
        .filter((s) => s.length > 0);
}

/** An allowed framing origin as the server accepts it: https://host[:port] (http only for localhost), no wildcard, path or query. */
const ORIGIN_RE = /^(https:\/\/[a-z0-9.-]+|http:\/\/(localhost|127\.0\.0\.1))(:\d{1,5})?$/i;
function embedOriginsValid(c: AbstractControl): ValidationErrors | null {
    const o = parseClasses(c.value as string | null);
    return o.length <= EMBED_REFUSE_MAX && o.every((x) => ORIGIN_RE.test(x)) ? null : { embedOrigins: true };
}

/** At most 20 names of at most 64 characters (server 422 otherwise). */
function embedClassesValid(c: AbstractControl): ValidationErrors | null {
    const names = parseClasses(c.value as string | null);
    return names.length <= EMBED_REFUSE_MAX && names.every((n) => n.length <= EMBED_NAME_MAX)
        ? null
        : { embedClasses: true };
}

/**
 * Settings ▸ **Link Analysis** — the NON-limit settings of `GET|PUT /settings/link-analysis` (masking mode, the Entity
 * Types in force, whether reads use the link index, the propagated-risk weights, which classifications and sites embed
 * links refuse or allow), above the three limits sections (`<inspecto-link-analysis-limits>`).
 *
 * <p>⚠ LA-CONFIG-LIMITS-1 CL-17: every numeric LIMIT (four-eyes thresholds, hub threshold, distinct caps, Working Set
 * budgets, graph-run budgets, index, Drafts, embed lifetimes, node caps) is edited in ONE place, the limits sections,
 * so this form has no field for any of them and its PUT never carries one: the server keeps a limit key the body leaves
 * out. A non-limit key is replaced as before (the Entity Types are sent back exactly as read).
 */
@Component({
    selector: 'inspecto-link-analysis-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatExpansionModule,
        PenFieldComponent,
        InspectoOptionPickerComponent,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
        LinkAnalysisLimitsComponent,
        LinkAnalysisDomainProfileComponent,
        ExclusionCategoriesComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Link Analysis"
                subtitle="Masking, Entity Types, the link index, propagated-risk weights and embed-link sharing for Investigations in this space. Every numeric limit is set in the sections below."
                [inset]="false"
            />
            @if (loading()) {
                <p class="text-secondary text-sm">Reading the Link Analysis settings…</p>
            } @else if (loadError()) {
                <inspecto-alert variant="error" title="Settings unavailable">{{ loadError() }}</inspecto-alert>
            } @else {
                <form
                    [formGroup]="form"
                    class="flex max-w-4xl flex-col gap-3"
                    [attr.aria-describedby]="canEdit() ? null : 'la-settings-readonly-reason'"
                    (ngSubmit)="save()"
                >
                    <mat-accordion multi class="flex flex-col gap-2">
                        <mat-expansion-panel [expanded]="true" data-group="Masking">
                            <mat-expansion-panel-header>
                                <mat-panel-title>Masking</mat-panel-title>
                            </mat-expansion-panel-header>
                            <div class="flex flex-col gap-3">
                                <inspecto-option-picker
                                    label="Masking mode"
                                    formControlName="maskingMode"
                                    [options]="maskingOptions"
                                    [readOnly]="!canEdit()"
                                />
                                @if (maskingInForce()) {
                                    <p class="text-secondary m-0 text-xs">In force: {{ maskingInForce() }}.</p>
                                }
                                <ul class="text-secondary list-disc pl-5 text-sm">
                                    @for (m of maskingModes; track m.value) {
                                        <li>
                                            <strong>{{ m.label }}</strong> - {{ m.hint }}
                                        </li>
                                    }
                                </ul>
                            </div>
                        </mat-expansion-panel>
                        <mat-expansion-panel [expanded]="true" data-group="Entity Types in force">
                            <mat-expansion-panel-header>
                                <mat-panel-title>Entity Types in force</mat-panel-title>
                            </mat-expansion-panel-header>
                            <div class="flex flex-col gap-3">
                                @if (entityTypes().length) {
                                    <ul class="text-sm" aria-label="Entity Types in force">
                                        @for (t of entityTypes(); track t.id) {
                                            <li>
                                                <strong>{{ t.label }}</strong> ({{ t.id }}) -
                                                {{ t.normaliser }} normaliser, {{ t.masked ? 'masked' : 'not masked' }};
                                                claims
                                                {{
                                                    t.classifications.length
                                                        ? t.classifications.join(', ')
                                                        : 'no classification'
                                                }}
                                            </li>
                                        }
                                    </ul>
                                } @else {
                                    <p class="text-secondary text-sm">None reported.</p>
                                }
                                <p class="text-secondary text-sm">
                                    Read only here; Entity Types are changed through the settings document.
                                </p>
                            </div>
                        </mat-expansion-panel>
                        <mat-expansion-panel [expanded]="true" data-group="Link index">
                            <mat-expansion-panel-header>
                                <mat-panel-title>Link index</mat-panel-title>
                            </mat-expansion-panel-header>
                            <div class="flex flex-col gap-3" formGroupName="index">
                                <inspecto-option-picker
                                    label="Serve reads from the link index when a fresh one exists"
                                    formControlName="enabled"
                                    [options]="indexOptions"
                                    [readOnly]="!canEdit()"
                                />
                                <p class="text-secondary m-0 text-xs">
                                    In force: {{ indexInForce()?.enabled ? 'on' : 'off' }}.
                                </p>
                            </div>
                        </mat-expansion-panel>
                        <mat-expansion-panel [expanded]="true" data-group="Server graph run">
                            <mat-expansion-panel-header>
                                <mat-panel-title>Server graph run</mat-panel-title>
                            </mat-expansion-panel-header>
                            <div class="flex flex-col gap-3" formGroupName="graphRun">
                                <inspecto-pen-field
                                    label="Propagated risk: per-hop weights"
                                    formControlName="propagatedRiskWeights"
                                    [readOnly]="!canEdit()"
                                    [placeholder]="'default ' + riskWeightsInForce()"
                                    [error]="
                                        form.controls.graphRun.controls.propagatedRiskWeights.invalid
                                            ? '1 to 6 numbers from 0 to 1, comma separated.'
                                            : ''
                                    "
                                />
                                <p class="text-secondary m-0 text-xs">
                                    In force: {{ riskWeightsInForce() }}. Comma separated, 1 to 6 numbers from 0 to 1;
                                    blank = the default.
                                </p>
                            </div>
                        </mat-expansion-panel>
                        <mat-expansion-panel [expanded]="true" data-group="Embed links">
                            <mat-expansion-panel-header>
                                <mat-panel-title>Embed links</mat-panel-title>
                            </mat-expansion-panel-header>
                            <div class="grid grid-cols-1 gap-x-6 gap-y-3 sm:grid-cols-2" formGroupName="embed">
                                <p class="text-secondary text-sm sm:col-span-2">
                                    A read-only link to one Investigation for a holder who has no account. The feature
                                    is OFF unless the host runs with <code>-Dla.embed.enabled=true</code>; these
                                    settings only apply once it is on.
                                </p>
                                <div class="flex min-w-0 flex-col">
                                    <inspecto-pen-field
                                        label="Classifications that may not be shared by link"
                                        formControlName="refuseClassifications"
                                        [readOnly]="!canEdit()"
                                        placeholder="e.g. PII, SECRET"
                                        [error]="
                                            form.controls.embed.controls.refuseClassifications.invalid
                                                ? 'At most ' +
                                                  embedRefuseMax +
                                                  ' names of at most ' +
                                                  embedNameMax +
                                                  ' characters each.'
                                                : ''
                                        "
                                    />
                                    <p class="text-secondary m-0 text-xs">
                                        In force: {{ embedRefusedInForce() }}. Comma separated; the strictest
                                        classification of the Investigation's columns decides.
                                    </p>
                                </div>
                                <div class="flex min-w-0 flex-col">
                                    <inspecto-pen-field
                                        label="Sites that may frame the viewer page"
                                        formControlName="frameAncestors"
                                        [readOnly]="!canEdit()"
                                        placeholder="e.g. https://portal.example.com"
                                        [error]="
                                            form.controls.embed.controls.frameAncestors.invalid
                                                ? 'At most ' +
                                                  embedRefuseMax +
                                                  ' origins, each https://host[:port] with no wildcard or path.'
                                                : ''
                                        "
                                    />
                                    <p class="text-secondary m-0 text-xs">
                                        In force: {{ embedFramesInForce() }}. Comma separated https origins; blank =
                                        nobody (link only). The data route itself is never framed.
                                    </p>
                                </div>
                            </div>
                        </mat-expansion-panel>
                    </mat-accordion>
                    <p class="text-sm">
                        <strong>Investigation store:</strong> {{ storeLabel() }}
                        <span class="text-secondary">(chosen when the server starts; not a setting)</span>
                    </p>
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
                        <inspecto-alert id="la-settings-readonly-reason" variant="info" title="Read only">
                            Changing these settings needs the Workbench authoring capability.
                        </inspecto-alert>
                    }
                </form>
            }
            <inspecto-link-analysis-domain-profile />
            <inspecto-exclusion-categories [entityTypes]="entityTypes()" />
            @if (withLimits()) {
                <inspecto-link-analysis-limits />
            }
        </div>
    `,
})
export class LinkAnalysisSettingsComponent implements OnInit {
    /** Draw the three limits sections under the form. True in the console's Settings; the Link Analysis app lists them as sections of their own. */
    readonly withLimits = input(true);

    private api = inject(LinkAnalysisSettingsService);
    private lens = inject(LensService);
    private toastr = inject(ToastrService);

    readonly maskingModes = MASKING_MODES;
    readonly maskingOptions: PickerOption[] = [
        { value: '', label: 'Inherit the default' },
        ...MASKING_MODES.map((m) => ({ value: m.value, label: m.label })),
    ];
    readonly indexOptions: PickerOption[] = [
        { value: '', label: 'Inherit the default' },
        { value: 'true', label: 'On' },
        { value: 'false', label: 'Off' },
    ];
    readonly embedRefuseMax = EMBED_REFUSE_MAX;
    readonly embedNameMax = EMBED_NAME_MAX;
    readonly riskWeightsInForce = computed(() => (this.served()?.propagatedRiskWeightsInForce ?? []).join(', '));
    readonly canEdit = computed(() => this.lens.canAuthorWorkbench());
    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly saveError = signal<string | null>(null);
    readonly writesDisabled = signal<string | null>(null);
    private readonly served = signal<LinkAnalysisLimits | null>(null);
    /** The server's answer only: the default depends on the edition (Basic: none), so the SPA never assumes one. */
    readonly maskingInForce = computed(() => this.served()?.maskingModeInForce ?? '');
    readonly entityTypes = computed(() => this.served()?.entityTypesInForce ?? []);
    readonly indexInForce = computed(() => this.served()?.indexInForce ?? null);
    private readonly embedInForce = computed(() => this.served()?.embedInForce ?? null);
    readonly embedRefusedInForce = computed(() => {
        const c = this.embedInForce()?.refuseClassifications ?? [];
        return c.length ? c.join(', ') : 'none';
    });
    readonly embedFramesInForce = computed(() => {
        const c = this.embedInForce()?.frameAncestors ?? [];
        return c.length ? c.join(', ') : 'nobody';
    });
    readonly storeLabel = computed(() => {
        const b = this.served()?.investigationStoreInForce;
        return b === 'db' ? 'PostgreSQL' : b === 'fs' ? 'filesystem' : 'not reported';
    });
    readonly form = new FormGroup({
        maskingMode: new FormControl<string | null>(null),
        index: new FormGroup({
            enabled: new FormControl<string | null>(''),
        }),
        embed: new FormGroup({
            refuseClassifications: new FormControl<string>('', embedClassesValid),
            frameAncestors: new FormControl<string>('', embedOriginsValid),
        }),
        graphRun: new FormGroup({
            propagatedRiskWeights: new FormControl<string>('', riskWeightsValid),
        }),
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
        if (!served || this.saving() || !this.canEdit()) return;
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        // The non-limit keys only: the server keeps every limit key the body leaves out (CL-17). Entity Types are not
        // edited here, so they go back exactly as read; a block with nothing stated is left out (= inherit).
        const body: Record<string, unknown> = {
            maskingMode: v.maskingMode || null,
            entityTypes: served.entityTypes ?? null,
        };
        if (v.index.enabled === 'true' || v.index.enabled === 'false')
            body['index'] = { enabled: v.index.enabled === 'true' };
        const weights = parseWeights(v.graphRun.propagatedRiskWeights);
        if (weights) body['graphRun'] = { propagatedRiskWeights: weights };
        const classes = parseClasses(v.embed.refuseClassifications);
        const frames = parseClasses(v.embed.frameAncestors);
        if (classes.length || frames.length)
            body['embed'] = {
                refuseClassifications: classes.length ? classes : null,
                frameAncestors: frames.length ? frames : null,
            };
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
            maskingMode: s.maskingMode ?? null,
            index: {
                enabled: s.index?.enabled === undefined || s.index.enabled === null ? '' : String(s.index.enabled),
            },
            embed: {
                refuseClassifications: (s.embed?.refuseClassifications ?? []).join(', '),
                frameAncestors: (s.embed?.frameAncestors ?? []).join(', '),
            },
            graphRun: { propagatedRiskWeights: (s.graphRun?.propagatedRiskWeights ?? []).join(', ') },
        });
        // A viewer without the authoring capability gets EVERY control disabled (values stay readable and copyable).
        if (this.canEdit()) this.form.enable({ emitEvent: false });
        else this.form.disable({ emitEvent: false });
    }
}
