import {
    ChangeDetectionStrategy,
    Component,
    OnInit,
    WritableSignal,
    computed,
    effect,
    inject,
    input,
    output,
    signal,
    untracked,
    viewChild,
} from '@angular/core';
import { FormArray, FormBuilder, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { QueryConditionGroupComponent } from '@inspecto/core/query/query-condition-group.component';
import { ColumnMeta, ConditionGroup, emptyGroup } from '@inspecto/core/query/query-types';
import { cloneGroup, hasConditions } from '@inspecto/link-analysis/graph/graph-filter';
import { PipelineSummary } from '@inspecto/core/api';
import { EntityProjection, GraphSource, GraphSourceId, GraphSourceQuery } from '@inspecto/core/graph';
import { DatasetRowsService } from '@inspecto/core/viz/dataset-rows.service';
import type { LaAiDraft, LaDataset } from '@inspecto/link-analysis/la-host';
import { LA_AI_ASSIST, LaHostSlotComponent } from '@inspecto/link-analysis/la-host';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisView } from './link-analysis.service';
import { ColumnLike, LinkShape, rankDatasets, suggestLinkColumns } from './la-starter';

/** `InvRoutes.MAX_MAPPINGS` — the server 422s above it; the form says so first. */
const MAX_MULTI_MAPPINGS = 16;

/** One line of the collapsed-query summary (also used by the host's canvas status bar). */
export interface QuerySummaryItem {
    icon: string;
    label: string;
    value: string;
}

/**
 * **Link Analysis — query panel** (the bottom panel's Query tab, extracted from the studio god
 * component per plan S2/B4). Owns the graph-source query form (entity-projection mappings, lineage
 * and provenance seeds) and turns it into a {@link GraphSourceQuery} via {@link buildQuery}. The host
 * keeps `sourceId` and the run/save/load lifecycle: this panel emits `run`/`edit`/`sourceIdChange` and
 * exposes `buildQuery()` + `patchFormFromView()` for the host to call (via a ViewChild).
 */
@Component({
    selector: 'inspecto-link-analysis-query-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        FormsModule,
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        LaHostSlotComponent,
        InspectoOptionPickerComponent,
        QueryConditionGroupComponent,
    ],
    templateUrl: './link-analysis-query-panel.component.html',
})
export class LinkAnalysisQueryPanelComponent implements OnInit {
    private fb = inject(FormBuilder);
    private datasetRows = inject(DatasetRowsService);
    /** DR-D2: the masking the last exploration read was served under, for the badge. */
    readonly masking = inject(InvService).masking;
    /** The host's AI assist component, rendered through `<inspecto-la-host-slot>`. */
    readonly aiAssist = inject(LA_AI_ASSIST).assist;
    readonly aiAssistOutputs = { applyDraft: (d: LaAiDraft) => this.applyProjectionDraft(d) };

    readonly sources = input<GraphSource[]>([]);
    readonly datasets = input<LaDataset[]>([]);
    readonly pipelines = input<PipelineSummary[]>([]);
    readonly sourceId = input<GraphSourceId>('entity-projection');
    /** Full form vs the collapsed selected-values summary (owned by the host). */
    readonly queryOpen = input(true);
    readonly loading = input(false);
    readonly querySummary = input<QuerySummaryItem[]>([]);
    readonly sourceLabel = input('');

    readonly run = output<void>();
    readonly edit = output<void>();
    readonly sourceIdChange = output<GraphSourceId>();

    readonly queryForm = this.fb.nonNullable.group({
        from: [''],
        depth: [2],
        direction: ['both' as 'out' | 'in' | 'both'],
        pipeline: [''],
        counts: [false],
        datasetId: [''],
        sourceCol: [''],
        targetCol: [''],
        linkKindCol: [''],
        attrCols: [[] as string[]],
        /** Only meaningful once a second mapping exists (Phase C) — see {@link EntityProjection.entityType}. */
        entityType: [''],
        /** Multi-root seeds (Phase D, lineage only): extra roots beyond `from`, comma-separated. */
        extraRoots: [''],
        /** Multi-root seeds (Phase D, provenance only): extra pipelines beyond `pipeline`. */
        extraPipelines: [[] as string[]],
    });

    /** Columns offered by the projection mapping selects — the picked Dataset's declared columns, or the
     *  ones the rows seam probes off its store. */
    readonly datasetColumns = signal<string[]>([]);
    /** The picked Dataset's first date-typed column ('' = none declared) - the default event time of an Investigation. */
    readonly datasetTimeColumn = signal('');

