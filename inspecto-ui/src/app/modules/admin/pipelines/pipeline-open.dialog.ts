import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { PipelineSummary, apiErrorMessage } from 'app/inspecto/api';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { StreamTransferService } from 'app/inspecto/transfer/stream-transfer.service';

export interface PipelineOpenData {
    /** Every pipeline the server lists — names only; no graph is fetched to build this. */
    pipelines: PipelineSummary[];
    /** Already-open ids, pre-ticked so the dialog reads as "what is open" rather than "what to add". */
    open: string[];
    /** Open ids with unsaved edits — a per-row export refuses these (an export carries SAVED state). */
    dirty?: string[];
}

/**
 * Open dialog — search the pipeline list and tick one or many to open as canvas tabs.
 *
 * <p>Exists because the editor no longer loads anything on arrival: listing pipelines is cheap
 * (`GET /pipelines`, names + flags), lifting each one's graph is not, so the open set is an explicit
 * user choice. Pre-ticking what is already open makes this the one place both opening and closing
 * happen — untick-and-Open closes a tab, which is why the result is the full desired set rather than
 * a delta.
 */
@Component({
    selector: 'app-pipeline-open-dialog',
    standalone: true,
    imports: [
        MatDialogModule,
        MatButtonModule,
        MatCheckboxModule,
        MatIconModule,
        MatTooltipModule,
        InspectoDialogResizeDirective,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title inspectoDialogResize>Open pipelines</h2>
        <mat-dialog-content>
            <div class="relative mb-2">
                <mat-icon
                    class="icon-size-4 text-secondary pointer-events-none absolute left-2 top-1/2 -translate-y-1/2"
                    svgIcon="heroicons_outline:magnifying-glass"
                ></mat-icon>
                <input
                    type="text"
                    class="bg-card w-full rounded border border-gray-300 py-1.5 pl-8 pr-2 text-sm dark:border-gray-600"
                    placeholder="Search pipelines…"
                    aria-label="Search pipelines"
                    cdkFocusInitial
                    [value]="query()"
                    (input)="onSearch($event)"
                />
            </div>

            <div class="max-h-80 min-w-80 overflow-y-auto">
                @for (p of filtered(); track p.name) {
                    <div class="flex items-center gap-1 rounded px-1 hover:bg-black/5 dark:hover:bg-white/10">
                        <label class="flex min-w-0 flex-auto cursor-pointer items-center gap-2 py-1 text-sm">
                            <!-- MDC's own inner (empty) label wins the association, so the row text
                                 never names the input — give it an explicit accessible name. -->
                            <mat-checkbox
                                [checked]="picked().has(p.name)"
                                (change)="toggle(p.name)"
                                [aria-label]="'Open ' + (p.displayName || p.name)"
                            >
                            </mat-checkbox>
                            <span class="flex min-w-0 flex-auto flex-col">
                                <span class="truncate">{{ p.displayName || p.name }}</span>
                                @if (p.description) {
                                    <span class="text-secondary truncate text-xs">{{ p.description }}</span>
                                }
                            </span>
                            @if (p.template) {
                                <span class="shrink-0 text-xs opacity-60">template</span>
                            } @else if (p.active) {
                                <span class="shrink-0 text-xs" style="color: var(--gamma-primary)">active</span>
                            }
                        </label>
                        <!-- OUTSIDE the label so a click can never toggle the checkbox. Export is a READ
                             (the saved server-held config), so no author gate — a Business-lens operator
                             handing a config to support is the point. -->
                        <button
                            mat-icon-button
                            class="shrink-0"
                            [disabled]="exporting().has(p.name)"
                            (click)="exportRow(p, $event)"
                            [matTooltip]="'Export configuration'"
                            [attr.aria-label]="'Export the ' + (p.displayName || p.name) + ' configuration'"
                        >
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-down-tray"></mat-icon>
                        </button>
                    </div>
                } @empty {
                    <p class="px-1 py-3 text-sm opacity-60">
                        @if (data.pipelines.length) {
                            No pipeline matches '{{ query() }}'.
                        } @else {
                            No authored pipelines yet.
                        }
                    </p>
                }
            </div>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <!-- The guided create — navigates to Catalog onboarding rather than importing its dialog
                 (a feature may not import a feature); onboarding redirects back into the editor. -->
            <button type="button" mat-button (click)="newPipeline()">
                <mat-icon svgIcon="heroicons_outline:plus"></mat-icon>
                <span>New pipeline&hellip;</span>
            </button>
            <span class="mr-auto pl-2 text-xs opacity-60">{{ picked().size }} selected</span>
            <button type="button" mat-button mat-dialog-close>Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="confirm()">Open</button>
        </mat-dialog-actions>
    `,
})
export class PipelineOpenDialog {
    private ref = inject(MatDialogRef<PipelineOpenDialog, string[]>);
    private router = inject(Router);
    private transfer = inject(StreamTransferService);
    private toast = inject(ToastrService);
    readonly data = inject<PipelineOpenData>(MAT_DIALOG_DATA);

    readonly query = signal('');
    readonly picked = signal<ReadonlySet<string>>(new Set(this.data.open));
    /** Rows with an export in flight — disables that row's download button, nothing else. */
    readonly exporting = signal<ReadonlySet<string>>(new Set());
    private readonly dirty = new Set(this.data.dirty ?? []);

    readonly filtered = computed<PipelineSummary[]>(() => {
        const q = this.query().trim().toLowerCase();
        if (!q) return this.data.pipelines;
        return this.data.pipelines.filter(
            (p) => p.name.toLowerCase().includes(q) || (p.displayName ?? '').toLowerCase().includes(q),
        );
    });

    onSearch(e: Event): void {
        this.query.set((e.target as HTMLInputElement).value);
    }

    toggle(name: string): void {
        this.picked.update((s) => {
            const next = new Set(s);
            if (!next.delete(name)) next.add(name);
            return next;
        });
    }

    /** Close first, then hand off to the Catalog onboarding entry — its flow redirects back here. */
    newPipeline(): void {
        this.ref.close();
        this.router.navigate(['/catalog'], { queryParams: { onboard: 'stream' } });
    }

    /**
     * Export THIS row's stream-config bundle without opening it — the same
     * {@link StreamTransferService.exportPipeline} seam the editor's menu item uses, reading the
     * SERVER-held config. A row that is open with unsaved edits refuses (same rule as the editor):
     * the file would quietly disagree with that tab's screen.
     */
    exportRow(p: PipelineSummary, event: Event): void {
        // Inside the row's <label>: without this the click also toggles the open checkbox.
        event.preventDefault();
        event.stopPropagation();
        if (this.exporting().has(p.name)) return;
        if (this.dirty.has(p.name)) {
            this.toast.warning('Apply this pipeline before exporting — an export carries the saved configuration.');
            return;
        }
        this.exporting.update((s) => new Set(s).add(p.name));
        const done = (): void =>
            this.exporting.update((s) => {
                const next = new Set(s);
                next.delete(p.name);
                return next;
            });
        this.transfer.exportPipeline(p.name).subscribe({
            next: ({ bundle, missing }) => {
                done();
                this.transfer.download(bundle);
                if (missing.length) this.toast.warning(`Exported without ${missing.join(', ')} — could not be read.`);
                else this.toast.success(`Exported "${p.name}" configuration`);
            },
            error: (err) => {
                done();
                this.toast.error(apiErrorMessage(err, 'Could not export the configuration.'));
            },
        });
    }

    /** Returns the full desired open set, in the listed order so tabs are stable across re-opens. */
    confirm(): void {
        const picked = this.picked();
        this.ref.close(this.data.pipelines.filter((p) => picked.has(p.name)).map((p) => p.name));
    }
}
