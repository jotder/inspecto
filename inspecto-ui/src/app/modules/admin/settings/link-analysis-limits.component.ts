import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { Observable } from 'rxjs';

import { apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { formatNumber } from 'app/inspecto/viz/number-format';
import { LaLimitItem, LaLimitsResponse } from '@inspecto/link-analysis/link-analysis/la-limits';
import { LaLimitsService } from '@inspecto/link-analysis/link-analysis/la-limits.service';

type Layer = 'space' | 'install';
type LimitsForm = FormGroup<Record<string, FormControl<number | null>>>;

/** Words for the catalogue's unit codes (plan §6.1). */
const UNITS: Record<string, string> = {
    count: '',
    nodes: 'nodes',
    edges: 'edges',
    rows: 'rows',
    bytes: 'bytes',
    ms: 'ms',
    s: 'seconds',
    min: 'minutes',
    h: 'hours',
    days: 'days',
    chars: 'characters',
    hops: 'hops',
    per_s: 'per second',
    pct_ram: '% of RAM',
    mb: 'MB',
};

/** Every value is a whole number except a `per_s` rate, which is a decimal (the wire contract). */
const numberPattern = (i: LaLimitItem): RegExp => (i.unit === 'per_s' ? /^\d+(\.\d+)?$/ : /^\d+$/);

/** Words written in capitals wherever they appear in a key (a generic rule, not a per-item label). */
const ACRONYMS: Record<string, string> = { hits: 'HITS', lod: 'LOD', id: 'ID', ids: 'IDs', url: 'URL', ttl: 'TTL', mb: 'MB', ram: 'RAM' };
/** A trailing word that qualifies the name rather than being part of it: `expand_budget_default` → "Expand budget" + "default". */
const QUALIFIERS = new Set(['default', 'max', 'min']);

/**
 * A key's last segment in readable words: snake_case and camelCase split, sentence case, acronyms kept, and a trailing
 * default / max / min split off as a qualifier. `browser_algorithms.betweennessCentrality` → "Betweenness centrality";
 * `investigation.expand_budget_default` → "Expand budget" + "default"; `display.lod_label_cap` → "LOD label cap".
 */
export function limitWords(key: string): { name: string; qualifier: string | null } {
    const parts = key
        .slice(key.lastIndexOf('.') + 1)
        .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
        .split('_')
        .filter((w) => w.length > 0)
        .map((w) => w.toLowerCase());
    const qualifier = parts.length > 1 && QUALIFIERS.has(parts[parts.length - 1]) ? parts.pop()! : null;
    const text = parts.map((w) => ACRONYMS[w] ?? w).join(' ');
    return { name: text.charAt(0).toUpperCase() + text.slice(1), qualifier };
}

function words(key: string): string {
    return limitWords(key).name;
}

function groupsOf(items: LaLimitItem[]): { group: string; items: LaLimitItem[] }[] {
    const out: { group: string; items: LaLimitItem[] }[] = [];
    for (const i of items) {
        const g = out.find((x) => x.group === i.group);
        if (g) g.items.push(i);
        else out.push({ group: i.group, items: [i] });
    }
    return out;
}

/** A FormGroup key may not contain `.`, which every grouped wire key does. */
const name = (key: string): string => key.replaceAll('.', '__');

const blank = (v: unknown): boolean => v === null || v === undefined || String(v).trim() === '';

/**
 * Settings ▸ Link Analysis ▸ the three limits sections (LA-CONFIG-LIMITS-1, plan §6.3, CL-17), rendered FROM
 * `GET /settings/link-analysis/limits` — one control per served item, grouped by its `group`, never a hand-written
 * field per limit:
 *
 * - **Analysis** — the Space's items; a control is editable when the server says THIS caller may (`editable`).
 * - **Host resources** — the install-wide items; read-only unless the caller is a system admin.
 * - **My browser** — the `browser:true` items: a personal value for this browser, at most the Space value.
 *
 * The client-side range only stops a request the server is known to refuse; the server stays the judge and its 422
 * is shown verbatim. A save sends only the changed keys (`null` = inherit) and the answer replaces the view.
 */
@Component({
    selector: 'inspecto-link-analysis-limits',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        NgTemplateOutlet,
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
    ],
    template: `
        <ng-template #field let-i let-c="control" let-max="max" let-placeholder="placeholder">
            <mat-form-field subscriptSizing="dynamic" class="w-full">
                <mat-label>{{ label(i) }}</mat-label>
                <input
                    matInput
                    type="number"
                    [formControl]="c"
                    [min]="i.min"
                    [max]="max"
                    [placeholder]="placeholder"
                    [attr.data-key]="i.key"
                />
                <mat-hint>{{ i.description }} In force: {{ inForceText(i) }}.</mat-hint>
                @if (c.invalid) {
                    <mat-error
                        >{{ i.unit === 'per_s' ? 'A number' : 'A whole number' }} from {{ num(i.min) }} to
                        {{ num(max) }}, or blank.</mat-error
                    >
                }
            </mat-form-field>
        </ng-template>

        <div class="flex max-w-160 flex-col gap-6">
            @if (limits.loading() && !limits.items().length) {
                <p class="text-secondary text-sm">Reading the Link Analysis limits…</p>
            } @else if (limits.loadError()) {
                <inspecto-alert variant="warning" title="Limits unavailable">{{ limits.loadError() }}</inspecto-alert>
            } @else {
                <section class="flex flex-col gap-3" aria-labelledby="la-limits-analysis">
                    <h2 id="la-limits-analysis" class="text-lg font-semibold">Analysis</h2>
                    <p class="text-secondary text-sm">
                        Limits for this Space, enforced by the server. Blank = the shipped default.
                    </p>
                    @if (spaceItems().length) {
                        <form
                            class="flex flex-col gap-3"
                            [formGroup]="spaceForm()"
                            (ngSubmit)="save('space')"
                            data-section="space"
                        >
                            @for (g of spaceGroups(); track g.group) {
                                <fieldset class="flex flex-col gap-3">
                                    <legend class="text-sm font-semibold">{{ groupLabel(g.group) }}</legend>
                                    @for (i of g.items; track i.key) {
                                        <ng-container
                                            [ngTemplateOutlet]="field"
                                            [ngTemplateOutletContext]="{
                                                $implicit: i,
                                                control: ctl(spaceForm(), i.key),
                                                max: i.max,
                                                placeholder: 'default ' + defaultText(i),
                                            }"
                                        />
                                    }
                                </fieldset>
                            }
                            @if (errors()['space']; as e) {
                                <inspecto-alert variant="error" title="Not saved">{{ e }}</inspecto-alert>
                            }
                            @if (anyEditable(spaceItems())) {
                                <div>
                                    <button mat-flat-button color="primary" type="submit" [disabled]="saving() === 'space'">
                                        Save Analysis limits
                                    </button>
                                </div>
                            } @else {
                                <inspecto-alert variant="info" title="Read only">
                                    Changing these limits needs the Workbench authoring capability.
                                </inspecto-alert>
                            }
                        </form>
                    } @else {
                        <p class="text-secondary text-sm">None reported.</p>
                    }
                </section>

                <section class="flex flex-col gap-3" aria-labelledby="la-limits-host">
                    <h2 id="la-limits-host" class="text-lg font-semibold">Host resources</h2>
                    <p class="text-secondary text-sm">
                        Install-wide limits shared by every Space on this server.
                        @if (limits.podScoped()) {
                            In a cluster they apply to this server (pod) only.
                        }
                    </p>
                    @if (installItems().length) {
                        <form
                            class="flex flex-col gap-3"
                            [formGroup]="installForm()"
                            (ngSubmit)="save('install')"
                            data-section="install"
                        >
                            @for (g of installGroups(); track g.group) {
                                <fieldset class="flex flex-col gap-3">
                                    <legend class="text-sm font-semibold">{{ groupLabel(g.group) }}</legend>
                                    @for (i of g.items; track i.key) {
                                        <ng-container
                                            [ngTemplateOutlet]="field"
                                            [ngTemplateOutletContext]="{
                                                $implicit: i,
                                                control: ctl(installForm(), i.key),
                                                max: i.max,
                                                placeholder: 'default ' + defaultText(i),
                                            }"
                                        />
                                    }
                                </fieldset>
                            }
                            @if (errors()['install']; as e) {
                                <inspecto-alert variant="error" title="Not saved">{{ e }}</inspecto-alert>
                            }
                            @if (anyEditable(installItems())) {
                                <div>
                                    <button mat-flat-button color="primary" type="submit" [disabled]="saving() === 'install'">
                                        Save host resources
                                    </button>
                                </div>
                            } @else {
                                <inspecto-alert variant="info" title="Read only">
                                    Changing host resources needs the system administrator capability.
                                </inspecto-alert>
                            }
                        </form>
                    } @else {
                        <p class="text-secondary text-sm">None reported.</p>
                    }
                </section>

                <section class="flex flex-col gap-3" aria-labelledby="la-limits-browser">
                    <h2 id="la-limits-browser" class="text-lg font-semibold">My browser</h2>
                    <p class="text-secondary text-sm">
                        Your own values for this browser only, at most the Space value. Blank = the Space value.
                    </p>
                    @if (browserItems().length) {
                        <form
                            class="flex flex-col gap-3"
                            [formGroup]="browserForm()"
                            (ngSubmit)="keepPersonal()"
                            data-section="browser"
                        >
                            @for (g of browserGroups(); track g.group) {
                                <fieldset class="flex flex-col gap-3">
                                    <legend class="text-sm font-semibold">{{ groupLabel(g.group) }}</legend>
                                    @for (i of g.items; track i.key) {
                                        <div class="flex flex-col gap-1">
                                            <ng-container
                                                [ngTemplateOutlet]="field"
                                                [ngTemplateOutletContext]="{
                                                    $implicit: i,
                                                    control: ctl(browserForm(), i.key),
                                                    max: spaceValue(i),
                                                    placeholder: 'Space value ' + num(spaceValue(i)),
                                                }"
                                            />
                                            @if (limits.personal(i.key) !== null) {
                                                <div>
                                                    <button
                                                        mat-button
                                                        type="button"
                                                        [attr.aria-label]="'Reset ' + label(i) + ' to the Space value'"
                                                        (click)="limits.resetPersonal(i.key)"
                                                    >
                                                        Reset to Space value
                                                    </button>
                                                </div>
                                            }
                                        </div>
                                    }
                                </fieldset>
                            }
                            <div>
                                <button mat-stroked-button type="submit">Keep in this browser</button>
                            </div>
                        </form>
                    } @else {
                        <p class="text-secondary text-sm">None reported.</p>
                    }
                </section>
            }
        </div>
    `,
})
export class LinkAnalysisLimitsComponent {
    readonly limits = inject(LaLimitsService);
    private readonly toastr = inject(ToastrService);

