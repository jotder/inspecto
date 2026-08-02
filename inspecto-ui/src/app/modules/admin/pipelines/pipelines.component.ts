import { ChangeDetectionStrategy, Component, ViewEncapsulation, computed, inject, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PipelineCombined, PipelineNode, PipelinesService, IconMap, IconMapService } from 'app/inspecto/api';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { TransferMenuComponent } from 'app/inspecto/transfer';
import { GraphViewComponent } from 'app/modules/admin/catalog/graph-view.component';
import { G6GraphData } from 'app/modules/admin/catalog/catalog-graph';
import { PipelineEditorComponent } from './pipeline-editor.component';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import {
    CATEGORY_ORDER,
    NodeTypeGroup,
    categoryColor,
    groupByCategory,
    nodeDisplayLabel,
    toCombinedG6Data,
} from './pipeline-graph';

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

    readonly mode = signal<PipelinesViewMode>('view');

    // ── topology (T24): one or many pipelines joined at their shared stores ──
    readonly iconMap = signal<IconMap>({});
    readonly nodeTypeGroups = signal<NodeTypeGroup[]>([]);
    readonly selectedNode = signal<PipelineNode | null>(null);
    readonly combined = signal<PipelineCombined | null>(null);
    readonly combinedLoading = signal(false);
    readonly combinedUnavailable = signal(false);
    /** Which pipelines are shown (empty ⇒ all) + the multiselect's search box. */
    readonly combinedSelected = signal<string[]>([]);
    readonly combinedSearch = signal('');
    /** Guards the lazy first load so re-entering Topology does not refetch. */
    private loaded = false;

    /** The topology mapped to G6 data, filtered to the chosen pipelines (empty selection ⇒ all). */
    readonly combinedG6 = computed<G6GraphData | null>(() => {
        const c = this.combined();
        if (!c) return null;
        const sel = this.combinedSelected();
        const active = sel.length ? new Set(sel) : new Set(c.flows.map((f) => f.name));
        const nodes = c.nodes.filter((n) => !n.flow || active.has(n.flow));
        const ids = new Set(nodes.map((n) => n.id));
        const edges = c.edges.filter((e) => ids.has(e.from) && ids.has(e.to));
        return toCombinedG6Data({ ...c, nodes, edges }, this.iconMap());
    });

    /** Pipeline names offered in the multiselect, filtered by its search box. */
    readonly combinedFlowOptions = computed<string[]>(() =>
        this.filterNames(this.combinedSearch(), (this.combined()?.flows ?? []).map((f) => f.name)),
    );

    /** Every pipeline in the topology as transfer references — what the export/import menu offers. */
    readonly transferItems = computed(() =>
        (this.combined()?.flows ?? []).map((f) => ({ kind: 'authored-pipeline' as const, id: f.name })),
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
                if (!this.combinedSelected().length) this.combinedSelected.set(c.flows.map((f) => f.name));
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

    onCombinedSearch(e: Event): void {
        this.combinedSearch.set((e.target as HTMLInputElement).value);
    }

    setCombinedSelected(names: string[]): void {
        this.combinedSelected.set(names);
    }

    private filterNames(query: string, names: string[]): string[] {
        const q = query.trim().toLowerCase();
        return q ? names.filter((n) => n.toLowerCase().includes(q)) : names;
    }
}
