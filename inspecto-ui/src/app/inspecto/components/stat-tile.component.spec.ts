import { describe, expect, it } from 'vitest';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoStatTileComponent } from './stat-tile.component';

@Component({
    standalone: true,
    imports: [InspectoStatTileComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-stat-tile [label]="label" [value]="value" [hint]="hint" [absentReason]="reason" />`,
})
class HostComponent {
    label = 'Incidents open';
    value: string | number | null | undefined = 0;
    hint = '';
    reason = 'Not available';
}

@Component({
    standalone: true,
    imports: [InspectoStatTileComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-stat-tile label="Service" contentValue><span>READY</span></inspecto-stat-tile>`,
})
class ContentHostComponent {}

describe('InspectoStatTileComponent', () => {
    function create(inputs: Partial<HostComponent> = {}) {
        TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(HostComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }

    it('renders a zero as "0", never as absent', () => {
        const el: HTMLElement = create({ value: 0 }).nativeElement;
        expect(el.querySelector('.tabular-nums')?.textContent?.trim()).toBe('0');
        expect(el.textContent).not.toContain('—');
    });

    it('renders null / undefined / empty as an em dash with the reason as its label', () => {
        for (const value of [null, undefined, ''] as const) {
            TestBed.resetTestingModule();
            const el: HTMLElement = create({ value, reason: 'Jobs backend not configured' }).nativeElement;
            const dash = el.querySelector('[aria-label]');
            expect(dash?.textContent?.trim()).toBe('—');
            expect(dash?.getAttribute('aria-label')).toBe('Jobs backend not configured');
        }
    });

    it('draws NO em dash when the value is projected content', () => {
        // The Overview "Service" tile renders a status badge instead of a number; the dash beside it read
        // as "unknown" next to a perfectly good answer.
        TestBed.resetTestingModule();
        TestBed.configureTestingModule({ imports: [ContentHostComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(ContentHostComponent);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.textContent).not.toContain('—');
        expect(el.textContent).toContain('READY');
    });

    it('renders label and hint', () => {
        const el: HTMLElement = create({ value: 12, hint: 'last 24h' }).nativeElement;
        expect(el.textContent).toContain('Incidents open');
        expect(el.textContent).toContain('last 24h');
    });

    it('has no axe violations', async () => {
        const fixture = create({ value: null });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
