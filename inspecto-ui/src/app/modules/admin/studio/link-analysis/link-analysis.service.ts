import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { GraphSourceId, GraphSourceQuery, DomainProfileId } from 'app/inspecto/graph';
import { SavedViewStore } from 'app/inspecto/investigation';
import { GraphDisplayOptions, GraphLayoutId, GraphViewPlugins } from 'app/modules/admin/catalog/graph-view.component';

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
export const SAVED_VIEW_NOT_EVIDENCE = 'Saved views re-project live data — a reopened view may differ from the one you saved.';

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
}

export interface LinkAnalysisViewOptions {
    plugins?: Omit<GraphViewPlugins, 'hulls'> & { hulls?: boolean };
    legend?: boolean;
    workingSet?: boolean;
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

    save(view: LinkAnalysisView, opts?: { update?: boolean }): Observable<LinkAnalysisView> {
        return this.store.save(view, opts);
    }

    remove(id: string): Observable<unknown> {
        return this.store.remove(id);
    }
}

function toContent(v: LinkAnalysisView): Record<string, unknown> {
    return {
        name: v.name,
        description: v.description,
        sourceId: v.sourceId,
        query: v.query,
        display: v.display,
        layout: v.layout,
        profile: v.profile,
        view: v.view,
    };
}

function fromContent(id: string, content: Record<string, unknown>): LinkAnalysisView {
    const c = content as {
        name?: string;
        description?: string;
        sourceId?: GraphSourceId;
        query?: GraphSourceQuery;
        display?: GraphDisplayOptions;
        layout?: GraphLayoutId;
        profile?: DomainProfileId;
        view?: LinkAnalysisViewOptions;
    };
    return {
        id,
        name: c.name ?? id,
        description: c.description,
        sourceId: c.sourceId ?? 'lineage',
        query: c.query ?? {},
        display: c.display,
        layout: c.layout,
        profile: c.profile,
        view: c.view,
    };
}
