// Public surface of the Link Analysis / Geo library (D-5 step 3) - ONLY what a shell (the Inspecto host, later la-app)
// needs. It is deliberately not a barrel of everything: the graph algorithms, dialogs, toolbox and API clients are
// internal and reach the host only through these names.
//
// ⚠ The two lazy route files (`link-analysis/link-analysis.routes`, `geo-map/geo-map.routes`) are NOT re-exported here:
// the shell loads them with a dynamic `import('@inspecto/link-analysis/<folder>/<file>')` so the lazy chunk boundary
// survives (a static re-export from this file would pull every component into the importer's chunk).

// Host seam: the ten `la-host` tokens, their contract types and the slot component.
export * from './la-host';

// Viz-kind registrations (called from the shell's app.config) and the widget-embed components (SEP-06 contract).
export { registerLinkAnalysisViz } from './link-analysis/link-analysis.viz';
export { registerGeoMapViz } from './geo-map/geo-map.viz';
export { LinkViewWidgetComponent } from './link-analysis/link-view-widget.component';
export { GeoViewWidgetComponent } from './geo-map/geo-view-widget.component';

// The map component the design-system page shows, and the two settings clients the Settings pages edit.
export { MapViewComponent, type GeoData } from './geo';
export { GeoSettingsService } from './api/geo-settings.service';
export { LinkAnalysisSettingsService, type LinkAnalysisLimits } from './link-analysis/link-analysis-settings.service';
