import { ChartData } from 'chart.js';
import { formatNumber, NumberFormat } from './number-format';
import { VizProps, VizRenderOptions } from './viz-types';

/**
 * The Combo's pure core: bar series (`kind: 'bar'`, the `y` channel) and line series (`kind: 'line'`, `y2`) on one
 * category axis. Lines draw on the secondary right axis `y2` unless `options.combo.secondaryAxis` is `false`, and on
 * top of the bars. Bars show as squares and lines as circles in the legend, so the two kinds differ by shape as well
 * as colour. Framework-free apart from the Chart.js data TYPE.
 */

/** Whether a combo series is a line (a `y2` measure). */
export function isLineSeries(s: { [k: string]: unknown }): boolean {
    return s['kind'] === 'line';
}

/** The secondary axis is on by default whenever there is a line to put on it. */
export function comboUsesSecondaryAxis(props: VizProps, o?: VizRenderOptions): boolean {
    return props.series.some(isLineSeries) && (o?.combo?.secondaryAxis ?? true);
}

/** Chart.js datasets for the combo. `colors[i]` colours `props.series[i]`; `labels` are the axis labels to show. */
export function comboChartData(
    props: VizProps,
    labels: readonly string[],
    colors: readonly string[],
    o: VizRenderOptions | undefined,
    seriesName: (label: string) => string,
): ChartData {
    const secondary = comboUsesSecondaryAxis(props, o);
    return {
        labels: [...labels],
        datasets: props.series.map((s, i) =>
            isLineSeries(s)
                ? {
                      type: 'line' as const,
                      label: seriesName(s.label),
                      data: s.data,
                      borderColor: colors[i],
                      backgroundColor: colors[i],
                      yAxisID: secondary ? 'y2' : 'y',
                      pointStyle: 'circle',
                      pointRadius: 3,
                      fill: false,
                      order: 0,
                  }
                : {
                      type: 'bar' as const,
                      label: seriesName(s.label),
                      data: s.data,
                      backgroundColor: colors[i],
                      borderColor: colors[i],
                      yAxisID: 'y',
                      pointStyle: 'rect',
                      stack: o?.stacked ? 'stack' : undefined,
                      order: 1,
                  },
        ),
    } as ChartData;
}

/** The format a combo series reads in: `format2` for a line measure (falling back to `format`), `format` for a bar. */
export function comboSeriesFormat(line: boolean, o?: VizRenderOptions): NumberFormat | undefined {
    return line ? (o?.format2 ?? o?.format) : o?.format;
}

/** The canvas's text alternative: which measures are bars and which are lines, then each category's values (capped). */
export function comboAltText(
    props: VizProps,
    labels: readonly string[],
    o: VizRenderOptions | undefined,
    seriesName: (label: string) => string,
): string {
    if (!labels.length) return 'Combo chart with no data.';
    const secondary = comboUsesSecondaryAxis(props, o);
    const bars = props.series.filter((s) => !isLineSeries(s)).map((s) => seriesName(s.label));
    const lines = props.series.filter(isLineSeries).map((s) => seriesName(s.label));
    const kinds = [
        bars.length ? `bars: ${bars.join(', ')}` : '',
        lines.length ? `line: ${lines.join(', ')}${secondary ? ' (right axis)' : ''}` : '',
    ]
        .filter(Boolean)
        .join('; ');
    const rows = labels.slice(0, 10).map((l, i) => {
        const values = props.series.map(
            (s) => `${seriesName(s.label)} ${formatNumber(s.data[i], comboSeriesFormat(isLineSeries(s), o))}`,
        );
        return `${l}: ${values.join(', ')}`;
    });
    const more = labels.length > 10 ? `; and ${labels.length - 10} more` : '';
    return `Combo chart, ${kinds}. ${rows.join('; ')}${more}.`;
}
