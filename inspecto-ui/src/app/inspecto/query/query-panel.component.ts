import { AfterViewInit, ChangeDetectionStrategy, Component, EventEmitter, Input, Output, ViewChild, computed, signal } from '@angular/core';
import { DataTableComponent } from 'app/inspecto/data-table';
import { QueryChange, QueryModel, QuerySource } from './query-types';

/**
 * Reusable **queryable table**: the Query Core builder (projection, nested AND/OR row filter, generated/
 * editable SQL, live preview) over a host-supplied {@link QuerySource}. A thin adapter over
 * `<inspecto-data-table tier="pro">` — that tier already composes the same pieces (column chooser =
 * projection, filter-builder toggle, SQL-editor toggle, preview grid); this component just forwards the
 * source/seed in and re-emits the table's `queryModelChange` as {@link QueryChange}, and opens the
 * Filter/SQL panels by default (they hide behind a toolbar toggle elsewhere, but authoring the query IS
 * the point of this surface). No save/templates here (that is the broader Rule Builder).
 */
@Component({
    selector: 'inspecto-query-panel',
    standalone: true,
    imports: [DataTableComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './query-panel.component.html',
})
export class QueryPanelComponent implements AfterViewInit {
    @ViewChild(DataTableComponent) private table?: DataTableComponent;

    private readonly _source = signal<QuerySource>({ name: 'data', rows: [] });

    /** A previously-saved model to seed the builder from (e.g. re-opening a saved query for edit). */
    @Input() initialModel: QueryModel | null = null;

    /** The data to query. */
    @Input({ required: true }) set source(s: QuerySource) {
        this._source.set(s);
    }

    @Output() queryChange = new EventEmitter<QueryChange>();

    readonly rows = computed(() => this._source().rows);
    readonly columnMeta = computed(() => this._source().columns);
    readonly sourceName = computed(() => this._source().name);

    /** Unlike a bare Pro data-table, the Filter/SQL panels start open — building them IS what this
     *  surface is for, so hiding them behind the toolbar's icon toggles would just look empty. */
    ngAfterViewInit(): void {
        this.table?.toggleSql();
        this.table?.toggleFilter();
    }
}
