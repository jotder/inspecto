import { Provider, inject } from '@angular/core';
import {
    LA_CATALOG,
    LA_DASHBOARD_HEADER,
    LA_DATASETS,
    LA_PIPELINE_GRAPH,
    LA_WIDGETS,
    LaCatalog,
    LaDatasets,
    LaPipelineGraph,
    LaWidgets,
} from 'app/inspecto/la-host';
import { ComponentsDataProvider } from 'app/modules/admin/catalog/components-data-provider';
import { REGISTRY_KINDS } from 'app/modules/admin/catalog/registry.component';
import { provenanceCounts, toPipelineG6Data } from 'app/modules/admin/pipelines/pipeline-graph';
import { DashboardHeaderComponent } from './dashboards/dashboard-header.component';
import { DatasetsService } from './datasets/datasets.service';
import { buildWidget } from './widgets/widget-types';
import { WidgetsService } from './widgets/widgets.service';

/**
 * The Inspecto SPA's implementation of the Link Analysis / Geo host-service tokens
 * (`inspecto/la-host`). The only file that lets those features reach `modules/admin/**`; a future LA App
 * shell provides its own set instead.
 */
export function provideLaHostServices(): Provider[] {
    return [
        {
            provide: LA_DATASETS,
            useFactory: (): LaDatasets => {
                const datasets = inject(DatasetsService);
                return { list: () => datasets.list(), get: (id) => datasets.get(id) };
            },
        },
        {
            provide: LA_WIDGETS,
            useFactory: (): LaWidgets => {
                const widgets = inject(WidgetsService);
                return {
                    saveWorkingSetWidget: (w) =>
                        widgets.save(
                            buildWidget(w.name, '', w.vizType, {}, {
                                viewId: w.viewId,
                                workingSet: w.workingSet,
                                description: w.description,
                            }),
                        ),
                };
            },
        },
        {
            provide: LA_CATALOG,
            useFactory: (): LaCatalog => {
                const provider = inject(ComponentsDataProvider);
                return { kinds: REGISTRY_KINDS, list: (kind) => provider.list(kind) };
            },
        },
        {
            provide: LA_PIPELINE_GRAPH,
            useFactory: (): LaPipelineGraph => ({ toG6Data: toPipelineG6Data, provenanceCounts }),
        },
        { provide: LA_DASHBOARD_HEADER, useValue: DashboardHeaderComponent },
    ];
}
