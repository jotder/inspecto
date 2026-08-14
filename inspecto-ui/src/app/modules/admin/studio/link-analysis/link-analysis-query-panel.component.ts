import { ChangeDetectionStrategy, Component, OnInit, inject, input, output, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiDraft } from 'app/inspecto/ai-assist/ai-draft';
import { PipelineSummary } from 'app/inspecto/api';
import { EntityProjection, GraphSource, GraphSourceId, GraphSourceQuery } from 'app/inspecto/graph';
import { DatasetRowsService } from 'app/inspecto/viz/dataset-rows.service';
import { Dataset } from 'app/modules/admin/studio/datasets/dataset-types';
import { LinkAnalysisView } from './link-analysis.service';

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
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatSelectModule,
        AiAssistComponent,
    ],
    templateUrl: './link-analysis-query-panel.component.html',
})
export class LinkAnalysisQueryPanelComponent implements OnInit {
    private fb = inject(FormBuilder);
    private datasetRows = inject(DatasetRowsService);

    readonly sources = input<GraphSource[]>([]);
    readonly datasets = input<Dataset[]>([]);
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

    /**
     * Extra entity-projection mappings beyond the primary one above (Phase C, multi-entity/multi-dataset
     * mapping): each row is its own Dataset + column mapping, merged client-side into one graph — no new
     * backend endpoint, {@code /inv/projection} runs once per row.
     */
    readonly extraMappings = this.fb.array<FormGroup>([]);
    /** Column choices per extra-mapping row, indexed like `extraMappings.controls`. */
    readonly extraMappingColumns = signal<string[][]>([]);

    ngOnInit(): void {
        this.queryForm.controls.datasetId.valueChanges.subscribe((id) => this.onDatasetPicked(id));
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

    private async onDatasetPicked(id: string): Promise<void> {
        this.datasetColumns.set(await this.columnsForDataset(id));
    }

    /** The columns a mapping row's Dataset select should offer — declared, else probed from the store. */
    private async columnsForDataset(id: string): Promise<string[]> {
        const ds = this.datasets().find((d) => d.id === id);
        if (!ds) return [];
        return (await this.datasetRows.columns(ds)).map((c) => c.name);
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
    applyProjectionDraft(draft: AiDraft): void {
        const query = draft.config['query'];
        if (typeof query !== 'object' || query === null) return;
        this.patchFormFromQuery(query as GraphSourceQuery, false);
        this.queryForm.markAsDirty();
    }
}
