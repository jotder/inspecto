import { InjectionToken, Type } from '@angular/core';
import { Observable } from 'rxjs';
import type { PipelineGraph, ProvenanceCount, WorkingSetRelationName } from 'app/inspecto/api';
import type { Component as ModelComponent } from 'app/inspecto/component-model';
import type { G6GraphData } from 'app/inspecto/graph';
import type { RowSourceRef } from 'app/inspecto/viz/dataset-rows.service';

/**
 * The **host-service contract** of the Link Analysis / Geo features (D-5 prep, la-separation-feasibility-plan §7.5).
 *
 * The LA and Geo feature code (`modules/admin/studio/link-analysis`, `…/geo-map`) imports NOTHING from
 * `modules/admin/**` (enforced by ESLint `no-restricted-imports`). Everything host-specific enters through the
 * tokens below: the Inspecto SPA provides them from `modules/admin/studio/la-host.providers.ts`, a future LA App
 * shell provides its own. A token with no provider fails LOUDLY at injection, naming itself.
 */

function hostToken<T>(name: string, what: string): InjectionToken<T> {
    return new InjectionToken<T>(name, {
        providedIn: 'root',
        factory: () => {
            throw new Error(
                `${name} has no provider: the Link Analysis / Geo features need the host to provide ${what}. ` +
                    'Add the host provider set (Inspecto: provideLaHostServices()) to the application or test providers.',
            );
        },
    });
}

// ── Datasets ──────────────────────────────────────────────────────────────────────────────────────

/** A Dataset as LA / Geo read it — identity plus what the rows seam needs; NOT the host's `Dataset` type. */
export interface LaDataset extends RowSourceRef {
    id: string;
    name: string;
}

export interface LaDatasets {
    list(): Observable<LaDataset[]>;
    get(id: string): Observable<LaDataset>;
}

export const LA_DATASETS = hostToken<LaDatasets>('LA_DATASETS', 'the Datasets service');

// ── Widgets ───────────────────────────────────────────────────────────────────────────────────────

/**
 * LA-21 — a **Working Set Widget**'s binding (decision D-E6). The Widget's `viewId` names the Investigation; this says
 * which relation it shows, how, and from where. It holds NO rows: every render reads through the Investigation-scoped
 * route, so the owner-only / PDP gate (D-E7) applies to every viewer of every dashboard it sits on.
 * - `frozen` (the default) re-reads the relation AT `pin.step` and checks the answer's hash against `pin.workingSetHash`;
 * - `live` re-reads the head and shows what changed since the pin. A Live Widget cannot leave its Space.
 */
export interface WorkingSetBinding {
    relation: WorkingSetRelationName;
    mode: 'frozen' | 'live';
    pin: { step: number; workingSetHash: string; pinnedAt: string };
}

/** What pinning a Working Set to a dashboard saves: the host builds and stores the Widget. */
export interface LaWorkingSetWidget {
    name: string;
    vizType: string;
    viewId: string;
    workingSet: WorkingSetBinding;
    description: string;
}

export interface LaWidgets {
    saveWorkingSetWidget(widget: LaWorkingSetWidget): Observable<unknown>;
}

export const LA_WIDGETS = hostToken<LaWidgets>('LA_WIDGETS', 'the Widgets service');

// ── Catalog ───────────────────────────────────────────────────────────────────────────────────────

/** The reuse-graph's data source: the registry kinds and the stored Components of one kind. */
export interface LaCatalog {
    readonly kinds: readonly string[];
    list(kind: string): Promise<ModelComponent[]>;
}

export const LA_CATALOG = hostToken<LaCatalog>('LA_CATALOG', 'the Component catalog');

// ── Pipeline graph ────────────────────────────────────────────────────────────────────────────────

export interface LaProvenanceOverlay {
    rowCount: number;
    simulated: boolean;
}

/** The Pipelines editor's graph mapping, used by the provenance GraphSource. */
export interface LaPipelineGraph {
    toG6Data(g: PipelineGraph, counts?: Map<string, LaProvenanceOverlay>): G6GraphData;
    provenanceCounts(rows: ProvenanceCount[]): Map<string, LaProvenanceOverlay>;
}

export const LA_PIPELINE_GRAPH = hostToken<LaPipelineGraph>('LA_PIPELINE_GRAPH', 'the Pipeline graph mapping');

// ── Dashboard header ──────────────────────────────────────────────────────────────────────────────

/** The host's Dashboard header component; takes a `header` input `{description?: string}`. */
export const LA_DASHBOARD_HEADER = hostToken<Type<unknown>>('LA_DASHBOARD_HEADER', 'the Dashboard header component');