    readonly saving = signal<Layer | null>(null);
    readonly errors = signal<Partial<Record<Layer, string>>>({});

    readonly spaceItems = computed(() => this.limits.items().filter((i) => i.layer === 'space'));
    readonly installItems = computed(() => this.limits.items().filter((i) => i.layer === 'install'));
    readonly browserItems = computed(() =>
        this.limits.items().filter((i) => i.browser && i.layer === 'space' && i.inForce !== null),
    );
    readonly spaceGroups = computed(() => groupsOf(this.spaceItems()));
    readonly installGroups = computed(() => groupsOf(this.installItems()));
    readonly browserGroups = computed(() => groupsOf(this.browserItems()));

    /** Rebuilt from each answer, so a save shows what the server stored. */
    readonly spaceForm = computed(() => this.layerForm(this.spaceItems()));
    readonly installForm = computed(() => this.layerForm(this.installItems()));
    readonly browserForm = computed<LimitsForm>(() => {
        const controls: Record<string, FormControl<number | null>> = {};
        for (const i of this.browserItems())
            controls[name(i.key)] = new FormControl<number | null>(this.limits.personal(i.key), [
                Validators.pattern(numberPattern(i)),
                Validators.min(i.min),
                Validators.max(this.spaceValue(i)),
            ]);
        return new FormGroup(controls);
    });