    /**
     * Extra entity-projection mappings beyond the primary one above (Phase C, multi-entity/multi-dataset
     * mapping): each row is its own Dataset + column mapping, merged client-side into one graph — no new
     * backend endpoint, {@code /inv/projection} runs once per row.
     */
    readonly extraMappings = this.fb.array<FormGroup>([]);
    /** Column choices per extra-mapping row, indexed like `extraMappings.controls`. */
    readonly extraMappingColumns = signal<string[][]>([]);

    /**
     * LA-08 (`entity-projection-multi`): node mappings and edge mappings over several Datasets, answered by one
     * `POST /inv/projection/multi` call. Column choices are indexed like each array's controls.
     */
    readonly nodeMappings = this.fb.array<FormGroup>([]);
    readonly edgeMappings = this.fb.array<FormGroup>([]);
    readonly nodeMappingColumns = signal<PickerOption[][]>([]);
    readonly edgeMappingColumns = signal<PickerOption[][]>([]);
    /** Each edge mapping's own `filter` tree (the condition editor mutates it in place) and the typed columns it may name. */
    readonly edgeFilters = signal<ConditionGroup[]>([]);
    readonly edgeFilterColumns = signal<ColumnMeta[][]>([]);
    /** The optional label column's choices — a blank-valued "none" first, per the picker idiom. */
    readonly nodeLabelColumnOptions = computed<PickerOption[][]>(() =>
        this.nodeMappingColumns().map((cols) => [{ value: '', label: '—' }, ...cols]),
    );

    // Picker option lists — the single-choice selects are `<inspecto-option-picker>`s over {value,label};
    // `attrCols` / `extraPipelines` stay `mat-select multiple` (the picker is single-choice).
    readonly sourceOptions = computed<PickerOption[]>(() =>
        this.sources().map((s) => ({ value: s.id, label: s.label })),
    );
    /**
     * Columns read off the store of a Dataset that declares none, by Dataset id - so the ranking below can
     * judge it too. Filled in the background (one 1-row read each, through the existing rows seam).
     */
    private readonly probedColumns = signal<ReadonlyMap<string, readonly ColumnLike[]>>(new Map());
    /** Every Dataset, those that look link-shaped first, each with a one-line reason (operator 2026-10-10). */
    readonly rankedDatasets = computed(() => rankDatasets(this.datasets(), this.probedColumns()));
    readonly datasetOptions = computed<PickerOption[]>(() =>
        this.rankedDatasets().map((d) => ({ value: d.id, label: d.name, hint: d.hint })),
    );
    /** The Source/Target pair filled in for the picked Dataset, while the form still holds it (editable). */
    readonly prefilled = signal<LinkShape | null>(null);
    /** The primary Dataset picker - the starter card's "Explore a Dataset" opens it. */
    private readonly datasetPicker = viewChild<InspectoOptionPickerComponent>('datasetPicker');
    /** Set by {@link startExplore}: the next Dataset pick that yields a full mapping also runs the query. */
    private runAfterPick = false;
    readonly pipelineOptions = computed<PickerOption[]>(() =>
        this.pipelines().map((p) => ({ value: p.name, label: p.name })),
    );
    readonly columnOptions = computed<PickerOption[]>(() => this.datasetColumns().map((c) => ({ value: c, label: c })));
    /** A blank-valued option is the real "none" choice — the picker shows its label, not the placeholder. */
    readonly optionalColumnOptions = computed<PickerOption[]>(() => [
        { value: '', label: '—' },
        ...this.columnOptions(),
    ]);
    readonly extraMappingColumnOptions = computed<PickerOption[][]>(() =>
        this.extraMappingColumns().map((cols) => cols.map((c) => ({ value: c, label: c }))),
    );
    readonly directionOptions: PickerOption[] = [
        { value: 'both', label: 'Both' },
        { value: 'out', label: 'Downstream' },
        { value: 'in', label: 'Upstream' },
    ];

    constructor() {
        effect(() => {
            const datasets = this.datasets();
            untracked(() => void this.probeUndeclared(datasets));
        });
    }

    ngOnInit(): void {
        this.queryForm.controls.datasetId.valueChanges.subscribe((id) => this.onDatasetPicked(id));
        // Editing either end of the suggested pair makes it the analyst's own: the "suggested" note goes.
        const stillSuggested = () => {
            const p = this.prefilled();
            const f = this.queryForm.controls;
            if (p && (f.sourceCol.value !== p.source || f.targetCol.value !== p.target)) this.prefilled.set(null);
        };
        this.queryForm.controls.sourceCol.valueChanges.subscribe(stillSuggested);
        this.queryForm.controls.targetCol.valueChanges.subscribe(stillSuggested);
    }

