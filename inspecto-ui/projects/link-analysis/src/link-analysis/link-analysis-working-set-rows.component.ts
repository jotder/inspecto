import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { InvService, WorkingSetRelation, WorkingSetRelationName } from '@inspecto/link-analysis/api/inv.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { DataTableComponent } from '@inspecto/core/data-table/data-table.component';
import { investigationErrorMessage } from './investigation-state';
import { hubBadgeText } from './investigate-number';
import { InvestigationSessionStore } from './link-analysis-investigation.store';
import { LinkAnalysisSettingsService } from './link-analysis-settings.service';
import { RELATION_NOUN } from './working-set-widget';

/** Rows per page — well under the route's 10 000 clamp, so a page stays a page. */
export const WORKING_SET_PAGE = 200;

/**
 * An entities row's `highConnectivity` flag in words — a bare `true`/`false` column says nothing about what the flag
 * means (shown, not expanded further); the row's own sealed `contacts` is named when served. Unflagged rows read blank; rows of other relations pass unchanged.
 */
export function hubRow(
    r: Record<string, unknown>,
    badge: (id: string, contacts: number | null) => string = (_id, contacts) => hubBadgeText(null, contacts),
): Record<string, unknown> {
    if (!('highConnectivity' in r)) return r;
    const contacts = typeof r['contacts'] === 'number' ? r['contacts'] : null;
    const id = typeof r['entityId'] === 'string' ? r['entityId'] : '';
    return { ...r, highConnectivity: r['highConnectivity'] === true ? badge(id, contacts) : '' };
}

/**
 * An entities row's `expanded` flag in words (LA-UNEXPANDED-MARKER-1): `Yes` once an effective expand followed its links,
 * else `Not yet`. Rows of other relations, and rows from a server that does not send the flag, pass unchanged.
 */
export function expandedRow(r: Record<string, unknown>): Record<string, unknown> {
    if (!('expanded' in r)) return r;
    return { ...r, expanded: r['expanded'] === true ? 'Yes' : 'Not yet' };
}

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
    /** The open Investigation's store, when hosted under the canvas: the badge then names threshold + override. */
    private store = inject(InvestigationSessionStore, { optional: true });
    private laSettings = inject(LinkAnalysisSettingsService);

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
            const store = this.store;
            const threshold = this.laSettings.limits().hubThresholdInForce;
            const rows = p.rows.map((r) =>
                expandedRow(store ? hubRow(r, (eid, c) => store.hubBadge(eid, c, threshold)) : hubRow(r)),
            );
            this.rows.set(offset === 0 ? rows : [...this.rows(), ...rows]);
            this.page.set(p);
        } catch (err) {
            this.error.set(investigationErrorMessage(err, 'Could not read the Working Set.'));
        } finally {
            this.loading.set(false);
        }
    }
}
