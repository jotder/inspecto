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
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';

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
        InspectoOptionPickerComponent,
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
                            executing right now@if (view()!.live.system_free !== null) {<span
                                >, {{ view()!.live.system_free }} slot(s) free</span
                            >}.
                        </p>
                        <div class="flex flex-wrap items-start gap-3">
                            <mat-form-field class="w-60" subscriptSizing="dynamic">
                                <mat-label>Max concurrent Consignments</mat-label>
                                <input
                                    matInput
                                    type="number"
                                    formControlName="system"
                                    min="0"
                                    max="100000"
                                    [placeholder]="'' + view()!.system.maxConcurrentConsignments"
                                />
                                @if (form.controls.system.invalid) {
                                    <mat-error>0 (unbounded) to 100000; blank = inherit the launch default.</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field class="w-52" subscriptSizing="dynamic">
                                <mat-label>Intake cap (files/cycle)</mat-label>
                                <input matInput type="number" formControlName="intakeMax" min="0" max="10000000" />
                                @if (form.controls.intakeMax.invalid) {
                                    <mat-error>0 (off) or more; blank = inherit the launch default.</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field class="w-52" subscriptSizing="dynamic">
                                <mat-label>Intake cap floor</mat-label>
                                <input matInput type="number" formControlName="intakeMin" min="1" max="10000000" />
                                @if (form.controls.intakeMin.invalid) {
                                    <mat-error>At least 1; blank = inherit the launch default.</mat-error>
                                }
                            </mat-form-field>
                            <inspecto-option-picker
                                class="block w-52 py-1"
                                formControlName="intakeAdaptive"
                                label="Adaptive intake control"
                                [options]="ADAPTIVE_OPTIONS"
                            />
                            <button
                                mat-flat-button
                                color="primary"
                                type="submit"
                                class="mt-1"
                                [disabled]="!canOperate() || saving()"
                            >
                                Save server settings
                            </button>
                        </div>
                        <p class="text-secondary text-xs">
                            The intake globals govern how many inbox files one poll cycle may admit per pipeline
                            (fleet default; a pipeline's own intake settings override it). In force now:
                            {{ view()!.system.effectiveIntake?.active ? (view()!.system.effectiveIntake?.maxFilesPerCycle + ' files/cycle') : 'off (unbounded)' }},
                            floor {{ view()!.system.effectiveIntake?.minFilesPerCycle }}, adaptive
                            {{ view()!.system.effectiveIntake?.adaptive ? 'on' : 'off' }}.
                        </p>
                    </section>

                    <section class="flex flex-col gap-2">
                        <div class="flex items-center gap-3">
                            <h2 class="text-lg font-medium">Resource caps</h2>
                            <!-- The two knobs have INDEPENDENT provenance (a stored memory limit beside an
                                 inherited Run bound is a normal state), so one section chip would lie about
                                 half the section — chip each field. -->
                            <inspecto-chip variant="soft" tone="neutral"
                                >memory: {{ view()!.system.duckdbMemoryLimitSource }}</inspecto-chip
                            >
                            <inspecto-chip variant="soft" tone="neutral"
                                >runs: {{ view()!.system.maxConcurrentJobRunsSource }}</inspecto-chip
                            >
                        </div>
                        <p class="text-secondary text-sm">
                            These two work as a pair: total memory at risk is the per-instance limit times the
                            number of Runs allowed at once. Capping one without the other leaves the product
                            unbounded.
                        </p>
                        <div class="flex flex-wrap items-start gap-3">
                            <mat-form-field class="w-60" subscriptSizing="dynamic">
                                <mat-label>Memory limit per Run</mat-label>
                                <input matInput formControlName="memoryLimit" placeholder="2GB or 80%" />
                                @if (form.controls.memoryLimit.invalid) {
                                    <mat-error
                                        >A size like 2GB or 512MB, or 1–100% of host RAM like 80%; blank = inherit the
                                        launch default.</mat-error
                                    >
                                }
                            </mat-form-field>
                            <mat-form-field class="w-60" subscriptSizing="dynamic">
                                <mat-label>Max concurrent Runs</mat-label>
                                <input matInput type="number" formControlName="jobRuns" min="0" max="100000" />
                                @if (form.controls.jobRuns.invalid) {
                                    <mat-error>0 (unbounded) to 100000; blank = inherit the launch default.</mat-error>
                                }
                            </mat-form-field>
                        </div>
                        <p class="text-secondary text-xs">
                            In force now:
                            {{ view()!.system.duckdbMemoryLimit ?? 'DuckDB’s own default (~80% of RAM per Run)' }}
                            ({{ view()!.system.duckdbMemoryLimitSource }}),
                            {{ view()!.system.maxConcurrentJobRuns === 0 ? 'unbounded' : view()!.system.maxConcurrentJobRuns }}
                            concurrent Run(s) ({{ view()!.system.maxConcurrentJobRunsSource }}). A Run beyond the
                            ceiling queues; it is not rejected.
                        </p>
                        <p class="text-secondary text-xs">
                            Do not tighten the memory limit below 1GB. Below roughly that, DuckDB's grouping and
                            de-duplication steps fail outright rather than spilling to disk, which turns working
                            jobs into failing ones. A percentage resolves against this host's RAM, so check what it
                            works out to before using one on a small machine.
                        </p>
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
                            the server-wide ceiling — and the space's poll cadences. Blank cadence = inherit the launch
                            default (currently {{ view()!.space.effectivePollSeconds ?? 60 }}s poll /
                            {{ view()!.space.effectiveAcquirePollSeconds ?? 60 }}s acquire).
                        </p>
                        <div class="flex flex-wrap items-start gap-3">
                            <mat-form-field class="w-60" subscriptSizing="dynamic">
                                <mat-label>Max concurrent Consignments</mat-label>
                                <input
                                    matInput
                                    type="number"
                                    formControlName="space"
                                    min="0"
                                    max="100000"
                                    [placeholder]="'' + view()!.space.maxConcurrentConsignments"
                                />
                                @if (spaceForm.controls.space.invalid) {
                                    <mat-error>0 (unbounded) to 100000; blank = no space-tier cap.</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field class="w-52" subscriptSizing="dynamic">
                                <mat-label>Poll interval (seconds)</mat-label>
                                <input matInput type="number" formControlName="poll" min="1" max="86400" />
                                @if (spaceForm.controls.poll.invalid) {
                                    <mat-error>1 to 86400 seconds, or blank to inherit.</mat-error>
                                }
                            </mat-form-field>
                            <mat-form-field class="w-52" subscriptSizing="dynamic">
                                <mat-label>Acquire interval (seconds)</mat-label>
                                <input matInput type="number" formControlName="acquire" min="1" max="86400" />
                                @if (spaceForm.controls.acquire.invalid) {
                                    <mat-error>1 to 86400 seconds, or blank to inherit.</mat-error>
                                }
                            </mat-form-field>
                            <button
                                mat-flat-button
                                color="primary"
                                type="submit"
                                class="mt-1"
                                [disabled]="!canOperate() || saving()"
                            >
                                Save space settings
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

                @if (throttled().length) {
                    <section class="flex flex-col gap-2">
                        <h2 class="text-lg font-medium">Throttled by intake control</h2>
                        <p class="text-secondary text-sm">
                            These pipelines are admitting fewer files per cycle than their cap allows, because
                            recent runs overran the poll interval. They recover automatically once runs fit again.
                        </p>
                        <ul class="flex flex-col gap-1">
                            @for (t of throttled(); track t.pipeline) {
                                <li class="text-sm">
                                    <span class="font-mono">{{ t.pipeline }}</span>
                                    <span class="text-secondary">
                                        — admitting {{ t.cap }} of {{ t.baseCap }} files/cycle (floor
                                        {{ t.floor }})</span
                                    >
                                </li>
                            }
                        </ul>
                        @if (view()!.live.throttled.truncated) {
                            <p class="text-secondary text-xs">
                                Showing {{ throttled().length }} of {{ view()!.live.throttled.total }} throttled
                                pipelines.
                            </p>
                        }
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

    /** Pipelines the intake governor has throttled below their base cap (S8). */
    readonly throttled = computed(() => this.view()?.live?.throttled?.pipelines ?? []);

    /** Pipelines with anything executing or queued, for the live-occupancy list. */
    readonly busyPipelines = computed(() => {
        const pipelines = this.view()?.live?.pipelines ?? {};
        return Object.entries(pipelines)
            .map(([name, p]) => ({ name, ...p }))
            .filter((p) => p.in_flight > 0 || p.waiting > 0);
    });

    /** Blank-valued option = the real, named "inherit" choice (the option-picker idiom) — never a
     *  spec default, which would materialize into every save. */
    readonly ADAPTIVE_OPTIONS: PickerOption[] = [
        { value: '', label: 'Inherit launch default', hint: 'Whatever -Dingest.backpressure.adaptive says (on unless set to false).' },
        { value: 'true', label: 'On', hint: 'Cycle overrun halves a pipeline’s admission cap; a comfortable fit restores it.' },
        { value: 'false', label: 'Off (hard cap)', hint: 'The stated cap is pinned; overrun never adjusts it.' },
    ];

    readonly form = this.fb.group({
        // Nullable, like every inheritable key: blank = inherit the -D launch default (a save sends
        // null = explicit clear; the server merges per key). Seeded only from a STORED value — see apply().
        system: this.fb.control<number | null>(null, [Validators.min(0), Validators.max(100_000)]),
        // Intake globals: blank = inherit -Dingest.* (a save sends null = explicit clear; the server
        // merges per key, so this is a deliberate revert, not a wipe).
        intakeMax: this.fb.control<number | null>(null, [Validators.min(0), Validators.max(10_000_000)]),
        intakeMin: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(10_000_000)]),
        intakeAdaptive: this.fb.nonNullable.control(''),
        // The resource pair (BACKLOG D11). Blank memory limit = inherit the -D bootstrap default, then
        // DuckDB's own ~80%-of-RAM-per-instance default. ⚠ Numbers, so they don't drift: 512MB is the
        // measured FAILURE point (blocking operators HARD-FAIL with OOM instead of spilling), ~1GB the
        // advisory floor shown to the operator, 2GB the shipped default (~2.2x the highest observed
        // peak). A tighter value turns working jobs into failing ones.
        // ⚠ The shape check is NOT declared here: the grammar is the SERVER's, served in the GET as
        // `duckdbMemoryLimitPattern` and installed by applyMemoryGrammar() — two hand-mirrored regexes
        // were a drift waiting to happen, where the form accepts what the PUT 422s.
        memoryLimit: this.fb.control<string | null>(null),
        jobRuns: this.fb.control<number | null>(null, [Validators.min(0), Validators.max(100_000)]),
    });
    readonly spaceForm = this.fb.group({
        // Nullable like the cadences: blank = no space-tier cap stored (the server-wide ceiling applies).
        space: this.fb.control<number | null>(null, [Validators.min(0), Validators.max(100_000)]),
        // Cadences are nullable: blank = inherit the launch default (a save sends null, which CLEARS
        // any stored value — the server merges per key, so this is an explicit revert, not a wipe).
        poll: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(86_400)]),
        acquire: this.fb.control<number | null>(null, [Validators.min(1), Validators.max(86_400)]),
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
        const v = this.form.getRawValue();
        this.api.saveSystem(v.system ?? null, {
            maxFilesPerCycle: v.intakeMax ?? null,
            minFilesPerCycle: v.intakeMin ?? null,
            adaptive: v.intakeAdaptive === '' ? null : v.intakeAdaptive === 'true',
        }, {
            duckdbMemoryLimit: v.memoryLimit?.trim() ? v.memoryLimit.trim() : null,
            maxConcurrentJobRuns: v.jobRuns ?? null,
        }).subscribe({
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
        const v = this.spaceForm.getRawValue();
        this.api.saveSpace(v.space ?? null, v.poll ?? null, v.acquire ?? null).subscribe({
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

    /**
     * Install the memory-limit shape check from the grammar the server served, so the form refuses
     * exactly what the PUT would refuse. Blank stays valid in every case — it means "inherit the
     * launch default", and an all-whitespace box is a cleared box, not a malformed size.
     *
     * <p>Degrades on purpose: an absent pattern (older backend) or an uncompilable one leaves only the
     * blank rule, and the server's 422 — surfaced by the existing error toast — remains the gate. A
     * client-side guess would be the hand-mirroring this replaced.
     */
    private applyMemoryGrammar(pattern: string | undefined): void {
        const control = this.form.controls.memoryLimit;
        let grammar: RegExp | null = null;
        if (pattern) {
            try {
                // The served pattern is anchored and flagless by contract; the `i` flag is ours to add.
                grammar = new RegExp(pattern, 'i');
            } catch {
                grammar = null; // never break the form over a pattern we could not compile
            }
        }
        control.setValidators((c) => {
            const raw = typeof c.value === 'string' ? c.value.trim() : c.value;
            if (raw === null || raw === undefined || raw === '') return null;
            return !grammar || grammar.test(String(raw)) ? null : { pattern: true };
        });
        control.updateValueAndValidity({ emitEvent: false });
    }

    private apply(v: SchedulerView): void {
        this.view.set(v);
        this.applyMemoryGrammar(v.system.duckdbMemoryLimitPattern);
        this.form.patchValue({
            // Seed the cap only from a STORED value (source `file`) — pre-filling the effective
            // `property`/`default` value would make the next Save write it into scheduler.toon and
            // silently take ownership away from the -D flag (the provenance-seizure defect).
            system: v.system.source === 'file' ? v.system.maxConcurrentConsignments : null,
            intakeMax: v.system.intakeMaxFilesPerCycle ?? null,
            intakeMin: v.system.intakeMinFilesPerCycle ?? null,
            intakeAdaptive: v.system.intakeAdaptive == null ? '' : String(v.system.intakeAdaptive),
            // Seed the boxes only from a STORED value: showing the served `default`/`property` value as
            // if it were typed would turn the next save into an accidental write of the inherited value
            // into the file, silently taking ownership away from the -D flag.
            memoryLimit: v.system.duckdbMemoryLimitSource === 'file' ? (v.system.duckdbMemoryLimit ?? null) : null,
            jobRuns: v.system.maxConcurrentJobRunsSource === 'file' ? (v.system.maxConcurrentJobRuns ?? null) : null,
        });
        this.spaceForm.patchValue({
            space: v.space.source === 'file' ? v.space.maxConcurrentConsignments : null,
            poll: v.space.pollSeconds ?? null,
            acquire: v.space.acquirePollSeconds ?? null,
        });
    }
}