    /** At most this many Datasets are probed in the background - the picker ranks the rest by name. */
    private static readonly PROBE_LIMIT = 12;

    private async probeUndeclared(datasets: readonly LaDataset[]): Promise<void> {
        const todo = datasets
            .filter((d) => !d.columns?.length && d.sourceName && !this.probedColumns().has(d.id))
            .slice(0, LinkAnalysisQueryPanelComponent.PROBE_LIMIT);
        for (const ds of todo) {
            try {
                const cols = await this.datasetRows.columns(ds);
                this.probedColumns.update((m) => new Map(m).set(ds.id, cols));
            } catch {
                // An unreadable store just stays unranked - the picker still lists it.
            }
        }
    }

    /**
     * The starter card "Explore a Dataset": open the guided Dataset picker (ranked, with reasons). Once a Dataset
     * is picked and its Source/Target could be suggested, the query runs - one click from the card to a graph.
     */
    startExplore(): void {
        this.runAfterPick = true;
        this.datasetPicker()?.open();
    }

    private newMappingGroup() {
        return this.fb.nonNullable.group({
            datasetId: [''],
            sourceCol: [''],
            targetCol: [''],
            linkKindCol: [''],
            attrCols: [[] as string[]],
            entityType: ['', Validators.required],
        });
    }

    addMapping(): void {
        const group = this.newMappingGroup();
        const i = this.extraMappings.length;
        group.controls.datasetId.valueChanges.subscribe(async (id) => {
            const cols = await this.columnsForDataset(id);
            this.extraMappingColumns.update((all) => all.map((c, idx) => (idx === i ? cols : c)));
        });
        this.extraMappings.push(group);
        this.extraMappingColumns.update((all) => [...all, []]);
    }

    removeMapping(i: number): void {
        this.extraMappings.removeAt(i);
        this.extraMappingColumns.update((all) => all.filter((_, idx) => idx !== i));
    }

    /** Add an LA-08 node mapping row: Dataset · id column · label column? · category?. */
    addNodeMapping(): FormGroup {
        const group = this.fb.nonNullable.group({
            datasetId: [''],
            idColumn: [''],
            labelColumn: [''],
            category: [''],
            attributes: [[] as string[]],
        });
        this.pushMultiRow(this.nodeMappings, this.nodeMappingColumns, group);
        return group;
    }

    /** Add an LA-08 edge mapping row: Dataset · source column · target column · link type? · attributes? · filter?. */
    addEdgeMapping(): FormGroup {
        const group = this.fb.nonNullable.group({
            datasetId: [''],
            sourceColumn: [''],
            targetColumn: [''],
            type: [''],
            attributes: [[] as string[]],
        });
        this.pushMultiRow(this.edgeMappings, this.edgeMappingColumns, group, this.edgeFilterColumns);
        this.edgeFilters.update((all) => [...all, emptyGroup()]);
        return group;
    }

    removeNodeMapping(i: number): void {
        this.nodeMappings.removeAt(i);
        this.nodeMappingColumns.update((all) => all.filter((_, idx) => idx !== i));
    }

    removeEdgeMapping(i: number): void {
        this.edgeMappings.removeAt(i);
        this.edgeMappingColumns.update((all) => all.filter((_, idx) => idx !== i));
        this.edgeFilterColumns.update((all) => all.filter((_, idx) => idx !== i));
        this.edgeFilters.update((all) => all.filter((_, idx) => idx !== i));
    }

    /** Append a row whose Dataset pick loads its column choices — resolved by the row's CURRENT index. */
    private pushMultiRow(
        rows: FormArray<FormGroup>,
        columns: WritableSignal<PickerOption[][]>,
        group: FormGroup,
        meta?: WritableSignal<ColumnMeta[][]>,
    ): void {
        group.controls['datasetId'].valueChanges.subscribe(async (id: string) => {
            const cols = await this.columnMetaForDataset(id);
            const opts = cols.map((c) => ({ value: c.name, label: c.name }));
            const i = rows.controls.indexOf(group);
            if (i < 0) return;
            columns.update((all) => all.map((c, idx) => (idx === i ? opts : c)));
            meta?.update((all) => all.map((c, idx) => (idx === i ? cols : c)));
        });
        rows.push(group);
        columns.update((all) => [...all, []]);
        meta?.update((all) => [...all, []]);
    }

