import { describe, expect, it } from 'vitest';
import { CHART_CATEGORICAL_NEUTRAL, CHART_TONE } from 'app/inspecto/theme/chart-tokens';
import { seriesColors } from './series-colors';
import { seriesLabel } from './viz-render.component';

describe('series colours (UIE-2)', () => {
    it('status labels take their meaning, whatever their position', () => {
        const [fail, pass, warning] = seriesColors(['Fail', 'Pass', 'Warning']);
        expect(fail).toBe(CHART_TONE.error);
        expect(pass).toBe(CHART_TONE.success);
        expect(warning).toBe(CHART_TONE.warning);
        const [amber, red] = seriesColors(['Amber', 'Red']);
        expect(amber).toBe(CHART_TONE.warning);
        expect(red).toBe(CHART_TONE.error);
    });

    it('a neutral label keeps its colour across widgets, whatever else is charted', () => {
        const a = seriesColors(['RA recovered', 'FM prevented'])[0];
        const b = seriesColors(['FM prevented', 'RTSC prevented', 'RA recovered'])[2];
        expect(a).toBe(b);
    });

    it('neutral series never take red or green, and never repeat while a colour is free', () => {
        const labels = ['voice', 'sms', 'data', 'charging', 'vas', 'roaming'];
        const colors = seriesColors(labels);
        expect(new Set(colors).size).toBe(labels.length);
        for (const c of colors) expect(CHART_CATEGORICAL_NEUTRAL).toContain(c);
    });
});

describe('series labels (UIE-4)', () => {
    it('reads a generated measure label as a field name', () => {
        expect(seriesLabel('sum(value_at_risk_sar)')).toBe('Value at risk (SAR)');
        expect(seriesLabel('count')).toBe('Count');
        expect(seriesLabel('Daily')).toBe('Daily');
    });
});
