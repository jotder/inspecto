import { Provider, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { map } from 'rxjs';
import { SessionService } from 'app/inspecto/api';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { AiAssistComponent } from 'app/inspecto/ai-assist/ai-assist.component';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';
import { TagAssignmentDialog } from 'app/inspecto/tags/tag-assignment.dialog';
import {
    BundleKind,
    BundleTransferService,
    ImportDraftBannerComponent,
    TransferMenuComponent,
} from 'app/inspecto/transfer';
import {
    LA_AI_ASSIST,
    LA_CASES,
    LA_CATALOG,
    LA_DASHBOARD_HEADER,
    LA_DATASETS,
    LA_FEATURES,
    LA_PIPELINE_GRAPH,
    LA_TAGS,
    LA_TRANSFER,
    LA_WIDGETS,
    LaCases,
    LaCatalog,
    LaDatasets,
    LaFeatures,
    LaPipelineGraph,
    LaTags,
    LaTransfer,
    LaWidgets,
} from '@inspecto/link-analysis';
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
                            buildWidget(
                                w.name,
                                '',
                                w.vizType,
                                {},
                                {
                                    viewId: w.viewId,
                                    workingSet: w.workingSet,
                                    description: w.description,
                                },
                            ),
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
        {
            provide: LA_TAGS,
            useFactory: (): LaTags => {
                const dialog = inject(MatDialog);
                return { open: (target) => void dialog.open(TagAssignmentDialog, { data: target }) };
            },
        },
        {
            provide: LA_TRANSFER,
            useFactory: (): LaTransfer => {
                const bundles = inject(BundleTransferService);
                return {
                    menu: TransferMenuComponent,
                    banner: ImportDraftBannerComponent,
                    recheck: (kind, id, content) => bundles.draftIntegrity(kind as BundleKind, id, content),
                };
            },
        },
        { provide: LA_AI_ASSIST, useValue: { assist: AiAssistComponent, explain: AiExplainComponent } },
        {
            provide: LA_FEATURES,
            useFactory: (): LaFeatures => {
                const session = inject(SessionService);
                return {
                    ops: session.opsEnabled,
                    exchange: session.exchangeEnabled,
                    geoLink: session.geoLinkEnabled,
                };
            },
        },
        {
            // The host decides real vs placeholder: `available` is false when `inspecto-ops` is not installed.
            provide: LA_CASES,
            useFactory: (): LaCases => {
                const objects = inject(ObjectsService);
                return {
                    available: inject(SessionService).opsEnabled,
                    list: () =>
                        objects
                            .list({ type: 'CASE' })
                            .pipe(map((rows) => rows.map((o) => ({ id: o.id, title: o.title })))),
                    openFromEntities: (title, description, members) =>
                        objects
                            .openCaseFromEntities(title, description, members)
                            .pipe(map((made) => ({ caseId: made.case.id, memberCount: made.members.length }))),
                };
            },
        },
    ];
}
