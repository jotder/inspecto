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
import {
    InspectoSchemaFieldsEditorComponent,
    InspectoSchemaMetadataGridComponent,
    SchemaFieldRow,
} from 'app/inspecto/schema';
import { ResizeDemoDialog } from './resize-demo.dialog';

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
        InspectoSchemaFormComponent,
        InspectoSkeletonComponent,
        AiAssistComponent,
        AiExplainComponent,
        DataTableComponent,
        TreeTableComponent,
        MapViewComponent,
        InspectoSchemaFieldsEditorComponent,
        InspectoSchemaMetadataGridComponent,
    ],
    templateUrl: './design-system.component.html',
})
export class DesignSystemComponent {
    private fb = inject(FormBuilder);
    private toast = inject(ToastrService);
    private dialog = inject(MatDialog);
    readonly themeSvc = inject(InspectoGridThemeService);

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
            pattern: '\d{4}-\d{2}-\d{2}',
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
            next.has(id) ? next.delete(id) : next.add(id);
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

    // ── Snippets (copy-paste) ────────────────────────────────────────────────────────────────
    readonly snippets = {
        badge: `<inspecto-status-badge [value]="event.level" />\n// in an ag-Grid cellRenderer:\ncellRenderer: (p) => statusBadgeHtml(p.value)`,
        chip: `<!-- tag / token / filter pill — variant: outline | soft, tone: neutral | primary -->\n<inspecto-chip variant="soft">{{ tag }}</inspecto-chip>\n<!-- selectable filter toggle: -->\n<button (click)="toggle(t)" [attr.aria-pressed]="active(t)">\n  <inspecto-chip [tone]="active(t) ? 'primary' : 'neutral'">{{ t }}</inspecto-chip>\n</button>\n<!-- removable active filter: -->\n<inspecto-chip variant="soft" tone="primary" removable (removed)="clear()">correlation: {{ id }}</inspecto-chip>`,
        alert: `<inspecto-alert variant="warning" title="Read-only">\n  Editing is disabled (no write root configured).\n</inspecto-alert>`,
        empty: `<inspecto-empty-state\n  icon="heroicons_outline:queue-list"\n  title="Nothing yet"\n  message="No events match the current filters."\n  actionLabel="Clear filters"\n  (action)="reset()" />`,
        skeleton: `<inspecto-skeleton width="40%" height="0.875rem" />   <!-- a label -->\n<inspecto-skeleton [lines]="4" />                     <!-- a paragraph -->\n<inspecto-skeleton height="12rem" />                  <!-- a block -->`,
        grid: `<ag-grid-angular\n  class="h-[42rem] w-full"\n  [theme]="themeSvc.theme()"\n  [rowData]="rows"\n  [columnDefs]="columnDefs"\n  [defaultColDef]="defaultColDef"\n  [loading]="loading"\n  [overlayNoRowsTemplate]="emptyOverlay"\n  (firstDataRendered)="refreshActions($event)"\n  (rowDataUpdated)="refreshActions($event)" />`,
        form: `form = this.fb.group({\n  id: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],\n});\nsubmit() {\n  if (this.form.invalid) { this.form.markAllAsTouched(); return; }\n  // ...\n}`,
        schemaForm: `// declare the attributes once (tier: required | optional | advanced)\nconst SPECS: AttributeSpec[] = [\n  { key: 'name', label: 'Source id', type: 'identifier', tier: 'required' },\n  { key: 'host', label: 'Host', type: 'string', tier: 'required',\n    dependsOn: { key: 'protocol', equals: 'sftp' } },\n  { key: 'parallel_fetch', label: 'Parallel fetch', type: 'number', tier: 'advanced', default: 4 },\n];\n\n<inspecto-schema-form #sf [specs]="specs" [initial]="existingConfig" />\n// on submit: if (!sf.validate()) return;  const config = sf.value();`,
        aiAssist: `<!-- the ONE inline authoring surface — adopt it, never fork it (AGT-6a A1) -->\n<!-- the pane's own context IS the tool's args, so the operator re-states nothing -->\n<inspecto-ai-assist\n  tool="suggest_expectations"        // any non-mutating tool; mutating ones are refused 403\n  [args]="{ table: table(), column: selectedColumn() }"\n  [current]="editing()?.config ?? null"   // omit/null for a create ⇒ every field reads as added\n  label="Suggest expectations"\n  [disabled]="!selectedColumn()"\n  disabledReason="Select a column first"\n  (applyDraft)="openForm($event.config)" />\n// The surface persists NOTHING. The pane applies the draft through its own\n// validated route, so the human is the audited actor (decision D2).`,
        aiExplain: `<!-- the read-only half — one icon button for a pane header (AGT-6a A4) -->\n<!-- the PANE declares the terms; nothing is typed, nothing is inferred -->\n<inspecto-ai-explain\n  screen="Pipelines"                       // reads as "About Pipelines" in the title + aria-label\n  [terms]="['Pipeline', 'Step', 'Trigger']" />  // canonical GLOSSARY.md spellings, never synonyms\n// No draft, no diff, no Apply — there is no write path at all, so this is NOT\n// gated on canAuthorWorkbench: a Business-lens user is who needs it most.\n// glossary_lookup, falling back to docs_search; a 503 explains itself.`,
        dataTable: `<!-- one component, four tiers; logic lives in inspecto/data-table/{core,sql} + inspecto/query -->\n<!-- standard: icon toolbar (columns · search · export) -->\n<!-- pro: + a CodeMirror SQL editor (runs offline via AlaSQL) + filter builder -->\n<!-- proMax: + "save as rule" (parameterized :fieldValue template) -->\n<inspecto-data-table\n  [tier]="'pro'"                 // 'mini' | 'standard' | 'pro' | 'proMax'\n  [rows]="rows"\n  [columns]="columnDefs"         // optional; omitted ⇒ one column per row key\n  [rowActions]="actions"\n  sourceName="cdr"\n  (rowClick)="open($event)"\n  (ruleSaved)="onRuleSaved($event)" />  // pro max`,
        schemaFields: `<!-- the shared raw.fields[] grid — cols: include · # · icon-only type menu · Name · Synonym -->
<!-- pure: seed [rows] from a SIGNAL (reference change = rebuild); read value() on submit -->
<inspecto-schema-fields-editor
  [rows]="schemaSeed()"
  [autoTypes]="typesMode() === 'auto'"     // Auto: type icons render read-only
  [nameBasedSelectors]="frontend() === 'json'" />  // json/text_regex: a Source column appears
// on submit: if (!grid.validate()) return;  const fields = grid.value();

<!-- the column-metadata grid (D1(b)) — description/unit/classification, Catalog-facing -->
<!-- a second VIEW over the SAME rows signal; merge by selector at submit -->
<inspecto-schema-metadata-grid [rows]="schemaSeed()" />
// const fields = metaGrid.applyTo(grid.value());`,
        grammarTabs: `// the 4-tab Grammar surface (delimited-grammar redesign U1) — driven by AttributeSpec.tab
// ≥2 distinct tabs in a spec set ⇒ <inspecto-grammar-editor> renders a mat-tab-group,
// one <inspecto-schema-form> per tab; any other spec set renders flat, byte-identical.
const SPECS: AttributeSpec[] = [
  { key: 'delimited__delimiter', label: 'Delimiter', tab: 'Dialect/Parsing', ... },
  { key: 'delimited__strict_mode', label: 'Strict', tab: 'Robustness/error handling', ... },
];
// ⚠ R9: the tab PANELS live OUTSIDE the mat-tab bodies, [hidden]-toggled — MatTab
// instantiates body content on FIRST ACTIVATION, so a form inside a body is invisible
// to value()/validate() until visited (silent loss on save).
// Hosts project write-path content via the named slots:
<inspecto-grammar-editor [initial]="block" [type]="'delimited'" [lockType]="true">
  <div tabTypes><!-- tab 2: the columns table --></div>
  <div tabFiles><!-- tab 4: the column-metadata grid --></div>
</inspecto-grammar-editor>`,
        mapView: `<!-- offline MapLibre host (bundled Natural Earth basemap, no network) -->\n<inspecto-map-view\n  [data]="geoData"          // GeoData { points, routes }; null ⇒ unmounted (show an empty state)\n  [fill]="true"             // grow into a flex column (default: 62vh page band)\n  (pointClick)="open($event)" />\n// colours live in theme/map-tokens.ts (the map's chart-tokens analog)`,
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
