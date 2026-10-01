import { InjectionToken, Signal, Type, signal } from '@angular/core';
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

// ── Tags ──────────────────────────────────────────────────────────────────────────────────────────

/** What a Tag assignment is about: the saved view being labelled. */
export interface LaTagTarget {
    targetKind: string;
    targetId: string;
    label: string;
}

/** Cross-entity Tag assignment: the host owns the dialog and the assignment edges. */
export interface LaTags {
    open(target: LaTagTarget): void;
}

export const LA_TAGS = hostToken<LaTags>('LA_TAGS', 'the Tag assignment dialog');

// ── Transfer (import / export) ────────────────────────────────────────────────────────────────────

/**
 * A bundle item handed to the editor as UNSAVED work (Import as draft). Same fields the host's `ImportDraft`
 * carries; `kind` is a plain string here so the contract does not own the host's bundle-kind union.
 */
export interface LaImportDraft {
    kind: string;
    id: string;
    content: Record<string, unknown>;
    sourceSpace: string | null;
    targetExists: boolean;
    integrity: string[] | null;
    prerequisites: string[];
}

/**
 * The host's transfer widgets, rendered with `<la-host-slot>`.
 * - `menu` — inputs `items` (`{kind, id}[]`), `allowedKinds`, `label`, `importDraft`; outputs `changed`,
 *   `draftImported` (a {@link LaImportDraft}).
 * - `banner` — inputs `draft` ({@link LaImportDraft}), `stored`; output `discard`.
 */
export interface LaTransfer {
    readonly menu: Type<unknown>;
    readonly banner: Type<unknown>;
}

export const LA_TRANSFER = hostToken<LaTransfer>('LA_TRANSFER', 'the Import / export menu and draft banner');

// ── AI assist ─────────────────────────────────────────────────────────────────────────────────────

/** A drafted config the operator reviewed and applied — only the part Link Analysis reads. */
export interface LaAiDraft {
    config: Record<string, unknown>;
}

/**
 * The host's AI components, rendered with `<la-host-slot>`.
 * - `assist` — inputs `tool`, `args`, `current`, `label`, `disabled`, `disabledReason`; output `applyDraft`
 *   ({@link LaAiDraft}).
 * - `explain` — inputs `screen`, `terms`.
 */
export interface LaAiAssist {
    readonly assist: Type<unknown>;
    readonly explain: Type<unknown>;
}

export const LA_AI_ASSIST = hostToken<LaAiAssist>('LA_AI_ASSIST', 'the AI assist and explain components');

// ── Cases ─────────────────────────────────────────────────────────────────────────────────────────

/** An open Case as the pickers offer it. */
export interface LaCaseRef {
    id: string;
    title: string;
}

/** One member of a Case opened from Link Analysis: an Entity to mint, or an Incident the graph already references. */
export type LaCaseEntityMember = { id: string; dataset: string; label?: string } | { objectId: string };

/** The Case that `openFromEntities` opened. */
export interface LaOpenedCase {
    caseId: string;
    memberCount: number;
}

/**
 * Case management as Link Analysis uses it. `available` is the HOST's decision: false when Case management
 * (the ops module) is not installed — Link Analysis then shows its own placeholder Cases and never calls
 * `list` / `openFromEntities`.
 */
export interface LaCases {
    readonly available: Signal<boolean>;
    /** The open Cases. */
    list(): Observable<LaCaseRef[]>;
    /** Open a Case whose first members come from these Entities (one call: a refused member creates nothing). */
    openFromEntities(
        title: string,
        description: string | undefined,
        members: LaCaseEntityMember[],
    ): Observable<LaOpenedCase>;
}

export const LA_CASES = hostToken<LaCases>('LA_CASES', 'Case management');

// ── Feature flags ─────────────────────────────────────────────────────────────────────────────────

/**
 * The host's installed-module facts that Link Analysis / Geo adapt to. Each is a signal the HOST owns (Inspecto:
 * `SessionService`, set from `/bootstrap`); a flag that is off hides the feature, it never errors.
 * - `ops` - Case management (`inspecto-ops`) is installed: Case actions are real, not placeholders.
 * - `exchange` - the multi-Space runtime hosts the cross-Space Exchange: an Investigation can be shared.
 * - `geoLink` - the Link Analysis / Geo routes are registered in this bundle (server-side Entity Lists, Identity groups).
 */
export interface LaFeatures {
    readonly ops: Signal<boolean>;
    readonly exchange: Signal<boolean>;
    readonly geoLink: Signal<boolean>;
}

/**
 * Unlike the service tokens above, this one has a SAFE default: with no host provider every flag is off, so the
 * features that depend on an optional module simply do not appear (it never throws).
 */
export const LA_FEATURES = new InjectionToken<LaFeatures>('LA_FEATURES', {
    providedIn: 'root',
    factory: () => ({ ops: signal(false), exchange: signal(false), geoLink: signal(false) }),
});
