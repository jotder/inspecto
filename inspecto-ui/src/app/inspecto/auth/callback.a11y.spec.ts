import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SessionService } from './session.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { CallbackComponent } from './callback.component';

describe('CallbackComponent (a11y, W6d)', () => {
    const navigate = vi.fn();
    const navigateByUrl = vi.fn();
    const completeLogin = vi.fn(() => of(true));
    const takeReturnUrl = vi.fn((): string | null => null);

    beforeEach(() => {
        navigate.mockClear();
        navigateByUrl.mockClear();
        takeReturnUrl.mockReset().mockReturnValue(null);
        completeLogin.mockClear();
        TestBed.configureTestingModule({
            imports: [CallbackComponent],
            providers: [
                provideNoopAnimations(),
                { provide: SessionService, useValue: { completeLogin, takeReturnUrl } },
                { provide: Router, useValue: { navigate, navigateByUrl } },
            ],
        });
    });

    it('redeems the code and routes into the app, with no accessibility violations', async () => {
        const fixture = TestBed.createComponent(CallbackComponent);
        fixture.componentRef.setInput('code', 'the-code');
        fixture.componentRef.setInput('state', 'the-state');
        fixture.detectChanges();
        expect(completeLogin).toHaveBeenCalledWith('the-code', 'the-state');
        expect(navigateByUrl).toHaveBeenCalledWith('/');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('returns to the page the user came from after sign-in', () => {
        takeReturnUrl.mockReturnValue('/link-analysis?case=7#g');
        const fixture = TestBed.createComponent(CallbackComponent);
        fixture.componentRef.setInput('code', 'c');
        fixture.componentRef.setInput('state', 's');
        fixture.detectChanges();
        expect(takeReturnUrl).toHaveBeenCalledWith('s');
        expect(navigateByUrl).toHaveBeenCalledWith('/link-analysis?case=7#g');
    });
});
