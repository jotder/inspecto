import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api/components.service';
import type { KpiDefinition } from 'app/inspecto/api/kpis.service';
import { ColumnMeta, ConditionGroup } from 'app/inspecto/query';
import type { OverlaySummary } from './chart-overlays';
import { DatasetResultService } from './dataset-result.service';
import { OverlaySuggestion, overlayEligible, suggestOverlays } from './show-me';
import { ControlValues, VizPlugin, VizRenderOptions } from './viz-types';

/** The signal Show-Me reads: the overlay summary and rows of the Widget's statement run with `overlay: {forecast, anomaly}`. */
export interface ShowMeProbe {
    summary: OverlaySummary[];
    rows: Record<string, unknown>[];
}

/** One Widget as Show-Me needs it: its Visualization Type, mapping and options, and where its data comes from. */
export interface ShowMeWidget<O extends VizRenderOptions = VizRenderOptions> {
    plugin: VizPlugin;
    controls: ControlValues;
    options: O;
    datasetId: string;
    sourceName: string;
    filters?: ConditionGroup | null;
    queryId?: string;
    columns?: ColumnMeta[];
}

/**
 * BI-INSIGHTS-1 I-20, shared: the probe and KPI read behind Show-Me, so the Widget builder, the Dashboard tile and the cog
 * dialog offer the same suggestions from the same code ({@link suggestOverlays}). The probe is the I-11 / I-7 route
 * (`POST /bi/query` with `overlay`, D-BI7: query time, nothing stored); an identical probe is served from
 * {@link DatasetResultService}'s cache. Nothing here applies a suggestion — the caller does, on the user's click.
 */
@Injectable({ providedIn: 'root' })
export class ShowMeService {
    private datasetResult = inject(DatasetResultService);
    private components = inject(ComponentsService);

    /**
     * The Widget's signal, or `null` when nothing can be offered from it. With both overlays already on, the plain
     * statement is read instead (its rows still feed the compare rule); a plugin that cannot carry overlays gives null.
     */
    async probe(w: ShowMeWidget): Promise<ShowMeProbe | null> {
        const o = w.options;
        const overlays = overlayEligible(w.plugin.meta.type, w.controls, o);
        if (!overlays) return null;
        const both = !!(o.overlay?.forecast && o.overlay?.anomaly);
        if (both && o.compare?.kind) return null; // every suggestion the probe feeds is on already
        const spec = w.plugin.buildQuery(w.controls, {
            datasetId: w.datasetId,
            sourceName: w.sourceName,
            filters: w.filters ?? null,
            options: both ? o : { ...o, overlay: { forecast: {}, anomaly: {} } },
        });
        if (!both && !spec.overlay) return null; // a plugin that does not ask for overlays
        if (w.queryId) spec.queryId = w.queryId;
        const res = await this.datasetResult.run(spec, w.columns ?? []);
        if (!res.ok) return null;
        return { summary: both ? [] : (res.overlays ?? []), rows: res.rows };
    }

    /** The bound KPI's definition (`options.kpi.kpiId`), read only to offer its target line / bands; null otherwise. */
    async kpi(options: VizRenderOptions): Promise<KpiDefinition | null> {
        const id = options.kpi?.kpiId;
        if (!id) return null;
        try {
            const c = await firstValueFrom(this.components.get('kpi', id));
            return c.content as unknown as KpiDefinition;
        } catch {
            return null;
        }
    }

    /** Probe, KPI and {@link suggestOverlays} in one: the suggestions for a saved Widget (the Dashboard tile). */
    async suggest<O extends VizRenderOptions>(w: ShowMeWidget<O>): Promise<OverlaySuggestion<O>[]> {
        const [probe, kpi] = await Promise.all([this.probe(w).catch(() => null), this.kpi(w.options)]);
        return suggestOverlays({
            vizType: w.plugin.meta.type,
            controls: w.controls,
            options: w.options,
            probe: probe?.summary,
            probeRows: probe?.rows,
            kpi,
        });
    }
}
