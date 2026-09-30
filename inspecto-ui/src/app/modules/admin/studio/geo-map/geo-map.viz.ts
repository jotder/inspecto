import { getVizComponentLoader, registerVizComponent } from 'app/inspecto/viz';

/**
 * Geo's own viz-embed registration (SEP-06): the `geo-map-view` render host, as an ASYNC loader so MapLibre stays out
 * of every eager bundle. The widget kind no longer imports this feature; the app registers it at start-up.
 */
export function registerGeoMapViz(): void {
    if (!getVizComponentLoader('geo-map-view')) {
        registerVizComponent('geo-map-view', () =>
            import('./geo-view-widget.component').then((m) => m.GeoViewWidgetComponent),
        );
    }
}
