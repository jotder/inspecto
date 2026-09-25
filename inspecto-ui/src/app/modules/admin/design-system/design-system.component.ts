import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AgGridAngular } from 'ag-grid-angular';
import { ColDef, GridApi } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';

import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { DefinitionDrawerComponent } from 'app/inspecto/components/definition-drawer.component';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoStatTileComponent } from 'app/inspecto/components/stat-tile.component';
import { InspectoSectionTabsComponent, SectionTab } from 'app/inspecto/components/section-tabs.component';
import { BulkAction, InspectoBulkActionsComponent } from 'app/inspecto/components/bulk-actions.component';
import { FilterField, FilterValues, InspectoFilterBarComponent } from 'app/inspecto/components/filter-bar.component';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { AttributeSpec, AttributeToken } from 'app/inspecto/component-model';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { statusBadgeHtml, StatusBadgeComponent, StatusTone } from 'app/inspecto/components/status-badge.component';
import {
    actionsColumn,
    INSPECTO_DEFAULT_COL_DEF,
    InspectoGridThemeService,
    noRowsOverlay,
    refreshActionsCells,
} from 'app/inspecto/grid';
import { QuerySource } from 'app/inspecto/query';
import { DataTableComponent, DataTableTier } from 'app/inspecto/data-table';
import { TreeTableComponent, TreeNode, varianceCell } from 'app/inspecto/tree-table';
import { GeoData, MapViewComponent } from 'app/inspecto/geo';
import { KpiComponent } from 'app/inspecto/viz/plugins/kpi.component';
import { KpiTrendComponent } from 'app/inspecto/viz/plugins/kpi-trend.component';
import { ProgressListComponent } from 'app/inspecto/viz/plugins/progress-list.component';
import { TreemapComponent } from 'app/inspecto/viz/plugins/treemap.component';
import { TreemapRow } from 'app/inspecto/viz/treemap-layout';
// More visualization types — heatmap
import { HeatmapComponent, HeatmapData } from 'app/inspecto/viz/plugins/heatmap.component';
// Dashboard tile section
import { InspectoTileCardComponent } from 'app/inspecto/components/tile-card.component';
import { CHART_CATEGORICAL } from 'app/inspecto/theme/chart-tokens';
import {
    InspectoSchemaFieldsEditorComponent,
    InspectoSchemaMetadataGridComponent,
    SchemaFieldRow,
} from 'app/inspecto/schema';
import { ResizeDemoDialog } from './resize-demo.dialog';
import { ChartData, ChartOptions } from 'chart.js';
import { InspectoChartComponent } from 'app/inspecto/components/chart.component';
import { seriesColors } from 'app/inspecto/viz/series-colors';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { DesignSystemFoundationsComponent } from './foundations.component';
// More visualization types: Waterfall + Combo
import { VizRenderComponent } from 'app/inspecto/viz/viz-render.component';
import { VizProps, VizRenderOptions } from 'app/inspecto/viz/viz-types';
import { COMBO_PLUGIN, WATERFALL_PLUGIN } from 'app/inspecto/viz/plugins';

interface DemoRow {
    pipeline: string;
    status: string;
    files: number;
}

/**
 * Living design-system gallery (UI/UX audit — Long-term #1b). A dev/reference page that renders
 * each shared Inspecto pattern with a live example and a copy-paste snippet, so new panes reuse the
 * canonical components instead of re-rolling status colors, empty states, skeletons, grids or forms.
 * Because it imports and renders the real components, it can't drift from them. Route: `/design`.
 */
@Component({
    selector: 'inspecto-design-system',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatButtonToggleModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        AgGridAngular,
        StatusBadgeComponent,
        InspectoAlertComponent,
        DefinitionDrawerComponent,
        ChipComponent,
        InspectoEmptyStateComponent,
        InspectoPageHeaderComponent,
        InspectoStatTileComponent,
        KpiComponent,
        KpiTrendComponent,
        ProgressListComponent,
        TreemapComponent,
        InspectoTileCardComponent,
        InspectoChartComponent,
        InspectoSectionTabsComponent,
        InspectoBulkActionsComponent,
        InspectoFilterBarComponent,
        InspectoSchemaFormComponent,
        InspectoSkeletonComponent,
        AiAssistComponent,
        AiExplainComponent,
        DataTableComponent,
        TreeTableComponent,
        MapViewComponent,
        InspectoSchemaFieldsEditorComponent,
        InspectoSchemaMetadataGridComponent,
        DesignSystemFoundationsComponent,
        VizRenderComponent,
        HeatmapComponent,
    ],
    templateUrl: './design-system.component.html',
})
export class DesignSystemComponent {
    private fb = inject(FormBuilder);
    private toast = inject(ToastrService);
    private dialog = inject(MatDialog);
    readonly themeSvc = inject(InspectoGridThemeService);

