import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal, ViewEncapsulation } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { AnalyticsCatalogue, AnalyticsCatalogueService, AnalyticsFunction } from 'app/inspecto/api';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { InspectoSectionTabsComponent, SectionTab } from 'app/inspecto/components/section-tabs.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { costLabel, formatParams, kernelLabel } from './analytics-functions';

const ALL = 'all';

/**
 * Analytics functions — the read-only catalogue of what the Analytics Kernel offers (GET /analytics/functions):
 * id, parameters, cost hint and result kind per function, grouped by kernel. The list is registry-driven on the
 * server, so a kernel added later appears here with no change to this pane.
 */
@Component({
    selector: 'app-analytics-functions',
    standalone: true,
    imports: [
        InspectoPageHeaderComponent,
        InspectoSectionTabsComponent,
        MatButtonModule,
        MatIconModule,
        MatTooltipModule,
        DataTableComponent,
    ],
    templateUrl: './analytics-functions.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class AnalyticsFunctionsComponent implements OnInit {
    private api = inject(AnalyticsCatalogueService);
    private toastr = inject(ToastrService);

    readonly catalogue = signal<AnalyticsCatalogue | null>(null);
    readonly loading = signal(false);
    readonly kernel = signal(ALL);

    readonly tabs = computed<SectionTab[]>(() => {
        const c = this.catalogue();
        return [
            { id: ALL, label: 'All', count: c?.total ?? 0 },
            ...(c?.kernels ?? []).map((k) => ({ id: k.id, label: k.label, count: k.count })),
        ];
    });

    readonly rows = computed<AnalyticsFunction[]>(() => {
        const c = this.catalogue();
        const k = this.kernel();
        return (c?.functions ?? []).filter((f) => k === ALL || f.kernel === k);
    });

    // DataTable's default flex overrides a bare width, so widths here are floors (minWidth).
    readonly columnDefs: ColDef<AnalyticsFunction>[] = [
        { field: 'label', headerName: 'Function', minWidth: 170, flex: 1 },
        { field: 'id', headerName: 'Id', minWidth: 190, flex: 1 },
        {
            field: 'kernel',
            headerName: 'Kernel',
            minWidth: 130,
            valueFormatter: (p) => kernelLabel(p.value as string, this.catalogue()?.kernels ?? []),
        },
        {
            field: 'cost',
            headerName: 'Cost',
            minWidth: 110,
            maxWidth: 120,
            valueFormatter: (p) => costLabel(p.value as AnalyticsFunction['cost']),
        },
        { field: 'result', headerName: 'Result', minWidth: 100, maxWidth: 120 },
        {
            field: 'forms',
            headerName: 'Runs as',
            minWidth: 100,
            maxWidth: 120,
            valueFormatter: (p) => (p.value as string[]).join(' + '),
        },
        {
            field: 'step',
            headerName: 'Step',
            minWidth: 190,
            flex: 1,
            valueFormatter: (p) => (p.value as string | null) ?? '—',
        },
        {
            headerName: 'Parameters',
            minWidth: 320,
            flex: 3,
            wrapText: true,
            autoHeight: true,
            cellStyle: { whiteSpace: 'pre-line' },
            valueGetter: (p) => (p.data ? formatParams(p.data) : ''),
        },
        { field: 'summary', headerName: 'What it does', minWidth: 280, flex: 3, wrapText: true, autoHeight: true },
    ];

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.loading.set(true);
        this.api.catalogue().subscribe({
            next: (c) => {
                this.catalogue.set(c);
                this.loading.set(false);
            },
            error: () => {
                this.catalogue.set(null);
                this.loading.set(false);
                // Unreachable-backend messaging is the connectivity banner's job (§8) — plain failure toast only.
                this.toastr.error('Failed to load the Analytics function catalogue');
            },
        });
    }
}
