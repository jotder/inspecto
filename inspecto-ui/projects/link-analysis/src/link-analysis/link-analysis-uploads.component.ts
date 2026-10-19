import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { InvService, UploadRecord, UploadTarget } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { StatusBadgeComponent } from '@inspecto/core/components/status-badge.component';
import { formatBytes, uploadErrorMessage, uploadFileProblem, uploadStateView } from './upload-state';

/**
 * **Link Analysis - Upload a CSV** (LA-ANALYST-UPLOAD-1 D-UP8, the pane) over `/inv/uploads*`: pick one of the Collectors
 * that opted in (`collector.accepts_upload`), choose a CSV, and land it. The file is not parsed here or on the route: the
 * Collector's own Pipeline ingests it, so what appears below is its ingest state (waiting, ingested, quarantined) read back
 * from the server. The analyst's own uploads can be deleted while they are still waiting.
 *
 * Capability `canUploadData` only (the server enforces it; the pane asks the same question). Off the module nothing is called.
 */
@Component({
    selector: 'inspecto-link-analysis-uploads',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoOptionPickerComponent,
        InspectoSkeletonComponent,
        StatusBadgeComponent,
    ],
    host: { class: 'block' },
    template: `
        @if (features.geoLink() && canUpload()) {
            <section class="flex flex-col gap-2 text-xs" aria-label="Upload a CSV">
                <div class="flex items-center gap-1">
                    <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Upload a CSV</h3>
                    <button
                        mat-icon-button
                        class="ml-auto"
                        [disabled]="loading()"
                        (click)="refresh()"
                        matTooltip="Re-read the uploads"
                        aria-label="Refresh the uploads"
                    >
                        <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-path"></mat-icon>
                    </button>
                </div>
                @if (error()) {
                    <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Upload">{{ error() }}</inspecto-alert>
                }
                @if (notice()) {
                    <inspecto-alert variant="success" title="Upload">{{ notice() }}</inspecto-alert>
                }
                @if (targets(); as ts) {
                    @if (!ts.length) {
                        <inspecto-empty-state
                            title="No Collector accepts uploads"
                            message="A Space administrator turns uploads on for a local Collector in its Pipeline (Accepts analyst upload). Until then there is nowhere to land a file."
                        ></inspecto-empty-state>
                    } @else {
                        <form class="flex flex-col gap-1" [formGroup]="form" (ngSubmit)="upload()" aria-label="Upload a CSV file">
                            <inspecto-option-picker
                                formControlName="collector"
                                label="Collector"
                                [options]="collectorOptions()"
                            ></inspecto-option-picker>
                            @if (target(); as t) {
                                <p class="text-secondary m-0">
                                    CSV only, UTF-8, up to {{ maxLabel() }}. File names this Collector picks up:
                                    <span class="font-mono">{{ t.includes.join(', ') }}</span>
                                </p>
                            }
                            <div class="flex items-center gap-2">
                                <button mat-stroked-button type="button" [disabled]="busy()" (click)="picker.click()">
                                    Choose a CSV file
                                </button>
                                <input
                                    #picker
                                    type="file"
                                    class="hidden"
                                    accept=".csv,text/csv"
                                    aria-label="CSV file to upload"
                                    (change)="chosen($event)"
                                />
                                <span class="min-w-0 flex-1 break-all" data-testid="la-upload-file">{{
                                    file() ? file()!.name + ' (' + sizeLabel() + ')' : 'No file chosen'
                                }}</span>
                            </div>
                            @if (problem()) {
                                <p class="text-warn m-0" role="alert">{{ problem() }}</p>
                            }
                            <div class="flex items-center gap-2">
                                <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !file()">
                                    {{ busy() ? 'Uploading...' : 'Upload' }}
                                </button>
                                @if (canReplace()) {
                                    <button
                                        mat-stroked-button
                                        type="button"
                                        [disabled]="busy()"
                                        (click)="upload(true)"
                                        data-testid="la-upload-replace"
                                    >
                                        Replace the waiting file
                                    </button>
                                }
                            </div>
                        </form>

                        @if (uploads(); as us) {
                            @if (us.length) {
                                <ul class="m-0 flex list-none flex-col gap-1 p-0" aria-label="Your uploads">
                                    @for (u of us; track u.file) {
                                        <li class="rounded-md border px-2 py-1" style="border-color: var(--gamma-border)">
                                            <div class="flex items-center gap-2">
                                                <span class="min-w-0 flex-1 break-all font-semibold">{{ u.file }}</span>
                                                <inspecto-status-badge
                                                    [value]="view(u).tone"
                                                    [label]="view(u).label"
                                                ></inspecto-status-badge>
                                                @if (u.state === 'pending') {
                                                    <button
                                                        mat-button
                                                        color="warn"
                                                        [disabled]="busy()"
                                                        (click)="remove(u)"
                                                        [attr.aria-label]="'Delete upload ' + u.file"
                                                    >
                                                        Delete
                                                    </button>
                                                }
                                            </div>
                                            <div class="text-secondary tabular-nums">
                                                {{ size(u.bytes) }}, {{ u.lines }} lines, landed {{ u.landedAt }}
                                            </div>
                                        </li>
                                    }
                                </ul>
                            } @else {
                                <p class="text-secondary m-0">You have not uploaded a file to this Collector yet.</p>
                            }
                        } @else if (loading()) {
                            <inspecto-skeleton [lines]="3"></inspecto-skeleton>
                        }
                    }
                } @else if (loading()) {
                    <inspecto-skeleton [lines]="3"></inspecto-skeleton>
                }
            </section>
        }
    `,
})
export class LinkAnalysisUploadsComponent {
    private inv = inject(InvService);
    private lens = inject(LensService);
    private confirm = inject(InspectoConfirmService);
    private fb = inject(FormBuilder);
    readonly features = inject(LA_FEATURES);

