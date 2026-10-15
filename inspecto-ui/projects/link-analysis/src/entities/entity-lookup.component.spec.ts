import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penEdit, penError } from '@inspecto/core/testing/pen-field';
import { EntityProfileService, EntityResolution } from '@inspecto/link-analysis/api/entity-profile.service';
import { EntityLookupComponent } from './entity-lookup.component';

@Component({
    standalone: true,
    imports: [EntityLookupComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-entity-lookup type="msisdn" [base]="base"></inspecto-la-entity-lookup>`,
})
class Host {
    base = ['/', 'studio', 'entities'];
}

@Component({ standalone: true, template: '' })
class Blank {}

const RAW = '+249 911 223 344';

function make(resolve: () => Observable<EntityResolution>) {
    const resolveEntity = vi.fn(resolve);
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([{ path: '**', component: Blank }]),
            { provide: EntityProfileService, useValue: { resolveEntity } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const pen = el.querySelector('inspecto-pen-field') as HTMLElement;
    const submit = () => {
        (el.querySelector('[data-testid=lookup-open]') as HTMLButtonElement).click();
        fixture.detectChanges();
    };
    return { fixture, el, pen, submit, resolveEntity, router: TestBed.inject(Router) };
}

describe('EntityLookupComponent (Open an Entity)', () => {
    it('resolves the typed value first and opens the Entity page by the returned key: the raw value never reaches the URL', async () => {
        const { fixture, pen, submit, resolveEntity, router } = make(() =>
            of({ type: 'msisdn', key: 'masked:f01a9b8ca40c4eb1', masked: true }),
        );
        const nav = vi.spyOn(router, 'navigate');
        penEdit(fixture, pen, RAW);
        submit();
        await fixture.whenStable();
        expect(resolveEntity).toHaveBeenCalledWith('msisdn', RAW);
        expect(nav).toHaveBeenCalledWith(['/', 'studio', 'entities', 'msisdn', 'masked:f01a9b8ca40c4eb1']);
        expect(router.url).toBe('/studio/entities/msisdn/masked:f01a9b8ca40c4eb1');
        const digits = RAW.replace(/\D/g, '');
        expect(decodeURIComponent(router.url).replace(/\D/g, '')).not.toContain(digits);
        expect(JSON.stringify(nav.mock.calls)).not.toContain('249 911');
    });

    it('shows a 422 refusal from resolve in place, with its reason, and does not navigate', async () => {
        const { fixture, pen, submit, router } = make(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 422,
                        error: { error: { message: 'not a valid MSISDN for the e164 normaliser' } },
                    }),
            ),
        );
        const nav = vi.spyOn(router, 'navigate');
        penEdit(fixture, pen, 'abc');
        submit();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(penError(pen)).toBe('not a valid MSISDN for the e164 normaliser');
        expect(nav).not.toHaveBeenCalled();
    });

    it('asks for a value instead of resolving a blank one', () => {
        const { pen, submit, resolveEntity } = make(() => of({ type: 'msisdn', key: 'x', masked: false }));
        submit();
        expect(resolveEntity).not.toHaveBeenCalled();
        expect(penError(pen)).toBe('Type a value to open.');
    });

    it('has no axe violations', async () => {
        const { el } = make(() => of({ type: 'msisdn', key: 'x', masked: false }));
        await expectNoA11yViolations(el);
    }, 30_000);
});
