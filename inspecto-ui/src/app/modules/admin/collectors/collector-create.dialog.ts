import { ChangeDetectionStrategy, Component, computed, inject, signal, ViewChild } from '@angular/core';
import { AbstractControl, FormBuilder, ReactiveFormsModule, ValidatorFn, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { switchMap } from 'rxjs/operators';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConfigService, PipelinesService } from 'app/inspecto/api';
import { CollectorConfigComponent } from 'app/inspecto/collector/collector-config.component';
import { COLLECTOR_ATTRIBUTES, nestKeys, pipelineId, pipelineScaffold } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';

export interface CollectorCreateResult {
    created?: boolean;
    pipeline?: string;
}

function uniquePipelineIdValidator(taken: () => Set<string>): ValidatorFn {
    return (c: AbstractControl) => {
        const raw = String(c.value ?? '').trim();
        if (!raw) return null;
        return taken().has(pipelineId(raw)) ? { duplicate: true } : null;
    };
}

/**
 * Create a Collector from the Collectors page — either by scaffolding a new Pipeline with its
 * collector + inbox (`dirs.poll`) configured, or by configuring the collector + inbox on an
 * existing Pipeline (`POST /config/patch`). Reuses `<inspecto-collector-config>` so Local inbox,
 * Connection, and Dataset modes stay identical to Onboarding and the Pipeline editor.
 */
@Component({
    selector: 'app-collector-create-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatDialogModule,
        MatIconModule,
        MatProgressSpinnerModule,
        CollectorConfigComponent,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    template: `
        <h2 mat-dialog-title>New collector</h2>

        <mat-dialog-content class="!pt-2">
            <form [formGroup]="form" (ngSubmit)="save()" class="flex flex-col gap-4">
                @if (existingPipelines().length) {
                    <div>
                        <div class="text-secondary mb-1 text-xs font-semibold uppercase tracking-wider">Pipeline</div>
                        <mat-button-toggle-group
                            [value]="targetMode()"
                            (change)="setTargetMode($event.value)"
                            aria-label="Target pipeline"
                        >
                            <mat-button-toggle value="new">New pipeline</mat-button-toggle>
                            <mat-button-toggle value="existing">Existing pipeline</mat-button-toggle>
                        </mat-button-toggle-group>
                    </div>
                }

                @if (targetMode() === 'new') {
                    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <inspecto-pen-field
                            label="Pipeline name"
                            formControlName="name"
                            placeholder="orders_ingest"
                            [required]="true"
                            [error]="nameError()"
                        />

                        <inspecto-pen-field
                            label="Inbox folder (dirs.poll)"
                            formControlName="poll"
                            placeholder="data/inbox/orders_ingest"
                            [mono]="true"
                        />
                    </div>
                } @else {
                    <div class="grid grid-cols-1 gap-3 sm:grid-cols-2">
                        <inspecto-option-picker
                            label="Existing pipeline"
                            formControlName="existingPipeline"
                            [options]="existingPipelineOptions()"
                            [required]="true"
                        />

                        <inspecto-pen-field
                            label="Inbox folder (dirs.poll, optional)"
                            formControlName="poll"
                            placeholder="Leave blank to keep existing"
                            [mono]="true"
                        />
                    </div>
                }

                <inspecto-collector-config #collector [specs]="collectorSpecs" (submitted)="save()" />

                @if (error(); as err) {
                    <inspecto-alert variant="error">{{ err }}</inspecto-alert>
                }
            </form>
        </mat-dialog-content>

        <mat-dialog-actions align="end">
            <button mat-button type="button" [disabled]="saving()" (click)="ref.close()">Cancel</button>
            <button mat-flat-button color="primary" type="button" [disabled]="saving()" (click)="save()">
                @if (saving()) {
                    <mat-progress-spinner diameter="16" mode="indeterminate" class="mr-2" />
                }
                Create collector
            </button>
        </mat-dialog-actions>
    `,
})
export class CollectorCreateDialog {
    protected readonly ref = inject<MatDialogRef<CollectorCreateDialog, CollectorCreateResult>>(MatDialogRef);
    private fb = inject(FormBuilder);
    private configApi = inject(ConfigService);
    private pipelinesApi = inject(PipelinesService);
    private toastr = inject(ToastrService);

    @ViewChild('collector') private collector?: CollectorConfigComponent;

