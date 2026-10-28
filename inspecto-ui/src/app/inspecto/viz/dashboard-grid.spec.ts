import { describe, expect, it } from 'vitest';
import { nextSpan, spanLabel, tileBasis, tileSpan } from './dashboard-grid';

describe('dashboard grid (UIE-3)', () => {
    it('four columns: a quarter, a half, three quarters and the full row', () => {
        expect(tileBasis(4)).toBe('100%');
        const cols = 'var(--dashboard-columns, 4)';
        expect(tileBasis(1)).toBe(
            `calc((100% - (${cols} - 1) * 1rem) * min(1, ${cols}) / ${cols} + (min(1, ${cols}) - 1) * 1rem)`,
        );
        expect(tileBasis(2)).toBe(
            `calc((100% - (${cols} - 1) * 1rem) * min(2, ${cols}) / ${cols} + (min(2, ${cols}) - 1) * 1rem)`,
        );
    });

    it('responsive: the width follows the live column count, the span clamped to it (gap #11)', () => {
        // Evaluate the CSS expression for a 600px row at 4 / 2 / 1 columns (1rem = 16px).
        const px = (span: number, cols: number) => {
            const css = tileBasis(span);
            if (css === '100%') return 600;
            const js = css
                .replaceAll('var(--dashboard-columns, 4)', String(cols))
                .replaceAll('100%', '600')
                .replaceAll('rem', '*16')
                .replace(/^calc/, '')
                .replaceAll('min', 'Math.min');
            return Function(`return ${js}`)() as number;
        };
        expect(px(1, 4)).toBe(138); // a quarter at desktop
        expect(px(1, 2)).toBe(292); // a KPI tile takes half the row at tablet
        expect(px(1, 1)).toBe(600); // and the whole row at phone
        expect(px(3, 2)).toBe(600); // a three-quarter tile clamps to the full row at tablet
        expect(px(2, 1)).toBe(600);
    });

    it('reads an invalid stored span as half', () => {
        expect(tileSpan(undefined)).toBe(2);
        expect(tileSpan(0)).toBe(2);
        expect(tileSpan('wide')).toBe(2);
        expect(tileSpan(3)).toBe(3);
    });

    it('the width button cycles quarter → half → three quarters → full → quarter', () => {
        expect([1, 2, 3, 4].map(nextSpan)).toEqual([2, 3, 4, 1]);
        expect(spanLabel(1)).toBe('quarter width');
    });
});
