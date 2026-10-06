import { ChangeDetectionStrategy, Component, Provider, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { map, of } from 'rxjs';
import { AiAssistComponent } from '@inspecto/core/ai-assist/ai-assist.component';
import { AiExplainComponent } from '@inspecto/core/ai-assist/ai-explain.component';
import { ComponentsService } from '@inspecto/core/api/components.service';
import { ObjectsService } from '@inspecto/core/api/objects.service';
import { isSharedRef } from '@inspecto/core/api/shared-ref';
import { SessionService } from '@inspecto/core/auth/session.service';
import { TagAssignmentDialog } from '@inspecto/core/tags/tag-assignment.dialog';
import {
    BundleKind,
    BundleTransferService,
    ImportDraftBannerComponent,
    TransferMenuComponent,
} from '@inspecto/core/transfer';
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
    LaDataset,
    LaDatasets,
    LaFeatures,
    LaPipelineGraph,
    LaTags,
    LaTransfer,
    LaWidgets,
} from '@inspecto/link-analysis';

/**
 * How the Link Analysis application answers each of the ten host tokens (`la-host`). `real` = a host-independent
 * implementation over the shared core; `stub` = the feature is not part of this application, the stub says so (see
 * {@link reportAbsent}) and the library hides the affordance. The spec pins that every token is listed here.
 */
export const LA_APP_TOKEN_PROVISION = [
    {
        token: 'LA_DATASETS',
        kind: 'real',
        note: 'Datasets read from the Component registry (`dataset` kind), no Studio code.',
    },
    {
        token: 'LA_CASES',
        kind: 'real',
        note: 'Cases through the core Objects client; `available` follows the `ops` flag.',
    },
    { token: 'LA_TAGS', kind: 'real', note: 'The core Tag assignment dialog.' },
    { token: 'LA_TRANSFER', kind: 'real', note: 'The core Import / export menu and draft banner.' },
    { token: 'LA_AI_ASSIST', kind: 'real', note: 'The core AI assist and explain components.' },
    { token: 'LA_FEATURES', kind: 'real', note: '`SessionService` flags from `/bootstrap`.' },
    {
        token: 'LA_WIDGETS',
        kind: 'stub',
        note: 'No Widget library here: `available: false`, "Pin to a Widget" is not offered.',
    },
    {
        token: 'LA_CATALOG',
        kind: 'stub',
        note: 'No Component catalog here: `available: false`, the reuse-graph source is not offered.',
    },
    {
        token: 'LA_PIPELINE_GRAPH',
        kind: 'stub',
        note: 'No Pipelines editor here: `available: false`, the provenance source is not offered.',
    },
    {
        token: 'LA_DASHBOARD_HEADER',
        kind: 'stub',
        note: 'No dashboards here: an empty header (the Link view widget is never embedded).',
    },
] as const;

const reported = new Set<string>();

/** An absent token REPORTS itself: one console.info line, once per token (never an error, never repeated). */
function reportAbsent(token: string): void {
    if (reported.has(token)) return;
    reported.add(token);
    console.info(
        `${token}: not available in la-app (no ${token.toLowerCase().replace('la_', '').replace('_', ' ')}); the affordance is hidden.`,
    );
}

/** The stand-in for the host's Dashboard header: renders nothing. */
@Component({
    selector: 'la-app-no-header',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: '',
})
export class NoDashboardHeaderComponent {}

/** The Link Analysis application's implementation of the ten host tokens. See {@link LA_APP_TOKEN_PROVISION}. */
export function provideLaAppHostServices(): Provider[] {
    return [
        {
            provide: LA_DATASETS,
            useFactory: (): LaDatasets => {
                const components = inject(ComponentsService);
                const toDataset = (name: string, content: Record<string, unknown>): LaDataset => ({
                    id: name,
                    name: (content['name'] as string) ?? name,
                    // A shared ref spans Spaces and is not a local store name; `physicalRef` is the landed store's name.
                    sourceName:
                        (content['sourceName'] as string) ||
                        (isSharedRef(content['physicalRef'] as string)
                            ? ''
                            : ((content['physicalRef'] as string) ?? '')) ||
                        '',
                    query: (content['query'] as LaDataset['query']) ?? null,
                    sql: (content['sql'] as string | null) ?? null,
                    columns: (content['columns'] as LaDataset['columns']) ?? [],
                    calculated: (content['calculated'] as LaDataset['calculated']) ?? [],
                });
                return {
                    list: () =>
                        components.list('dataset').pipe(map((defs) => defs.map((d) => toDataset(d.name, d.content)))),
                    get: (id) => components.get('dataset', id).pipe(map((d) => toDataset(d.name, d.content))),
                };
            },
        },
        {
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
                return { ops: session.opsEnabled, exchange: session.exchangeEnabled, geoLink: session.geoLinkEnabled };
            },
        },
        {
            provide: LA_WIDGETS,
            useFactory: (): LaWidgets => {
                reportAbsent('LA_WIDGETS');
                return {
                    available: false,
                    saveWorkingSetWidget: () => {
                        reportAbsent('LA_WIDGETS'); // absence is reported once, never thrown: the affordance is hidden by available:false
                        return of(null);
                    },
                };
            },
        },
        {
            provide: LA_CATALOG,
            useFactory: (): LaCatalog => {
                reportAbsent('LA_CATALOG');
                return { available: false, kinds: [], list: () => Promise.resolve([]) };
            },
        },
        {
            provide: LA_PIPELINE_GRAPH,
            useFactory: (): LaPipelineGraph => {
                reportAbsent('LA_PIPELINE_GRAPH');
                return {
                    available: false,
                    toG6Data: () => ({ nodes: [], edges: [] }),
                    provenanceCounts: () => new Map(),
                };
            },
        },
        {
            provide: LA_DASHBOARD_HEADER,
            useFactory: () => {
                reportAbsent('LA_DASHBOARD_HEADER');
                return NoDashboardHeaderComponent;
            },
        },
    ];
}
