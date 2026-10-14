import { ChangeDetectionStrategy, Component, ViewChild, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ValidatorFn } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { apiErrorMessage, JobsService } from 'app/inspecto/api';
import { AttributeSpec } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { paramDeclsToSpecs, paramExtraValidators } from './job-parameter-specs';

export interface JobRunParamsData {
    /** The Job's name (`POST /jobs/{name}/trigger`). */
    name: string;
    /** The Job's type id — its declared parameters come from `GET /jobs/types/{id}`. */
    type: string;
}

/** Closes with the submitted run's id, or nothing on Cancel. */
export type JobRunParamsResult = { runId: string } | undefined;

/**
 * Run a Job once with explicit trigger parameters. Lists the Job Type's declared parameters as typed
 * fields seeded with the Job's configured values (else the declared default); only the fields the user
 * changed travel as `params`, so every untouched parameter keeps resolving exactly as a scheduled fire
 * would. The server stays the gate: a refusal (422) is shown inline and the dialog stays open.
 * Undeclared free-form keys are deliberately not offered — a Job's contract is its declaration.
 */
@Component({
    selector: 'app-job-run-params-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, InspectoAlertComponent, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Run with parameters — {{ data.name }}</h2>
        <mat-dialog-content class="!pt-2">
            @if (error()) {
                <inspecto-alert class="mb-4 block" variant="error" role="alert">{{ error() }}</inspecto-alert>
            }
            @if (loading()) {
                <p class="text-secondary text-sm">Loading parameters…</p>
            } @else if (!specs().length) {
                <p class="text-secondary text-sm">
                    This job type declares no runtime parameters; it will run with its configured values.
                </p>
            } @else {
                <p class="text-secondary mb-3 text-sm">
                    Only the values you change are sent for this run; the rest resolve as configured.
                </p>
                <inspecto-schema-form
                    #pf
                    [specs]="specs()"
                    [initial]="initial()"
                    [extraValidators]="validators()"
                    (submitted)="run()"
                ></inspecto-schema-form>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" (click)="ref.close()">Cancel</button>
            <button mat-flat-button color="primary" type="button" (click)="run()" [disabled]="loading() || running()">
                Run
            </button>
        </mat-dialog-actions>
    `,
})
export class JobRunParamsDialog {
    private api = inject(JobsService);
    readonly data = inject<JobRunParamsData>(MAT_DIALOG_DATA);
    readonly ref = inject(MatDialogRef<JobRunParamsDialog, JobRunParamsResult>);

    @ViewChild('pf') paramForm?: InspectoSchemaFormComponent;

    readonly loading = signal(true);
    readonly running = signal(false);
    readonly error = signal('');
    readonly specs = signal<AttributeSpec[]>([]);
    readonly initial = signal<Record<string, unknown> | undefined>(undefined);
    readonly validators = signal<Record<string, ValidatorFn[]>>({});

    constructor() {
        forkJoin({ type: this.api.describeType(this.data.type), job: this.api.get(this.data.name) }).subscribe({
            next: ({ type, job }) => {
                this.validators.set(paramExtraValidators(type.parameters));
                this.specs.set(paramDeclsToSpecs(type.parameters));
                // Configured values win over the declared default (which the spec already carries).
                const declared = new Set(type.parameters.map((p) => p.name));
                const init = Object.fromEntries(Object.entries(job.params ?? {}).filter(([k]) => declared.has(k)));
                this.initial.set(Object.keys(init).length ? init : undefined);
                this.loading.set(false);
            },
            error: (e) => {
                this.error.set(apiErrorMessage(e, 'Could not load the job parameters.'));
                this.loading.set(false);
            },
        });
    }

    run(): void {
        const pf = this.paramForm;
        if (pf && !pf.validate()) return;
        const all = pf?.value() ?? {};
        const params: Record<string, unknown> = {};
        for (const k of pf?.dirtyKeys() ?? [])
            if (all[k] !== undefined && all[k] !== null)
                // A multi parameter is CSV on the wire (the resolver splits it), not a JSON array.
                params[k] = Array.isArray(all[k]) ? (all[k] as unknown[]).join(',') : all[k];
        this.running.set(true);
        this.error.set('');
        this.api.trigger(this.data.name, params).subscribe({
            next: (r) => this.ref.close(r),
            error: (e) => {
                this.running.set(false);
                this.error.set(apiErrorMessage(e, `Run failed for ${this.data.name}`));
            },
        });
    }
}
