import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { InvService, WorkingSetRelation, WorkingSetRelationName } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { DataTableComponent } from 'app/inspecto/data-table/data-table.component';
import { investigationErrorMessage } from './investigation-state';
import { RELATION_NOUN } from './working-set-widget';

/** Rows per page — well under the route's 10 000 clamp, so a page stays a page. */
export const WORKING_SET_PAGE = 200;

/**
 * **Link Analysis — Working Set rows** (LA-20, SPA half) over `GET …/working-set`: the Working Set as a derived
 * relation — entities, links or excluded — with the provenance columns (`opSeq`, `seedId`, `hop`, `reason`),
 * paged with true offsets. The route is owner-only and evaluated from the sealed log, so it needs no Dataset read;
 * `cached` and the head step are shown quietly for whoever audits the answer.
 */
@Component({
    selector: 'inspecto-link-analysis-working-set-rows',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        MatButtonModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoOptionPickerComponent,
        DataTableComponent,
    ],
    host: { class: 'block' },
    template: `
        <section class="flex flex-col gap-2 text-xs" aria-label="Working Set rows">
            <div class="flex items-center gap-1">
                <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Working Set rows</h3>
                <button mat-button class="ml-auto" [disabled]="loading()" (click)="load()">Refresh</button>
            </div>
            <inspecto-option-picker
                label="Relation"
                [options]="relationOptions"
                [ngModel]="relation()"
                (ngModelChange)="relation.set($event)"
            ></inspecto-option-picker>
            @if (error()) {
                <inspecto-alert variant="error" title="Working Set rows">{{ error() }}</inspecto-alert>
            } @else {
                @if (page(); as p) {
                    <p class="text-secondary m-0 tabular-nums" aria-label="Relation status">
                        {{ rows().length }} of {{ p.total }} rows · at step {{ p.head.step }}
                        <span class="opacity-70">· {{ p.cached ? 'cached' : 'evaluated' }}</span>
                    </p>
                    @if (p.truncated) {
                        <p class="m-0">More rows exist than are loaded — use “Load more”.</p>
                    }
                }
                @if (rows().length) {
                    <inspecto-data-table
                        tier="standard"
                        height="20rem"
                        [rows]="rows()"
                        [loading]="loading()"
                        [serverPage]="true"
                        [hasMore]="page()?.truncated ?? false"
                        (loadMore)="loadMore()"
                        [stateKey]="'la-working-set-' + relation()"
                        [exportName]="investigationId() + '-' + relation()"
                    ></inspecto-data-table>
                } @else if (page()) {
                    <!-- An empty ag-grid fails axe aria-required-children — say so instead of drawing one. -->
                    <inspecto-empty-state
                        title="No rows in this relation"
                        message="This relation of the Working Set is empty at the current head."
                    ></inspecto-empty-state>
                }
            }
        </section>
    `,
})
export class LinkAnalysisWorkingSetRowsComponent {
    private inv = inject(InvService);

    readonly investigationId = input.required<string>();
    /** Bumped by the host whenever the log moves (an op or an undo) — a new head is a full refetch. */
    readonly version = input<unknown>(null);
    readonly relation = signal<WorkingSetRelationName>('entities');
    readonly relationOptions: PickerOption[] = (['entities', 'links', 'excluded'] as const).map((r) => ({
        value: r,
        label: RELATION_NOUN[r].title,
    }));

    readonly page = signal<WorkingSetRelation | null>(null);
    readonly rows = signal<Record<string, unknown>[]>([]);
    readonly loading = signal(false);
    readonly error = signal('');

    constructor() {
        // A relation, Investigation or head change is a full refetch from offset 0.
        effect(() => {
            this.investigationId();
            this.relation();
            this.version();
            untracked(() => void this.load());
        });
    }

    load(): Promise<void> {
        return this.fetch(0);
    }

    /** The NEXT offset page, appended — never a refetch from 0. */
    loadMore(): Promise<void> {
        return this.fetch(this.rows().length);
    }

    private async fetch(offset: number): Promise<void> {
        const id = this.investigationId();
        const of = this.relation();
        this.loading.set(true);
        this.error.set('');
        try {
            const p = await firstValueFrom(this.inv.workingSetRelation(id, { of, offset, limit: WORKING_SET_PAGE }));
            if (id !== this.investigationId() || of !== this.relation()) return; // switched meanwhile
            this.rows.set(offset === 0 ? p.rows : [...this.rows(), ...p.rows]);
            this.page.set(p);
        } catch (err) {
            this.error.set(investigationErrorMessage(err, 'Could not read the Working Set.'));
        } finally {
            this.loading.set(false);
        }
    }
}
