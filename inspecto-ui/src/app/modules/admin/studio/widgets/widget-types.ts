import { ControlValues, VizRenderOptions } from 'app/inspecto/viz';
import type { WorkingSetBinding } from '@inspecto/link-analysis';

export type { WorkingSetBinding };

/**
 * Studio **Widget** model — a saved visualization = a dataset reference + a viz plugin type + the field→channel
 * mapping. Stored as a `widget` component (mock-served); its component `config` is the {@link WidgetConfig}.
 * The widget's "wiring" (in component-model terms) is the channel mapping. Mirrors `dataset-types.ts`.
 */
export interface WidgetConfig {
    /** Empty for view-bound widgets (`viewId` is their binding instead). */
    datasetId: string;
    /** Query-bound widgets (R3): a saved `query` component supplies the rows instead of the dataset's own
     *  columns→spec path. The widget's `binds` edge then points at the query (which binds the dataset). */
    queryId?: string;
    /** The VizPlugin type (`bar`/`line`/`kpi`/…, or the view-bound `geo-map`/`link-analysis`). */
    vizType: string;
    /** The field→channel mapping the plugin compiles to a QuerySpec (empty for view-bound widgets). */
    controls: ControlValues;
    /** View-bound widgets only: the saved investigation view (`geo-map-view`/`link-analysis-view`) to render —
     *  or, for a Working Set Widget, the Investigation it reads. */
    viewId?: string;
    /** Working Set Widgets only (LA-21): the relation, the Frozen/Live mode and the pin. */
    workingSet?: WorkingSetBinding;
    /** Free-text tags for the library gallery's search/filter (e.g. `ops`, `billing`). */
    tags?: string[];
    /** Shown as the library card's subtitle. */
    description?: string;
    /** The advanced/cog options — all optional, the render host applies sane defaults when omitted. */
    options?: WidgetOptions;
}

/**
 * The advanced (cog-icon) config, distinct from the mandatory field mapping. A closed, curated set — no
 * free-form styling — so the widget stays simple to configure (colors resolve only from a named palette).
 * Extends the render-affecting {@link VizRenderOptions} with the two caption-only fields.
 */
export interface WidgetOptions extends VizRenderOptions {
    /** Caption override; defaults to the widget's name. */
    title?: string;
    subtitle?: string;
}

export interface Widget extends WidgetConfig {
    id: string;
    name: string;
}

/** Build a {@link Widget} from a name + dataset/viz/controls (mirrors `buildDataset`). */
export function buildWidget(
    name: string,
    datasetId: string,
    vizType: string,
    controls: ControlValues,
    extra?: Pick<WidgetConfig, 'tags' | 'description' | 'options' | 'viewId' | 'queryId' | 'workingSet'>,
): Widget {
    return {
        id: name,
        name,
        datasetId,
        queryId: extra?.queryId,
        vizType,
        controls,
        viewId: extra?.viewId,
        workingSet: extra?.workingSet,
        tags: extra?.tags,
        description: extra?.description,
        options: extra?.options,
    };
}

/**
 * A KPI tile bound to a KPI definition (`options.kpi.kpiId`, ASSURE-KPI-DEFINITIONS-1) and to no Dataset: the KPI
 * component reads its own value through the definition, so the host runs no query and needs no Dataset (LIVEFIX2 #2 —
 * the dashboard drew "Dataset for “” not found." for every such tile).
 */
export function isKpiBound(w: Pick<WidgetConfig, 'datasetId' | 'options' | 'vizType'>): boolean {
    return w.vizType === 'kpi' && !w.datasetId && !!w.options?.kpi?.kpiId;
}
