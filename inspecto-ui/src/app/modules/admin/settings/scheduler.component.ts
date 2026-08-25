import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ToastrService } from 'ngx-toastr';

import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { LensService, SchedulerSettingsService, SchedulerView, apiErrorMessage } from 'app/inspecto/api';

/**
 * Settings ▸ **Scheduler** — the hot-tunable Consignment concurrency caps (scheduler-system-config
 * plan Part B): the server-wide ceiling every space draws from, and the active space's own cap.
 * Together with the per-pipeline knobs on the Pipelines editor (`processing.threads` = concurrent
 * Consignments per pipeline, `processing.priority` = share weight 1–3, `processing.intake.*` =
 * admission caps), this is the whole concurrency story.
 *
 * <p>A save **hot-applies** — no restart. A shrink **drains**: Consignments already executing finish,
 * and the new ceiling gates the next admissions; nothing is interrupted. `0` = unbounded.
 *
 * <p>Saves are gated on `canOperateRuns` (tuning a live scheduler is runtime operation, not workbench
 * authoring); the read renders for every lens. Each value carries its provenance (`file` — saved
 * here | `property` — a `-D` bootstrap default | `default` — unbounded) so two declarations never
 * leave the operator guessing which won.
 */
@Component({
    selector: 'inspecto-scheduler-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatProgressSpinnerModule,
        InspectoAlertComponent,
        ChipComponent,
        InspectoEmptyStateComponent,
    ],
    template: `
        <div class="flex flex-col gap-6">
            <header>
                <h1 class="text-2xl font-semibold">Scheduler</h1>
                <p class="text-secondary mt-1 text-sm">
                    How many Consignments may execute at once — server-wide, per space, and (on each pipeline) per
                    pipeline with a 1–3 priority share.
                </p>
            </header>

            <inspecto-alert variant="info" title="Changes apply live; a shrink drains">
                A saved cap takes effect immediately — no restart. Lowering a cap never interrupts running work:
                Consignments already executing finish, and the new ceiling gates the next admissions. 0 means
                unbounded. Rule of thumb: server cap × each pipeline's DuckDB threads ≈ the host's
                {{ view()?.cores ?? '…' }} cores.
            </inspecto-alert>

            @if (loading()) {
                <div class="flex items-center gap-2 py-6">
                    <mat-spinner diameter="20"></mat-spinner>
                    <span class="text-secondary text-sm">Reading the live scheduler state…</span>
                </div>
            } @else if (!view()) {
                <inspecto-empty-state
                    icon="heroicons_outline:queue-list"
                    title="Scheduler state unavailable"
                    [message]="loadError()"
                ></inspecto-empty-state>
            } @else {
                <form [formGroup]="form" class="flex max-w-160 flex-col gap-6" (ngSubmit)="saveSystem()">
                    <section class="flex flex-col gap-2">
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-medium">Server-wide cap</h2>
                            <inspecto-chip variant="soft" tone="neutral">{{ view()!.system.source }}</inspecto-chip>
                        </div>
                        <p class="text-secondary text-sm">
                            The ceiling every space draws from. {{ view()!.live.system_in_flight }} Consignment(s)
                            executing right now.
                        </p>
                        <div class="flex items-start gap-3">
                            <mat-form-field class="w-60" subscriptSizing="dynamic">
                                <mat-label>Max concurrent Consignments</mat-label>
                                <input matInput type="number" formControlName="system" min="0" max="100000" />
                                @if (form.controls.system.invalid) {
                                    <mat-error>0 (unbounded) to 100000.</mat-error>
                                }
                            </mat-form-field>
                            <button
                                mat-flat-button
                                color="primary"
                                type="submit"
                                class="mt-1"
                                [disabled]="!canOperate() || saving()"
                            >
                                Save server cap
                            </button>
                        </div>
                    </section>
                </form>

                <form [formGroup]="spaceForm" class="flex max-w-160 flex-col gap-6" (ngSubmit)="saveSpace()">
                    <section class="flex flex-col gap-2">
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-medium">This space's cap</h2>
                            <inspecto-chip variant="soft" tone="neutral">{{ view()!.space.source }}</inspecto-chip>
                        </div>
                        <p class="text-secondary text-sm">
                            An optional tighter bound for the active space ({{ view()!.space.id || 'default' }}) inside
                            the server-wide ceiling.
                        </p>
                        <div class="flex items-start gap-3">
                            <mat-form-field class="w-60" subscriptSizing="dynamic">
                                <mat-label>Max concurrent Consignments</mat-label>
                                <input matInput type="number" formControlName="space" min="0" max="100000" />
                                @if (spaceForm.controls.space.invalid) {
                                    <mat-error>0 (unbounded) to 100000.</mat-error>
                                }
                            </mat-form-field>
                            <button
                                mat-flat-button
                                color="primary"
                                type="submit"
                                class="mt-1"
                                [disabled]="!canOperate() || saving()"
                            >
                                Save space cap
                            </button>
                        </div>
                    </section>
                </form>

                @if (!canOperate()) {
                    <inspecto-alert variant="warning" title="Operate-runs capability required">
                        Your current lens cannot tune the live scheduler — switch to a lens with the operate-runs
                        capability to save changes.
                    </inspecto-alert>
                }

                @if (busyPipelines().length) {
                    <section class="flex flex-col gap-2">
                        <h2 class="text-lg font-medium">Live occupancy</h2>
                        <ul class="flex flex-col gap-1">
                            @for (p of busyPipelines(); track p.name) {
                                <li class="text-sm">
                                    <span class="font-mono">{{ p.name }}</span>
                                    <span class="text-secondary">
                                        — {{ p.in_flight }} executing, {{ p.waiting }} waiting, priority
                                        {{ p.priority }}</span
                                    >
                                </li>
                            }
                        </ul>
                    </section>
                }
            }
        </div>
    `,
})
export class SchedulerSettingsComponent implements OnInit {
    private api = inject(SchedulerSettingsService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private lens = inject(LensService);

    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly loadError = signal('The scheduler routes did not answer.');
    readonly view = signal<SchedulerView | null>(null);
    readonly canOperate = computed(() => this.lens.canOperateRuns());

    /** Pipelines with anything executing or queued, for the live-occupancy list. */
    readonly busyPipelines = computed(() => {
        const pipelines = this.view()?.live?.pipelines ?? {};
        return Object.entries(pipelines)
            .map(([name, p]) => ({ name, ...p }))
            .filter((p) => p.in_flight > 0 || p.waiting > 0);
    });

    readonly form = this.fb.nonNullable.group({
        system: [0, [Validators.required, Validators.min(0), Validators.max(100_000)]],
    });
    readonly spaceForm = this.fb.nonNullable.group({
        space: [0, [Validators.required, Validators.min(0), Validators.max(100_000)]],
    });

    ngOnInit(): void {
        this.refresh();
    }

    saveSystem(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        this.saving.set(true);
        this.api.saveSystem(this.form.getRawValue().system).subscribe({
            next: (v) => {
                this.saving.set(false);
                this.apply(v);
                this.toastr.success('Server-wide cap applied to the running scheduler.');
            },
            error: (err) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(err, 'Saving the server-wide cap failed.'));
            },
        });
    }

    saveSpace(): void {
        if (this.spaceForm.invalid) {
            this.spaceForm.markAllAsTouched();
            return;
        }
        this.saving.set(true);
        this.api.saveSpace(this.spaceForm.getRawValue().space).subscribe({
            next: () => {
                this.saving.set(false);
                this.refresh();
                this.toastr.success('Space cap applied to the running scheduler.');
            },
            error: (err) => {
                this.saving.set(false);
                this.toastr.error(apiErrorMessage(err, 'Saving the space cap failed.'));
            },
        });
    }

    private refresh(): void {
        this.api.view().subscribe({
            next: (v) => {
                this.loading.set(false);
                this.apply(v);
            },
            error: (err) => {
                this.loading.set(false);
                this.view.set(null);
                this.loadError.set(apiErrorMessage(err, 'The scheduler routes did not answer.'));
            },
        });
    }

    private apply(v: SchedulerView): void {
        this.view.set(v);
        this.form.patchValue({ system: v.system.maxConcurrentConsignments });
        this.spaceForm.patchValue({ space: v.space.maxConcurrentConsignments });
    }
}