    private async onDatasetPicked(id: string): Promise<void> {
        const meta = await this.columnMetaForDataset(id);
        this.datasetColumns.set(meta.map((c) => c.name));
        this.datasetTimeColumn.set(meta.find((c) => c.type === 'date')?.name ?? '');
        const f = this.queryForm.controls;
        // Pre-fill only a blank mapping: a loaded saved view, or the analyst's own pick, is never overwritten.
        const guess = !f.sourceCol.value && !f.targetCol.value ? suggestLinkColumns(meta) : null;
        if (guess) {
            this.queryForm.patchValue({ sourceCol: guess.source, targetCol: guess.target });
            this.prefilled.set(guess);
        } else {
            this.prefilled.set(null);
        }
        const run = this.runAfterPick;
        this.runAfterPick = false;
        if (run && f.datasetId.value === id && f.sourceCol.value && f.targetCol.value) this.run.emit();
    }

    /** The columns a mapping row's Dataset select should offer — declared, else probed from the store. */
    private async columnsForDataset(id: string): Promise<string[]> {
        return (await this.columnMetaForDataset(id)).map((c) => c.name);
    }

    /** The same columns with their types — what a mapping's own `filter` tree may name. */
    private async columnMetaForDataset(id: string): Promise<ColumnMeta[]> {
        const ds = this.datasets().find((d) => d.id === id);
        return ds ? this.datasetRows.columns(ds) : [];
    }

    /** The query the current form + source amounts to (also what a saved view persists). */
    buildQuery(): GraphSourceQuery | { error: string } {
        const f = this.queryForm.getRawValue();
        switch (this.sourceId()) {
            case 'entity-projection': {
                if (!f.datasetId || !f.sourceCol || !f.targetCol) {
                    return { error: 'Pick a dataset plus its source and target columns.' };
                }
                const primary: EntityProjection = {
                    datasetId: f.datasetId,
                    sourceCol: f.sourceCol,
                    targetCol: f.targetCol,
                    linkKindCol: f.linkKindCol || undefined,
                    attrCols: f.attrCols.length ? f.attrCols : undefined,
                    entityType: f.entityType || undefined,
                };
                const extras = this.extraMappings.controls
                    .map((g) => g.getRawValue())
                    .filter((m) => m.datasetId && m.sourceCol && m.targetCol) as EntityProjection[];
                if (!extras.length) return { projection: primary };
                if (extras.some((m) => !m.entityType) || !primary.entityType) {
                    return { error: 'Every mapping needs an entity type when combining more than one.' };
                }
                return { projections: [primary, ...extras] };
            }
            case 'entity-projection-multi': {
                const nodeRows = this.nodeMappings.controls.map((g) => g.getRawValue());
                const edgeRows = this.edgeMappings.controls.map((g) => g.getRawValue());
                if (!nodeRows.length && !edgeRows.length) return { error: 'Add at least one node or edge mapping.' };
                // A half-filled row is refused, never silently dropped — a dropped row reads as "no rows there".
                if (nodeRows.some((m) => !m.datasetId || !m.idColumn))
                    return { error: 'Every node mapping needs a Dataset and an id column.' };
                if (edgeRows.some((m) => !m.datasetId || !m.sourceColumn || !m.targetColumn))
                    return { error: 'Every edge mapping needs a Dataset plus its source and target columns.' };
                if (nodeRows.length + edgeRows.length > MAX_MULTI_MAPPINGS)
                    return { error: `At most ${MAX_MULTI_MAPPINGS} mappings per query.` };
                return {
                    multi: {
                        nodes: nodeRows.map((m) => ({
                            dataset: m.datasetId,
                            idColumn: m.idColumn,
                            labelColumn: m.labelColumn || undefined,
                            category: m.category.trim() || undefined,
                            attributes: m.attributes.length ? m.attributes : undefined,
                        })),
                        edges: edgeRows.map((m, i) => {
                            const own = this.edgeFilters()[i];
                            return {
                                dataset: m.datasetId,
                                sourceColumn: m.sourceColumn,
                                targetColumn: m.targetColumn,
                                type: m.type.trim() || undefined,
                                attributes: m.attributes.length ? m.attributes : undefined,
                                filter: own && hasConditions(own) ? cloneGroup(own) : undefined,
                            };
                        }),
                    },
                };
            }
            case 'provenance': {
                if (!f.pipeline) return { error: 'Pick a pipeline.' };
                const pipelineRoots = [f.pipeline, ...f.extraPipelines.filter((p) => p && p !== f.pipeline)];
                return pipelineRoots.length > 1
                    ? { roots: pipelineRoots, counts: f.counts }
                    : { from: f.pipeline, counts: f.counts };
            }
            case 'lineage': {
                const extra = f.extraRoots
                    .split(',')
                    .map((r) => r.trim())
                    .filter(Boolean);
                const lineageRoots = [f.from, ...extra].filter((r): r is string => !!r);
                return lineageRoots.length > 1
                    ? { roots: lineageRoots, depth: f.depth, direction: f.direction }
                    : { from: f.from || undefined, depth: f.depth, direction: f.direction };
            }
            default:
                return {};
        }
    }

