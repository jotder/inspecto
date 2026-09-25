import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    contrastRatio,
    DesignSystemFoundationsComponent,
    formatCssColor,
    parseCssColor,
} from './foundations.component';

function create() {
    TestBed.configureTestingModule({ imports: [DesignSystemFoundationsComponent] });
    const fixture = TestBed.createComponent(DesignSystemFoundationsComponent);
    fixture.detectChanges();
    return fixture;
}

describe('DesignSystemFoundationsComponent', () => {
    it('computes the WCAG contrast ratio', () => {
        expect(contrastRatio([255, 255, 255], [0, 0, 0])).toBeCloseTo(21, 5);
        expect(contrastRatio([0, 0, 0], [255, 255, 255])).toBeCloseTo(21, 5);
        // the error badge in light mode: red-800 on red-100
        expect(contrastRatio([153, 27, 27], [254, 226, 226])).toBeCloseTo(6.8, 1);
        // primary-600 on the dark card (slate-800) — the pair the chip fix exists for
        expect(contrastRatio([79, 70, 229], [30, 41, 59])).toBeLessThan(4.5);
    });

    it('parses computed colours and formats them for display', () => {
        expect(parseCssColor('rgb(30, 41, 59)')).toEqual([30, 41, 59, 1]); // ds-allow — parser input
        expect(parseCssColor('rgba(241, 245, 249, 0.12)')).toEqual([241, 245, 249, 0.12]); // ds-allow
        expect(parseCssColor('')).toBeNull();
        expect(parseCssColor('transparent')).toBeNull();
        expect(formatCssColor('rgb(30, 41, 59)')).toBe('#1e293b'); // ds-allow — formatter input
        expect(formatCssColor('rgba(241, 245, 249, 0.12)')).toBe('#f1f5f9 @ 12%'); // ds-allow
        expect(formatCssColor(undefined)).toBe('—');
    });

    it('renders a light and a dark island with every status tone as a measured specimen', () => {
        const el = create().nativeElement as HTMLElement;
        expect(el.querySelector(':scope > div > .light')).toBeTruthy();
        expect(el.querySelector(':scope > div > .dark')).toBeTruthy();
        for (const scheme of ['light', 'dark']) {
            for (const tone of ['success', 'warning', 'error', 'info', 'neutral']) {
                const probe = el.querySelector(`[data-probe="${scheme}:tone:${tone}"]`) as HTMLElement;
                expect(probe).toBeTruthy();
                // the island's own classes, never a dark: variant that a .light island could not switch off
                expect(probe.className).not.toContain('dark:');
            }
        }
        // jsdom has no stylesheet: unresolved values read as a dash, never as a fabricated number
        expect(el.textContent).not.toContain('NaN');
    });

    it('has no a11y violations', async () => {
        await expectNoA11yViolations(create().nativeElement);
    });
});