    /** "Expand budget (default, rows)", "Betweenness centrality (nodes)", "Hub threshold": derived from the key, never hand-written. */
    label(i: LaLimitItem): string {
        const { name, qualifier } = limitWords(i.key);
        const note = [qualifier, UNITS[i.unit] ?? i.unit].filter((x) => !!x).join(', ');
        return note ? `${name} (${note})` : name;
    }

    groupLabel(group: string): string {
        return words(group);
    }

    num(n: number): string {
        return formatNumber(n);
    }

    defaultText(i: LaLimitItem): string {
        return i.default === null ? 'none' : this.num(i.default);
    }

    inForceText(i: LaLimitItem): string {
        if (i.inForce === null) return 'none';
        const unit = UNITS[i.unit] ?? i.unit;
        return unit ? `${this.num(i.inForce)} ${unit}` : this.num(i.inForce);
    }

    /** The Space value a personal value is bounded by. */
    spaceValue(i: LaLimitItem): number {
        return i.inForce ?? i.max;
    }

    /** The control of one item (a FormGroup key may not contain a dot, so the wire key is mapped). */
    ctl(form: LimitsForm, key: string): FormControl<number | null> {
        return form.controls[name(key)];
    }

    anyEditable(items: LaLimitItem[]): boolean {
        return items.some((i) => i.editable);
    }

