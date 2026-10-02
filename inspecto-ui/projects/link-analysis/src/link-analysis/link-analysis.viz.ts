import { getVizComponentLoader, registerVizComponent } from '@inspecto/core/viz';

/**
 * Link Analysis's own viz-embed registration (SEP-06): the `link-analysis-view` and `working-set` render hosts, as
 * ASYNC loaders so G6 stays out of every eager bundle. The widget kind no longer imports this feature; the app
 * registers it at start-up.
 */
export function registerLinkAnalysisViz(): void {
    if (!getVizComponentLoader('link-analysis-view')) {
        registerVizComponent('link-analysis-view', () =>
            import('./link-view-widget.component').then((m) => m.LinkViewWidgetComponent),
        );
    }
    if (!getVizComponentLoader('working-set')) {
        registerVizComponent('working-set', () =>
            import('./working-set-widget.component').then((m) => m.WorkingSetWidgetComponent),
        );
    }
}
