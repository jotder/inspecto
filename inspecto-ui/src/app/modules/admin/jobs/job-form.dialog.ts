import { AfterViewInit, ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormArray, FormBuilder, FormGroup, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, JobDetail, JobExpressionDecl, JobsService, JobType, JobTypeDescriptor, JobUpsert } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { AttributeOptionLoader, InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { datasetOptionLoader, pipelineOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { AttributeSpec } from 'app/inspecto/component-model';
import { JOB_ATTRIBUTES } from './job-attributes';
import { paramDeclsToSpecs, paramTokens, paramValueToApi, paramValueToForm } from './job-parameter-specs';

/** Dialog input: an existing job ⇒ edit; absent ⇒ create. `focusSchedule` opens with the schedule emphasized
 *  (the "Reschedule" action). */
export interface JobFormData {
    job?: JobDetail;
    focusSchedule?: boolean;
    /** Ids already in use — on create the name control rejects a duplicate inline (product-wide rule). */
    existingNames?: string[];
}
export interface JobFormResult {
    saved?: JobDetail;
}

type ScheduleMode = 'cron' | 'event' | 'signal' | 'manual';

/** The schedule mode in the wire spelling an Expression's `availableIn` uses (`ExpressionDecl.TriggerKind`,
 *  lowercased) — the picker filters on this, so the two vocabularies have to meet somewhere. */
const TRIGGER_KIND: Record<ScheduleMode, string> = {
    cron: 'cron',
    event: 'on_pipeline',
    signal: 'on_signal',
    manual: 'manual',
};

/** A value that is a runtime token rather than a literal: `$`-led and not the `$$` escape — mirrors
 *  `ExpressionRegistry.isExpression`, so the form exempts from format validation exactly what the engine
 *  evaluates. */
const TOKEN_SYNTAX = /^\$(?!\$)/;

const CRON_PRESETS: { label: string; cron: string }[] = [
    { label: 'Every hour', cron: '0 0 * * * *' },
    { label: 'Daily 06:00', cron: '0 0 6 * * *' },
    { label: 'Weekly (Sun 02:00)', cron: '0 0 2 * * 0' },
    { label: 'Monthly (1st 01:00)', cron: '0 0 1 1 * *' },
];

/** Which trigger a saved job is using. Cron wins over an event trigger, which wins over a signal one —
 *  the same precedence the reschedule action applies (a cron supersedes an event trigger). */
export function triggerModeOf(job: { cron?: string | null; onPipeline?: string | null; onSignal?: string | null }): ScheduleMode {
    if (job.cron) return 'cron';
    if (job.onPipeline) return 'event';
    return job.onSignal ? 'signal' : 'manual';
}

/** Rejects a value (case-insensitive, trimmed) already present in `taken` → `{ duplicate: true }`. */
function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Create / edit / reschedule a scheduled job — the W2 pilot of `<inspecto-schema-form>`: every scalar
 * attribute (identity, type, trigger, arming, catch-up) is declared in {@link JOB_ATTRIBUTES} and
 * rendered by the shared spec-driven form; only the genuinely bespoke pieces stay hand-built here
 * (cron preset quick-pick, the key/value params editor). Mock-served until the real write endpoints
 * land — a 503 surfaces the writes-disabled banner.
 */
@Component({
    selector: 'app-job-form-dialog',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        MatTooltipModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoSchemaFormComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './job-form.dialog.html',
})
export class JobFormDialog implements AfterViewInit {
    private fb = inject(FormBuilder);
    private api = inject(JobsService);
    private ref = inject(MatDialogRef<JobFormDialog, JobFormResult>);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private destroyRef = inject(DestroyRef);
    readonly data = inject<JobFormData>(MAT_DIALOG_DATA);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;
    @ViewChild('pf') paramForm?: InspectoSchemaFormComponent;

    readonly isEdit = !!this.data.job;
    readonly saving = signal(false);
    readonly writesDisabled = signal(false);
    readonly cronPresets = CRON_PRESETS;
    /** A signal, not the const: the `type` picker's options are replaced by the server's catalog (§8.2). */
    readonly attributes = signal<AttributeSpec[]>(JOB_ATTRIBUTES);
    /**
     * Every registered Job Type (`GET /jobs/types`) — the picker's options, and the descriptor source.
     * The catalog route serves the SAME descriptor + provenance as `GET /jobs/types/{id}`
     * (`JobService.jobTypeViews()` maps each type through `jobTypeView(id)`), so once this has arrived a
     * type switch needs no second round trip.
     */
    readonly typeCatalog = signal<JobTypeDescriptor[]>([]);
    /** The selected type's full descriptor, for the panel. Set alongside its parameters. */
    readonly selectedType = signal<JobTypeDescriptor | undefined>(undefined);
    /**
     * The type published no descriptor (a legacy type, or a 404). The free key/value editor is the escape
     * hatch for exactly this state and is surfaced as a warning rather than offered as a normal path (§8.4).
     */
    readonly descriptorMissing = computed(() => !!this.selectedTypeId() && !this.selectedType());
    /** The type id whose descriptor was last looked up — read by the template, so not private. */
    readonly selectedTypeId = signal('');

    /** `name (kind)` per declared Run Artifact — what a run of this type records. */
    readonly artifactSummary = computed(() =>
        (this.selectedType()?.artifacts ?? []).map((a) => `${a.name} (${a.kind})`).join(', '),
    );
    /** The Event Types a run of this type publishes. */
    readonly emitsSummary = computed(() => (this.selectedType()?.emits ?? []).join(', '));

    /** Create flow: `config` (type + trigger + params) → `save` (the job id, asked last). Edit stays on `config`. */
    readonly step = signal<'config' | 'save'>('config');

    /** Save-step field (create only): the job id — pre-filled from the config, asked only at save time. */
    readonly saveForm = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                ...(this.data.existingNames?.length ? [uniqueNameValidator(this.data.existingNames)] : []),
            ],
        ],
    });

    /** The selected Job Type's declared parameters (R3), rendered as a typed form (P3c). */
    readonly paramSpecs = signal<AttributeSpec[]>([]);
    /** The type's declared Platform Service grants (`requires:`, S1-2) — its reach, shown before arming. */
    readonly typeRequires = signal<string[]>([]);
    /** Existing values for the declared parameters (edit) — patched over the schema-form defaults. */
    readonly paramInitial = signal<Record<string, unknown> | undefined>(undefined);

    /** The job's current values, mapped onto the attribute keys (schedule mode is derived). */
    readonly initialValue: Record<string, unknown> | undefined = this.data.job
        ? {
              name: this.data.job.name,
              type: this.data.job.type,
              scheduleMode: triggerModeOf(this.data.job),
              cron: this.data.job.cron ?? '0 0 6 * * *',
              onPipeline: this.data.job.onPipeline ?? '',
              onPipelineGate: this.data.job.onPipelineGate ?? 'any',
              onSignal: this.data.job.onSignal ?? '',
              when: this.data.job.when ?? '',
              enabled: this.data.job.enabled,
              catchUp: !!this.data.job.catchUp,
          }
        : undefined;

    readonly paramsForm = this.fb.group({ params: this.fb.array<FormGroup>([]) });

    get paramsArray(): FormArray<FormGroup> {
        return this.paramsForm.controls.params;
    }

    /** Guarded close: Esc / backdrop / Cancel confirm before discarding a dirty form. */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () =>
            (this.schemaForm?.isDirty() ?? false) ||
            (this.paramForm?.isDirty() ?? false) ||
            this.paramsForm.dirty ||
            this.saveForm.dirty,
        this.confirm,
    );

    /** Suggestion source for the on-signal trigger's pipeline. */
    readonly optionLoaders = { onPipeline: pipelineOptionLoader() };

    /** Suggestion sources for the DECLARED parameters — built per descriptor, because the keys are the
     *  Job Type's own (a `DATASET_REF` renders as an autocomplete and would otherwise offer nothing). */
    readonly paramOptionLoaders = signal<Record<string, AttributeOptionLoader>>({});

    /** The runtime Expression vocabulary (`GET /jobs/expressions`, §4.3) — the token picker's source. */
    readonly expressions = signal<JobExpressionDecl[]>([]);
    /** The selected trigger, in `availableIn`'s spelling. The picker follows it, because switching to cron
     *  must WITHDRAW the `$signal.*` tokens rather than leave them offerable. */
    readonly triggerKind = signal<string>(TRIGGER_KIND.cron);
    /** Exposed for the template's `[tokenSyntax]`. */
    readonly tokenSyntax = TOKEN_SYNTAX;

    /**
     * Offerable tokens per declared parameter (§8.5). A computed, so the offer follows both late arrivals —
     * the catalog fetch and a type switch — and the author's own trigger choice.
     *
     * <p>⚠ Filtered from the DESCRIPTOR's declarations, not from `paramSpecs()`: the deciding `expressions`
     * flag has no home on an `AttributeSpec` and is deliberately not given one. `AttributeSpec`'s unions are
     * server-published (a Findings section is authored as one), so widening them for a Jobs-only policy flag
     * would drag `FindingsSpec` along with it — the coupling step 11 recorded and refused to feed.
     */
    readonly paramTokenMap = computed(() =>
        paramTokens(this.selectedType()?.parameters ?? [], this.expressions(), this.triggerKind()),
    );

    constructor() {
        for (const [key, value] of Object.entries(this.data.job?.params ?? {})) this.addParam(key, String(value));
    }

    ngAfterViewInit(): void {
        // Descriptor-driven parameters (P3c): render the selected Job Type's declared params, and follow
        // the type picker so the form re-shapes when the author switches type. Deferred a microtask so the
        // schema-form's async paramSpecs input doesn't mutate during this change-detection pass.
        //
        // ⚠ Subscribed on the FORM GROUP, not on the `type` control. Reassigning `specs` (which the type
        // catalog below does) rebuilds every control, so a subscription held on the old control instance
        // would go silent and switching type would stop reloading parameters. The FormGroup instance is
        // stable across a spec swap; the control is not.
        this.schemaForm.form.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((v) => {
                // The group emits on every field's keystroke, not just the picker's. `selectedTypeId`
                // already records the type whose descriptor is rendered, so it is the dedupe key.
                const t = String((v as { type?: unknown })?.type ?? '');
                if (t !== this.selectedTypeId()) this.loadParams(t);
                this.syncTriggerKind();
            });
        queueMicrotask(() => {
            this.loadParams(String(this.schemaForm.form.get('type')?.value ?? ''));
            this.syncTriggerKind();
        });
        this.loadTypeCatalog();
        this.loadExpressions();
    }

    /** Mirror the trigger control into the picker's filter. Read from the control rather than tracked
     *  separately so an edited job's derived mode and a create default behave identically. */
    private syncTriggerKind(): void {
        const mode = String(this.schemaForm.form.get('scheduleMode')?.value ?? 'cron') as ScheduleMode;
        this.triggerKind.set(TRIGGER_KIND[mode] ?? TRIGGER_KIND.manual);
    }

    /**
     * Fetch the Expression vocabulary once (§4.3). Degrades silently to no pickers: the token affordance is
     * an accelerator over a field an author can still type into, so a server predating step 6 (404) or a
     * failed call must cost the pickers and nothing else.
     */
    private loadExpressions(): void {
        this.api.expressions().subscribe({
            next: (list) => this.expressions.set(list ?? []),
            error: () => undefined,
        });
    }

    /**
     * Replace the hardcoded type list with the server's registry (§8.2). Deployed plugins and Job Packs
     * then appear with no UI change — which is the whole point of a descriptor-driven form.
     *
     * <p>Degrades to the declared list on failure: an empty picker would make the dialog unusable, and the
     * built-ins really are present on any server this UI talks to.
     */
    private loadTypeCatalog(): void {
        this.api.types().subscribe({
            next: (list) => {
                if (!list?.length) return;
                this.typeCatalog.set(list);
                // Reassigning `specs` rebuilds every control from its declared default, so capture the
                // live values first and put them back — the same trap the collector/grammar editors hit.
                const live = this.schemaForm?.form.getRawValue() ?? {};
                this.attributes.set(
                    JOB_ATTRIBUTES.map((s) =>
                        s.key === 'type'
                            ? { ...s, options: list.map((d) => ({ value: d.id, label: d.title || d.id })) }
                            : s,
                    ),
                );
                queueMicrotask(() => this.schemaForm?.form.patchValue({ ...this.initialValue, ...live }));
            },
            error: () => undefined, // keep the declared options; the picker must not go empty
        });
    }

    /** Render the selected type's declared parameters, from the catalog when it has arrived and from
     *  `GET /jobs/types/{id}` for a type the registry did not list. */
    private loadParams(typeId: string): void {
        this.selectedTypeId.set(typeId);
        if (!typeId) {
            this.clearDescriptor();
            return;
        }
        const known = this.typeCatalog().find((d) => d.id === typeId);
        if (known) {
            // Applied asynchronously even from cache: `paramSpecs` feeds the schema-form's `specs` input,
            // and mutating it inside this change-detection pass is what the deferral above avoids.
            queueMicrotask(() => this.applyDescriptor(known));
            return;
        }
        this.api.describeType(typeId).subscribe({
            next: (d) => this.applyDescriptor(d),
            // Unknown type / no descriptor (e.g. a legacy type or 404) → free key/value editor only,
            // and `descriptorMissing()` surfaces that as a warning rather than letting it look normal.
            error: () => this.clearDescriptor(),
        });
    }

    /** Everything a descriptor drives: typed params, declared grants, suggestions, the "what this does" panel. */
    private applyDescriptor(d: JobTypeDescriptor): void {
        const specs = paramDeclsToSpecs(d.parameters);
        this.paramSpecs.set(specs);
        this.selectedType.set(d);
        this.typeRequires.set(d.requires ?? []);
        // A declared DATASET_REF maps to an autocomplete; give each one the dataset suggestions.
        const loaders: Record<string, AttributeOptionLoader> = {};
        for (const decl of d.parameters ?? []) {
            if (decl.type === 'DATASET_REF') loaders[decl.name] = datasetOptionLoader();
        }
        this.paramOptionLoaders.set(loaders);
        const init: Record<string, unknown> = {};
        for (const s of specs) {
            const v = this.data.job?.params?.[s.key];
            if (v !== undefined) init[s.key] = paramValueToForm(s, v);
        }
        this.paramInitial.set(Object.keys(init).length ? init : undefined);
        // Declared params own their typed field — drop any duplicate from the free key/value editor.
        const declared = new Set(specs.map((s) => s.key));
        for (let i = this.paramsArray.length - 1; i >= 0; i--) {
            if (declared.has(String(this.paramsArray.at(i).value.key ?? '').trim())) this.paramsArray.removeAt(i);
        }
    }

    /** No descriptor for the selected type: no typed params, no declared grants, no panel. */
    private clearDescriptor(): void {
        this.paramSpecs.set([]);
        this.paramInitial.set(undefined);
        this.paramOptionLoaders.set({});
        this.typeRequires.set([]);
        this.selectedType.set(undefined);
    }

    addParam(key = '', value = ''): void {
        this.paramsArray.push(this.fb.group({ key: [key], value: [value] }));
    }
    removeParam(i: number): void {
        this.paramsArray.removeAt(i);
    }
    applyPreset(cron: string): void {
        this.schemaForm.form.get('cron')?.setValue(cron);
    }

    /** The suggested job id: `<type>_<trigger>` when the trigger names something, else just `<type>`. */
    suggestedName(): string {
        const v = this.schemaForm.value() as { type?: string; scheduleMode?: ScheduleMode; onPipeline?: string; onSignal?: string };
        let base = String(v.type ?? 'job');
        if (v.scheduleMode === 'event' && v.onPipeline) base = `${v.type}_${v.onPipeline}`;
        // A signal id is dotted (`dataset.write`) and a glob ends in `.*` — both get sanitised below.
        else if (v.scheduleMode === 'signal' && v.onSignal) base = `${v.type}_${v.onSignal}`;
        return base.replace(/[^A-Za-z0-9._-]+/g, '_').replace(/^[^A-Za-z0-9]+/, '').replace(/[^A-Za-z0-9]+$/, '');
    }

    /** Create flow only: leave the save step back to the config step (the id is kept). */
    backToConfig(): void {
        this.step.set('config');
    }

    save(): void {
        if (!this.schemaForm.validate()) return;
        if (this.paramForm && !this.paramForm.validate()) return;
        // Create asks the job id only now, at save time — config valid ⇒ advance to the save step.
        if (!this.isEdit && this.step() === 'config') {
            if (this.saveForm.controls.name.pristine) this.saveForm.patchValue({ name: this.suggestedName() });
            this.step.set('save');
            return;
        }
        if (!this.isEdit && this.saveForm.invalid) {
            this.saveForm.markAllAsTouched();
            return;
        }
        const v = this.schemaForm.value() as {
            type: JobType;
            scheduleMode: ScheduleMode;
            cron?: string;
            onPipeline?: string;
            onPipelineGate?: string;
            onSignal?: string;
            when?: string;
            enabled?: boolean;
            catchUp?: boolean;
        };
        const params: Record<string, unknown> = {};
        // Declared (descriptor-driven) parameters first — blank optionals are omitted.
        if (this.paramForm) {
            for (const [k, val] of Object.entries(this.paramForm.value())) {
                // A cleared chip list is already null (the renderer writes null, not []), so it is
                // omitted here like any other blank optional.
                if (val !== null && val !== undefined && val !== '') params[k] = paramValueToApi(val);
            }
        }
        // Then any additional key/value params the author added by hand (never overriding a declared one).
        for (const g of this.paramsArray.controls) {
            const k = String(g.value.key ?? '').trim();
            if (k && !(k in params)) params[k] = g.value.value;
        }
        const body: JobUpsert = {
            name: this.isEdit ? this.data.job!.name : String(this.saveForm.getRawValue().name ?? '').trim(),
            type: v.type,
            cron: v.scheduleMode === 'cron' ? String(v.cron ?? '').trim() : null,
            onPipeline: v.scheduleMode === 'event' ? String(v.onPipeline ?? '').trim() : null,
            // The gate only travels with the event trigger it modifies — switching away drops it.
            onPipelineGate: v.scheduleMode === 'event' ? String(v.onPipelineGate ?? '').trim() || null : null,
            onSignal: v.scheduleMode === 'signal' ? String(v.onSignal ?? '').trim() : null,
            // The guard only travels with the signal trigger it narrows — switching away drops it.
            when: v.scheduleMode === 'signal' ? String(v.when ?? '').trim() || null : null,
            enabled: v.enabled !== false,
            catchUp: !!v.catchUp,
            params,
        };
        this.saving.set(true);
        const call = this.isEdit ? this.api.update(body.name, body) : this.api.create(body);
        call.subscribe({
            next: (saved) => this.ref.close({ saved }),
            error: (e) => {
                this.saving.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else this.toastr.error(apiErrorMessage(e, 'Could not save the job.'));
            },
        });
    }
}
