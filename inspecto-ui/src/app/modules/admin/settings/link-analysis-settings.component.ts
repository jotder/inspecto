import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import {
    AbstractControl,
    FormControl,
    FormGroup,
    ReactiveFormsModule,
    ValidationErrors,
} from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';

import { LensService, apiErrorMessage } from 'app/inspecto/api';
import {
    LinkAnalysisLimits,
    LinkAnalysisSettingsService,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { LinkAnalysisLimitsComponent } from './link-analysis-limits.component';

/** `ConfigSpecs.LINK_ANALYSIS_MASKING_MODES`; blank = inherit (`typed`). */
const MASKING_MODES: { value: string; label: string; hint: string }[] = [
    { value: 'typed', label: 'typed', hint: 'Only values of Entity Types marked masked are shown as tokens; the rest are shown as they are.' },
    { value: 'all', label: 'all', hint: 'Every entity value is shown as a token; the raw value stays on the server.' },
    { value: 'none', label: 'none', hint: 'No entity value is masked; every value is shown as it is.' },
];
/** SettingsRoutes graphRun.propagatedRiskWeights: 1..6 numbers in [0,1], comma separated; blank = inherit. */
const RISK_WEIGHTS_MAX = 6;

/** The risk weights as typed: comma separated numbers; null when blank (NaN entries kept so the validator refuses them). */
function parseWeights(text: string | null): number[] | null {
    const parts = (text ?? '').split(',').map((x) => x.trim()).filter((x) => x.length > 0);
    return parts.length ? parts.map(Number) : null;
}

function riskWeightsValid(c: AbstractControl): ValidationErrors | null {
    const w = parseWeights(c.value as string | null);
    if (w === null) return null;
    return w.length <= RISK_WEIGHTS_MAX && w.every((n) => Number.isFinite(n) && n >= 0 && n <= 1) ? null : { riskWeights: true };
}
/** `SettingsRoutes` `embed.*`: at most 20 refused classifications of at most 64 characters, at most 20 framing origins. */
const EMBED_REFUSE_MAX = 20;
const EMBED_NAME_MAX = 64;

/** The refused classifications as typed: comma separated, blanks dropped. */
function parseClasses(text: string | null): string[] {
    return (text ?? '').split(',').map((s) => s.trim()).filter((s) => s.length > 0);
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
    return names.length <= EMBED_REFUSE_MAX && names.every((n) => n.length <= EMBED_NAME_MAX) ? null : { embedClasses: true };
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
        MatFormFieldModule,
        MatInputModule,
        MatSelectModule,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
        LinkAnalysisLimitsComponent,
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
                    class="flex max-w-160 flex-col gap-3"
                    [attr.aria-describedby]="canEdit() ? null : 'la-settings-readonly-reason'"
                    (ngSubmit)="save()"
                >
                    <fieldset class="flex flex-col gap-3">
                        <legend class="text-sm font-semibold">Masking</legend>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Masking mode</mat-label>
                            <mat-select formControlName="maskingMode">
                                <mat-option [value]="null">Inherit the default (typed)</mat-option>
                                @for (m of maskingModes; track m.value) {
                                    <mat-option [value]="m.value">{{ m.label }}</mat-option>
                                }
                            </mat-select>
                            <mat-hint>In force: {{ maskingInForce() }}.</mat-hint>
                        </mat-form-field>
                        <ul class="text-secondary list-disc pl-5 text-sm">
                            @for (m of maskingModes; track m.value) {
                                <li>
                                    <strong>{{ m.label }}</strong> - {{ m.hint }}
                                </li>
                            }
                        </ul>
                    </fieldset>
                    <fieldset class="flex flex-col gap-2">
                        <legend class="text-sm font-semibold">Entity Types in force</legend>
                        @if (entityTypes().length) {
                            <ul class="text-sm" aria-label="Entity Types in force">
                                @for (t of entityTypes(); track t.id) {
                                    <li>
                                        <strong>{{ t.label }}</strong> ({{ t.id }}) - {{ t.normaliser }} normaliser,
                                        {{ t.masked ? 'masked' : 'not masked' }}; claims
                                        {{ t.classifications.length ? t.classifications.join(', ') : 'no classification' }}
                                    </li>
                                }
                            </ul>
                        } @else {
                            <p class="text-secondary text-sm">None reported.</p>
                        }
                        <p class="text-secondary text-sm">Read only here; Entity Types are changed through the settings document.</p>
                    </fieldset>
                    <fieldset formGroupName="index" class="flex flex-col gap-3">
                        <legend class="text-sm font-semibold">Link index</legend>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Serve reads from the link index when a fresh one exists</mat-label>
                            <mat-select formControlName="enabled">
                                <mat-option [value]="null">Inherit the default</mat-option>
                                <mat-option [value]="true">On</mat-option>
                                <mat-option [value]="false">Off</mat-option>
                            </mat-select>
                            <mat-hint>In force: {{ indexInForce()?.enabled ? 'on' : 'off' }}.</mat-hint>
                        </mat-form-field>
                    </fieldset>
                    <fieldset formGroupName="graphRun" class="flex flex-col gap-3">
                        <legend class="text-sm font-semibold">Server graph run</legend>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Propagated risk: per-hop weights</mat-label>
                            <input matInput type="text" formControlName="propagatedRiskWeights" [readonly]="!canEdit()"
                                [placeholder]="'default ' + riskWeightsInForce()" />
                            <mat-hint>In force: {{ riskWeightsInForce() }}. Comma separated, 1 to 6 numbers from 0 to 1; blank = the default.</mat-hint>
                            @if (form.controls.graphRun.controls.propagatedRiskWeights.invalid) {
                                <mat-error>1 to 6 numbers from 0 to 1, comma separated.</mat-error>
                            }
                        </mat-form-field>
                    </fieldset>
                    <p class="text-sm">
                        <strong>Investigation store:</strong> {{ storeLabel() }}
                        <span class="text-secondary">(chosen when the server starts; not a setting)</span>
                    </p>
                    <fieldset formGroupName="embed" class="flex flex-col gap-3">
                        <legend class="text-sm font-semibold">Embed links</legend>
                        <p class="text-secondary text-sm">
                            A read-only link to one Investigation for a holder who has no account. The feature is OFF unless the host
                            runs with <code>-Dla.embed.enabled=true</code>; these settings only apply once it is on.
                        </p>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Classifications that may not be shared by link</mat-label>
                            <input matInput type="text" formControlName="refuseClassifications" [readonly]="!canEdit()"
                                placeholder="e.g. PII, SECRET" />
                            <mat-hint>In force: {{ embedRefusedInForce() }}. Comma separated; the strictest classification of the Investigation's columns decides.</mat-hint>
                            @if (form.controls.embed.controls.refuseClassifications.invalid) {
                                <mat-error>At most {{ embedRefuseMax }} names of at most {{ embedNameMax }} characters each.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Sites that may frame the viewer page</mat-label>
                            <input matInput type="text" formControlName="frameAncestors" [readonly]="!canEdit()"
                                placeholder="e.g. https://portal.example.com" />
                            <mat-hint>In force: {{ embedFramesInForce() }}. Comma separated https origins; blank = nobody (link only). The data route itself is never framed.</mat-hint>
                            @if (form.controls.embed.controls.frameAncestors.invalid) {
                                <mat-error>At most {{ embedRefuseMax }} origins, each https://host[:port] with no wildcard or path.</mat-error>
                            }
                        </mat-form-field>
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
                        <inspecto-alert id="la-settings-readonly-reason" variant="info" title="Read only">
                            Changing these settings needs the Workbench authoring capability.
                        </inspecto-alert>
                    }
                </form>
            }
            <inspecto-link-analysis-limits />
        </div>
    `,
})
export class LinkAnalysisSettingsComponent implements OnInit {
    private api = inject(LinkAnalysisSettingsService);
    private lens = inject(LensService);
    private toastr = inject(ToastrService);

    readonly maskingModes = MASKING_MODES;
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
    readonly maskingInForce = computed(() => this.served()?.maskingModeInForce ?? 'typed');
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
            enabled: new FormControl<boolean | null>(null),
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
        const body: Record<string, unknown> = { maskingMode: v.maskingMode || null, entityTypes: served.entityTypes ?? null };
        if (v.index.enabled !== null) body['index'] = { enabled: v.index.enabled };
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
            index: { enabled: s.index?.enabled ?? null },
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
