/** The scale a Gauge draws its value on (`options.gauge`). Absent: 0–100 (a ratio / percentage). */
export interface GaugeScale {
    min: number;
    max: number;
}

export const DEFAULT_GAUGE_SCALE: GaugeScale = { min: 0, max: 100 };

/**
 * The Gauge's scale from `options.gauge`: each end defaults on its own (0 and 100), and a range that is empty or
 * inverted (`min >= max`) or not finite is `invalid` — the Gauge then draws on 0–100 rather than a broken arc.
 * Framework-free.
 */
export function gaugeScale(opts?: { min?: number; max?: number }): GaugeScale & { invalid: boolean } {
    const min = opts?.min ?? DEFAULT_GAUGE_SCALE.min;
    const max = opts?.max ?? DEFAULT_GAUGE_SCALE.max;
    if (!Number.isFinite(min) || !Number.isFinite(max) || min >= max) return { ...DEFAULT_GAUGE_SCALE, invalid: true };
    return { min, max, invalid: false };
}

/**
 * How much of the arc `value` fills, as a percent of the arc (0–100): `(value − min) / (max − min)`, clamped — a value
 * (or a target) outside the scale pins to its end; the text beside the Gauge still states the real number. A
 * non-finite value fills nothing. Computed as `(value − min) × 100 / (max − min)` so the default 0–100 scale returns the
 * value itself, exactly.
 */
export function gaugeFill(value: number, scale: GaugeScale): number {
    if (!Number.isFinite(value)) return 0;
    const pct = ((value - scale.min) * 100) / (scale.max - scale.min);
    return Math.max(0, Math.min(100, pct));
}
