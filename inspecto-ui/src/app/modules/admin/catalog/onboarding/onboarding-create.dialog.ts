import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ToastrService } from 'ngx-toastr';
import { ConfigService, SpacesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import { StreamBundle, parseStreamBundle, planStreamImport } from 'app/inspecto/transfer/stream-bundle';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';

export interface OnboardingCreateData {
    kind: 'stream' | 'reference';
    /** Pipeline names already in use — the name control rejects a duplicate inline. */
    existingNames?: string[];
}

export interface OnboardingCreateResult {
    /** The created draft's name — the caller navigates to /catalog/onboard/{name}. */
    name?: string;
}

function uniqueNameValidator(taken: string[]): ValidatorFn {
    const set = new Set(taken.map((t) => t.trim().toLowerCase()));
    return (c: AbstractControl) => (set.has(String(c.value ?? '').trim().toLowerCase()) ? { duplicate: true } : null);
}

/**
 * Onboard a Stream / Reference — the ask-the-minimum entry point: kind + name (+ optional
 * description). There is no prior config step here — the stages ARE the config, so the name is
 * asked exactly at artifact-creation time. Submitting writes a minimal, spec-valid,
 * `active:false` draft (`POST /config/write`) and registers it (`POST /runs`) so it is
 * catalog-visible immediately; the caller then opens the guided editor. Directory defaults
 * follow the space convention and sit behind Advanced — never blocking the first write.
 *
 * **Import (2026-07-31)** is the same entry point, pre-loaded: pick a file exported by the
 * onboarding shell's *Export config* and the draft is created from it — pipeline body, schema,
 * per-segment plugin schemas and the enrichment companion — instead of from the minimal template.
 * The name is still asked here (it is this instance's identity, and the source's may be taken), and
 * the preview states every rewrite BEFORE the write: directories are re-derived, the draft lands
 * INACTIVE, and anything that cannot travel (a Connection's credentials, masked secrets) is listed
 * as work still to do. Kind comes from the file and the toggle is locked — a Reference imported as a
 * Stream would silently change its load semantics.
 */
@Component({
    selector: 'app-onboarding-create-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatDialogModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        InspectoAlertComponent,
    ],
    template: `
        <h2 mat-dialog-title>Onboard {{ kind() === 'reference' ? 'Reference' : 'Stream' }}</h2>

        <mat-dialog-content class="!pt-2">
            @if (writesDisabled()) {
                <inspecto-alert class="mb-4 block" variant="warning" icon="heroicons_outline:lock-closed">
                    Config writes are disabled on this server (no write root configured).
                </inspecto-alert>
            }

            <!-- Import: start from a config exported elsewhere instead of the blank template. -->
            <div class="mb-4 flex flex-wrap items-center gap-3">
                <button mat-stroked-button type="button" (click)="fileInput.click()">
                    <mat-icon svgIcon="heroicons_outline:arrow-up-tray" class="icon-size-4"></mat-icon>
                    <span class="ml-1">{{ imported() ? 'Choose another file' : 'Import configuration' }}</span>
                </button>
                <input
                    #fileInput
                    type="file"
                    accept="application/json,.json"
                    class="hidden"
                    aria-label="Stream configuration file to import"
                    (change)="onFilePicked($event)"
                />
                @if (!imported() && !importErrors().length) {
                    <span class="text-secondary text-sm">Or fill in the fields below to start fresh.</span>
                }
            </div>

            @if (importErrors().length) {
                <inspecto-alert class="mb-4 block" variant="error" title="That file cannot be imported">
                    <ul class="m-0 list-disc pl-5">
                        @for (e of importErrors(); track e) {
                            <li>{{ e }}</li>
                        }
                    </ul>
                </inspecto-alert>
            }

            @if (imported(); as b) {
                <inspecto-alert class="mb-4 block" variant="info" icon="heroicons_outline:document-arrow-up" title="Importing a configuration">
                    <p class="m-0">
                        From <span class="font-semibold">{{ b.source.name }}</span>
                        @if (b.source.space) {
                            <span> (space {{ b.source.space }})</span>
                        }
                        — {{ importSummary() }}.
                    </p>
                    <ul class="mb-0 mt-2 list-disc pl-5">
                        @for (n of importNotes(); track n) {
                            <li>{{ n }}</li>
                        }
                    </ul>
                    @if (b.requires.length) {
                        <p class="mb-0 mt-2 font-semibold">Must already exist on this server:</p>
                        <ul class="mb-0 list-disc pl-5">
                            @for (r of b.requires; track r.id) {
                                <li>{{ r.kind }} “{{ r.id }}” — {{ r.reason }}</li>
                            }
                        </ul>
                    }
                </inspecto-alert>
            }

            <form [formGroup]="form" class="flex flex-col gap-1" (ngSubmit)="create()">
                <mat-button-toggle-group
                    class="mb-4"
                    [value]="kind()"
                    (change)="kind.set($event.value)"
                    [disabled]="!!imported()"
                    aria-label="Data origin kind"
                >
                    <mat-button-toggle value="stream">Stream — event / fact</mat-button-toggle>
                    <mat-button-toggle value="reference">Reference — dimension</mat-button-toggle>
                </mat-button-toggle-group>

                <mat-form-field class="w-full" subscriptSizing="dynamic">
                    <mat-label>Name</mat-label>
                    <input matInput formControlName="name" required cdkFocusInitial placeholder="orders_feed" />
                    @if (form.controls.name; as c) {
                        @if (c.hasError('required')) {
                            <mat-error>A name is required.</mat-error>
                        } @else if (c.hasError('pattern')) {
                            <mat-error>Start with a letter or digit; then letters, digits, <code>. _ -</code> only.</mat-error>
                        } @else if (c.hasError('duplicate')) {
                            <mat-error>A pipeline with this name already exists.</mat-error>
                        }
                    }
                </mat-form-field>

                <mat-form-field class="mt-3 w-full" subscriptSizing="dynamic">
                    <mat-label>Description (optional)</mat-label>
                    <input matInput formControlName="description" />
                </mat-form-field>

                <button
                    type="button"
                    class="text-secondary mt-3 flex items-center gap-1 self-start text-sm"
                    (click)="advancedOpen.set(!advancedOpen())"
                    [attr.aria-expanded]="advancedOpen()"
                >
                    <mat-icon
                        class="icon-size-4"
                        [svgIcon]="advancedOpen() ? 'heroicons_outline:chevron-down' : 'heroicons_outline:chevron-right'"
                    ></mat-icon>
                    Advanced — directories
                </button>
                @if (advancedOpen()) {
                    <div class="mt-2 flex flex-col gap-1">
                        <mat-form-field class="w-full" subscriptSizing="dynamic">
                            <mat-label>Inbox (poll) directory</mat-label>
                            <input matInput formControlName="poll" />
                            <mat-hint>Where dropped files are collected from.</mat-hint>
                        </mat-form-field>
                        <mat-form-field class="mt-2 w-full" subscriptSizing="dynamic">
                            <mat-label>Database (output) directory</mat-label>
                            <input matInput formControlName="database" />
                        </mat-form-field>
                    </div>
                }
            </form>
        </mat-dialog-content>

        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="requestClose()">Cancel</button>
            <button mat-flat-button color="primary" [disabled]="creating() || writesDisabled()" (click)="create()">
                {{ imported() ? 'Create from import' : 'Create draft' }}
            </button>
        </mat-dialog-actions>
    `,
})
export class OnboardingCreateDialog {
    private fb = inject(FormBuilder);
    private configApi = inject(ConfigService);
    private spaces = inject(SpacesService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    private transfer = inject(StreamTransferService);
    private ref = inject(MatDialogRef<OnboardingCreateDialog, OnboardingCreateResult>);
    readonly data = inject<OnboardingCreateData>(MAT_DIALOG_DATA);

    readonly kind = signal<'stream' | 'reference'>(this.data.kind);
    readonly advancedOpen = signal(false);
    readonly creating = signal(false);
    readonly writesDisabled = signal(false);

    /** A parsed configuration file, when the operator chose to import one. */
    readonly imported = signal<StreamBundle | null>(null);
    readonly importErrors = signal<string[]>([]);

    /** What the file carries, in the operator's words — so "Create" is not a blind action. */
    readonly importSummary = computed(() => {
        const b = this.imported();
        if (!b) return '';
        const parts = ['pipeline configuration'];
        if (b.schema) parts.push('schema');
        const segs = Object.keys(b.segments ?? {}).length;
        if (segs) parts.push(`${segs} segment schema${segs === 1 ? '' : 's'}`);
        if (b.enrichment) parts.push('enrichment');
        return parts.join(', ');
    });

    /** The plan's notes for the CURRENT name — recomputed as the name is typed. */
    readonly importNotes = computed<string[]>(() => {
        const b = this.imported();
        if (!b) return [];
        return planStreamImport(b, { name: this.plannedName(), space: this.spaces.currentSpaceId() }).notes;
    });

    private readonly nameValue = signal('');
    private plannedName(): string {
        return this.nameValue().trim() || this.imported()?.source.name || 'the new stream';
    }

    readonly form = this.fb.group({
        name: [
            '',
            [
                Validators.required,
                Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/),
                ...(this.data.existingNames?.length ? [uniqueNameValidator(this.data.existingNames)] : []),
            ],
        ],
        description: [''],
        poll: [''],
        database: [''],
    });

