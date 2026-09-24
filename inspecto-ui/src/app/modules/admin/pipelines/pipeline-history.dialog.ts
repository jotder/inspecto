import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { PipelineHistoryDiff, PipelineHistoryVersion, PipelinesService } from 'app/inspecto/api/pipelines.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoDialogResizeDirective } from 'app/inspecto/components/dialog-resize.directive';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';

export interface PipelineHistoryData {
    id: string;
}

/** What the selected version is compared with. */
export type HistoryCompare = 'previous' | 'current';

/**
 * `PIPELINE-CONFIG-HISTORY-1`: the saved versions of one Pipeline's config, newest first, and a READ-ONLY
 * line diff of the selected one — against the version before it, or against the config as it is now.
 * The server keeps a version per successful save (the newest 50); nothing here writes.
 */
@Component({
    selector: 'app-pipeline-history-dialog',
    standalone: true,
    imports: [
        DatePipe,
        MatDialogModule,
        MatButtonModule,
        MatButtonToggleModule,
        InspectoAlertComponent,
        InspectoDialogResizeDirective,
        InspectoEmptyStateComponent,
        InspectoSkeletonComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title inspectoDialogResize>Config history — {{ data.id }}</h2>
        <mat-dialog-content>
            @if (error()) {
                <inspecto-alert variant="error" title="Could not load the history">{{ error() }}</inspecto-alert>
            } @else if (versions() === null) {
                <inspecto-skeleton [lines]="4" />
            } @else if (versions()!.length === 0) {
                <inspecto-empty-state
                    icon="heroicons_outline:clock"
                    message="No saved versions yet. A version is recorded each time this Pipeline is saved."
                />
            } @else {
                <div class="flex min-h-0 gap-4">
                    <ul class="w-48 shrink-0 overflow-auto" aria-label="Saved versions">
                        @for (v of versions(); track v.version) {
                            <li>
                                <button
                                    type="button"
                                    class="version w-full rounded px-2 py-1 text-left"
                                    [class.selected]="v.version === selected()"
                                    [attr.aria-pressed]="v.version === selected()"
                                    (click)="select(v.version)"
                                >
                                    <span class="font-medium">v{{ v.version }}</span>
                                    <span class="text-secondary block text-xs">{{ v.savedAt | date: 'medium' }}</span>
                                </button>
                            </li>
                        }
                    </ul>
                    <div class="flex min-w-0 flex-1 flex-col gap-2">
                        <mat-button-toggle-group
                            aria-label="Compare the selected version with"
                            [value]="compare()"
                            (change)="setCompare($event.value)"
                        >
                            <mat-button-toggle value="previous" [disabled]="previousOf(selected()) === null">
                                Previous version
                            </mat-button-toggle>
                            <mat-button-toggle value="current">Current config</mat-button-toggle>
                        </mat-button-toggle-group>
                        @if (diffError()) {
                            <inspecto-alert variant="error" title="Could not load the diff">{{
                                diffError()
                            }}</inspecto-alert>
                        } @else if (diff(); as d) {
                            <p class="text-secondary text-sm" aria-live="polite">{{ summary() }}</p>
                            <div
                                class="diff overflow-auto rounded border font-mono text-xs"
                                role="region"
                                aria-label="Diff"
                            >
                                @for (l of d.lines; track $index) {
                                    @switch (l.op) {
                                        @case ('remove') {
                                            <del class="line block text-red-700 no-underline dark:text-red-400"
                                                ><span aria-hidden="true">- </span>{{ l.text }}</del
                                            >
                                        }
                                        @case ('add') {
                                            <ins class="line block text-green-700 no-underline dark:text-green-400"
                                                ><span aria-hidden="true">+ </span>{{ l.text }}</ins
                                            >
                                        }
                                        @default {
                                            <span class="line text-secondary block"
                                                ><span aria-hidden="true">&nbsp; </span>{{ l.text }}</span
                                            >
                                        }
                                    }
                                }
                            </div>
                        } @else {
                            <inspecto-skeleton [lines]="6" />
                        }
                    </div>
                </div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
    styles: [
        `
            .version:hover {
                background: var(--gamma-bg-hover);
            }
            .version.selected {
                background: var(--gamma-bg-hover);
                color: var(--gamma-primary);
            }
            .version:focus-visible {
                outline: 2px solid var(--gamma-primary);
                outline-offset: 1px;
            }
            .diff {
                border-color: var(--gamma-border);
                max-height: 60vh;
            }
            .line {
                white-space: pre;
                padding: 0 0.5rem;
            }
        `,
    ],
})
export class PipelineHistoryDialog implements OnInit {
    readonly data = inject<PipelineHistoryData>(MAT_DIALOG_DATA);
    private readonly api = inject(PipelinesService);

    /** `null` while loading. */
    readonly versions = signal<PipelineHistoryVersion[] | null>(null);
    readonly error = signal<string | null>(null);
    readonly selected = signal<number | null>(null);
    readonly compare = signal<HistoryCompare>('previous');
    readonly diff = signal<PipelineHistoryDiff | null>(null);
    readonly diffError = signal<string | null>(null);

    readonly summary = computed(() => {
        const d = this.diff();
        if (!d) return '';
        if (d.added === 0 && d.removed === 0) return 'No differences.';
        const against = d.to === 'current' ? 'the current config' : `v${d.to}`;
        return `v${d.from} → ${against}: ${d.added} line(s) added, ${d.removed} removed.`;
    });

    ngOnInit(): void {
        this.api.history(this.data.id).subscribe({
            next: (h) => {
                this.versions.set(h.versions);
                if (h.versions.length) this.select(h.versions[0].version);
            },
            error: (err) => this.error.set(apiErrorMessage(err, 'The history could not be read')),
        });
    }

    /** The kept version just before `version`, or `null` when it is the oldest kept one. */
    previousOf(version: number | null): number | null {
        const list = this.versions() ?? [];
        const i = list.findIndex((v) => v.version === version);
        return i >= 0 && i + 1 < list.length ? list[i + 1].version : null;
    }

    select(version: number): void {
        this.selected.set(version);
        if (this.compare() === 'previous' && this.previousOf(version) === null) this.compare.set('current');
        this.loadDiff();
    }

    setCompare(mode: HistoryCompare): void {
        this.compare.set(mode);
        this.loadDiff();
    }

    private loadDiff(): void {
        const v = this.selected();
        if (v === null) return;
        const prev = this.previousOf(v);
        const call =
            this.compare() === 'previous' && prev !== null
                ? this.api.historyDiff(this.data.id, prev, v)
                : this.api.historyDiff(this.data.id, v);
        this.diff.set(null);
        this.diffError.set(null);
        call.subscribe({
            next: (d) => this.diff.set(d),
            error: (err) => this.diffError.set(apiErrorMessage(err, 'The diff could not be computed')),
        });
    }
}