    /** Patch the form from a saved view's query (the host sets sourceId/display/layout separately). */
    patchFormFromView(view: LinkAnalysisView): void {
        this.patchFormFromQuery(view.query, view.sourceId === 'provenance');
    }

    /**
     * Patch the form from any graph-source query — a saved view's or an AI-drafted one.
     *
     * `projections[]` takes precedence over `projection` exactly as {@link GraphSourceQuery} declares:
     * mapping 0 is the primary, the rest rebuild the extras `FormArray`. Reading only `projection` (as
     * this did until the V2 (d) authoring pass) loads a multi-mapping view blank.
     */
    private patchFormFromQuery(query: GraphSourceQuery, provenance: boolean): void {
        const mappings = query.projections?.length ? query.projections : query.projection ? [query.projection] : [];
        const [primary, ...extras] = mappings;
        this.queryForm.patchValue({
            from: query.from ?? '',
            depth: query.depth ?? 2,
            direction: query.direction ?? 'both',
            pipeline: provenance ? (query.from ?? '') : '',
            counts: query.counts ?? false,
            datasetId: primary?.datasetId ?? '',
            sourceCol: primary?.sourceCol ?? '',
            targetCol: primary?.targetCol ?? '',
            linkKindCol: primary?.linkKindCol ?? '',
            attrCols: primary?.attrCols ?? [],
            entityType: primary?.entityType ?? '',
        });
        this.nodeMappings.clear();
        this.edgeMappings.clear();
        this.nodeMappingColumns.set([]);
        this.edgeMappingColumns.set([]);
        for (const m of query.multi?.nodes ?? []) {
            this.addNodeMapping().patchValue({
                datasetId: m.dataset,
                idColumn: m.idColumn,
                labelColumn: m.labelColumn ?? '',
                category: m.category ?? '',
                attributes: m.attributes ?? [],
            });
        }
        this.edgeFilterColumns.set([]);
        this.edgeFilters.set([]);
        (query.multi?.edges ?? []).forEach((m, i) => {
            this.addEdgeMapping().patchValue({
                datasetId: m.dataset,
                sourceColumn: m.sourceColumn,
                targetColumn: m.targetColumn,
                type: m.type ?? '',
                attributes: m.attributes ?? [],
            });
            if (m.filter)
                this.edgeFilters.update((all) => all.map((g, idx) => (idx === i ? cloneGroup(m.filter!) : g)));
        });
        this.extraMappings.clear();
        this.extraMappingColumns.set([]);
        for (const m of extras) {
            this.addMapping();
            this.extraMappings.at(this.extraMappings.length - 1).patchValue({
                datasetId: m.datasetId,
                sourceCol: m.sourceCol,
                targetCol: m.targetCol,
                linkKindCol: m.linkKindCol ?? '',
                attrCols: m.attrCols ?? [],
                entityType: m.entityType ?? '',
            });
        }
    }

    /**
     * AGT-6a A2/A3 — `projection_author`'s arguments: the picked Dataset plus the column list this panel
     * already resolved. **The pane supplies the columns deliberately**: no agent tool or tool-layer route
     * returns a Dataset's columns, and passing the list the selects are already drawn from is both
     * cheaper and more correct than a second server-side resolver.
     */
    aiProjectionArgs(): Record<string, unknown> {
        return { datasetId: this.queryForm.controls.datasetId.value, columns: this.datasetColumns() };
    }

    /**
     * The current mapping as the diff baseline — null until the mapping is complete enough to build (a
     * create, so every field reads as added). Normalized to `projections[]`, the shape the draft uses, or
     * the diff would report every field twice under two different paths.
     */
    aiCurrentProjection(): Record<string, unknown> | null {
        const built = this.buildQuery();
        if ('error' in built) return null;
        const mappings = built.projections ?? (built.projection ? [built.projection] : []);
        return mappings.length ? { query: { projections: mappings } } : null;
    }

    /**
     * Adopt a drafted mapping into the form (AGT-6a A2). It stops at the form — dirty, never saved: the
     * operator still presses Run and the host's own Save, so the human stays the actor.
     */
    applyProjectionDraft(draft: LaAiDraft): void {
        const query = draft.config['query'];
        if (typeof query !== 'object' || query === null) return;
        this.patchFormFromQuery(query as GraphSourceQuery, false);
        this.queryForm.markAsDirty();
    }
}
