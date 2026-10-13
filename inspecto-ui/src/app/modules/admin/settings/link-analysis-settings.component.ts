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
import { MatSelectModule } from '@angular/material/select';
import { ToastrService } from 'ngx-toastr';

import { LensService, apiErrorMessage } from 'app/inspecto/api';
import {
    LinkAnalysisLimits,
    LinkAnalysisSettingsService,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
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
/** `ConfigSpecs.LINK_ANALYSIS_MASKING_MODES`; blank = inherit (`typed`). */
const MASKING_MODES: { value: string; label: string; hint: string }[] = [
    { value: 'typed', label: 'typed', hint: 'Only values of Entity Types marked masked are shown as tokens; the rest are shown as they are.' },
    { value: 'all', label: 'all', hint: 'Every entity value is shown as a token; the raw value stays on the server.' },
    { value: 'none', label: 'none', hint: 'No entity value is masked; every value is shown as it is.' },
];
/** `SettingsRoutes` `graphRun.*`: 1..10 000 000 each, refused 422 outside (the service's own ceilings clamp a larger default). */
const RUN_MAX = 10_000_000;
const runValidators = [Validators.min(1), Validators.max(RUN_MAX), Validators.pattern('\\d*')];
/** `SettingsRoutes` `index.*`: threads 1..64, queue 1..1000, maxDiskBytes 0..10^15 (0 = no limit). */
const IDX_THREADS_MAX = 64;
const IDX_QUEUE_MAX = 1000;
const IDX_DISK_MAX = 1_000_000_000_000_000;
/** SettingsRoutes index.keepVersions: 1..100 published versions. */
const IDX_KEEP_MAX = 100;
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
/** SettingsRoutes index.maxAgeHours: 1..8760 hours (one year). */
const IDX_MAX_AGE_MAX = 8760;
/** `SettingsRoutes` `embed.*`: lifetimes 1..8760 hours (the cap defaults to 720 and a Space may raise it that far); the default may not exceed the cap. */
const EMBED_TTL_MAX = 8760;
const EMBED_MAX_TTL_DEFAULT = 720;
const EMBED_REFUSE_MAX = 20;
const EMBED_NAME_MAX = 64;
const embedTtlValidators = [Validators.min(1), Validators.max(EMBED_TTL_MAX), Validators.pattern('\\d*')];

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

/** The default lifetime may not exceed the cap in force (a blank cap is the shipped 720 h) - the server's 422, caught before the save. */
function embedDefaultWithinMax(g: AbstractControl): ValidationErrors | null {
    const v = g.getRawValue() as { defaultTtlHours: number | null | ''; maxTtlHours: number | null | '' };
    if (v.defaultTtlHours === null || v.defaultTtlHours === '') return null;
    const max = v.maxTtlHours === null || v.maxTtlHours === '' ? EMBED_MAX_TTL_DEFAULT : Number(v.maxTtlHours);
    return Number(v.defaultTtlHours) <= max ? null : { embedDefaultAboveMax: true };
}
const draftValidators = (max: number) => [Validators.min(1), Validators.max(max), Validators.pattern('\\d*')];

type RunKey = 'maxNodes' | 'maxEdges' | 'timeoutMs';
type Key = 'fourEyesBudgetAbove' | 'fourEyesFanOutAbove' | 'mergedDistinctCap' | 'seedByDistinctCap' | 'hubThreshold';

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
 * surface for: the two four-eyes thresholds (D-U7), the hub threshold (supernode suppression, `hub_threshold`), `mergedDistinctCap` (LA-17 merged traversal, with the value
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
        MatSelectModule,
        InspectoAlertComponent,
        InspectoPageHeaderComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Link Analysis"
                subtitle="Masking, four-eyes thresholds, traversal caps, link index, graph-run budgets and Draft limits for Investigations in this space."
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
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Index build workers</mat-label>
                            <input matInput type="number" min="1" [max]="idxThreadsMax" formControlName="threads"
                                [readonly]="!canEdit()" [placeholder]="'default ' + (indexInForce()?.threads ?? '')" />
                            <mat-hint>In force: {{ indexInForce()?.threads ?? '-' }}.</mat-hint>
                            @if (form.controls.index.controls.threads.invalid) {
                                <mat-error>A whole number from 1 to {{ idxThreadsMax }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Index build waiting line</mat-label>
                            <input matInput type="number" min="1" [max]="idxQueueMax" formControlName="queue"
                                [readonly]="!canEdit()" [placeholder]="'default ' + (indexInForce()?.queue ?? '')" />
                            <mat-hint>In force: {{ indexInForce()?.queue ?? '-' }}.</mat-hint>
                            @if (form.controls.index.controls.queue.invalid) {
                                <mat-error>A whole number from 1 to {{ idxQueueMax }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Index versions kept</mat-label>
                            <input matInput type="number" min="1" [max]="idxKeepMax" formControlName="keepVersions"
                                [readonly]="!canEdit()" [placeholder]="'default ' + (indexInForce()?.keepVersions ?? '')" />
                            <mat-hint>In force: {{ indexInForce()?.keepVersions ?? '-' }}. Published versions kept per index.</mat-hint>
                            @if (form.controls.index.controls.keepVersions.invalid) {
                                <mat-error>A whole number from 1 to {{ idxKeepMax }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Index disk budget (bytes)</mat-label>
                            <input matInput type="number" min="0" [max]="idxDiskMax" formControlName="maxDiskBytes"
                                [readonly]="!canEdit()" placeholder="no limit" />
                            <mat-hint>In force: {{ indexInForce()?.maxDiskBytes ? indexInForce()?.maxDiskBytes + ' bytes' : 'no limit' }}. A build whose estimate is above it is refused; 0 or blank = no limit.</mat-hint>
                            @if (form.controls.index.controls.maxDiskBytes.invalid) {
                                <mat-error>A whole number of bytes from 0 to {{ idxDiskMax }}, or blank for no limit.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Longest age of an index that may serve an uncheckable Dataset (hours)</mat-label>
                            <input matInput type="number" min="1" [max]="idxMaxAgeMax" formControlName="maxAgeHours"
                                [readonly]="!canEdit()" [placeholder]="'default ' + (indexInForce()?.maxAgeHours ?? '')" />
                            <mat-hint>In force: {{ indexInForce()?.maxAgeHours ?? '-' }} hours. A Dataset whose input files cannot be fingerprinted (view-backed) is read from the Dataset, not the index, once the index is older than this.</mat-hint>
                            @if (form.controls.index.controls.maxAgeHours.invalid) {
                                <mat-error>A whole number of hours from 1 to {{ idxMaxAgeMax }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
                    </fieldset>
                    <fieldset formGroupName="graphRun" class="flex flex-col gap-3">
                        <legend class="text-sm font-semibold">Server graph run - default budget</legend>
                        @for (r of runFields; track r.key) {
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>{{ r.label }}</mat-label>
                                <input matInput type="number" min="1" [formControlName]="r.key" [readonly]="!canEdit()"
                                    [placeholder]="'default ' + runInForce(r.key)" />
                                <mat-hint>In force: {{ runInForce(r.key) }}; server ceiling: {{ runCeiling(r.key) }}. A larger value is clamped to the ceiling.</mat-hint>
                                @if (form.controls.graphRun.controls[r.key].invalid) {
                                    <mat-error>A whole number from 1 to {{ runMax }}, or blank for the default.</mat-error>
                                }
                            </mat-form-field>
                        }
                        @for (r of runExtraFields; track r.key) {
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>{{ r.label }}</mat-label>
                                <input matInput type="number" min="1" [formControlName]="r.key" [readonly]="!canEdit()"
                                    [placeholder]="'default ' + extraInForce(r.key)" />
                                <mat-hint>In force: {{ extraInForce(r.key) }}. {{ r.hint }}</mat-hint>
                                @if (form.controls.graphRun.controls[r.key].invalid) {
                                    <mat-error>A whole number from 1 to {{ runMax }}, or blank for the default.</mat-error>
                                }
                            </mat-form-field>
                        }
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
                    <fieldset formGroupName="embed" class="flex flex-col gap-3">
                        <legend class="text-sm font-semibold">Embed links</legend>
                        <p class="text-secondary text-sm">
                            A read-only link to one Investigation for a holder who has no account. The feature is OFF unless the host
                            runs with <code>-Dla.embed.enabled=true</code>; these settings only apply once it is on.
                        </p>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Default link lifetime (hours)</mat-label>
                            <input matInput type="number" min="1" [max]="embedTtlMax" formControlName="defaultTtlHours"
                                [readonly]="!canEdit()" [placeholder]="'default ' + (embedInForce()?.defaultTtlHours ?? '')" />
                            <mat-hint>In force: {{ embedInForce()?.defaultTtlHours ?? '-' }} hours, for a link minted without a lifetime.</mat-hint>
                            @if (form.controls.embed.controls.defaultTtlHours.invalid) {
                                <mat-error>A whole number of hours from 1 to {{ embedTtlMax }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
                        <mat-form-field subscriptSizing="dynamic">
                            <mat-label>Longest link lifetime (hours)</mat-label>
                            <input matInput type="number" min="1" [max]="embedTtlMax" formControlName="maxTtlHours"
                                [readonly]="!canEdit()" [placeholder]="'default ' + (embedInForce()?.maxTtlHours ?? '')" />
                            <mat-hint>In force: {{ embedInForce()?.maxTtlHours ?? '-' }} hours. A longer request is refused, never shortened.</mat-hint>
                            @if (form.controls.embed.controls.maxTtlHours.invalid) {
                                <mat-error>A whole number of hours from 1 to {{ embedTtlMax }}, or blank for the default.</mat-error>
                            }
                        </mat-form-field>
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
                        @if (form.controls.embed.hasError('embedDefaultAboveMax')) {
                            <p class="text-warn text-sm" role="alert">The default lifetime must not exceed the longest lifetime.</p>
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
                        <inspecto-alert id="la-settings-readonly-reason" variant="info" title="Read only">
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
    private runs = inject(GraphRunsService);
    private lens = inject(LensService);
    private toastr = inject(ToastrService);

    readonly max = MAX;
    readonly maskingModes = MASKING_MODES;
    readonly runMax = RUN_MAX;
    readonly idxThreadsMax = IDX_THREADS_MAX;
    readonly idxQueueMax = IDX_QUEUE_MAX;
    readonly idxKeepMax = IDX_KEEP_MAX;
    readonly idxDiskMax = IDX_DISK_MAX;
    readonly idxMaxAgeMax = IDX_MAX_AGE_MAX;
    readonly embedTtlMax = EMBED_TTL_MAX;
    readonly embedRefuseMax = EMBED_REFUSE_MAX;
    readonly embedNameMax = EMBED_NAME_MAX;
    readonly runFields: { key: RunKey; label: string }[] = [
        { key: 'maxNodes', label: 'Graph run: default node budget' },
        { key: 'maxEdges', label: 'Graph run: default edge budget' },
        { key: 'timeoutMs', label: 'Graph run: default time budget (ms)' },
    ];
    /** The other `graphRun` keys the server accepts (1..10 000 000 each); their shipped default is not served, so it is stated here. */
    readonly runExtraFields: { key: 'threads' | 'queue' | 'maxResultItems'; label: string; hint: string }[] = [
        { key: 'threads', label: 'Graph run: worker threads', hint: 'Runs executing at once; blank = the service default.' },
        { key: 'queue', label: 'Graph run: waiting line', hint: 'Runs allowed to wait for a worker; blank = the service default.' },
        { key: 'maxResultItems', label: 'Graph run: items per result list', hint: 'Each list of a run result is cut to this many items (hard ceiling 1 000 000).' },
    ];
    /** The served in-force worker / waiting-line / list-length values (`graphRunInForce`). */
    extraInForce(k: 'threads' | 'queue' | 'maxResultItems'): string | number {
        return this.served()?.graphRunInForce?.[k] ?? '-';
    }
    readonly riskWeightsInForce = computed(() => (this.served()?.propagatedRiskWeightsInForce ?? []).join(', '));
    /** `GET /inv/graph/algorithms`: the budget in force and the server's hard ceilings; null if that route did not answer. */
    private readonly catalogue = signal<{ defaults: Record<RunKey, number>; ceilings: Record<RunKey, number> } | null>(null);
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
    readonly maskingInForce = computed(() => this.served()?.maskingModeInForce ?? 'typed');
    readonly entityTypes = computed(() => this.served()?.entityTypesInForce ?? []);
    readonly indexInForce = computed(() => this.served()?.indexInForce ?? null);
    readonly embedInForce = computed(() => this.served()?.embedInForce ?? null);
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
    runInForce(k: RunKey): string | number {
        return this.catalogue()?.defaults[k] ?? '-';
    }
    runCeiling(k: RunKey): string | number {
        return this.catalogue()?.ceilings[k] ?? '-';
    }
    readonly invBytesInForce = computed(() => this.served()?.maxInvestigationBytesInForce ?? INV_BYTES_DEFAULT);

    readonly form = new FormGroup({
        fourEyesBudgetAbove: new FormControl<number | null>(null, intOrBlank),
        fourEyesFanOutAbove: new FormControl<number | null>(null, intOrBlank),
        mergedDistinctCap: new FormControl<number | null>(null, intOrBlank),
        seedByDistinctCap: new FormControl<number | null>(null, intOrBlank),
        hubThreshold: new FormControl<number | null>(null, intOrBlank),
        maxSetBytes: new FormControl<number | null>(null, setBytesValidators),
        maxInvestigationBytes: new FormControl<number | null>(null, invBytesValidators),
        maskingMode: new FormControl<string | null>(null),
        index: new FormGroup({
            enabled: new FormControl<boolean | null>(null),
            threads: new FormControl<number | null>(null, [Validators.min(1), Validators.max(IDX_THREADS_MAX), Validators.pattern('\\d*')]),
            queue: new FormControl<number | null>(null, [Validators.min(1), Validators.max(IDX_QUEUE_MAX), Validators.pattern('\\d*')]),
            keepVersions: new FormControl<number | null>(null, [Validators.min(1), Validators.max(IDX_KEEP_MAX), Validators.pattern('\\d*')]),
            maxDiskBytes: new FormControl<number | null>(null, [Validators.min(0), Validators.max(IDX_DISK_MAX), Validators.pattern('\\d*')]),
            maxAgeHours: new FormControl<number | null>(null, [Validators.min(1), Validators.max(IDX_MAX_AGE_MAX), Validators.pattern('\\d*')]),
        }),
        embed: new FormGroup(
            {
                defaultTtlHours: new FormControl<number | null>(null, embedTtlValidators),
                maxTtlHours: new FormControl<number | null>(null, embedTtlValidators),
                refuseClassifications: new FormControl<string>('', embedClassesValid),
                frameAncestors: new FormControl<string>('', embedOriginsValid),
            },
            embedDefaultWithinMax,
        ),
        graphRun: new FormGroup({
            maxNodes: new FormControl<number | null>(null, runValidators),
            maxEdges: new FormControl<number | null>(null, runValidators),
            timeoutMs: new FormControl<number | null>(null, runValidators),
            threads: new FormControl<number | null>(null, runValidators),
            queue: new FormControl<number | null>(null, runValidators),
            maxResultItems: new FormControl<number | null>(null, runValidators),
            propagatedRiskWeights: new FormControl<string>('', riskWeightsValid),
        }),
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
        if (s && 'hubThreshold' in s)
            out.push({
                key: 'hubThreshold',
                label: 'Hub threshold (distinct contacts)',
                hint:
                    `In force: ${s.hubThresholdInForce ?? '—'}. An entity an expand finds with more distinct contacts ` +
                    'is flagged high connectivity: shown, but not expanded further unless the analyst overrides it.',
                placeholder: `default ${s.hubThresholdInForce ?? ''}`,
            });
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
        this.loadCatalogue();
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

    private loadCatalogue(): void {
        this.runs.algorithms().subscribe({
            next: (c) => this.catalogue.set({ defaults: c.defaults, ceilings: c.ceilings }),
            error: () => this.catalogue.set(null), // the hints then show "-"; the settings stay editable
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
        const num = (x: number | null): number | null => (x === null || (x as unknown) === '' ? null : Number(x));
        // Everything stated stays as read; the server-computed *InForce keys are not part of the document.
        const body: Record<string, unknown> = {};
        for (const [k, x] of Object.entries(served)) if (!k.endsWith('InForce')) body[k] = x;
        body['fourEyesBudgetAbove'] = num(v.fourEyesBudgetAbove);
        body['fourEyesFanOutAbove'] = num(v.fourEyesFanOutAbove);
        body['mergedDistinctCap'] = num(v.mergedDistinctCap);
        if ('hubThreshold' in served) body['hubThreshold'] = num(v.hubThreshold);
        if ('seedByDistinctCap' in served) body['seedByDistinctCap'] = num(v.seedByDistinctCap);
        else delete body['seedByDistinctCap'];
        if ('maxSetBytes' in served) body['maxSetBytes'] = num(v.maxSetBytes);
        if ('maxInvestigationBytes' in served) body['maxInvestigationBytes'] = num(v.maxInvestigationBytes);
        body['maskingMode'] = v.maskingMode || null;
        // Edited keys replace; a key the form does not show stays as read.
        const idx: Record<string, unknown> = { ...(served.index ?? {}) };
        idx['enabled'] = v.index.enabled;
        idx['threads'] = num(v.index.threads);
        idx['queue'] = num(v.index.queue);
        idx['keepVersions'] = num(v.index.keepVersions);
        idx['maxDiskBytes'] = num(v.index.maxDiskBytes);
        idx['maxAgeHours'] = num(v.index.maxAgeHours);
        if (Object.values(idx).some((x) => x !== null && x !== undefined)) body['index'] = idx;
        else delete body['index'];
        // Blank = inherit; no stated key at all = no block (the PUT replaces, so a cleared block must not be sent back).
        const classes = parseClasses(v.embed.refuseClassifications);
        const frames = parseClasses(v.embed.frameAncestors);
        const embed = {
            frameAncestors: frames.length ? frames : null,
            defaultTtlHours: num(v.embed.defaultTtlHours),
            maxTtlHours: num(v.embed.maxTtlHours),
            refuseClassifications: classes.length ? classes : null,
        };
        if (Object.values(embed).some((x) => x !== null)) body['embed'] = embed;
        else delete body['embed'];
        const run: Record<string, unknown> = { ...(served.graphRun ?? {}) };
        for (const r of this.runFields) run[r.key] = num(v.graphRun[r.key]);
        for (const r of this.runExtraFields) run[r.key] = num(v.graphRun[r.key]);
        run['propagatedRiskWeights'] = parseWeights(v.graphRun.propagatedRiskWeights);
        if (Object.values(run).some((x) => x !== null && x !== undefined)) body['graphRun'] = run;
        else delete body['graphRun'];
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
                this.loadCatalogue(); // the budget in force follows the saved defaults
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
            hubThreshold: s.hubThreshold ?? null,
            maxSetBytes: s.maxSetBytes ?? null,
            maxInvestigationBytes: s.maxInvestigationBytes ?? null,
            maskingMode: s.maskingMode ?? null,
            index: {
                enabled: s.index?.enabled ?? null,
                threads: s.index?.threads ?? null,
                queue: s.index?.queue ?? null,
                keepVersions: s.index?.keepVersions ?? null,
                maxDiskBytes: s.index?.maxDiskBytes ?? null,
                maxAgeHours: s.index?.maxAgeHours ?? null,
            },
            embed: {
                defaultTtlHours: s.embed?.defaultTtlHours ?? null,
                maxTtlHours: s.embed?.maxTtlHours ?? null,
                refuseClassifications: (s.embed?.refuseClassifications ?? []).join(', '),
                frameAncestors: (s.embed?.frameAncestors ?? []).join(', '),
            },
            graphRun: {
                maxNodes: s.graphRun?.maxNodes ?? null,
                maxEdges: s.graphRun?.maxEdges ?? null,
                timeoutMs: s.graphRun?.timeoutMs ?? null,
                threads: s.graphRun?.threads ?? null,
                queue: s.graphRun?.queue ?? null,
                maxResultItems: s.graphRun?.maxResultItems ?? null,
                propagatedRiskWeights: (s.graphRun?.propagatedRiskWeights ?? []).join(', '),
            },
            drafts: {
                maxOpen: s.drafts?.maxOpen ?? null,
                hibernateAfterMinutes: s.drafts?.hibernateAfterMinutes ?? null,
                expireAfterDays: s.drafts?.expireAfterDays ?? null,
            },
        });
        // A viewer without the authoring capability gets EVERY control disabled (values stay readable and copyable).
        if (this.canEdit()) this.form.enable({ emitEvent: false });
        else this.form.disable({ emitEvent: false });
        this.form.controls.drafts.setValidators(expiryAfterHibernation(s.draftsInForce));
        this.form.controls.drafts.updateValueAndValidity();
    }
}
