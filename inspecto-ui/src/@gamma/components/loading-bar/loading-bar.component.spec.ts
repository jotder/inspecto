import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { GammaLoadingBarComponent } from './loading-bar.component';

describe('GammaLoadingBarComponent', () => {
    /** LA-A11Y-AUDIT-1 (axe aria-progressbar-name): the shell's loading bar needs an accessible name. */
    it('names the progress bar', () => {
        TestBed.configureTestingModule({ imports: [GammaLoadingBarComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(GammaLoadingBarComponent);
        fixture.componentInstance.show = true;
        fixture.detectChanges();
        const bar = fixture.nativeElement.querySelector('mat-progress-bar') as HTMLElement;
        expect(bar.getAttribute('aria-label')).toBe('Loading');
    });
});
