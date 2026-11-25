import {
    ChangeDetectionStrategy,
    Component,
    ViewChild,
    ViewEncapsulation,
    computed,
    effect,
    inject,
    signal,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { ActivatedRoute } from '@angular/router';
import { map } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PipelineCombined, PipelineNode, PipelinesService, IconMap, IconMapService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { FormsModule } from '@angular/forms';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { GraphViewComponent } from 'app/inspecto/graph/graph-view.component';
import { G6GraphData } from 'app/inspecto/graph/catalog-graph';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { PipelineStageId } from './pipeline-stages';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import {
    CATEGORY_ORDER,
    NodeTypeGroup,
    categoryColor,
    groupByCategory,
    nodeDisplayLabel,
    toCombinedG6Data,
} from './pipeline-graph';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

/**
 * Which lens the Pipelines pane shows.
 *
 * - `view` / `editor` — the **same** {@link PipelineEditorComponent} shell; `view` passes `readOnly`.
 * - `topology` — the cross-pipeline picture: every chosen pipeline on one canvas, joined at the STORE
 *   nodes they share. Genuinely a different question ("how do these fit together?"), which is why it is
 *   its own mode rather than something the tabbed editor could express.
 */
export type PipelinesViewMode = 'view' | 'editor' | 'topology';

/**
 * Pipelines — three lenses over the same pipelines.
 *
 * <p>View and Edit are one component (see {@link PipelinesViewMode}); Topology is the store-joined
 * overview restored as its own mode. **Nothing is fetched until asked for**: the editor opens no graph
 * until the Open dialog says so, and the topology (`GET /pipelines/combined`, the expensive one) is
 * loaded lazily the first time Topology is entered — never on arrival.
 */
@Component({
    selector: 'app-pipelines',
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        AiExplainComponent,
        NgTemplateOutlet,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatMenuModule,
        MatSelectModule,
        MatTooltipModule,
        GraphViewComponent,
        InspectoEmptyStateComponent,
        InspectoOptionPickerComponent,
        FormsModule,
        PipelineEditorComponent,
        TransferMenuComponent,
    ],
    templateUrl: './pipelines.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class PipelinesComponent {
    private api = inject(PipelinesService);
    private iconMapApi = inject(IconMapService);
    private route = inject(ActivatedRoute);

    readonly mode = signal<PipelinesViewMode>('view');

    /**
     * `?guided=1` puts the editor in guided mode (the checklist strip, P6-d). It is a routing fact —
     * "the operator arrived from Onboard" — which is why it rides the URL rather than being derived
     * from the pipeline: P6-a's redirect out of `/catalog/onboard` is what will set it.
     */
    readonly guided = toSignal(this.route.queryParamMap.pipe(map((p) => p.get('guided') === '1')), {
        initialValue: false,
    });

    /**
     * `?open=<pipeline>&stage=<chip>` — the deep link the retired `/catalog/onboard/:name/:stage`
     * route redirects into (P6-a). Deliberately NOT stripped after use, unlike the `?create=1`
     * handshake: this URL is the wizard's replacement, so it has to survive a reload and a bookmark.
     */
    readonly openId = toSignal(this.route.queryParamMap.pipe(map((p) => p.get('open') ?? '')), { initialValue: '' });
    readonly focusStage = toSignal(
        this.route.queryParamMap.pipe(map((p) => (p.get('stage') ?? '') as PipelineStageId | '')),
        { initialValue: '' as PipelineStageId | '' },
    );

    /** Arriving with a pipeline to open means arriving to AUTHOR it — the wizard was never read-only. */
    private readonly editOnDeepLink = effect(() => {
        if (this.openId()) this.mode.set('editor');
    });

    // ── topology (T24): one or many pipelines joined at their shared stores ──
    readonly iconMap = signal<IconMap>({});
    readonly nodeTypeGroups = signal<NodeTypeGroup[]>([]);
    readonly selectedNode = signal<PipelineNode | null>(null);
    readonly combined = signal<PipelineCombined | null>(null);
    readonly combinedLoading = signal(false);
    readonly combinedUnavailable = signal(false);
    @ViewChild(PipelineEditorComponent) private editor?: PipelineEditorComponent;

    /** Which pipelines are shown (empty ⇒ all) + the multiselect's search box. */
    readonly combinedSelected = signal<string[]>([]);
    readonly combinedSearch = signal('');
    /** JG4: Open by Dataset — focus Topology on everything around one Dataset. */
    readonly combinedDataset = signal<string | null>(null);
    /** Guards the lazy first load so re-entering Topology does not refetch. */
    private loaded = false;

    /** The topology mapped to G6 data, filtered to the chosen pipelines (empty selection ⇒ all). */
    readonly combinedG6 = computed<G6GraphData | null>(() => {
        const c = this.combined();
        if (!c) return null;
        const sel = this.combinedSelected();
        const active = sel.length ? new Set(sel) : new Set(c.pipelines.map((p) => p.name));
        const nodes = c.nodes.filter((n) => !n.pipeline || active.has(n.pipeline));
        const ids = new Set(nodes.map((n) => n.id));
        const edges = c.edges.filter((e) => ids.has(e.from) && ids.has(e.to));
        return toCombinedG6Data({ ...c, nodes, edges }, this.iconMap());
    });

    /** Pipeline names offered in the multiselect, filtered by its search box. */
    readonly combinedPipelineOptions = computed<string[]>(() =>
        this.filterNames(
            this.combinedSearch(),
            (this.combined()?.pipelines ?? []).map((p) => p.name),
        ),
    );

    /** Dataset/store names available for JG4 "Open by Dataset" filtering. */
    readonly combinedDatasetOptions = computed<string[]>(() =>
        (this.combined()?.nodes ?? [])
            .filter((n) => n.category === 'STORE' && !!n.store)
            .map((n) => n.store!)
            .sort((a, b) => a.localeCompare(b)),
    );

    /** The same choices for the option picker, "All datasets" (no filter) first. */
    readonly combinedDatasetPickerOptions = computed<PickerOption[]>(() => [
        { value: '', label: 'All datasets' },
        ...this.combinedDatasetOptions().map((ds) => ({ value: ds, label: ds })),
    ]);

    /** Every pipeline in the topology as transfer references — what the export/import menu offers. */
    readonly transferItems = computed(() =>
        (this.combined()?.pipelines ?? []).map((p) => ({ kind: 'pipeline' as const, id: p.name })),
    );

    readonly nodeDisplayLabel = nodeDisplayLabel;
    readonly categoryColor = categoryColor;
    readonly legendCategories = CATEGORY_ORDER;

    /** Switch lens. Entering Topology is what triggers its (one-time) fetch. */
    setMode(m: PipelinesViewMode): void {
        if (this.mode() === m) return;
        this.mode.set(m);
        this.selectedNode.set(null);
        if (m === 'topology' && !this.loaded) this.load();
    }

    /** Load the topology + its palette legend. Called on first entry to Topology, and by Refresh. */
    load(): void {
        this.loaded = true;
        // The legend degrades independently — a failed catalog fetch must not blank the page.
        this.api.nodeTypes().subscribe({
            next: (ts) => this.nodeTypeGroups.set(groupByCategory(ts)),
            error: () => this.nodeTypeGroups.set([]),
        });
        this.iconMapApi.get().subscribe({
            next: (m) => this.iconMap.set(m),
            error: () => this.iconMap.set({}),
        });
        this.loadCombined();
    }

    loadCombined(): void {
        this.combinedLoading.set(true);
        this.combinedUnavailable.set(false);
        this.api.combined().subscribe({
            next: (c) => {
                this.combined.set(c);
                if (!this.combinedSelected().length) this.combinedSelected.set(c.pipelines.map((p) => p.name));
                this.combinedLoading.set(false);
            },
            error: () => {
                this.combined.set(null);
                this.combinedLoading.set(false);
                this.combinedUnavailable.set(true);
            },
        });
    }

    onNodeClick(id: string): void {
        this.selectedNode.set(this.combined()?.nodes.find((n) => n.id === id) ?? null);
    }

    /** JG4: double-click a Pipeline node in Topology to open it in the Editor. */
    onTopologyNodeDblClick(id: string): void {
        const node = this.combined()?.nodes.find((n) => n.id === id);
        if (!node?.pipeline) return;
        this.mode.set('editor');
        this.editor?.openPipelineById(node.pipeline);
    }

    /** JG4: filter Topology to everything around a chosen Dataset (producer, data/reference consumers, outputs). */
    setCombinedDataset(ds: string | null): void {
        this.combinedDataset.set(ds || null);
        const c = this.combined();
        if (!c) return;
        if (!ds) {
            this.combinedSelected.set(c.pipelines.map((p) => p.name));
            return;
        }
        const storeNodeId = `store:${ds}`;
        const nodePipeline = new Map<string, string>();
        for (const n of c.nodes) if (n.pipeline) nodePipeline.set(n.id, n.pipeline);
        const picked = new Set<string>();
        for (const e of c.edges) {
            if (e.from === storeNodeId) {
                const p = nodePipeline.get(e.to);
                if (p) picked.add(p);
            }
            if (e.to === storeNodeId) {
                const p = nodePipeline.get(e.from);
                if (p) picked.add(p);
            }
        }
        this.combinedSelected.set(picked.size ? [...picked] : c.pipelines.map((p) => p.name));
    }

    /** JG4: create a new Job Pipeline in place from a store node (`connector: dataset`). */
    createJobPipelineFromStore(store: string): void {
        this.mode.set('editor');
        this.editor?.createJobPipelineFromStore(store);
    }

    /** JG4: append a `transform.join` referencing `store` into an existing Pipeline and open it in the Editor. */
    joinStoreIntoPipeline(store: string, targetPipeline: string): void {
        this.mode.set('editor');
        this.editor?.joinStoreIntoPipeline(store, targetPipeline);
    }

    onCombinedSearch(e: Event): void {
        this.combinedSearch.set((e.target as HTMLInputElement).value);
    }

    setCombinedSelected(names: string[]): void {
        this.combinedDataset.set(null);
        this.combinedSelected.set(names);
    }

    private filterNames(query: string, names: string[]): string[] {
        const q = query.trim().toLowerCase();
        return q ? names.filter((n) => n.toLowerCase().includes(q)) : names;
    }
}
