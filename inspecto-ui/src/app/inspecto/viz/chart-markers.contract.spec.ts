import CONTRACT from 'app/inspecto/contracts/chart-markers.contract.json';
import { ChartMarker, MarkerKpi, resolveMarkers } from './chart-markers';
import { OverlayCell, OverlaySummary, overlayNote, resolveOverlays } from './chart-overlays';

/**
 * REPORT-CHART-MARKERS-1: the screen's resolvers against the contract the report PDF's `ChartMarkers` (Java) is held to
 * by `ChartMarkersContractTest` — same stored markers and overlays, same lines, bands and words.
 */
type Expected = Record<string, unknown>;

function expectMatches(actual: unknown[], expected: Expected[]): void {
    expect(actual.length).toBe(expected.length);
    expected.forEach((e, i) => {
        const a = actual[i] as Record<string, unknown>;
        for (const [k, v] of Object.entries(e)) {
            if (typeof v === 'number') expect(a[k] as number).toBeCloseTo(v, 9);
            else expect(a[k] ?? null).toEqual(v);
        }
    });
}

describe('chart-markers contract (REPORT-CHART-MARKERS-1)', () => {
    for (const c of CONTRACT.markers) {
        it(`markers: ${c.name}`, () => {
            const out = resolveMarkers(c.markers as unknown as ChartMarker[], {
                values: c.values as number[],
                target: (c as { target?: number }).target,
                kpi: c.kpi as MarkerKpi | null,
            });
            expectMatches(out, c.expected as Expected[]);
        });
    }
    for (const c of CONTRACT.overlays) {
        it(`overlays: ${c.name}`, () => {
            const series = (c.series as { label: string; data: (number | null)[]; overlay?: OverlayCell[] }[]).map((s) => ({
                label: s.label,
                data: s.data.map((v) => (v == null ? NaN : v)),
                overlay: s.overlay,
            }));
            const out = resolveOverlays(series, c.labels, { overlay: c.overlay }, c.overlay.anomaly?.['sensitivity' as never]);
            expectMatches(out, c.expected as Expected[]);
        });
    }
    for (const c of CONTRACT.notes) {
        it(`notes: ${c.name}`, () => {
            expect(overlayNote(c.summary as OverlaySummary[], c.flagged, c.sensitivity ?? undefined, c.unit)).toEqual(c.expected);
        });
    }
});
