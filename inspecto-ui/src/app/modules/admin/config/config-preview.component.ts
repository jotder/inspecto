import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { map, Observable } from 'rxjs';
import { apiErrorMessage, ConfigService, EnrichmentPreview, ParsingPreview, SchemaPreview } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoOptionPickerComponent } from 'app/inspecto/components/option-picker.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { InspectoStatTileComponent } from 'app/inspecto/components/stat-tile.component';
import { StatusBadgeComponent } from 'app/inspecto/components/status-badge.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { parseDraft, parseRows, PREVIEW_KINDS, PreviewKind, sampleIsText } from './config-preview';

type PreviewResult =
    | { kind: 'parsing'; r: ParsingPreview }
    | { kind: 'schema'; r: SchemaPreview }
    | { kind: 'enrichment'; r: EnrichmentPreview };

/**
 * Config preview — the stateless, scratch-only draft previews (`POST /config/preview/parsing`,
 * `/config/preview/schema`, `/enrichment/preview`) over a pasted draft + sample. Nothing is saved.
 * Hosted as the third tab of the Configuration pane, next to Author draft / Validate file.
 */
@Component({
    selector: 'app-config-preview',
    standalone: true,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoOptionPickerComponent,
        InspectoSkeletonComponent,
        InspectoStatTileComponent,
        StatusBadgeComponent,
        DataTableComponent,
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <div class="mt-6 flex flex-col gap-4">
            <inspecto-option-picker class="w-56" label="Preview" [options]="kinds" [formControl]="kindCtrl" />
            <p class="text-secondary m-0 text-sm">{{ hint() }}</p>

            <div class="grid grid-cols-1 gap-4 lg:grid-cols-2">
                <mat-form-field class="gamma-mat-dense" subscriptSizing="dynamic">
                    <mat-label>Draft config (JSON)</mat-label>
                    <textarea matInput rows="10" class="font-mono text-xs" [formControl]="draftCtrl"></textarea>
                </mat-form-field>
                <mat-form-field class="gamma-mat-dense" subscriptSizing="dynamic">
                    <mat-label>{{ sampleLabel() }}</mat-label>
                    <textarea matInput rows="10" class="font-mono text-xs" [formControl]="sampleCtrl"></textarea>
                </mat-form-field>
            </div>

            <div>
                <button mat-flat-button color="primary" [disabled]="running()" (click)="run()">Run preview</button>
            </div>

            @if (error(); as msg) {
                <inspecto-alert variant="error" title="Preview failed">{{ msg }}</inspecto-alert>
            }

            @if (running()) {
                <div aria-busy="true">
                    <inspecto-skeleton width="40%" height="1rem" />
                    <inspecto-skeleton class="mt-2" height="8rem" />
                </div>
            } @else if (result(); as res) {
                <section class="flex flex-col gap-3" aria-label="Preview result">
                    @switch (res.kind) {
                        @case ('parsing') {
                            <div class="flex flex-wrap items-center gap-4">
                                <inspecto-status-badge value="info" [label]="res.r.frontend" />
                                <inspecto-stat-tile label="Rows parsed" [value]="res.r.rowCount" />
                                <inspecto-stat-tile label="Rows rejected" [value]="res.r.rejectedRows" />
                            </div>
                        }
                        @case ('schema') {
                            <div class="flex flex-wrap items-center gap-4">
                                <inspecto-stat-tile label="Rows that cast" [value]="res.r.okCount" />
                                <inspecto-stat-tile label="Rows rejected" [value]="res.r.rejectedCount" />
                            </div>
                        }
                        @case ('enrichment') {
                            @if (res.r.truncated) {
                                <inspecto-alert variant="info">
                                    Only the first rows are shown — the result was cut.
                                </inspecto-alert>
                            }
                        }
                    }
                    <h2 class="m-0 text-sm font-semibold">{{ tableTitle() }}</h2>
                    <inspecto-data-table tier="standard" [rows]="tableRows()" noRowsTitle="No rows came back" />
                    @if (rejected().length) {
                        <h2 class="m-0 text-sm font-semibold">Rejected rows</h2>
                        <inspecto-data-table tier="mini" [rows]="rejected()" />
                    }
                </section>
            } @else if (!error()) {
                <inspecto-empty-state
                    icon="heroicons_outline:eye"
                    title="Nothing previewed yet"
                    message="Paste a draft and a sample, then run the preview. Nothing is saved."
                ></inspecto-empty-state>
            }
        </div>
    `,
})
export class ConfigPreviewComponent {
    private api = inject(ConfigService);

    readonly kinds = PREVIEW_KINDS;
    readonly kindCtrl = new FormControl<PreviewKind>('parsing', { nonNullable: true });
    readonly draftCtrl = new FormControl('{}', { nonNullable: true });
    readonly sampleCtrl = new FormControl('', { nonNullable: true });

    readonly running = signal(false);
    readonly error = signal<string | null>(null);
    readonly result = signal<PreviewResult | null>(null);

    private readonly kind = signal<PreviewKind>('parsing');
    readonly hint = computed(() => PREVIEW_KINDS.find((k) => k.value === this.kind())?.hint ?? '');
    readonly sampleLabel = computed(() => (sampleIsText(this.kind()) ? 'Sample text' : 'Sample rows (JSON array)'));

    readonly tableTitle = computed(() => (this.result()?.kind === 'schema' ? 'Mapped output' : 'Rows'));
    readonly tableRows = computed<unknown[]>(() => {
        const res = this.result();
        if (!res) return [];
        return res.kind === 'schema' ? (res.r.mappedRows ?? []) : res.r.rows;
    });
    readonly rejected = computed<unknown[]>(() => {
        const res = this.result();
        return res?.kind === 'schema' ? res.r.rejectedRows : [];
    });

    constructor() {
        this.kindCtrl.valueChanges.subscribe((k) => {
            this.kind.set(k);
            this.result.set(null);
            this.error.set(null);
        });
    }

    run(): void {
        const kind = this.kindCtrl.value;
        const draft = parseDraft(this.draftCtrl.value);
        if ('error' in draft) return this.fail(draft.error);
        let call: Observable<PreviewResult>;
        if (kind === 'parsing') {
            const text = this.sampleCtrl.value;
            if (!text.trim()) return this.fail('Paste some sample text to parse.');
            call = this.api.previewParsing(draft.value, text).pipe(map((r) => ({ kind: 'parsing', r })));
        } else {
            const rows = parseRows(this.sampleCtrl.value);
            if ('error' in rows) return this.fail(rows.error);
            call =
                kind === 'schema'
                    ? this.api.previewSchema(draft.value, rows.value).pipe(map((r) => ({ kind: 'schema', r })))
                    : this.api.previewEnrichment(draft.value, rows.value).pipe(map((r) => ({ kind: 'enrichment', r })));
        }
        this.running.set(true);
        this.error.set(null);
        this.result.set(null);
        call.subscribe({
            next: (r) => {
                this.result.set(r);
                this.running.set(false);
            },
            error: (e) => {
                this.running.set(false);
                this.error.set(
                    e?.status === 403
                        ? 'This preview executes the draft, which needs Workbench authoring.'
                        : apiErrorMessage(e, 'The preview could not be run.'),
                );
            },
        });
    }

    private fail(msg: string): void {
        this.result.set(null);
        this.error.set(msg);
    }
}