    readonly requestClose = guardDirtyClose(this.ref, () => this.form.dirty, this.confirm);

    constructor() {
        // Directory defaults follow the space convention, live while the author hasn't overridden.
        this.form.controls.name.valueChanges.subscribe((name) => {
            const slug = String(name ?? '').trim();
            this.nameValue.set(slug);
            if (!slug) return;
            const base = this.spaces.currentSpaceId() ? `spaces/${this.spaces.currentSpaceId()}` : '.';
            if (this.form.controls.poll.pristine) this.form.controls.poll.setValue(`${base}/data/inbox/${slug}`, { emitEvent: false });
            if (this.form.controls.database.pristine) this.form.controls.database.setValue(`${base}/data/${slug}/database`, { emitEvent: false });
        });
    }

    /** Read + validate a picked file. Nothing is written here — this only loads the preview. */
    onFilePicked(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';   // allow re-picking the same file after a failed parse
        if (!file) return;
        this.imported.set(null);
        this.importErrors.set([]);
        file.text().then(
            (text) => {
                const { bundle, errors } = parseStreamBundle(text);
                if (!bundle) {
                    this.importErrors.set(errors);
                    return;
                }
                this.imported.set(bundle);
                // Kind is the file's, not the caller's — importing a Reference as a Stream would
                // change its load semantics silently.
                this.kind.set(bundle.kind);
                // Suggest the source's name, de-duplicated against this instance; the operator can
                // still change it, and the unique validator remains the gate.
                const taken = new Set((this.data.existingNames ?? []).map((t) => t.trim().toLowerCase()));
                let candidate = bundle.source.name;
                for (let i = 2; taken.has(candidate.toLowerCase()); i++) candidate = `${bundle.source.name}_${i}`;
                this.form.controls.name.setValue(candidate);
                this.form.controls.name.markAsDirty();
                const desc = bundle.pipeline['description'];
                if (typeof desc === 'string' && desc) this.form.controls.description.setValue(desc);
            },
            () => this.importErrors.set(['Could not read that file.']),
        );
    }