    readonly collectorSpecs = COLLECTOR_ATTRIBUTES;

    /** The pipeline name's error once touched, in words (the pen field renders it as an alert line). */
    nameError(): string {
        const c = this.form.controls.name;
        if (!c.touched) return '';
        if (c.hasError('required')) return 'A pipeline name is required.';
        return c.hasError('duplicate') ? 'A pipeline with this id already exists.' : '';
    }
    readonly existingPipelines = signal<string[]>([]);
    readonly existingPipelineOptions = computed<PickerOption[]>(() =>
        this.existingPipelines().map((p) => ({ value: p, label: p })),
    );
    private readonly existingSet = computed(() => new Set(this.existingPipelines().map((p) => p.toLowerCase())));

    readonly targetMode = signal<'new' | 'existing'>('new');
    readonly saving = signal(false);
    readonly error = signal<string | null>(null);

    readonly form = this.fb.group({
        name: ['', [Validators.required, uniquePipelineIdValidator(() => this.existingSet())]],
        existingPipeline: [''],
        poll: [''],
    });

    constructor() {
        this.pipelinesApi.list().subscribe({
            next: (rows) => {
                const names = rows.filter((r) => !r.template).map((r) => r.name);
                this.existingPipelines.set(names);
                this.form.controls.name.updateValueAndValidity({ emitEvent: false });
            },
            error: () => this.existingPipelines.set([]),
        });

        this.form.controls.name.valueChanges.subscribe((name) => {
            if (this.targetMode() !== 'new' || !this.form.controls.poll.pristine) return;
            const raw = String(name ?? '').trim();
            this.form.controls.poll.setValue(raw ? `data/inbox/${pipelineId(raw)}` : '', { emitEvent: false });
        });
    }

    setTargetMode(mode: 'new' | 'existing'): void {
        if (mode === this.targetMode()) return;
        this.targetMode.set(mode);
        this.error.set(null);
        if (mode === 'new') {
            this.form.controls.name.setValidators([
                Validators.required,
                uniquePipelineIdValidator(() => this.existingSet()),
            ]);
            this.form.controls.existingPipeline.clearValidators();
        } else {
            this.form.controls.name.clearValidators();
            this.form.controls.existingPipeline.setValidators([Validators.required]);
        }
        this.form.controls.name.updateValueAndValidity({ emitEvent: false });
        this.form.controls.existingPipeline.updateValueAndValidity({ emitEvent: false });
        if (this.form.controls.poll.pristine) {
            this.form.controls.poll.setValue('', { emitEvent: false });
        }
    }

    save(): void {
        if (this.saving()) return;
        this.form.markAllAsTouched();
        if (this.form.invalid) return;
        if (this.collector && !this.collector.validate()) return;
        const connector = this.collector?.resolveConnector() ?? 'local';
        if (!connector) return;

        this.error.set(null);
        this.saving.set(true);

        const flat = this.collector?.value() ?? {};
        const nested = nestKeys(flat);
        const poll = String(this.form.controls.poll.value ?? '').trim();

        if (this.targetMode() === 'new') {
            const rawName = String(this.form.controls.name.value ?? '').trim();
            const id = pipelineId(rawName);
            const config = pipelineScaffold(rawName, poll ? { poll } : {});
            config['collector'] = { id, connector, ...nested };

            this.configApi
                .write('pipeline', config)
                .pipe(switchMap((written) => this.configApi.registerPipeline(written.path)))
                .subscribe({
                    next: () => {
                        this.toastr.success(`Collector created for "${id}".`);
                        this.ref.close({ created: true, pipeline: id });
                    },
                    error: (err) => {
                        this.saving.set(false);
                        this.error.set(apiErrorMessage(err, `Could not create collector for "${id}".`));
                    },
                });
        } else {
            const target = String(this.form.controls.existingPipeline.value ?? '').trim();
            const patch: Record<string, unknown> = {
                collector: { connector, ...nested },
            };
            if (poll) patch['dirs'] = { poll };

            this.configApi.patch('pipeline', target, patch).subscribe({
                next: () => {
                    this.toastr.success(`Collector updated on "${target}".`);
                    this.ref.close({ created: true, pipeline: target });
                },
                error: (err) => {
                    this.saving.set(false);
                    this.error.set(apiErrorMessage(err, `Could not update collector on "${target}".`));
                },
            });
        }
    }
}
