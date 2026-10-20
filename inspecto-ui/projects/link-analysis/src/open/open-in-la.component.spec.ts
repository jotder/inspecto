import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { OpenInLaComponent } from './open-in-la.component';
import { DeepLinkResolution, DeepLinkService } from './open-link';

@Component({
    standalone: true,
    imports: [OpenInLaComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-open></inspecto-la-open>`,
})
class Host {}

const ALIAS = 'masked:f01a9b8ca40c4eb1';
const RESOLVED: DeepLinkResolution = {
    entity: { type: 'msisdn', key: ALIAS, masked: true },
    investigation: { id: 'ext-0123', title: 'Alert AL-1001' },
    window: { from: '2026-10-01T00:00:00Z', to: '2026-10-04T00:00:00Z' },
};

function make(
    params: Record<string, string>,
    opts: { canReveal?: boolean; resolve?: ReturnType<typeof vi.fn>; investigations?: boolean } = {},
) {
    const resolve =
        opts.resolve ??
        vi.fn((_link: unknown, reveal?: boolean) =>
            of(
                reveal
                    ? { ...RESOLVED, entity: { ...RESOLVED.entity!, revealed: '+249912000001' } }
                    : (RESOLVED as DeepLinkResolution),
            ),
        );
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            {
                provide: GammaConfigService,
                useValue: { config$: of({ scheme: 'light' }), config: { scheme: 'light' } },
            },
            { provide: DeepLinkService, useValue: { resolve } },
            {
                provide: LA_FEATURES,
                useValue: {
                    ops: signal(false),
                    exchange: signal(false),
                    geoLink: signal(true),
                    investigations: signal(opts.investigations ?? true),
                },
            },
            { provide: LensService, useValue: { canRevealLinkEntities: () => !!opts.canReveal } },
            {
                provide: ActivatedRoute,
                useValue: {
                    queryParamMap: of(convertToParamMap(params)),
                    snapshot: {
                        pathFromRoot: [{ url: [] }, { url: [{ path: 'studio' }] }, { url: [{ path: 'open' }] }],
                    },
                },
            },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, resolve };
}

const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid=${id}]`) as HTMLElement | null;

describe('OpenInLaComponent (Open in Inspecto LA)', () => {
    it('resolves the link on the server and offers its Investigation and Entity, beside the Entities pages', () => {
        const { el, resolve } = make({
            entity: `msisdn:${ALIAS}`,
            investigation: 'ext-0123',
            from: '2026-10-01T00:00:00Z',
            to: '2026-10-04T00:00:00Z',
        });
        expect(resolve).toHaveBeenCalledWith({
            entity: `msisdn:${ALIAS}`,
            investigation: 'ext-0123',
            window: { from: '2026-10-01T00:00:00Z', to: '2026-10-04T00:00:00Z' },
        });
        expect(q(el, 'investigation')!.textContent).toContain('Alert AL-1001');
        expect(q(el, 'entity')!.textContent).toContain(ALIAS);
        expect(q(el, 'masked')).toBeTruthy();
        expect(q(el, 'window')!.textContent).toContain('2026-10-01T00:00:00Z');
        expect(q(el, 'open-investigation')!.getAttribute('href')).toBe('/studio/link-analysis?investigation=ext-0123');
        expect(q(el, 'open-entity')!.getAttribute('href')).toBe(
            `/studio/entities/msisdn/${ALIAS}?from=2026-10-01T00:00:00Z&to=2026-10-04T00:00:00Z`,
        );
        expect(q(el, 'reveal')).toBeNull();
    });

    it('on Basic (no Investigations) explains that links from other tools are a Standard feature, and never calls the server', () => {
        const resolve = vi.fn();
        const { el } = make({ entity: 'msisdn:+249912000001' }, { resolve, investigations: false });
        expect(resolve).not.toHaveBeenCalled();
        expect(el.querySelector('[data-testid="open-error"]')?.textContent).toContain(
            'available in Standard and above',
        );
    });

    it('refuses an invalid link with a readable reason and never calls the server', () => {
        const { el, resolve } = make({ entity: 'no-colon' });
        expect(resolve).not.toHaveBeenCalled();
        expect(q(el, 'open-error')!.textContent).toContain('"<type>:<key>"');
        expect(q(el, 'open-entity')).toBeNull();
    });

    it("shows the server's refusal (a raw value of a masked type, an unreadable Investigation)", () => {
        const resolve = vi.fn(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 422,
                        error: {
                            error: {
                                message: "entity: Entity Type 'msisdn' is masked, so a link names it by its alias",
                            },
                        },
                    }),
            ),
        );
        const { el } = make({ entity: 'msisdn:+249912000001' }, { resolve });
        expect(q(el, 'open-error')!.textContent).toContain('masked');
    });

    it('reveals a masked entity only for a holder of canRevealLinkEntities, on request', () => {
        const { el, fixture, resolve } = make({ entity: `msisdn:${ALIAS}` }, { canReveal: true });
        expect(q(el, 'entity')!.textContent).not.toContain('+249912000001');
        q(el, 'reveal')!.click();
        fixture.detectChanges();
        expect(resolve).toHaveBeenLastCalledWith({ entity: `msisdn:${ALIAS}` }, true);
        expect(q(el, 'entity')!.textContent).toContain('+249912000001');
        expect(q(el, 'masked')).toBeNull();
        expect(q(el, 'reveal')).toBeNull();
        expect(q(el, 'open-entity')!.getAttribute('href')).toBe(
            `/studio/entities/msisdn/${ALIAS}?from=2026-10-01T00:00:00Z&to=2026-10-04T00:00:00Z`,
        );
    });

    it('has no axe violations and one h1, resolved or refused', async () => {
        const ok = make({ entity: `msisdn:${ALIAS}`, investigation: 'ext-0123' }, { canReveal: true });
        await ok.fixture.whenStable();
        expect(ok.el.querySelectorAll('h1')).toHaveLength(1);
        await expectNoA11yViolations(ok.el);
    }, 30_000);

    it('has no axe violations when refused', async () => {
        const bad = make({ seed: '1' });
        await bad.fixture.whenStable();
        expect(bad.el.querySelectorAll('h1')).toHaveLength(1);
        await expectNoA11yViolations(bad.el);
    }, 30_000);
});