    // ── Page chrome (UI consolidation plan, 2026-09-22) ──────────────────────────────────────
    readonly demoTabs: SectionTab[] = [
        { id: 'grammar', label: 'Grammar', count: 3 },
        { id: 'schema', label: 'Schema', count: 0 },
        { id: 'sink', label: 'Sink' },
    ];
    readonly demoTab = signal('grammar');
    readonly demoBulkCount = signal(0);
    readonly demoBulkActions: BulkAction[] = [
        { id: 'accept', label: 'Accept', icon: 'heroicons_outline:check' },
        { id: 'tag', label: 'Tag', icon: 'heroicons_outline:tag' },
        { id: 'archive', label: 'Archive', icon: 'heroicons_outline:archive-box', destructive: true },
    ];
    readonly demoFilterFields: FilterField[] = [
        {
            key: 'level',
            label: 'Min level',
            type: 'select',
            defaultValue: '',
            width: 'w-36',
            options: [
                { value: '', label: 'All' },
                { value: 'WARN', label: 'WARN' },
                { value: 'ERROR', label: 'ERROR' },
            ],
        },
        { key: 'pipeline', label: 'Pipeline', type: 'text', placeholder: 'exact name', width: 'w-44' },
        { key: 'limit', label: 'Limit', type: 'number', defaultValue: 100, width: 'w-28' },
    ];
    readonly demoFilterValue = signal<FilterValues>({ level: 'WARN', pipeline: '', limit: 100 });

    // ── Status badges ────────────────────────────────────────────────────────────────────────
    readonly tones: StatusTone[] = ['error', 'warning', 'info', 'success', 'neutral'];
    /** A few real tokens to show the case-insensitive token → tone classification. */
    readonly tokenExamples = ['FAILED', 'PAUSED', 'PENDING', 'HEALTHY', 'QUARANTINED', 'UNKNOWN'];

    // ── Grid / table ─────────────────────────────────────────────────────────────────────────
    readonly defaultColDef: ColDef = INSPECTO_DEFAULT_COL_DEF;
    readonly columnDefs: ColDef<DemoRow>[] = [
        { field: 'pipeline', headerName: 'Pipeline', flex: 1 },
        {
            field: 'status',
            headerName: 'Status',
            width: 140,
            // Sanctioned status-color path for a cell renderer — the shared builder, no hand-rolled colors.
            cellRenderer: (p: { value: string }) => statusBadgeHtml(p.value),
        },
        { field: 'files', headerName: 'Files', width: 110 },
        actionsColumn<DemoRow>([
            {
                icon: 'heroicons_outline:eye',
                hint: 'View (demo)',
                onClick: (r) => this.toast.info(`View ${r.pipeline}`),
            },
        ]),
    ];
    readonly fullRows: DemoRow[] = [
        { pipeline: 'orders-daily', status: 'HEALTHY', files: 128 },
        { pipeline: 'inventory-sync', status: 'PAUSED', files: 0 },
        { pipeline: 'returns-feed', status: 'FAILED', files: 3 },
    ];
    readonly emptyOverlay = noRowsOverlay('No data to display', 'This is the shared empty-grid overlay.');
    showEmpty = false;
    get gridRows(): DemoRow[] {
        return this.showEmpty ? [] : this.fullRows;
    }
    refreshActions(e: { api: GridApi }): void {
        refreshActionsCells(e);
        // Static rowData renders synchronously, hitting the same ag-Grid + Angular 21 initial-render
        // skip that affects the actions column — force the string-renderer status cells too.
        setTimeout(() => {
            if (e.api.isDestroyed()) return;
            e.api.refreshCells({ force: true, columns: ['status'] });
        });
    }

    // ── Tree table (aligned hierarchy + multi-entry comparison / variance) ───────────────────
    readonly treeColumns: ColDef[] = [
        { field: 'e1', headerName: 'Entry 1', width: 130 },
        { field: 'e2', headerName: 'Entry 2', width: 130 },
        { field: 'delta', headerName: 'Δ', width: 120, cellRenderer: varianceCell() },
    ];
    readonly treeNodes: TreeNode[] = [
        {
            id: 'north',
            label: 'Region North',
            icon: 'heroicons_outline:globe-americas',
            values: { e1: 100, e2: 120, delta: 20 },
            children: [
                { id: 'north/a', label: 'Product A', values: { e1: 40, e2: 45, delta: 5 } },
                { id: 'north/b', label: 'Product B', values: { e1: 60, e2: 75, delta: 15 } },
            ],
        },
        {
            id: 'south',
            label: 'Region South',
            icon: 'heroicons_outline:globe-americas',
            values: { e1: 200, e2: 190, delta: -10 },
            children: [
                { id: 'south/a', label: 'Product A', values: { e1: 120, e2: 110, delta: -10 } },
                { id: 'south/b', label: 'Product B', values: { e1: 80, e2: 80, delta: 0 } },
            ],
        },
    ];

