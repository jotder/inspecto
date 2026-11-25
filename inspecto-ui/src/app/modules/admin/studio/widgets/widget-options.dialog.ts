import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    DestroyRef,
    ViewChild,
    inject,
    signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { kpiOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { MARKER_VIZ_TYPES } from 'app/inspecto/viz/chart-markers';
import { ChartMarkersEditorComponent } from './chart-markers-editor.component';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { RowLinkKind } from 'app/inspecto/viz/viz-types';
import { HeatmapScale } from 'app/inspecto/viz/heatmap';
import { NumberFormat } from 'app/inspecto/viz/number-format';
import { COMPARE_KINDS, CompareKind } from 'app/inspecto/viz/compare';
import { WidgetOptions } from './widget-types';
import { WIDGET_OPTION_ATTRIBUTES, WIDGET_OPTION_VIZ_TYPES } from './widget-option-attributes';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { guardDirtyClose } from 'app/inspecto/dialog-dirty-guard';

/** The advanced (cog) dialog's input — the widget's current options and its Visualization Type (which decides the
 *  per-type fields shown). The dialog closes with the edited options (or undefined on cancel). A closed, curated set
 *  of knobs — no free-form styling. */
export interface WidgetOptionsData {
    options: WidgetOptions;
    vizType?: string;
}

/**
 * Advanced widget options (the cog dialog) — spec-driven via `<inspecto-schema-form>`
 * ({@link WIDGET_OPTION_ATTRIBUTES}). Every knob is optional; most are always visible, the per-type ones
 * ({@link WIDGET_OPTION_VIZ_TYPES}) only for their vizType. The flat form values are re-nested on save.
 */
@Component({
    selector: 'app-widget-options-dialog',
    standalone: true,
    imports: [MatDialogModule, MatButtonModule, InspectoSchemaFormComponent, ChartMarkersEditorComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Advanced options</h2>
        <mat-dialog-content>
            <inspecto-schema-form
                #sf
                [specs]="attributes"
                [initial]="initialValue"
                [optionLoaders]="optionLoaders"
            ></inspecto-schema-form>
            @if (marksSupported) {
                <app-chart-markers-editor #markers [initial]="data.markers ?? []" [kpiBound]="kpiChosen()" />
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class WidgetOptionsDialog implements AfterViewInit {
    private ref = inject(MatDialogRef<WidgetOptionsDialog, WidgetOptions>);
    private confirm = inject(InspectoConfirmService);
    private destroyRef = inject(DestroyRef);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(
        this.ref,
        () => (this.schemaForm?.isDirty() ?? false) || (this.markersEditor?.isDirty() ?? false),
        this.confirm,
    );
    private readonly input = inject<WidgetOptionsData>(MAT_DIALOG_DATA);
    readonly data: WidgetOptions = this.input.options;

    /** BI-INSIGHTS-1 I-2: Chart Markers are offered on the types that draw them. */
    readonly marksSupported = this.input.vizType !== undefined && MARKER_VIZ_TYPES.includes(this.input.vizType);
    /** The KPI picker suggests saved KPI definitions. */
    readonly optionLoaders = { kpiId: kpiOptionLoader() };
    /** Whether a KPI is chosen right now — a KPI status bands marker says so when it is not. */
    readonly kpiChosen = signal(!!this.data.kpi?.kpiId);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;
    @ViewChild('markers') markersEditor?: ChartMarkersEditorComponent;

    ngAfterViewInit(): void {
        if (!this.marksSupported) return;
        this.schemaForm.form.valueChanges
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe((v: Record<string, unknown>) =>
                this.kpiChosen.set(typeof v['kpiId'] === 'string' && v['kpiId'].trim() !== ''),
            );
    }
    /** Every option, minus the per-type ones belonging to another vizType. */
    readonly attributes = WIDGET_OPTION_ATTRIBUTES.filter(
        (s) =>
            !(s.key in WIDGET_OPTION_VIZ_TYPES) ||
            (this.input.vizType !== undefined && [WIDGET_OPTION_VIZ_TYPES[s.key]].flat().includes(this.input.vizType)),
    );

    /** Current options mapped onto the flat attribute keys (axis/legend are flattened). */
    readonly initialValue: Record<string, unknown> = {
        title: this.data.title ?? '',
        subtitle: this.data.subtitle ?? '',
        xTitle: this.data.axis?.xTitle ?? '',
        yTitle: this.data.axis?.yTitle ?? '',
        y2Title: this.data.axis?.y2Title ?? '',
        legendShow: this.data.legend?.show === undefined ? 'auto' : this.data.legend.show ? 'show' : 'hide',
        legendPosition: this.data.legend?.position ?? 'top',
        palette: this.data.palette ?? undefined,
        sort: this.data.sort ?? '',
        limit: this.data.limit ?? null,
        stacked: this.data.stacked ?? false,
        hideBlank: this.data.hideBlank ?? false,
        numberStyle: this.data.format?.style === 'number' ? '' : (this.data.format?.style ?? ''),
        currency: this.data.format?.currency ?? '',
        compact: this.data.format?.compact ?? false,
        decimals: this.data.format?.decimals ?? null,
        kpiTarget: this.data.kpi?.target ?? null,
        kpiBetter: this.data.kpi?.better ?? 'higher',
        kpiId: this.data.kpi?.kpiId ?? '',
        waterfallStart: this.data.waterfall?.start ?? '',
        waterfallShowTotal: this.data.waterfall?.totalLabel !== '',
        waterfallTotalLabel: this.data.waterfall?.totalLabel ?? '',
        waterfallOrder: this.data.waterfall?.order ?? 'data',
        comboSecondaryAxis: this.data.combo?.secondaryAxis ?? true,
        heatmapScale: this.data.heatmap?.scale ?? 'sequential',
        heatmapMidpoint: this.data.heatmap?.midpoint ?? null,
        rowLinkKind: this.data.rowLink?.kind ?? '',
        rowLinkIdField: this.data.rowLink?.idField ?? '',
        // Per-Visualization-Type options (WIDGET_OPTION_VIZ_TYPES).
        trendCompareBack: this.data.trend?.compareBack ?? null,
        progressMax: this.data.progress?.max ?? null,
        treemapLimit: this.data.treemap?.limit ?? null,
        format2Style: this.data.format2?.style === 'number' ? '' : (this.data.format2?.style ?? ''),
        format2Currency: this.data.format2?.currency ?? '',
        format2Compact: this.data.format2?.compact ?? false,
        format2Decimals: this.data.format2?.decimals ?? null,
        gaugeMin: this.data.gauge?.min ?? null,
        gaugeMax: this.data.gauge?.max ?? null,
        tableSortField: this.data.tableSort?.field ?? '',
        tableSortDir: this.data.tableSort?.dir ?? 'desc',
        categoryOrderField: this.data.categoryOrder?.field ?? '',
        categoryOrderDir: this.data.categoryOrder?.dir ?? 'asc',
        categoryOrderAgg: this.data.categoryOrder?.agg ?? 'min',
        rowLinkLabelField: this.data.rowLink?.labelField ?? '', // UIE-6 link text column
        compareKind: this.data.compare?.kind ?? '', // BI-INSIGHTS-1 I-1
        forecastOn: !!this.data.overlay?.forecast, // BI-INSIGHTS-1 I-11
        forecastLevel: String(this.data.overlay?.forecast?.level ?? 0.8),
        anomalyOn: !!this.data.overlay?.anomaly, // BI-INSIGHTS-1 I-7
        anomalySensitivity: String(this.data.overlay?.anomaly?.sensitivity ?? 3),
    };

    save(): void {
        // A Chart Marker row that needs a fix keeps the dialog open, its error line on screen.
        const markers = this.marksSupported ? this.markersEditor?.markers() : undefined;
        if (markers === null) return;
        const v = this.schemaForm.value();
        const str = (x: unknown) => (typeof x === 'string' ? x.trim() : '');
        // The KPI the Widget is bound to: the picker where it is shown, else kept as stored (a KPI tile's binding).
        const kpiId = 'kpiId' in v ? str(v['kpiId']) || undefined : this.data.kpi?.kpiId;
        const xTitle = str(v['xTitle']);
        const yTitle = str(v['yTitle']);
        const y2Title = str(v['y2Title']);
        const style = str(v['numberStyle']) as '' | 'currency' | 'percent';
        const currency = str(v['currency']).toUpperCase();
        const decimals = typeof v['decimals'] === 'number' ? (v['decimals'] as number) : undefined;
        const compact = (v['compact'] as boolean) ?? false;
        const format =
            style || currency || compact || decimals != null
                ? {
                      style: style || undefined,
                      currency: currency || undefined,
                      compact: compact || undefined,
                      decimals,
                  }
                : undefined;
        const target = typeof v['kpiTarget'] === 'number' ? (v['kpiTarget'] as number) : undefined;
        const better = (str(v['kpiBetter']) || 'higher') as 'higher' | 'lower';
        // A waterfall block only when something differs from the defaults (opening none, total "Total", data order).
        const start = str(v['waterfallStart']);
        const totalLabel =
            (v['waterfallShowTotal'] as boolean) === false ? '' : str(v['waterfallTotalLabel']) || undefined;
        const order = (str(v['waterfallOrder']) || 'data') as 'data' | 'asc' | 'desc';
        const waterfall =
            start || totalLabel !== undefined || order !== 'data'
                ? { start: start || undefined, totalLabel, order: order === 'data' ? undefined : order }
                : undefined;
        const secondaryAxis = (v['comboSecondaryAxis'] as boolean) ?? true;
        const heatScale = (str(v['heatmapScale']) || 'sequential') as HeatmapScale;
        const heatMid = typeof v['heatmapMidpoint'] === 'number' ? (v['heatmapMidpoint'] as number) : undefined;
        const linkKind = str(v['rowLinkKind']) as '' | RowLinkKind;
        const linkField = str(v['rowLinkIdField']);
        // Legend: Auto writes no `show` (the chart theme decides); the default top position alone writes no block.
        const legendMode = str(v['legendShow']) || 'auto';
        const legendPosition = (str(v['legendPosition']) || 'top') as 'top' | 'right' | 'bottom' | 'left';
        const linkLabel = str(v['rowLinkLabelField']); // UIE-6: optional; blank = link on the id cell
        const options: WidgetOptions = {
            // Keep what this dialog does not model (columnLabels, badgeColumns, columnFormats…): rebuilding the
            // object from the form alone silently wiped them on every save.
            ...this.data,
            title: str(v['title']) || undefined,
            subtitle: str(v['subtitle']) || undefined,
            axis:
                xTitle || yTitle || y2Title
                    ? { xTitle: xTitle || undefined, yTitle: yTitle || undefined, y2Title: y2Title || undefined }
                    : undefined,
            legend:
                legendMode === 'auto' && legendPosition === 'top'
                    ? undefined
                    : {
                          ...(legendMode === 'auto' ? {} : { show: legendMode === 'show' }),
                          position: legendPosition,
                      },
            palette: (v['palette'] as string) || undefined,
            sort: (str(v['sort']) || undefined) as WidgetOptions['sort'],
            limit: (v['limit'] as number) ?? undefined,
            stacked: (v['stacked'] as boolean) ?? false,
            hideBlank: (v['hideBlank'] as boolean) || undefined,
            format,
            kpi:
                target != null || better === 'lower' || kpiId
                    ? { target, better, ...(kpiId ? { kpiId } : {}) }
                    : undefined,
            waterfall,
            combo: secondaryAxis ? undefined : { secondaryAxis: false },
            heatmap:
                heatScale !== 'sequential' || heatMid != null
                    ? { scale: heatScale, ...(heatMid == null ? {} : { midpoint: heatMid }) }
                    : undefined,
            rowLink:
                linkKind && linkField
                    ? { kind: linkKind, idField: linkField, ...(linkLabel ? { labelField: linkLabel } : {}) }
                    : undefined,
        };
        this.applyPerTypeOptions(v, options);
        if (markers !== undefined) options.markers = markers.length ? markers : undefined;
        this.ref.close(options);
    }

    /**
     * The per-Visualization-Type options ({@link WIDGET_OPTION_VIZ_TYPES}). A field not shown for this vizType is
     * absent from `v` and leaves the stored value as it was; a shown field left blank or at its default removes the
     * key, so the stored TOON stays minimal. Sibling keys this dialog does not model (`progress.limit`) are kept.
     */
    private applyPerTypeOptions(v: Record<string, unknown>, o: WidgetOptions): void {
        const int = (x: unknown, min: number) => (typeof x === 'number' && x >= min ? Math.floor(x) : undefined);
        const merge = <T extends object>(base: T | undefined, patch: Partial<T>): T | undefined => {
            const out = { ...base, ...patch } as Record<string, unknown>;
            for (const k of Object.keys(out)) if (out[k] === undefined) delete out[k];
            return Object.keys(out).length ? (out as T) : undefined;
        };
        if ('trendCompareBack' in v) {
            const back = int(v['trendCompareBack'], 1);
            o.trend = merge(o.trend, { compareBack: back === 1 ? undefined : back });
        }
        if ('progressMax' in v) {
            const max = typeof v['progressMax'] === 'number' ? (v['progressMax'] as number) : undefined;
            o.progress = merge(o.progress, { max });
        }
        if ('treemapLimit' in v) {
            const limit = int(v['treemapLimit'], 2);
            o.treemap = merge(o.treemap, { limit: limit === 20 ? undefined : limit });
        }
        if ('format2Style' in v) {
            const str = (x: unknown) => (typeof x === 'string' ? x.trim() : '');
            const format2: NumberFormat = {
                style: (str(v['format2Style']) || undefined) as NumberFormat['style'],
                currency: str(v['format2Currency']).toUpperCase() || undefined,
                compact: (v['format2Compact'] as boolean) || undefined,
                decimals: typeof v['format2Decimals'] === 'number' ? (v['format2Decimals'] as number) : undefined,
            };
            o.format2 = merge<NumberFormat>(undefined, format2);
        }
        if ('gaugeMin' in v) {
            const num = (x: unknown) => (typeof x === 'number' ? x : undefined);
            o.gauge = merge(o.gauge, { min: num(v['gaugeMin']), max: num(v['gaugeMax']) });
        }
        if ('tableSortField' in v) {
            const raw = v['tableSortField'];
            const field = typeof raw === 'string' ? raw.trim() : '';
            o.tableSort = field ? { field, dir: v['tableSortDir'] === 'asc' ? 'asc' : 'desc' } : undefined;
        }
        if ('categoryOrderField' in v) {
            const raw = v['categoryOrderField'];
            const field = typeof raw === 'string' ? raw.trim() : '';
            o.categoryOrder = field
                ? {
                      field,
                      dir: v['categoryOrderDir'] === 'desc' ? 'desc' : 'asc',
                      ...(v['categoryOrderAgg'] === 'max' ? { agg: 'max' as const } : {}),
                  }
                : undefined;
        }
        if ('forecastOn' in v || 'anomalyOn' in v) {
            // BI-INSIGHTS-1 I-11 / I-7: both off writes no `overlay` at all.
            const level = Number(v['forecastLevel']);
            const sensitivity = Number(v['anomalySensitivity']);
            const forecast =
                v['forecastOn'] === true
                    ? { ...(Number.isFinite(level) && level !== 0.8 ? { level } : {}) }
                    : undefined;
            const anomaly =
                v['anomalyOn'] === true
                    ? { ...(Number.isInteger(sensitivity) && sensitivity !== 3 ? { sensitivity } : {}) }
                    : undefined;
            o.overlay =
                forecast || anomaly
                    ? { ...(forecast ? { forecast } : {}), ...(anomaly ? { anomaly } : {}) }
                    : undefined;
        }
        if ('compareKind' in v) {
            const kind = v['compareKind'];
            o.compare =
                typeof kind === 'string' && (COMPARE_KINDS as readonly string[]).includes(kind)
                    ? { kind: kind as CompareKind }
                    : undefined;
        }
    }
}
