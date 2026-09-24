import { ChangeDetectionStrategy, Component, computed, inject, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import {
    AlertComparator,
    AlertSeverity,
    InstantiateTemplateResult,
    InvService,
    InvestigationAlertRuleResult,
    InvestigationLogEntry,
    InvestigationMeasure,
    InvestigationTemplate,
    apiErrorMessage,
} from 'app/inspecto/api';
import { AttributeSpec } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { datasetOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { AttributeOptionLoader, InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { DatasetsService } from '../datasets/datasets.service';
import { investigationErrorMessage } from './investigation-state';
import { SAFE_ID_PATTERN, templatePreview } from './investigation-template';

const SAFE_ID_HINT = "Letters, digits, '.', '_' or '-', starting with a letter or digit (at most 128).";

/** A 503 is an expected deployment state (module / write root / alert engine absent) — explained, not an error. */
export function isUnavailable(err: unknown): boolean {
    return err instanceof HttpErrorResponse ? err.status === 503 : (err as { status?: number })?.status === 503;
}

// ── Save as template ─────────────────────────────────────────────────────────────────────────────────

export interface SaveTemplateData {
    investigationId: string;
    entries: InvestigationLogEntry[];
    /** The log read was capped — the preview then covers only the loaded steps. */
    truncated: boolean;
}

/**
 * LA-23 **Save as template** — shows what the D-E8 extraction will drop and generalise BEFORE the write-once save
 * (the route has no dry run), then the server's own answer, which is authoritative.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
    ],
    template: `
        <h2 mat-dialog-title>Save as Investigation Template</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            @if (saved(); as t) {
                <inspecto-alert variant="success" title="Template saved">
                    <code>{{ t.id }}</code> — {{ t.parameters.length }} parameter(s), {{ t.ops.length }} step(s).
                    Instantiate it from this panel with that id.
                </inspecto-alert>
                <ul class="m-0 pl-4 text-xs" aria-label="What the server dropped and generalised">
                    @for (d of t.dropped; track d.step) {
                        <li>Step {{ d.step }} ({{ d.op }}, {{ d.count }} entities) — dropped.</li>
                    }
                    @for (g of t.generalised; track g.step) {
                        <li>
                            Step {{ g.step }} expand of {{ g.namedFrontier }} named entities — generalised to the whole
                            Working Set{{ g.exact ? ' (exact: that WAS the whole Working Set)' : ' (not exact)' }}.
                        </li>
                    }
                    @if (!t.dropped.length && !t.generalised.length) {
                        <li>Nothing was dropped or generalised.</li>
                    }
                </ul>
            } @else {
                <p class="m-0 text-xs">
                    A template keeps the <strong>method</strong>, not the case. Seeds become parameters (their ids are
                    not stored); exclusions, hides and keeps are judgements about this graph and stay with the
                    Investigation. Templates are write-once — a changed method is a new id.
                </p>
                <section aria-label="What will change">
                    <ul class="m-0 pl-4 text-xs">
                        @for (p of preview.parameters; track p.step) {
                            <li>
                                Step {{ p.step }} {{ p.kind }} → parameter <code>{{ p.name }}</code>
                            </li>
                        }
                        @for (d of preview.dropped; track d.step) {
                            <li>Step {{ d.step }} {{ d.op }} of {{ d.count }} entities → dropped</li>
                        }
                        @for (g of preview.generalised; track g.step) {
                            <li>
                                Step {{ g.step }} expand of {{ g.namedFrontier }} named entities → expands the whole
                                Working Set
                            </li>
                        }
                    </ul>
                </section>
                @if (data.truncated) {
                    <inspecto-alert variant="warning" title="Partial preview">
                        Only the loaded steps are previewed; the server extracts from the whole log.
                    </inspecto-alert>
                }
                @if (!hasSeed) {
                    <inspecto-alert variant="warning" title="Nothing to template">
                        This Investigation has no effective seed step, so there is nothing to parameterise — the server
                        will refuse it.
                    </inspecto-alert>
                }
                <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="save()">
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Title (optional)</mat-label>
                        <input matInput formControlName="title" />
                        @if (form.controls.title.hasError('maxlength')) {
                            <mat-error>At most 200 characters.</mat-error>
                        }
                    </mat-form-field>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Template id (optional — generated when blank)</mat-label>
                        <input matInput formControlName="id" />
                        @if (form.controls.id.hasError('pattern')) {
                            <mat-error>{{ idHint }}</mat-error>
                        }
                    </mat-form-field>
                </form>
            }
            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Template not saved">
                    {{ error() }}
                </inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (saved()) {
                <button mat-flat-button color="primary" (click)="ref.close(saved())">Done</button>
            } @else {
                <button mat-button (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" [disabled]="busy()" (click)="save()">Save template</button>
            }
        </mat-dialog-actions>
    `,
})
export class SaveTemplateDialog {
    readonly data = inject<SaveTemplateData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<SaveTemplateDialog, InvestigationTemplate | undefined>);
    private inv = inject(InvService);
    private confirm = inject(InspectoConfirmService);

    readonly preview = templatePreview(this.data.entries);
    readonly hasSeed = this.preview.parameters.some((p) => p.kind === 'seed');
    readonly idHint = SAFE_ID_HINT;
    readonly form = new FormGroup({
        title: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
        id: new FormControl('', { nonNullable: true, validators: [Validators.pattern(SAFE_ID_PATTERN)] }),
    });
    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly saved = signal<InvestigationTemplate | null>(null);
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty && !this.saved(), this.confirm);

    async save(): Promise<void> {
        if (this.busy() || this.saved()) return;
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const { title, id } = this.form.getRawValue();
        this.busy.set(true);
        this.error.set('');
        try {
            this.saved.set(
                await firstValueFrom(
                    this.inv.saveInvestigationTemplate(this.data.investigationId, {
                        ...(title.trim() ? { title: title.trim() } : {}),
                        ...(id.trim() ? { id: id.trim() } : {}),
                    }),
                ),
            );
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(investigationErrorMessage(err, 'Could not save the template.'));
        } finally {
            this.busy.set(false);
        }
    }
}

// ── Instantiate a template ───────────────────────────────────────────────────────────────────────────

/** Columns of the Dataset a sibling field names — declared, else probed (the query panel's own rule). */
function datasetColumnLoader(sourceKey: string): AttributeOptionLoader {
    const datasets = inject(DatasetsService);
    const rows = inject(DatasetRowsService);
    return async (v) => {
        const id = String(v[sourceKey] ?? '').trim();
        if (!id) return [];
        const ds = await firstValueFrom(datasets.get(id));
        return (await rows.columns(ds)).map((c) => ({ value: c.name, label: c.name }));
    };
}

/** The seed parameters — the ones instantiation REQUIRES a non-empty id list for. */
function seedParameters(t: InvestigationTemplate) {
    return t.parameters.filter((p) => p.kind === 'seed');
}

/** The instantiate form for one template: a seed list per parameter, then the Dataset + column roles. */
export function instantiateSpecs(t: InvestigationTemplate): AttributeSpec[] {
    const specs: AttributeSpec[] = [
        { key: 'title', label: 'Title', type: 'string', tier: 'required', required: false },
        {
            key: 'purpose',
            label: 'Purpose / legal basis',
            type: 'string',
            tier: 'required',
            required: true,
            help: 'Recorded with the new Investigation and shown in its Dossier (D-U5).',
        },
        ...seedParameters(t).map<AttributeSpec>((p) => ({
            key: `param:${p.name}`,
            label: `${p.name}${p.entityType ? ' (' + p.entityType + ')' : ''} — seed ids`,
            type: 'list',
            tier: 'required',
            help: `Replaces the seed at template step ${p.step}. Ids are raw Dataset values.`,
        })),
        {
            key: 'dataset',
            label: 'Dataset',
            type: 'autocomplete',
            tier: 'required',
            default: t.roles.dataset,
            group: 'Bind to a Dataset',
        },
        {
            key: 'sourceCol',
            label: 'Source column',
            type: 'autocomplete',
            tier: 'required',
            default: t.roles.sourceCol,
            group: 'Bind to a Dataset',
        },
        {
            key: 'targetCol',
            label: 'Target column',
            type: 'autocomplete',
            tier: 'required',
            default: t.roles.targetCol,
            group: 'Bind to a Dataset',
        },
    ];
    if (t.roles.linkKindCol)
        specs.push({
            key: 'linkKindCol',
            label: 'Link kind column',
            type: 'autocomplete',
            tier: 'required',
            default: t.roles.linkKindCol,
            group: 'Bind to a Dataset',
            help: 'The template binds a link-kind column, so the new Investigation must bind one too.',
        });
    return specs;
}

/**
 * LA-23 **Instantiate** — there is no template list route, so the analyst names the template id; the dialog reads
 * it, asks one seed list per parameter plus the Dataset and column roles (defaulting to the template's), and
 * creates a NEW Investigation whose every expand reads the Dataset now.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        InspectoSchemaFormComponent,
    ],
    template: `
        <div class="flex items-center" mat-dialog-title inspectoDialogResize #chrome="inspectoDialogResize">
            <h2 class="m-0 flex-auto text-lg">Instantiate an Investigation Template</h2>
            <button
                mat-icon-button
                (click)="chrome.toggleMaximize()"
                [attr.aria-label]="chrome.maximized() ? 'Restore dialog size' : 'Maximize dialog'"
            >
                <mat-icon
                    [svgIcon]="
                        chrome.maximized()
                            ? 'heroicons_outline:arrows-pointing-in'
                            : 'heroicons_outline:arrows-pointing-out'
                    "
                ></mat-icon>
            </button>
        </div>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <form class="flex items-start gap-2" (ngSubmit)="loadTemplate()">
                <mat-form-field class="flex-auto" subscriptSizing="dynamic">
                    <mat-label>Template id</mat-label>
                    <input matInput [formControl]="templateId" />
                    @if (templateId.hasError('required')) {
                        <mat-error>Name the template to instantiate.</mat-error>
                    } @else if (templateId.hasError('pattern')) {
                        <mat-error>{{ idHint }}</mat-error>
                    }
                </mat-form-field>
                <button mat-stroked-button type="submit" class="mt-2" [disabled]="busy()">Load</button>
            </form>
            @if (template(); as t) {
                <p class="text-secondary m-0 text-xs">
                    <strong>{{ t.title || t.id }}</strong> — {{ t.ops.length }} step(s) from Investigation
                    {{ t.derivedFrom.investigation }}, originally over {{ t.roles.dataset }}.
                </p>
                <inspecto-schema-form
                    [specs]="specs()"
                    [optionLoaders]="loaders"
                    (submitted)="instantiate()"
                ></inspecto-schema-form>
            }
            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Instantiate">
                    {{ error() }}
                </inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="busy() || !template()" (click)="instantiate()">
                Create Investigation
            </button>
        </mat-dialog-actions>
    `,
})
export class InstantiateTemplateDialog {
    readonly ref = inject(MatDialogRef<InstantiateTemplateDialog, InstantiateTemplateResult | undefined>);
    private inv = inject(InvService);
    private confirm = inject(InspectoConfirmService);
    private readonly schemaForm = viewChild(InspectoSchemaFormComponent);

    readonly idHint = SAFE_ID_HINT;
    readonly templateId = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(SAFE_ID_PATTERN)],
    });
    readonly template = signal<InvestigationTemplate | null>(null);
    readonly specs = computed(() => {
        const t = this.template();
        return t ? instantiateSpecs(t) : [];
    });
    readonly loaders: Record<string, AttributeOptionLoader> = {
        dataset: datasetOptionLoader(),
        sourceCol: datasetColumnLoader('dataset'),
        targetCol: datasetColumnLoader('dataset'),
        linkKindCol: datasetColumnLoader('dataset'),
    };
    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => this.templateId.dirty || (this.schemaForm()?.isDirty() ?? false),
        this.confirm,
    );

    async loadTemplate(): Promise<void> {
        if (this.templateId.invalid) {
            this.templateId.markAsTouched();
            return;
        }
        await this.run(
            'Could not read the template.',
            async () => {
                this.template.set(await firstValueFrom(this.inv.investigationTemplate(this.templateId.value.trim())));
            },
            'This template is not available — it does not exist or it is not yours (the server answers the same for both).',
        );
    }

    async instantiate(): Promise<void> {
        const t = this.template();
        const form = this.schemaForm();
        if (!t || !form || this.busy() || !form.validate()) return;
        const v = form.value();
        const str = (k: string) =>
            typeof v[k] === 'string' && (v[k] as string).trim() ? (v[k] as string).trim() : undefined;
        const params: Record<string, string[]> = {};
        // Window parameters are optional — omitted, the server re-reads the template's authored window.
        for (const p of seedParameters(t))
            params[p.name] = ((v[`param:${p.name}`] as string[] | null) ?? []).map((s) => s.trim());
        await this.run('Could not create the Investigation.', async () => {
            const res = await firstValueFrom(
                this.inv.instantiateTemplate(t.id, {
                    params,
                    title: str('title'),
                    purpose: str('purpose') ?? '',
                    dataset: str('dataset'),
                    sourceCol: str('sourceCol'),
                    targetCol: str('targetCol'),
                    linkKindCol: str('linkKindCol'),
                }),
            );
            this.ref.close(res);
        });
    }

    private async run(fallback: string, body: () => Promise<void>, notFound?: string): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        try {
            await body();
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            const status = (err as { status?: number })?.status;
            this.error.set(
                notFound && status === 404
                    ? `${notFound} Server: ${apiErrorMessage(err, fallback)}`
                    : investigationErrorMessage(err, fallback),
            );
        } finally {
            this.busy.set(false);
        }
    }
}

// ── Watch this Measure ───────────────────────────────────────────────────────────────────────────────

export interface WatchMeasureData {
    investigationId: string;
    measure: InvestigationMeasure;
}

export const COMPARATOR_OPTIONS: PickerOption[] = [
    { value: 'gt', label: 'greater than' },
    { value: 'gte', label: 'at least' },
    { value: 'lt', label: 'less than' },
    { value: 'lte', label: 'at most' },
];

export const SEVERITY_OPTIONS: PickerOption[] = [
    { value: 'INFO', label: 'Info' },
    { value: 'WARNING', label: 'Warning' },
    { value: 'CRITICAL', label: 'Critical', hint: 'Also opens an Incident when it fires.' },
];

/** The message for a failed binding: the capability here is Alert-Rule authoring, not Incident management. */
export function alertRuleErrorMessage(err: unknown): string {
    const status = (err as { status?: number })?.status;
    const server = apiErrorMessage(err, 'Could not create the Alert Rule.');
    if (status === 403)
        return 'You are not allowed to author Alert Rules (it needs that capability). Server: ' + server;
    if (status === 503)
        return (
            'Alert Rules cannot be armed here — the alert engine (or a write root) is not running in this deployment. Server: ' +
            server
        );
    if (status === 409) return 'An Alert Rule with this name already exists — pick another name. Server: ' + server;
    return investigationErrorMessage(err, 'Could not create the Alert Rule.');
}

/**
 * LA-23 **Watch this Measure** — bind an Alert Rule to one of the Investigation's Measures. The answer carries the
 * value now, whether the rule would fire now, and the backend's disclosure of what a fired Alert shows to whom.
 */
@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
    ],
    template: `
        <h2 mat-dialog-title>Watch “{{ data.measure.name }}”</h2>
        <mat-dialog-content class="flex flex-col gap-3 text-sm">
            <p class="m-0 text-xs">
                <code>{{ data.measure.measure }}</code> over {{ data.measure.relation }} — now
                <strong class="tabular-nums">{{ data.measure.value ?? '—' }}</strong
                >.
            </p>
            @if (result(); as r) {
                <inspecto-alert [variant]="r.wouldFire ? 'warning' : 'success'" title="Alert Rule armed">
                    Current value {{ r.current ?? '—' }} —
                    {{ r.wouldFire ? 'it would fire now.' : 'it would not fire now.' }}
                </inspecto-alert>
                <inspecto-alert variant="info" title="What a fired Alert discloses">{{ r.disclosure }}</inspecto-alert>
            } @else {
                <form class="flex flex-col gap-2" [formGroup]="form" (ngSubmit)="bind()">
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Alert Rule name</mat-label>
                        <input matInput formControlName="name" />
                        @if (form.controls.name.hasError('required')) {
                            <mat-error>A name is required.</mat-error>
                        } @else if (form.controls.name.hasError('pattern')) {
                            <mat-error>{{ idHint }}</mat-error>
                        }
                    </mat-form-field>
                    <inspecto-option-picker
                        label="Fire when the value is"
                        formControlName="comparator"
                        [options]="comparators"
                    ></inspecto-option-picker>
                    <mat-form-field subscriptSizing="dynamic">
                        <mat-label>Threshold</mat-label>
                        <input matInput type="number" formControlName="threshold" />
                        @if (form.controls.threshold.hasError('required')) {
                            <mat-error>A threshold is required.</mat-error>
                        }
                    </mat-form-field>
                    <inspecto-option-picker
                        label="Severity"
                        formControlName="severity"
                        [options]="severities"
                    ></inspecto-option-picker>
                </form>
            }
            @if (error()) {
                <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Alert Rule not created">
                    {{ error() }}
                </inspecto-alert>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (result()) {
                <button mat-flat-button color="primary" (click)="ref.close(result())">Done</button>
            } @else {
                <button mat-button (click)="requestClose()">Cancel</button>
                <button mat-flat-button color="primary" [disabled]="busy()" (click)="bind()">Create Alert Rule</button>
            }
        </mat-dialog-actions>
    `,
})
export class WatchMeasureDialog {
    readonly data = inject<WatchMeasureData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<WatchMeasureDialog, InvestigationAlertRuleResult | undefined>);
    private inv = inject(InvService);
    private confirm = inject(InspectoConfirmService);

    readonly idHint = SAFE_ID_HINT;
    readonly comparators = COMPARATOR_OPTIONS;
    readonly severities = SEVERITY_OPTIONS;
    readonly form = new FormGroup({
        name: new FormControl(
            `${this.data.investigationId}-${this.data.measure.name}`.replace(/[^A-Za-z0-9._-]/g, '-'),
            {
                nonNullable: true,
                validators: [Validators.required, Validators.pattern(SAFE_ID_PATTERN)],
            },
        ),
        comparator: new FormControl<AlertComparator>('gt', { nonNullable: true }),
        threshold: new FormControl<number | null>(null, { validators: [Validators.required] }),
        severity: new FormControl<AlertSeverity>('WARNING', { nonNullable: true }),
    });
    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly result = signal<InvestigationAlertRuleResult | null>(null);
    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty && !this.result(), this.confirm);

    async bind(): Promise<void> {
        if (this.busy() || this.result()) return;
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const f = this.form.getRawValue();
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        try {
            this.result.set(
                await firstValueFrom(
                    this.inv.bindInvestigationAlertRule(this.data.investigationId, {
                        name: f.name.trim(),
                        relation: this.data.measure.relation,
                        measure: this.data.measure.measure,
                        comparator: f.comparator,
                        threshold: Number(f.threshold),
                        severity: f.severity,
                    }),
                ),
            );
        } catch (err) {
            this.unavailable.set(isUnavailable(err));
            this.error.set(alertRuleErrorMessage(err));
        } finally {
            this.busy.set(false);
        }
    }
}
