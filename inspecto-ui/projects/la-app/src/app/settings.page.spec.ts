import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { LensService } from 'app/inspecto/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { SettingsPageComponent } from './settings.page';

describe('SettingsPageComponent (axe, LA-A11Y-AUDIT-1)', () => {
    function render(canAuthor: boolean) {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(),
                provideHttpClientTesting(),
                provideNoopAnimations(),
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
                { provide: LensService, useValue: { canAuthorWorkbench: signal(canAuthor) } },
            ],
        });
        const fixture = TestBed.createComponent(SettingsPageComponent);
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('names its region and has no axe violations while the settings are loading', async () => {
        const el = render(true);
        expect(el.querySelector('section')?.getAttribute('aria-label')).toBe('Link Analysis settings');
        await expectNoA11yViolations(el);
    });

    it('has no axe violations without the authoring capability (read-only)', async () => {
        await expectNoA11yViolations(render(false));
    });
});
