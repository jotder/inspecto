// Public surface of the Link Analysis / Geo library (D-5 step 3) - ONLY what a shell's EAGER code (app.config, the
// la-host provider set) needs. It is deliberately not a barrel of everything, and deliberately light:
//
//   * the ten `la-host` tokens, their contract types and the slot component (the host seam), and
//   * the two viz-kind registration functions, which register ASYNC loaders so G6 / MapLibre stay out of every eager bundle.
//
// ⚠ A static import of this file from main-chunk code evaluates every module it re-exports (esbuild keeps a source
// module's side effects), so NOTHING heavy may be added here. Measured 2026-10-02: re-exporting the widget components and
// `MapViewComponent` moved MapLibre (1.2 MB) and the Link Analysis widgets from their lazy chunks into `main`. Lazy
// consumers therefore import the deep path they need:
//   - routes:   `import('@inspecto/link-analysis/link-analysis/link-analysis.routes')` and `.../geo-map/geo-map.routes`
//   - widgets:  `@inspecto/link-analysis/link-analysis/link-view-widget.component`, `.../geo-map/geo-view-widget.component`
//   - the map component and the two Settings clients: `.../geo`, `.../api/geo-settings.service`,
//     `.../link-analysis/link-analysis-settings.service`
// (the `@inspecto/link-analysis/*` alias exists for exactly these lazy boundaries and for the library's own cross-folder
// imports - a library file may not use `../`).

export * from './la-host';
export { registerLinkAnalysisViz } from './link-analysis/link-analysis.viz';
export { registerGeoMapViz } from './geo-map/geo-map.viz';