    save(layer: Layer): void {
        const form = layer === 'space' ? this.spaceForm() : this.installForm();
        const items = layer === 'space' ? this.spaceItems() : this.installItems();
        if (this.saving()) return;
        if (form.invalid) {
            form.markAllAsTouched();
            return;
        }
        const body: Record<string, number | null> = {};
        for (const i of items) {
            const c = this.ctl(form, i.key);
            if (i.editable && c.dirty) body[i.key] = blank(c.value) ? null : Number(c.value);
        }
        if (!Object.keys(body).length) return;
        this.saving.set(layer);
        this.errors.update((e) => ({ ...e, [layer]: undefined }));
        const call: Observable<LaLimitsResponse> =
            layer === 'space' ? this.limits.saveSpace(body) : this.limits.saveInstall(body);
        call.subscribe({
            next: () => {
                this.saving.set(null);
                this.toastr.success(layer === 'space' ? 'Analysis limits saved.' : 'Host resources saved.');
            },
            error: (err) => {
                this.saving.set(null);
                const message = apiErrorMessage(err, 'Saving the limits failed.');
                if (err?.status === 422 || err?.status === 503) this.errors.update((e) => ({ ...e, [layer]: message }));
                else if (err?.status === 403)
                    this.errors.update((e) => ({
                        ...e,
                        [layer]: 'You are not allowed to change these limits. Server: ' + message,
                    }));
                else this.toastr.error(message);
            },
        });
    }

    keepPersonal(): void {
        const form = this.browserForm();
        if (form.invalid) {
            form.markAllAsTouched();
            return;
        }
        for (const i of this.browserItems()) {
            const c = this.ctl(form, i.key);
            if (c.dirty) this.limits.setPersonal(i.key, blank(c.value) ? null : Number(c.value));
        }
    }

    private layerForm(items: LaLimitItem[]): LimitsForm {
        const controls: Record<string, FormControl<number | null>> = {};
        for (const i of items)
            controls[name(i.key)] = new FormControl<number | null>({ value: i.value, disabled: !i.editable }, [
                Validators.pattern(numberPattern(i)),
                Validators.min(i.min),
                Validators.max(i.max),
            ]);
        return new FormGroup(controls);
    }
}
