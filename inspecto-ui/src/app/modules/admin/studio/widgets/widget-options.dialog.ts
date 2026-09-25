import { ChangeDetectionStrategy, Component, ViewChild, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { RowLinkKind } from 'app/inspecto/viz/viz-types';
import { HeatmapScale } from 'app/inspecto/viz/heatmap';
import { NumberFormat } from 'app/inspecto/viz/number-format';
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
    imports: [MatDialogModule, MatButtonModule, InspectoSchemaFormComponent],
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        <h2 mat-dialog-title>Advanced options</h2>
        <mat-dialog-content>
            <inspecto-schema-form #sf [specs]="attributes" [initial]="initialValue"></inspecto-schema-form>
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button type="button" mat-button (click)="requestClose()">Cancel</button>
            <button type="button" mat-flat-button color="primary" (click)="save()">Save</button>
        </mat-dialog-actions>
    `,
})
export class WidgetOptionsDialog {
    private ref = inject(MatDialogRef<WidgetOptionsDialog, WidgetOptions>);
    private confirm = inject(InspectoConfirmService);

    /** Cancel/Esc/backdrop ask before discarding typed input (ui-design-review R2). */
    readonly requestClose = guardDirtyClose(this.ref, () => this.schemaForm?.isDirty() ?? false, this.confirm);
    private readonly input = inject<WidgetOptionsData>(MAT_DIALOG_DATA);
    readonly data: WidgetOptions = this.input.options;

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;
    /** Every option, minus the per-type ones belonging to another vizType. */
    readonly attributes = WIDGET_OPTION_ATTRIBUTES.filter(
        (s) => !(s.key in WIDGET_OPTION_VIZ_TYPES) || WIDGET_OPTION_VIZ_TYPES[s.key] === this.input.vizType,
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
    };

    save(): void {
        const v = this.schemaForm.value();
        const str = (x: unknown) => (typeof x === 'string' ? x.trim() : '');
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
            kpi: target != null || better === 'lower' ? { target, better } : undefined,
            waterfall,
            combo: secondaryAxis ? undefined : { secondaryAxis: false },
            heatmap:
                heatScale !== 'sequential' || heatMid != null
                    ? { scale: heatScale, ...(heatMid == null ? {} : { midpoint: heatMid }) }
                    : undefined,
            rowLink: linkKind && linkField ? { kind: linkKind, idField: linkField } : undefined,
        };
        this.applyPerTypeOptions(v, options);
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
    }
}