    readonly targets = signal<UploadTarget[] | null>(null);
    readonly uploads = signal<UploadRecord[] | null>(null);
    readonly file = signal<File | null>(null);
    readonly loading = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly notice = signal('');
    readonly problem = signal('');
    readonly unavailable = signal(false);
    /** A 409 on the last upload: the same name is still waiting, so *Replace* is offered (never the default). */
    readonly canReplace = signal(false);

    readonly canUpload = computed(() => this.lens.canUploadData());
    readonly form = this.fb.nonNullable.group({ collector: [''] });
    private readonly chosenCollector = signal('');
    readonly collectorOptions = computed<PickerOption[]>(() =>
        (this.targets() ?? []).map((t) => ({ value: t.collector, label: t.collector, hint: 'Pipeline ' + t.pipeline })),
    );
    readonly target = computed(() => (this.targets() ?? []).find((t) => t.collector === this.chosenCollector()));
    readonly maxLabel = computed(() => formatBytes(this.target()?.maxBytes ?? 0));
    readonly sizeLabel = computed(() => formatBytes(this.file()?.size ?? 0));
    readonly size = formatBytes;
    readonly view = uploadStateView;

    constructor() {
        this.form.controls.collector.valueChanges.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((c) => {
            this.chosenCollector.set(c);
            this.canReplace.set(false);
            this.problem.set('');
            void this.loadUploads();
        });
        // Off the module every route 503s, and without the capability every call 403s - no call for either.
        if (this.features.geoLink() && this.canUpload()) void this.loadTargets();
    }

    async refresh(): Promise<void> {
        await this.loadUploads();
    }

    private async loadTargets(): Promise<void> {
        this.loading.set(true);
        try {
            const ts = (await firstValueFrom(this.inv.uploadTargets())).collectors;
            this.targets.set(ts);
            if (ts.length === 1) this.form.controls.collector.setValue(ts[0].collector);
        } catch (err) {
            this.targets.set(null);
            this.fail(err, 'Could not read the Collectors that accept uploads.', 'read');
        } finally {
            this.loading.set(false);
        }
    }

    private async loadUploads(): Promise<void> {
        const collector = this.chosenCollector();
        if (!collector) {
            this.uploads.set(null);
            return;
        }
        this.loading.set(true);
        try {
            this.uploads.set((await firstValueFrom(this.inv.listUploads(collector))).uploads);
        } catch (err) {
            this.uploads.set(null);
            this.fail(err, 'Could not read your uploads.', 'read');
        } finally {
            this.loading.set(false);
        }
    }

    chosen(ev: Event): void {
        const input = ev.target as HTMLInputElement;
        this.file.set(input.files?.[0] ?? null);
        this.canReplace.set(false);
        this.notice.set('');
        this.problem.set(this.file() ? uploadFileProblem(this.file(), this.target()) : '');
        input.value = '';   // choosing the same file again must fire `change`
    }

    async upload(replace = false): Promise<void> {
        const collector = this.chosenCollector();
        if (!collector) {
            this.problem.set('Choose a Collector first.');
            return;
        }
        const problem = uploadFileProblem(this.file(), this.target());
        this.problem.set(problem);
        if (problem) return;
        this.busy.set(true);
        this.error.set('');
        this.notice.set('');
        this.canReplace.set(false);
        try {
            const res = await firstValueFrom(this.inv.uploadCsv(collector, this.file()!, replace));
            this.notice.set(
                `${res.replaced ? 'Replaced' : 'Uploaded'} ${res.file} (${formatBytes(res.bytes)}). The Collector ingests it on its next poll; ` +
                    'its state shows below.',
            );
            this.file.set(null);
            await this.loadUploads();
        } catch (err) {
            this.canReplace.set((err as { status?: number } | null)?.status === 409);
            this.fail(err, 'The upload failed.', 'upload');
        } finally {
            this.busy.set(false);
        }
    }

    async remove(u: UploadRecord): Promise<void> {
        const ok = await this.confirm.confirmDestructive(
            `Delete ${u.file}? The Collector has not picked it up yet, so nothing is ingested from it.`,
            { title: 'Delete upload', confirmText: 'Delete' },
        );
        if (!ok) return;
        this.busy.set(true);
        this.error.set('');
        this.notice.set('');
        try {
            await firstValueFrom(this.inv.deleteUpload(u.collector, u.file));
            this.notice.set(`Deleted ${u.file}.`);
        } catch (err) {
            this.fail(err, 'Could not delete the upload.', 'delete');
        } finally {
            await this.loadUploads();
            this.busy.set(false);
        }
    }

    private fail(err: unknown, fallback: string, action: 'upload' | 'delete' | 'read'): void {
        const status = (err as { status?: number } | null)?.status;
        this.unavailable.set(status === 503);
        this.error.set(uploadErrorMessage(err, fallback, action));
    }
}
