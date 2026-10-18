import { AnalyticsFunction, AnalyticsParam } from 'app/inspecto/api';

/** Framework-free helpers for the Analytics functions pane. */

/** "q: decimal 0–1" · "mode: continuous | discrete (default continuous)" · "column: column". */
export function formatParam(p: AnalyticsParam): string {
    let detail: string = p.type;
    if (p.type === 'choice' && p.choices?.length) detail = p.choices.join(' | ');
    else if (p.min !== undefined && p.max !== undefined) detail = `${p.type} ${p.min}–${p.max}`;
    const dflt = p.default !== null && p.default !== undefined ? ` (default ${p.default})` : '';
    return `${p.name}: ${detail}${dflt}`;
}

/** All of a function's parameters on one line each; "none" when it takes none. */
export function formatParams(f: AnalyticsFunction): string {
    return f.params.length ? f.params.map(formatParam).join('\n') : 'none';
}

/** The word shown for a cost hint: "linearithmic" → "n log n". */
export function costLabel(cost: AnalyticsFunction['cost']): string {
    switch (cost) {
        case 'constant':
            return 'constant';
        case 'linear':
            return 'linear';
        case 'linearithmic':
            return 'n log n';
        case 'quadratic':
            return 'quadratic';
    }
}

/** "Time series" for "time_series". */
export function kernelLabel(id: string, kernels: { id: string; label: string }[]): string {
    return kernels.find((k) => k.id === id)?.label ?? id;
}
