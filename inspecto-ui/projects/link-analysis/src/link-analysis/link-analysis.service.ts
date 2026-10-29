import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ComponentsService } from '@inspecto/core/api';
import { GraphSourceId, GraphSourceQuery } from '@inspecto/core/graph';
import { DomainProfileId } from '@inspecto/link-analysis/graph/domain-profile';
import { SavedViewStore } from '@inspecto/link-analysis/investigation/saved-view-store';
import { GraphDisplayOptions, GraphLayoutId, GraphViewPlugins } from '@inspecto/core/graph/graph-view.component';
import type { InvestigationRef } from './investigation-state';

/**
 * A saved investigation — a **Link-Analysis View** (GLOSSARY §11), persisted as the
 * `link-analysis-view` component kind. Backed server-side since INV-1 (2026-07-08):
 * `link-analysis-view` is in `ComponentStore.WRITABLE_TYPES` with generic `/components` CRUD +
 * version history, so views persist through the real backend as well as the offline mock store.
 */
/**
 * What a saved view IS, stated wherever one is offered, loaded or rendered. A view stores the QUERY and
 * its presentation, never the result rows, and no read anywhere in the backend is version-addressable
 * (decision D-S1, grounded 2026-09-22) — so reopening re-runs the projection against whatever the Dataset
 * holds now. 🔴 An analyst who reads a reopened view as the graph they saved is reading a different graph
 * with the same name; in investigative use that is a false finding, not a stale cache.
 */
export const SAVED_VIEW_NOT_EVIDENCE =
    'Saved views re-project live data — a reopened view may differ from the one you saved.';

export interface LinkAnalysisView {
    id: string;
    name: string;
    description?: string;
    sourceId: GraphSourceId;
    query: GraphSourceQuery;
    /** Presentation (labels + per-kind colours) captured with the view; absent = defaults. */
    display?: GraphDisplayOptions;
    /** The chosen graph layout; absent = the default layered layout. */
    layout?: GraphLayoutId;
    /** The domain profile shaping statistics and suggested tools; absent = generic. */
    profile?: DomainProfileId;
    /** View toolbox state: canvas plugins/behaviors and which overlays are open; absent = defaults. */
    view?: LinkAnalysisViewOptions;
    /**
     * LA-10: the Investigations started from this view. The backend has no list or get-one route, so this is
     * where their ids are remembered; the log and Working Set stay server-side (owner-only). Absent = none.
     */
    investigations?: InvestigationRef[];
    /**
     * LA-ONBOARD-1 A-1b: a **case** on the Explorer level is this named view plus the Entity Lists it uses - the list the
     * graph started from and the exclusion lists written from it. Ids only; absent = it uses none.
     */
    lists?: CaseLists;
    /**
     * LA-ONBOARD-1 phase 7: the Start the graph was drawn from (phase 6), so reopening draws that start and its ring
     * again over the data as it is now, instead of the whole Dataset `query` names. `values` are the drawn and grown
     * entities; `listId` re-reads that list's members. Absent = the view reruns `query`.
     */
    start?: CaseStart;
    /**
     * Read-only access envelope the server keeps on the Component (`ComponentAccess`): who created it, and whether it is
     * shared (`shares` present). A shared view may be deleted only by its owner or an access admin. Never written back:
     * an update without them keeps the stored ones.
     */
    owner?: string;
    shared?: boolean;
}

/** The Entity Lists a case uses (A-1b). */
export interface CaseLists {
    /** The list the graph started from. */
    start?: string;
    /** Exclusion lists the analyst wrote to from this graph. */
    exclusions?: string[];
}

/** The start a case re-draws when it opens. */
export interface CaseStart {
    datasetId: string;
    /** The Entity Type the start named (LA-ONBOARD-DEFECTS-1); absent = the Dataset's primary type (older cases). */
    entityType?: string;
    values?: string[];
    listId?: string;
}

/** Every Entity List id a view uses, start first, without repeats. */
export function caseListIds(view: Pick<LinkAnalysisView, 'lists'>): string[] {
    return [...new Set([...(view.lists?.start ? [view.lists.start] : []), ...(view.lists?.exclusions ?? [])])];
}