    create(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        if (this.imported()) {
            this.createFromImport();
            return;
        }
        const v = this.form.getRawValue();
        const name = String(v.name ?? '').trim();
        // Beyond the two asked-for dirs, the whole space-convention dir set is derived silently —
        // without dirs.status_dir the run audit never lands (Runs history stays empty), and
        // without processing.duplicate_check the LOCAL poll path re-ingests the same file every
        // cycle (the collector-level `duplicate:` block only drives the collection engine, not
        // the legacy local path) — both found by the P3 live walk.
        const home = (v.database || `data/${name}/database`).replace(/\/database$/, '');
        const config: Record<string, unknown> = {
            name,
            active: false,
            dirs: {
                poll: v.poll || `data/inbox/${name}`,
                database: v.database || `data/${name}/database`,
                backup: `${home}/backup`,
                temp: `${home}/temp`,
                errors: `${home}/errors`,
                quarantine: `${home}/quarantine`,
                markers: `${home}/markers`,
                status_dir: `${home}/status`,
                log_dir: `${home}/logs`,
            },
            processing: {
                threads: 1,
                duplicate_check: { enabled: true, marker_extension: '.processed', retention_days: 30 },
            },
        };
        if (String(v.description ?? '').trim()) config['description'] = String(v.description).trim();
        if (this.kind() === 'reference') config['produces'] = 'reference';

        this.creating.set(true);
        this.configApi.write('pipeline', config).subscribe({
            next: (written) => {
                this.configApi.registerPipeline(written.path).subscribe({
                    next: () => {
                        this.toastr.success(`Draft "${name}" created`);
                        this.ref.close({ name });
                    },
                    error: (e) => {
                        // The file exists — onboarding still opens; the catalog row appears after a restart/rescan.
                        this.toastr.warning(apiErrorMessage(e, 'Draft saved, but registering it failed.'));
                        this.ref.close({ name });
                    },
                });
            },
            error: (e) => {
                this.creating.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else if (e?.status === 409) this.form.controls.name.setErrors({ duplicate: true });
                else this.toastr.error(apiErrorMessage(e, 'Could not create the draft.'));
            },
        });
    }

    /** Create the draft from the loaded file: satellites first, then the pipeline (the service's
     *  ordering rule), with the dialog's own directory overrides applied on top of the plan. */
    private createFromImport(): void {
        const bundle = this.imported();
        if (!bundle) return;
        const v = this.form.getRawValue();
        const name = String(v.name ?? '').trim();
        const plan = planStreamImport(bundle, { name, space: this.spaces.currentSpaceId() });

        // The Advanced fields still win if the operator touched them — same contract as a fresh create.
        const dirs = plan.pipeline['dirs'] as Record<string, string>;
        if (v.poll) dirs['poll'] = v.poll;
        if (v.database) dirs['database'] = v.database;
        const description = String(v.description ?? '').trim();
        if (description) plan.pipeline['description'] = description;
        else delete plan.pipeline['description'];

        this.creating.set(true);
        this.transfer.applyImport(plan).subscribe({
            next: () => {
                const extras = [
                    plan.schema ? 'schema' : null,
                    plan.segments.length ? `${plan.segments.length} segment schema${plan.segments.length === 1 ? '' : 's'}` : null,
                    plan.enrichment ? 'enrichment' : null,
                ].filter(Boolean);
                this.toastr.success(
                    extras.length
                        ? `Imported "${name}" with ${extras.join(', ')} — review the stages, then go live.`
                        : `Imported "${name}" — review the stages, then go live.`,
                );
                this.ref.close({ name });
            },
            error: (e) => {
                this.creating.set(false);
                if (e?.status === 503) this.writesDisabled.set(true);
                else if (e?.status === 409) this.form.controls.name.setErrors({ duplicate: true });
                else this.toastr.error(apiErrorMessage(e, 'Could not import the configuration.'));
            },
        });
    }
}