    // ── Reactive form + inline mat-error ─────────────────────────────────────────────────────
    readonly form = this.fb.group({
        id: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
    });
    submitForm(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            this.toast.warning('Fix the highlighted field.');
            return;
        }
        this.toast.success(`Valid id: ${this.form.value.id}`);
    }

    // ── Schema-driven form (AttributeSpec → 3-tier disclosure) ───────────────────────────────
    readonly schemaFormSpecs: AttributeSpec[] = [
        { key: 'name', label: 'Source id', type: 'identifier', tier: 'required', placeholder: 'e.g. cdr_sftp' },
        {
            key: 'protocol',
            label: 'Protocol',
            type: 'select',
            tier: 'required',
            default: 'sftp',
            options: [
                { value: 'sftp', label: 'SFTP' },
                { value: 'ftps', label: 'FTPS' },
                { value: 'local', label: 'Local directory' },
            ],
        },
        {
            key: 'host',
            label: 'Host',
            type: 'string',
            tier: 'required',
            dependsOn: { key: 'protocol', equals: 'sftp' },
            help: 'Shown only while protocol = SFTP.',
        },
        { key: 'include', label: 'Include pattern', type: 'string', tier: 'optional', placeholder: 'glob:**/*.csv' },
        {
            key: 'as_of',
            label: 'As of date',
            type: 'string',
            tier: 'required',
            // 2026-09-17: was '\d{4}-…' in a plain string, where `\d` is just `d` — the showcase pattern rejected
            // its own placeholder. Found by no-useless-escape; the schema-form applies `pattern` as a RegExp source.
            pattern: '\\d{4}-\\d{2}-\\d{2}',
            placeholder: '2026-08-10',
            help: 'Has a token picker — a token replaces the whole value.',
        },
        {
            key: 'parallel_fetch',
            label: 'Parallel fetch',
            type: 'number',
            tier: 'advanced',
            default: 4,
            min: 1,
            max: 32,
        },
    ];

    /**
     * Whole-value tokens for the demo's `as_of` field, keyed by attribute key exactly as a host supplies
     * them. The HOST filters — the renderer is told what to offer, never how to decide.
     */
    readonly schemaFormTokens: Record<string, AttributeToken[]> = {
        as_of: [
            { token: '$today', description: 'The date at fire time', preview: '2026-08-10' },
            { token: '$yesterday', description: 'The day before', preview: '2026-08-09' },
        ],
    };
    /** Marks which values are tokens, so they are exempt from the field's `pattern` (a `$`-token is not a
     *  date, and holding it to the date format would make the picker unusable). Never a global RegExp. */
    readonly tokenSyntax = /^\$(?!\$)/;

    // ── Schema columns table + column metadata (delimited-grammar redesign U2/D1) ────────────
    /** One seed, two views: the columns table edits identity/type, the metadata grid annotates. */
    readonly schemaFieldRows = signal<SchemaFieldRow[]>([
        { include: true, name: 'MSISDN', selector: '0', type: 'VARCHAR', synonym: 'subscriber' },
        { include: true, name: 'DURATION', selector: '1', type: 'DOUBLE', unit: 'seconds' },
        { include: true, name: 'START_TIME', selector: '2', type: 'TIMESTAMP' },
        { include: false, name: 'RAW_NOTE', selector: '3', type: 'VARCHAR' },
    ]);
    /** §4.4 Auto mode: the icon-only type menu locks (inferred types are the sniffer's). */
    readonly schemaAutoTypes = signal(false);

    // ── Data table (tiered: mini / standard / pro / pro max) ─────────────────────────────────
    readonly dtTiers: DataTableTier[] = ['mini', 'standard', 'pro', 'proMax'];
    readonly dtTier = signal<DataTableTier>('standard');
    /** Explicit columns incl. a badge `cellRenderer` — verifies it renders on first paint AND survives the
     *  pro-tier SQL re-run (regression: badge cells used to come up empty). */
    readonly cdrColumns: ColDef[] = [
        { field: 'msisdn', headerName: 'MSISDN', flex: 1 },
        { field: 'cell_id', headerName: 'Cell', width: 130 },
        { field: 'duration_s', headerName: 'Duration (s)', width: 130 },
        {
            field: 'tariff',
            headerName: 'Tariff',
            width: 130,
            cellRenderer: (p: { value: string }) => statusBadgeHtml(p.value),
        },
        { field: 'start_time', headerName: 'Start', flex: 1 },
    ];
    readonly querySource: QuerySource = {
        name: 'cdr_sample',
        rows: Array.from({ length: 40 }, (_, i) => ({
            id: 1000 + i,
            msisdn: '8801' + String(700000000 + i),
            cell_id: 'CELL-' + (100 + (i % 8)),
            duration_s: (i * 37) % 600,
            tariff: ['standard', 'premium', 'roaming'][i % 3],
            start_time: `2026-06-${String(1 + (i % 27)).padStart(2, '0')} 0${i % 9}:${String(10 + (i % 50)).padStart(2, '0')}:00`,
        })),
    };

    /** Data-table sizing demos: a short table fits its 3 rows; an empty one is a compact empty state. */
    readonly dtShortRows = this.querySource.rows.slice(0, 3);
    readonly dtNoRows: unknown[] = [];

    // ── Map host (MapLibre GL, offline basemap) ──────────────────────────────────────────────
    readonly mapDemo: GeoData = {
        points: [
            { id: 'dhk', lat: 23.8103, lon: 90.4125, kind: 'tower', label: 'Dhaka' },
            { id: 'sin', lat: 1.3521, lon: 103.8198, kind: 'tower', label: 'Singapore' },
            { id: 'lon', lat: 51.5074, lon: -0.1278, kind: 'device', label: 'London' },
            { id: 'nyc', lat: 40.7128, lon: -74.006, kind: 'device', label: 'New York' },
        ],
        routes: [],
    };

    // ── Menu favorites (personal client-local overlay) ───────────────────────────────────────
    readonly favDemoItems = [
        { id: 'revenue_overview', label: 'Revenue dashboard', icon: 'heroicons_outline:presentation-chart-line' },
        { id: 'top_usages', label: 'Top usages', icon: 'heroicons_outline:signal' },
        { id: 'fraud_categories', label: 'Fraud categories', icon: 'heroicons_outline:chart-pie' },
    ];
    /** Demo-only favorite set (the real overlay persists to localStorage per space, never to the server). */
    readonly favIds = signal<Set<string>>(new Set(['revenue_overview']));
    isFav(id: string): boolean {
        return this.favIds().has(id);
    }
    toggleFav(id: string): void {
        this.favIds.update((s) => {
            const next = new Set(s);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    }

    // ── Resizable dialog (shared [inspectoDialogResize] chrome) ─────────────────────────────
    openResizeDemo(): void {
        this.dialog.open(ResizeDemoDialog, { width: '32rem' });
    }

    // ── Definition drawer (definition-surface P1) ────────────────────────────────────────────
    /** Demo-only dirty flag — a real host derives this from its definition pane. */
    readonly drawerDemoDirty = signal(false);

    // ── Chart theme (theme/chart-theme.ts) — series colours via seriesColors(), never inline ──
    private readonly chartDays = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
    private readonly chartOutcomes = seriesColors(['Pass', 'Fail']);
    readonly chartBar: ChartData = {
        labels: ['North', 'South', 'East'],
        datasets: [{ label: 'Revenue', data: [4200, 3100, 5600], backgroundColor: seriesColors(['Revenue'])[0] }],
    };
    readonly chartStacked: ChartData = {
        labels: this.chartDays,
        datasets: [
            {
                label: 'Pass',
                data: [120, 132, 101, 134, 90, 60, 72],
                backgroundColor: this.chartOutcomes[0],
                stack: 's',
            },
            { label: 'Fail', data: [12, 8, 20, 6, 14, 3, 5], backgroundColor: this.chartOutcomes[1], stack: 's' },
        ],
    };
    readonly chartStackedOptions: ChartOptions = { scales: { x: { stacked: true }, y: { stacked: true } } };
    readonly chartLine: ChartData = (() => {
        const [calls, dropped] = seriesColors(['Calls', 'Dropped']);
        return {
            labels: this.chartDays,
            datasets: [
                {
                    label: 'Calls',
                    data: [820, 932, 901, 934, 1290, 1330, 1320],
                    borderColor: calls,
                    backgroundColor: calls,
                    fill: true,
                },
                {
                    label: 'Dropped',
                    data: [120, 132, 101, 134, 90, 230, 210],
                    borderColor: dropped,
                    backgroundColor: dropped,
                },
            ],
        };
    })();
    readonly chartDonut: ChartData = {
        labels: ['Open', 'Pending', 'Closed'],
        datasets: [{ data: [14, 6, 31], backgroundColor: seriesColors(['Open', 'Pending', 'Closed']) }],
    };
    // ── More visualization types ▸ Treemap — fraud loss by typology × channel (the zero row shows the exclusion note) ──
    readonly treemapDemo: TreemapRow[] = [
        { group: 'SIM box', subgroup: 'Online', value: 412000 },
        { group: 'SIM box', subgroup: 'Retail', value: 188000 },
        { group: 'SIM box', subgroup: 'Dealer', value: 61000 },
        { group: 'IRSF', subgroup: 'Roaming', value: 356000 },
        { group: 'IRSF', subgroup: 'Online', value: 94000 },
        { group: 'Wangiri', subgroup: 'Online', value: 143000 },
        { group: 'Subscription fraud', subgroup: 'Retail', value: 121000 },
        { group: 'Subscription fraud', subgroup: 'Dealer', value: 77000 },
        { group: 'PBX hacking', subgroup: 'Enterprise', value: 52000 },
        { group: 'Payment-gateway fraud', subgroup: 'Online', value: 38000 },
        { group: 'Payment-gateway fraud', subgroup: 'Retail', value: 0 },
    ];
    readonly treemapPicked = signal('Click a cell — the drill event appears here.');
    readonly treemapSelect = (channel: string, value: string): void =>
        this.treemapPicked.set(`Drill: ${channel} = "${value}"`);
    // ── More visualization types — heatmap (viz/plugins/heatmap.*) ─────────────────────────────
    private readonly heatDays = [
        '2026-09-18',
        '2026-09-19',
        '2026-09-20',
        '2026-09-21',
        '2026-09-22',
        '2026-09-23',
        '2026-09-24',
    ];
    private readonly heatControls = ['RA-C01 Usage to rating', 'RA-C02 Rating to billing', 'RA-C07 Top-up to balance'];
    /** Sequential: exceptions per control per day, one day missing (an empty cell, not a zero). */
    readonly heatSequential: HeatmapData = {
        rows: this.heatControls,
        columns: this.heatDays,
        cells: [
            [0, 3, 1, 0, 2, 0, 1],
            [48, 52, 61, 45, 70, 66, 58],
            [4, null, 9, 12, 2, 0, 5],
        ],
        rowLabel: 'Control',
        columnLabel: 'Event date',
        valueLabel: 'Exceptions (total)',
    };
    /** Diverging: variance vs plan in points, around a midpoint of 0. */
    readonly heatDiverging: HeatmapData = {
        rows: ['Billing', 'Collections', 'Interconnect'],
        columns: ['2026-06-01', '2026-07-01', '2026-08-01', '2026-09-01'],
        cells: [
            [-2.4, -0.8, 0.6, 1.9],
            [1.2, 0.4, -1.5, -3.1],
            [0.2, 2.8, 3.6, -0.4],
        ],
        rowLabel: 'Domain',
        columnLabel: 'Month',
        valueLabel: 'Variance vs plan (pts)',
    };
    /** Status: the RAG matrix — control x day, each cell the run's status word. */
    readonly heatStatus: HeatmapData = {
        ...this.heatSequential,
        cells: [
            ['Pass', 'Warning', 'Warning', 'Pass', 'Warning', 'Pass', 'Warning'],
            ['Fail', 'Fail', 'Fail', 'Fail', 'Fail', 'Fail', 'Fail'],
            ['Warning', null, 'Warning', 'Warning', 'Warning', 'Pass', 'Warning'],
        ],
        valueLabel: 'Status',
    };
    readonly heatmapSnippet = `// a Widget: vizType 'heatmap', channels rows / columns / value (one aggregate per cell)
{ vizType: 'heatmap', datasetId: 'control_runs',
  controls: { rows: [{ field: 'control' }], columns: [{ field: 'event_date', grain: 'day' }],
              value: [{ field: 'status', agg: 'max' }] },          // a status column needs max / min
  options: { heatmap: { scale: 'status' } } }                       // 'sequential' (default) | 'diverging' | 'status'
// diverging: options.heatmap.midpoint (default 0); status on numbers: options.kpi.target + better
// the component on its own (viz-render mounts it; a cell click drills on its row AND column as one toggle):
<inspecto-heatmap [data]="props.heatmap" [options]="{ scale: 'diverging', midpoint: 0 }" [format]="fmt"
                  (cellClick)="drill($event.row, $event.column)" />`;

    /** Dashboard tile section: the chart tile's demo series. */
    readonly tileDemoChart: ChartData = {
        labels: ['North', 'South', 'East', 'West'],
        datasets: [{ label: 'Exposure', data: [420, 310, 180, 260], backgroundColor: CHART_CATEGORICAL[0] }],
    };

    // ── More visualization types: KPI trend + Progress list ─────────────────────────────────
    readonly trendMonths = Array.from({ length: 12 }, (_, i) =>
        new Date(Date.UTC(2025, 9 + i, 1)).toISOString().slice(0, 10),
    );
    readonly trendLeakage = [1.9, 1.7, 1.8, 1.6, 1.5, 1.6, 1.3, 1.2, 1.3, 1.1, 1.0, 0.9];
    readonly trendCases = [41, 44, 39, 47, 52, 49, 55, 58, 54, 61, 63, 60];
    readonly progressDetectors = [
        'SIM box',
        'Wangiri',
        'IRSF',
        'PBX hacking',
        'Subscription fraud',
        'Bypass',
        'Roaming leakage',
        'Interconnect',
        'Rating error',
        'Prepaid top-up',
        'CLI spoofing',
        'Refiling',
    ];
    readonly progressAlerts = [182, 141, 97, 88, 64, 57, 43, 38, 31, 22, 17, 9];
    readonly progressControls = [
        'Usage → billing',
        'Recharge → balance',
        'Interconnect CDR',
        'Roaming TAP',
        'Provisioning',
    ];
    readonly progressPassRate = [99.1, 97.4, 94.2, 91.8, 96.5];
    readonly progressDemoSelect = (label: string): void => {
        this.toast.info(`Drill: ${label}`);
    };
    readonly moreVizSnippet = `<!-- a Widget picks the type: vizType kpi-trend | progress-list; viz-render mounts the component -->
kpi-trend      controls: { x: [{field: 'month', grain: 'month'}], value: [{field: 'leakage_pct', agg: 'avg'}] }
               options:  { format: {style: 'percent'}, kpi: {target: 1.5, better: 'lower'}, trend: {compareBack: 1} }
progress-list  controls: { x: [{field: 'detector'}], y: [{field: 'alert_id', agg: 'count'}] }
               options:  { progress: {limit: 10, max: 100}, sort: 'desc', kpi: {target: 95} }
// standalone (no Widget):
<inspecto-kpi-trend [labels]="months" [values]="values" [format]="{ style: 'percent' }" [target]="1.5" better="lower" />
<inspecto-progress-list [labels]="names" [values]="values" [target]="95" [select]="drill" />`;
    // ── More visualization types: Waterfall + Combo (the real plugins through the real render host) ──
    readonly waterfallPlugin = WATERFALL_PLUGIN;
    readonly waterfallProps: VizProps = {
        labels: ['Billed revenue', 'Rating errors', 'Unbilled usage', 'Duplicate CDRs', 'Recovered'],
        series: [{ label: 'Change', data: [4200000, -310000, -180000, -95000, 260000] }],
    };
    readonly waterfallOptions: VizRenderOptions = {
        waterfall: { start: 'Billed revenue', totalLabel: 'Net billed' },
        format: { style: 'currency', currency: 'SAR', compact: true },
    };
    readonly comboPlugin = COMBO_PLUGIN;
    readonly comboProps: VizProps = {
        labels: ['2026-08-03', '2026-08-10', '2026-08-17', '2026-08-24', '2026-08-31', '2026-09-07'],
        series: [
            { label: 'Alerts', data: [142, 118, 165, 97, 131, 88], kind: 'bar' },
            { label: 'Precision', data: [61, 66, 58, 72, 70, 79], kind: 'line' },
        ],
    };
    readonly comboOptions: VizRenderOptions = {
        axis: { yTitle: 'Alerts', y2Title: 'Precision' },
        format2: { style: 'percent', decimals: 0 },
    };

    // ── Snippets (copy-paste) ────────────────────────────────────────────────────────────────
    readonly snippets = {
        dashboardTile: `<!-- the Dashboard tile frame — WidgetHostComponent already wraps every Widget in it -->
<inspecto-tile-card [title]="w.options?.title || w.name" [subtitle]="w.options?.subtitle"
                    [state]="tileState()"      // 'loading' (skeleton) | 'empty' (one quiet line) | 'ready'
                    [shape]="tileShapeOf(plugin.render)">  // 'kpi' | 'chart' | 'table' — picks the skeleton
  <inspecto-status-badge tileStatus value="WARNING" label="Stale" />   <!-- always visible -->
  <button tileActions mat-icon-button aria-label="Export as PNG">…</button>  <!-- hover AND focus -->
  <inspecto-viz-render … />                   <!-- the body: render it only when state is 'ready' -->
</inspecto-tile-card>
<!-- a Dashboard host adds its own controls (drag, width, remove) the same way: -->
<app-dashboard-tile [widget]="w" [dataset]="d"><ng-container tileActions [ngTemplateOutlet]="controls" /></app-dashboard-tile>`,
        pageHeader: `<!-- the ONE page header: 22px title, ONE-line subtitle, ? explain, actions right -->
<inspecto-page-header title="Alerts" subtitle="Fired alert-rule breaches." [terms]="['Alert', 'Alert Rule']">
  <ng-container actions>
    <button mat-icon-button aria-label="Refresh" (click)="load()">
      <mat-icon svgIcon="heroicons_outline:arrow-path" />
    </button>
    <button mat-flat-button color="primary" (click)="newRule()">New rule</button>
  </ng-container>
</inspecto-page-header>`,
        statTile: `<!-- an ABSENT value renders as an em dash with a reason — never a 0 nobody measured -->
<inspecto-stat-tile label="Recent Runs" [value]="runs() ?? null" absentReason="Jobs backend not configured" />
<inspecto-stat-tile label="Datasets written" [value]="written()" hint="last 24h" />`,
        kpi: `<!-- the dashboard KPI Widget (vizType: kpi) — format, a signed prior-period delta, a target and its good direction -->
<inspecto-kpi [value]="148" [compare]="163" [target]="120" better="lower" />
<inspecto-kpi [value]="88.9" [compare]="91.5" [target]="90" [format]="{ style: 'percent', decimals: 1 }" />
<inspecto-kpi [value]="373300" [format]="{ style: 'currency', currency: 'SAR', compact: true }" />
<!-- a Widget TOON: options.format {style, currency, compact}, options.kpi {target, better}, controls.compare[1]{field,agg} -->`,
        sectionTabs: `<!-- label strip only: the HOST owns the content, so no lazily-mounted tab bodies -->
<inspecto-section-tabs [tabs]="tabs" [selected]="tab()" (selectedChange)="tab.set($event)" />
@switch (tab()) { @case ('grammar') { ... } @case ('schema') { ... } }`,
        bulkActions: `<!-- renders NOTHING at 0 selected — no row of greyed-out pills on first load -->
<inspecto-bulk-actions [count]="selected().length" [actions]="actions"
                       (run)="apply($event)" (clear)="clearSelection()" />`,
        filterBar: `<!-- collapsed to chips; the host queries on (apply), not on every keystroke -->
<inspecto-filter-bar [fields]="fields" [value]="filters()" (valueChange)="filters.set($event)" (apply)="load()">
  <button end mat-stroked-button (click)="exportCsv()">Export CSV</button>
</inspecto-filter-bar>`,
        badge: `<inspecto-status-badge [value]="event.level" />\n// in an ag-Grid cellRenderer:\ncellRenderer: (p) => statusBadgeHtml(p.value)\n// dense table column — dot + row ink:\n<inspecto-status-badge [value]="row.result" variant="dot" />\ncellRenderer: (p) => statusBadgeHtml(p.value, undefined, 'dot')`,
        chip: `<!-- tag / token / filter pill — variant: outline | soft, tone: neutral | primary | warning -->\n<inspecto-chip variant="soft">{{ tag }}</inspecto-chip>\n<!-- a caution (the Dashboard header's Illustrative data), explanation as a tooltip: -->\n<inspecto-chip variant="soft" tone="warning"><span title="…">Illustrative data</span></inspecto-chip>\n<!-- selectable filter toggle: -->\n<button (click)="toggle(t)" [attr.aria-pressed]="active(t)">\n  <inspecto-chip [tone]="active(t) ? 'primary' : 'neutral'">{{ t }}</inspecto-chip>\n</button>\n<!-- removable active filter: -->\n<inspecto-chip variant="soft" tone="primary" removable (removed)="clear()">correlation: {{ id }}</inspecto-chip>`,
        alert: `<inspecto-alert variant="warning" title="Read-only">\n  Editing is disabled (no write root configured).\n</inspecto-alert>`,
        empty: `<inspecto-empty-state\n  icon="heroicons_outline:queue-list"\n  title="Nothing yet"\n  message="No events match the current filters."\n  actionLabel="Clear filters"\n  (action)="reset()" />`,
        skeleton: `<inspecto-skeleton width="40%" height="0.875rem" />   <!-- a label -->\n<inspecto-skeleton [lines]="4" />                     <!-- a paragraph -->\n<inspecto-skeleton height="12rem" />                  <!-- a block -->`,
        grid: `<ag-grid-angular\n  class="h-[42rem] w-full"\n  [theme]="themeSvc.theme()"\n  [rowData]="rows"\n  [columnDefs]="columnDefs"\n  [defaultColDef]="defaultColDef"\n  [loading]="loading"\n  [overlayNoRowsTemplate]="emptyOverlay"\n  (firstDataRendered)="refreshActions($event)"\n  (rowDataUpdated)="refreshActions($event)" />`,
        form: `form = this.fb.group({\n  id: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],\n});\nsubmit() {\n  if (this.form.invalid) { this.form.markAllAsTouched(); return; }\n  // ...\n}`,
        schemaForm: `// declare the attributes once (tier: required | optional | advanced)\nconst SPECS: AttributeSpec[] = [\n  { key: 'name', label: 'Source id', type: 'identifier', tier: 'required' },\n  { key: 'host', label: 'Host', type: 'string', tier: 'required',\n    dependsOn: { key: 'protocol', equals: 'sftp' } },\n  { key: 'parallel_fetch', label: 'Parallel fetch', type: 'number', tier: 'advanced', default: 4 },\n];\n\n<inspecto-schema-form #sf [specs]="specs" [initial]="existingConfig" />\n// on submit: if (!sf.validate()) return;  const config = sf.value();`,
        aiAssist: `<!-- the ONE inline authoring surface — adopt it, never fork it (AGT-6a A1) -->\n<!-- the pane's own context IS the tool's args, so the operator re-states nothing -->\n<inspecto-ai-assist\n  tool="suggest_expectations"        // any non-mutating tool; mutating ones are refused 403\n  [args]="{ table: table(), column: selectedColumn() }"\n  [current]="editing()?.config ?? null"   // omit/null for a create ⇒ every field reads as added\n  label="Suggest expectations"\n  [disabled]="!selectedColumn()"\n  disabledReason="Select a column first"\n  (applyDraft)="openForm($event.config)" />\n// The surface persists NOTHING. The pane applies the draft through its own\n// validated route, so the human is the audited actor (decision D2).`,
        aiExplain: `<!-- the read-only half — one icon button for a pane header (AGT-6a A4) -->\n<!-- the PANE declares the terms; nothing is typed, nothing is inferred -->\n<inspecto-ai-explain\n  screen="Pipelines"                       // reads as "About Pipelines" in the title + aria-label\n  [terms]="['Pipeline', 'Step', 'Trigger']" />  // canonical GLOSSARY.md spellings, never synonyms\n// No draft, no diff, no Apply — there is no write path at all, so this is NOT\n// gated on canAuthorWorkbench: a Business-lens user is who needs it most.\n// glossary_lookup, falling back to docs_search; a 503 explains itself.`,
        dataTable: `<!-- one component, four tiers; logic lives in inspecto/data-table/{core,sql} + inspecto/query -->\n<!-- standard: icon toolbar (columns · search · export) -->\n<!-- pro: + a CodeMirror SQL editor (runs offline via AlaSQL) + filter builder -->\n<!-- proMax: + "save as rule" (parameterized :fieldValue template) -->\n<inspecto-data-table\n  [tier]="'pro'"                 // 'mini' | 'standard' | 'pro' | 'proMax'\n  [rows]="rows"\n  [columns]="columnDefs"         // optional; omitted ⇒ one column per row key\n  [rowActions]="actions"\n  sourceName="cdr"\n  (rowClick)="open($event)"\n  (ruleSaved)="onRuleSaved($event)" />  // pro max\n<!-- sizing: fits its rows, 10 per page (pager: 10 · 25 · 50 · 100 + your [pageSize]) -->\n<!-- height="15rem" ONLY for a docked panel that needs a stable box -->`,
        schemaFields: `<!-- the shared raw.fields[] grid — cols: include · # · icon-only type menu · Name · Synonym -->
<!-- pure: seed [rows] from a SIGNAL (reference change = rebuild); read value() on submit -->
<inspecto-schema-fields-editor
  [rows]="schemaSeed()"
  [autoTypes]="typesMode() === 'auto'"     // Auto: type icons render read-only
  [nameBasedSelectors]="frontend() === 'json'" />  // json/text_regex: a Selector column appears
// on submit: if (!grid.validate()) return;  const fields = grid.value();

<!-- the column-metadata grid (D1(b)) — description/unit/classification, Catalog-facing -->
<!-- a second VIEW over the SAME rows signal; merge by selector at submit -->
<inspecto-schema-metadata-grid [rows]="schemaSeed()" />
// const fields = metaGrid.applyTo(grid.value());`,
        grammarTabs: `// the collapsible Grammar surface (parse-pane-redesign-plan S2) — driven by AttributeSpec.section
// ≥2 distinct sections in a spec set ⇒ <inspecto-grammar-editor> renders a mat-accordion,
// one FLAT <inspecto-schema-form [flat]="true"> per mat-expansion-panel; any other spec set renders flat.
const SPECS: AttributeSpec[] = [
  { key: 'delimited__delimiter', label: 'Delimiter', section: 'dialect', ... },
  { key: 'delimited__strict_mode', label: 'Strict', section: 'robustness', ... },
];
// Panel content is placed DIRECTLY (never <ng-template matExpansionPanelContent>), so Angular
// Material keeps it mounted whether the panel is open or collapsed — this is what let S2 delete
// the old R9 [hidden]-tab-panel hack (MatTab instantiated a tab body only on first activation, so
// an unvisited tab's form was invisible to value()/validate() until visited — silent loss on save).
// Hosts project write-path content via the named slots:
<inspecto-grammar-editor [initial]="block" [type]="'delimited'" [lockType]="true">
  <div tabTypes><!-- Types section: the columns table + filename column + column metadata --></div>
</inspecto-grammar-editor>`,
        mapView: `<!-- offline MapLibre host (bundled Natural Earth basemap, no network) -->\n<inspecto-map-view\n  [data]="geoData"          // GeoData { points, routes }; null ⇒ unmounted (show an empty state)\n  [fill]="true"             // grow into a flex column (default: 62vh page band)\n  (pointClick)="open($event)" />\n// colours live in theme/map-tokens.ts (the map's chart-tokens analog)`,
        moreVizWaterfallCombo: `<!-- Waterfall (bridge) — vizType 'waterfall': x = step, y = the step's SIGNED change -->
options: { waterfall: { start: 'Billed revenue',   // the step whose value is the OPENING total (drawn from 0, first)
                        totalLabel: 'Net billed',   // trailing computed total; '' draws none (default 'Total')
                        order: 'data' },            // 'data' (by step) | 'asc' | 'desc' (by signed change)
           kpi: { better: 'lower' } }               // flips the tones: a decrease is the good (success) step
// generic sort/limit never reorder a waterfall; hideBlank does apply

<!-- Combo — vizType 'combo': x, y = bar measure(s), y2 = line measure(s) -->
options: { combo: { secondaryAxis: true },          // default when a y2 exists; false = one shared axis
           axis: { yTitle: 'Alerts', y2Title: 'Precision' },
           format: { … },                           // bars (left axis)
           format2: { style: 'percent' } }          // lines (right axis + their tooltips); absent = format
<inspecto-viz-render [plugin]="plugin" [props]="props" [renderOptions]="options" (categoryClick)="drill($event)" />`,
        chartTheme: `<!-- every Chart.js chart goes through <inspecto-chart>: it applies theme/chart-theme.ts -->
<!-- (font, muted ticks, value-axis gridlines, rounded capped bars, card tooltip, point legend) -->
<inspecto-chart type="bar" [data]="data" [options]="{ scales: { x: { stacked: true }, y: { stacked: true } } }" />
// series colours: seriesColors(labels) / CHART_TONE / CHART_PALETTES — set on the DATASETS, never a hex inline
// your [options] deep-merge OVER the theme; one series ⇒ no legend unless plugins.legend.display says so`,
        treemap: `# vizType: treemap — a Widget TOON; the tile renders <inspecto-treemap> through viz-render
name: fm_ty_treemap
vizType: treemap
datasetId: fraud_cases
controls:
  group[1]{field}:
    typology
  subgroup[1]{field}:
    enabler
  value[1]{field,agg}:
    confirmed_loss_sar,sum
options:
  title: Confirmed fraud loss by typology and enabler
  format:
    style: currency
    currency: SAR
    compact: true
  treemap:
    limit: 20
# subgroup is optional (one level); zero / negative rows are left out and counted in the text alternative;
# groups past the limit fold into "Other"; a group cell drills on group's field, a subgroup cell on subgroup's`,
        definitionDrawer: `<!-- the shared definition shell for an editor's right dock (definition-surface D1/D2) -->\n<inspecto-definition-drawer\n  [title]="node.name || node.id"\n  kindLabel="Collector"\n  icon="heroicons_outline:inbox-arrow-down"\n  [dirty]="paneDirty()"          // reported by the projected pane\n  (apply)="pane.submit()"         // Apply = in-memory patch — the toolbar Save persists (D2)\n  (discard)="recreatePane()"      // Discard = recreate the pane from the model\n  (closed)="closeDrawer()">       // dirty close already confirmed by the shell\n  <app-my-definition-pane [node]="node" (applied)="applyPatch($event)" (dirtyChange)="paneDirty.set($event)" />\n</inspecto-definition-drawer>`,
        dialogResize: `<!-- shared resizable/maximizable dialog chrome (inspecto/components/dialog-resize.directive.ts) -->\n<!-- the attribute goes on the dialog title; the drag grip is appended automatically -->\n<h2 mat-dialog-title class="flex items-center gap-2" inspectoDialogResize #chrome="inspectoDialogResize">\n  <span class="min-w-0 truncate">Edit Grammar · {{ node.id }}</span>\n  <span class="flex-1"></span>\n  <!-- big dialogs add a maximize button; it reuses the .dialog-fullscreen panel class -->\n  <button mat-icon-button type="button" (click)="chrome.toggleMaximize()"\n          [attr.aria-label]="chrome.maximized() ? 'Exit full screen' : 'Full screen'">\n    <mat-icon [svgIcon]="chrome.maximized() ? 'heroicons_outline:arrows-pointing-in'\n                                            : 'heroicons_outline:arrows-pointing-out'" />\n  </button>\n</h2>\n// panel styles live in styles.scss (.inspecto-dialog-resizable); outside a dialog the directive is inert`,
        menuFavorites: `// personal, client-local overlay — never PUT to the server (inspecto/menu/menu-favorites.ts)\n// storage key: inspecto.menuFavorites.v1, keyed by space id\nfavIds = signal<Set<string>>(loadForSpace());\ntoggleFavorite(id): void { /* mutate the set, persist to localStorage */ }\n\n<!-- a star toggle on each leaf row (aria-pressed, mirrors the sql-editor favorites idiom) -->\n<button [attr.aria-pressed]="isFav(id)" (click)="toggleFavorite(id)"\n        [attr.aria-label]="isFav(id) ? 'Unfavorite' : 'Favorite'">\n  <mat-icon [svgIcon]="isFav(id) ? 'heroicons_solid:star' : 'heroicons_outline:star'" />\n</button>\n\n// a virtual top-of-sidebar "Favorites" group (favoritesNavGroup in menu-nav.ts), prepended in\n// NavigationService: resolves ids against the current tree, drops stale/deleted, re-ids fav-<id>.`,
    };
    copy(text: string): void {
        navigator.clipboard?.writeText(text).then(
            () => this.toast.success('Snippet copied'),
            () => this.toast.error('Copy failed'),
        );
    }
}