export interface LinkAnalysisViewOptions {
    plugins?: Omit<GraphViewPlugins, 'hulls'> & { hulls?: boolean };
    legend?: boolean;
    workingSet?: boolean;
    /** The folded nodes (Collapse, §5.5): display state, so it travels with the view. */
    collapsed?: string[];
}

/** View store — a thin kind/codec binding over the shared {@link SavedViewStore}. */
@Injectable({ providedIn: 'root' })
export class LinkAnalysisService {
    private store = new SavedViewStore<LinkAnalysisView>(inject(ComponentsService), 'link-analysis-view', {
        toContent,
        fromContent,
    });

    list(): Observable<LinkAnalysisView[]> {
        return this.store.list();
    }

    get(id: string): Observable<LinkAnalysisView | null> {
        return this.store.get(id);
    }

    save(view: LinkAnalysisView, opts?: { update?: boolean; ifMatch?: string }): Observable<LinkAnalysisView> {
        return this.store.save(view, opts);
    }

    /** The Component `content` a save of this view writes. */
    toContent(view: LinkAnalysisView): Record<string, unknown> {
        return this.store.toContent(view);
    }

    /** A view decoded from a Component's `content` (an imported bundle item — Import as draft). */
    fromContent(id: string, content: Record<string, unknown>): LinkAnalysisView {
        return this.store.fromContent(id, content);
    }

    remove(id: string): Observable<unknown> {
        return this.store.remove(id);
    }
}

function toContent(v: LinkAnalysisView): Record<string, unknown> {
    return {
        // The registry stamps `name` with the id on every write (ComponentStore.write: in-file identity == URL id), so
        // the case's own name travels as `title`; `name` stays for older readers and is overwritten by the server.
        name: v.name,
        title: v.name,
        description: v.description,
        sourceId: v.sourceId,
        query: v.query,
        display: v.display,
        layout: v.layout,
        profile: v.profile,
        view: v.view,
        investigations: v.investigations,
        lists: v.lists,
        start: v.start,
    };
}

function fromContent(id: string, content: Record<string, unknown>): LinkAnalysisView {
    const c = content as {
        title?: string;
        name?: string;
        description?: string;
        sourceId?: GraphSourceId;
        query?: GraphSourceQuery;
        display?: GraphDisplayOptions;
        layout?: GraphLayoutId;
        profile?: DomainProfileId;
        view?: LinkAnalysisViewOptions;
        investigations?: InvestigationRef[];
        lists?: unknown;
        start?: unknown;
        owner?: unknown;
        shares?: unknown;
    };
    return {
        id,
        name: (typeof c.title === 'string' && c.title.trim()) || c.name || id,
        description: c.description,
        sourceId: c.sourceId ?? 'lineage',
        query: c.query ?? {},
        display: c.display,
        layout: c.layout,
        profile: c.profile,
        view: c.view,
        investigations: Array.isArray(c.investigations) ? c.investigations : undefined,
        lists: listsOf(c.lists),
        start: startOf(c.start),
        ...(typeof c.owner === 'string' && c.owner ? { owner: c.owner } : {}),
        ...(c.shares != null ? { shared: true } : {}),
    };
}

const ids = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string' && x !== '') : []);

/** A stored `lists` read defensively: an older view, or a hand-edited one, carries none or a wrong shape. */
function listsOf(v: unknown): CaseLists | undefined {
    if (!v || typeof v !== 'object') return undefined;
    const r = v as Record<string, unknown>;
    const start = typeof r['start'] === 'string' && r['start'] ? r['start'] : undefined;
    const exclusions = ids(r['exclusions']);
    return start || exclusions.length ? { ...(start ? { start } : {}), ...(exclusions.length ? { exclusions } : {}) } : undefined;
}

function startOf(v: unknown): CaseStart | undefined {
    if (!v || typeof v !== 'object') return undefined;
    const r = v as Record<string, unknown>;
    if (typeof r['datasetId'] !== 'string' || !r['datasetId']) return undefined;
    const values = ids(r['values']);
    const listId = typeof r['listId'] === 'string' && r['listId'] ? r['listId'] : undefined;
    if (!values.length && !listId) return undefined;
    const entityType = typeof r['entityType'] === 'string' && r['entityType'] ? r['entityType'] : undefined;
    return {
        datasetId: r['datasetId'],
        ...(entityType ? { entityType } : {}),
        ...(values.length ? { values } : {}),
        ...(listId ? { listId } : {}),
    };
}
