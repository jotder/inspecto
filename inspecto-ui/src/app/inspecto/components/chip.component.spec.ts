import { Component, ChangeDetectionStrategy, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { statusBadgeClasses } from './status-badge.component';
import { CHIP_BASE, CHIP_TONES, ChipComponent, chipClasses, chipSoftSchemeClasses } from './chip.component';

@Component({
    standalone: true,
    imports: [ChipComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <inspecto-chip [variant]="variant()" [tone]="tone()" [removable]="removable()" (removed)="onRemoved()"
            >label</inspecto-chip
        >
    `,
})
class HostComponent {
    // Zoneless CD: plain-field mutations don't mark the OnPush host dirty, so the verify sweep
    // would trip NG0100 on stale stored bindings. Signals keep the harness honest.
    variant = signal<'outline' | 'soft'>('outline');
    tone = signal<'neutral' | 'primary' | 'warning'>('neutral');
    removable = signal(false);
    onRemoved = vi.fn();
}

function create() {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
}

describe('ChipComponent', () => {
    it('renders the projected label in an outline neutral pill by default', () => {
        const fixture = create();
        const pill = fixture.nativeElement.querySelector('span > span, inspecto-chip > span') as HTMLElement;
        expect(fixture.nativeElement.textContent).toContain('label');
        expect(pill.className).toContain('border');
        expect(pill.className).toContain('rounded-full');
    });

    it('uses the primary tint for the soft primary variant', () => {
        const fixture = create();
        fixture.componentInstance.variant.set('soft');
        fixture.componentInstance.tone.set('primary');
        fixture.detectChanges();
        const pill = fixture.nativeElement.querySelector('inspecto-chip > span') as HTMLElement;
        expect(pill.className).toContain('bg-primary-100');
        expect(pill.className).not.toContain('border ');
    });

    it('uses the status warning pair for the soft warning tone, in both schemes', () => {
        const fixture = create();
        fixture.componentInstance.variant.set('soft');
        fixture.componentInstance.tone.set('warning');
        fixture.detectChanges();
        const pill = fixture.nativeElement.querySelector('inspecto-chip > span') as HTMLElement;
        for (const c of statusBadgeClasses('warning').split(' ')) expect(pill.className).toContain(c);
        expect(pill.className).toContain('dark:text-amber-200');
    });

    it('keeps the per-scheme soft table in step with the dark:-prefixed one', () => {
        for (const tone of CHIP_TONES) {
            const light = chipSoftSchemeClasses(tone, 'light');
            const dark = chipSoftSchemeClasses(tone, 'dark')
                .split(' ')
                .map((c) => `dark:${c}`)
                .join(' ');
            expect(chipClasses('soft', tone)).toBe(`${CHIP_BASE} ${light} ${dark}`);
        }
    });

    it('shows no remove button unless removable, then emits (removed) on click', () => {
        const fixture = create();
        expect(fixture.nativeElement.querySelector('button')).toBeNull();
        fixture.componentInstance.removable.set(true);
        fixture.detectChanges();
        const btn = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
        expect(btn).not.toBeNull();
        btn.click();
        expect(fixture.componentInstance.onRemoved).toHaveBeenCalled();
    });

    it('pins a 20 px height (explicit line-height) and steps outline primary up in dark mode for AA', () => {
        const fixture = create();
        fixture.componentInstance.tone.set('primary');
        fixture.detectChanges();
        const pill = fixture.nativeElement.querySelector('inspecto-chip > span') as HTMLElement;
        expect(pill.className).toContain('leading-4');
        // primary-600 text on the dark card measures 2.3:1; primary-400 measures 4.9:1
        expect(pill.className).toContain('dark:text-primary-400');
    });

    it('has no a11y violations (removable)', async () => {
        const fixture = create();
        fixture.componentInstance.removable.set(true);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
