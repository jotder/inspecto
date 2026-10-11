import { ChangeDetectionStrategy, Component, computed, DestroyRef, inject, signal, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, FormControl, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { switchMap, takeWhile } from 'rxjs';
import {
    apiErrorMessage,
    Space,
    SpaceComparisonResult,
    SpaceComparisonRun,
    SpaceComparisonService,
    visibleInterval,
} from 'app/inspecto/api';
import { AttributeSpec } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { DataTableComponent } from 'app/inspecto/data-table';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';
import {
    comparisonColumns,
    comparisonRequest,
    comparisonSummary,
    isTerminalRunStatus,
    notComparableList,
} from 'app/inspecto/spaces/space-comparison';

/** Dialog input: the hosted Spaces to choose from. */
export interface SpaceComparisonData {
    spaces: Space[];
}

/** Poll cadence for the admitted run (ms). */
export const SPACE_COMPARISON_POLL_MS = 1500;

/** The run options; the Spaces themselves are the checkbox list above the form. */
export const SPACE_COMPARISON_ATTRIBUTES: AttributeSpec[] = [
    {
        key: 'windowDays',
        label: 'Trend window (days)',
        type: 'number',
        tier: 'required',
        required: false,
        default: 30,
        min: 1,
        help: 'How far back the storage samples are read.',
    },
    {
        key: 'top',
        label: 'Storage axes to report',
        type: 'number',
        tier: 'optional',
        required: false,
        default: 5,
        min: 1,
        help: 'The axes with the widest spread between Spaces are reported first.',
    },
    {
        key: 'axes',
        label: 'Only these storage axes',
        type: 'list',
        tier: 'optional',
        required: false,
        help: 'Leave empty to compare every axis.',
    },
];

function atLeastTwo(c: AbstractControl<string[]>): ValidationErrors | null {
    return (c.value?.length ?? 0) >= 2 ? null : { tooFew: true };
}

type Phase = 'form' | 'running' | 'done';

/**
 * Compare the storage growth of two or more hosted Spaces — a view over `POST /space-comparisons`
 * (the `space.comparison` Job Type, gated on `canAdminister`). It admits one run, polls
 * `GET /jobs/runs/{runId}` until it finishes, then reads the structured result from the run's
 * `space.comparison.completed` Signal (falling back to the Run message). Nothing is saved: a
 * comparison is not a stored artifact, and scheduling one is refused by design.
 */
@Component({
    selector: 'app-space-comparison-dialog',
    standalone: true,
    imports: [
        MatButtonModule,
        MatCheckboxModule,
        MatDialogModule,
        MatProgressBarModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoSchemaFormComponent,
        StatusBadgeComponent,
        DataTableComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Compare Spaces</h2>
        <mat-dialog-content>
            <p class="text-secondary mb-4 text-sm">
                Compares how storage grows in each Space: the latest size and daily growth per storage axis, the spread
                between Spaces and the fastest grower. Nothing is changed or saved.
            </p>

            @if (error(); as message) {
                <inspecto-alert class="mb-4 block" variant="error" title="The comparison could not run">
                    {{ message }}
                </inspecto-alert>
            }

            <div [hidden]="phase() !== 'form'">
                <fieldset class="mb-4">
                    <legend class="mb-2 text-sm font-semibold">Spaces to compare</legend>
                    <div class="flex flex-col gap-1">
                        @for (s of data.spaces; track s.id) {
                            <mat-checkbox [checked]="isSelected(s.id)" (change)="toggle(s.id, $event.checked)">
                                {{ s.displayName || s.id }}
                                @if (s.displayName && s.displayName !== s.id) {
                                    <span class="text-secondary font-mono text-xs">({{ s.id }})</span>
                                }
                            </mat-checkbox>
                        }
                    </div>
                    @if (spacesCtrl.touched && spacesCtrl.hasError('tooFew')) {
                        <p class="text-warn mt-1 text-xs" role="alert">Choose at least two Spaces.</p>
                    }
                </fieldset>
                <inspecto-schema-form [specs]="attributes" (submitted)="compare()"></inspecto-schema-form>
            </div>

            @if (phase() === 'running') {
                <div class="flex flex-col gap-2">
                    <mat-progress-bar mode="indeterminate" aria-label="Comparison running"></mat-progress-bar>
                    <p class="text-sm" role="status">
                        Comparing {{ spacesCtrl.value.join(', ') }}… Closing this dialog does not stop the run.
                    </p>
                </div>
            }

            @if (phase() === 'done' && run(); as r) {
                <div class="mb-3 flex items-center gap-2 text-sm">
                    <inspecto-status-badge [value]="r.status"></inspecto-status-badge>
                    <span class="text-secondary font-mono text-xs">{{ r.runId }}</span>
                </div>
                @if (r.status !== 'SUCCESS') {
                    <inspecto-alert variant="error" title="The comparison did not finish">
                        {{ r.message || 'The run ended without a message.' }}
                    </inspecto-alert>
                } @else if (result(); as res) {
                    <p class="mb-3 text-sm">{{ summary() }}</p>
                    @if (notComparable().length) {
                        <inspecto-alert class="mb-3 block" variant="warning" title="Not compared">
                            <ul class="list-disc pl-5">
                                @for (n of notComparable(); track n.space) {
                                    <li>
                                        <span class="font-mono">{{ n.space }}</span> — {{ n.reason }}
                                    </li>
                                }
                            </ul>
                        </inspecto-alert>
                    }
                    @if (res.axes.length) {
                        <inspecto-data-table
                            tier="mini"
                            [rows]="res.axes"
                            [columns]="columns()"
                            exportName="space-comparison"
                        ></inspecto-data-table>
                    } @else {
                        <inspecto-empty-state
                            icon="heroicons_outline:scale"
                            title="Nothing to compare"
                            message="At least two Spaces need two or more storage samples inside the trend window. Storage samples are recorded by the storage report maintenance task."
                        ></inspecto-empty-state>
                    }
                } @else {
                    <inspecto-alert variant="info" title="Run message">
                        {{ r.message || 'The run finished without a message.' }}
                    </inspecto-alert>
                }
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">
                {{ phase() === 'form' ? 'Cancel' : 'Close' }}
            </button>
            @if (phase() === 'form') {
                <button type="button" mat-flat-button color="primary" [disabled]="starting()" (click)="compare()">
                    Compare
                </button>
            } @else if (phase() === 'done') {
                <button type="button" mat-stroked-button (click)="again()">Compare again</button>
            }
        </mat-dialog-actions>
    `,
})
export class SpaceComparisonDialog {
    private api = inject(SpaceComparisonService);
    private ref = inject(MatDialogRef<SpaceComparisonDialog>);
    private confirm = inject(InspectoConfirmService);
    private destroyRef = inject(DestroyRef);
    readonly data = inject<SpaceComparisonData>(MAT_DIALOG_DATA);

    @ViewChild(InspectoSchemaFormComponent) schemaForm!: InspectoSchemaFormComponent;

    readonly attributes = SPACE_COMPARISON_ATTRIBUTES;
    readonly spacesCtrl = new FormControl<string[]>([], { nonNullable: true, validators: [atLeastTwo] });

    readonly phase = signal<Phase>('form');
    readonly starting = signal(false);
    readonly error = signal<string | null>(null);
    readonly run = signal<SpaceComparisonRun | null>(null);
    readonly result = signal<SpaceComparisonResult | null>(null);

    readonly summary = computed(() => {
        const r = this.result();
        return r ? comparisonSummary(r) : '';
    });
    readonly notComparable = computed(() => {
        const r = this.result();
        return r ? notComparableList(r) : [];
    });
    readonly columns = computed(() => {
        const r = this.result();
        return r ? comparisonColumns(r) : [];
    });

    /** Only unsent choices ask before closing; a running or finished comparison closes freely. */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => this.phase() === 'form' && (this.spacesCtrl.dirty || (this.schemaForm?.isDirty() ?? false)),
        this.confirm,
    );

    isSelected(id: string): boolean {
        return this.spacesCtrl.value.includes(id);
    }

    toggle(id: string, on: boolean): void {
        const current = this.spacesCtrl.value;
        // Keep the Spaces in list order, so the request reads the way the dialog does.
        const ordered = this.data.spaces.map((s) => s.id).filter((s) => (s === id ? on : current.includes(s)));
        this.spacesCtrl.setValue(ordered);
        this.spacesCtrl.markAsDirty();
        this.spacesCtrl.markAsTouched();
    }

    compare(): void {
        this.spacesCtrl.markAsTouched();
        const optionsValid = this.schemaForm.validate();
        if (this.spacesCtrl.invalid || !optionsValid) return;
        const v = this.schemaForm.value();
        const body = comparisonRequest(this.spacesCtrl.value, {
            windowDays: v['windowDays'],
            top: v['top'],
            axes: v['axes'],
        });
        this.error.set(null);
        this.starting.set(true);
        this.api.compare(body).subscribe({
            next: (admitted) => {
                this.starting.set(false);
                this.phase.set('running');
                this.poll(admitted.runId);
            },
            error: (e) => {
                this.starting.set(false);
                this.error.set(this.startError(e));
            },
        });
    }

    again(): void {
        this.run.set(null);
        this.result.set(null);
        this.error.set(null);
        this.phase.set('form');
    }

    private poll(runId: string): void {
        visibleInterval(SPACE_COMPARISON_POLL_MS)
            .pipe(
                switchMap(() => this.api.run(runId)),
                takeWhile((r) => !isTerminalRunStatus(r.status), true),
                takeUntilDestroyed(this.destroyRef),
            )
            .subscribe({
                next: (r) => {
                    if (!isTerminalRunStatus(r.status)) return;
                    this.run.set(r);
                    if (r.status === 'SUCCESS') this.loadResult(runId);
                    else this.phase.set('done');
                },
                error: (e) => {
                    this.phase.set('form');
                    this.error.set(apiErrorMessage(e, 'Lost track of the comparison run.'));
                },
            });
    }

    private loadResult(runId: string): void {
        this.api.result(runId).subscribe({
            // No Signal (or the ledger unavailable) still shows the Run message — never a blank result.
            next: (res) => {
                this.result.set(res);
                this.phase.set('done');
            },
            error: () => this.phase.set('done'),
        });
    }

    private startError(e: { status?: number }): string {
        if (e?.status === 403) return 'Comparing Spaces needs the administer capability.';
        if (e?.status === 409) return 'A comparison is already running in this Space. Try again when it finishes.';
        return apiErrorMessage(e, 'Could not start the comparison.');
    }
}
