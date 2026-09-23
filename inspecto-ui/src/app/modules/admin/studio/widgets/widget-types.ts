import { ControlValues, VizRenderOptions } from 'app/inspecto/viz';
import type { WorkingSetRelationName } from 'app/inspecto/api';

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
