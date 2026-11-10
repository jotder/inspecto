import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { LuxonDateAdapter } from '@angular/material-luxon-adapter';
import { DateAdapter, MAT_DATE_FORMATS, MatDateFormats } from '@angular/material/core';
import { MAT_DIALOG_DEFAULT_OPTIONS, MatDialogConfig } from '@angular/material/dialog';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import {
    RouteReuseStrategy,
    Routes,
    provideRouter,
    withComponentInputBinding,
    withInMemoryScrolling,
} from '@angular/router';
import { provideToastr } from 'ngx-toastr';
import { AppEnvironment, provideAppEnvironment } from '../api/app-environment';
import { errorInterceptor } from '../api/error.interceptor';
import { spaceInterceptor } from '../api/space.interceptor';
import { v1Interceptor } from '../api/v1.interceptor';
import { authInterceptor } from '../auth/auth.interceptor';
import { KeepAliveRouteReuseStrategy } from '../keep-alive.strategy';
import { GeneratedProduct } from '../product/product-host';

/** The date formats every shell's Luxon adapter parses and displays with. */
export const SHELL_DATE_FORMATS: MatDateFormats = {
    parse: { dateInput: 'D' },
    display: {
        dateInput: 'DDD',
        monthYearLabel: 'LLL yyyy',
        dateA11yLabel: 'DD',
        monthYearA11yLabel: 'LLLL yyyy',
    },
};

/**
 * What every Inspecto shell configures alike (PRODUCT-ASSEMBLY-1 PA-4): the console (gamma) and each Product's own host
 * (la-app) call this and add only their routes, initialisers, host services, icons and theme. Before PA-4 the two hosts
 * held a hand-copied set that had to agree line for line.
 *
 * - The environment, with the Product's brand from its generated host data (`product.generated.ts`): the name on the
 *   sign-in screen and, where the Product declares one, its sign-in pitch.
 * - Zone.js change detection (explicit opt-in). The zoneless flip was reverted: it shipped without a component-by-component
 *   audit and froze eight surfaces whose state is a plain field written from an async callback (sidebar, Connections Test,
 *   Spaces expand, ...). Re-attempt it only alongside that audit - see docs/BACKLOG.md.
 * - HttpClient with the four interceptors in this order: v1 envelope unwrap (W7 - first), Space scope rewrite, auth bearer
 *   and refresh (a pass-through unless the edition signs people in, W6d), error tracker.
 * - The router with scroll restoration and route-input binding, and the keep-alive reuse strategy (the Link Analysis page
 *   stays alive while the analyst visits another page; KEEP_ALIVE routes only).
 * - Every Material dialog modal to assistive technology (LA-A11Y-AUDIT-1). ⚠ The options REPLACE `new MatDialogConfig()`
 *   rather than merge with it, so the defaults (role, hasBackdrop, ...) are spread in.
 */
export function provideInspectoShell(opts: {
    environment: AppEnvironment;
    product: GeneratedProduct;
    routes: Routes;
}): ApplicationConfig['providers'] {
    return [
        provideAppEnvironment({
            ...opts.environment,
            productName: opts.product.brand.name,
            ...(opts.product.brand.signInPitch ? { signInPitch: opts.product.brand.signInPitch } : {}),
        }),
        provideZoneChangeDetection({ eventCoalescing: true }),
        provideHttpClient(
            withXhr(),
            withInterceptors([v1Interceptor, spaceInterceptor, authInterceptor, errorInterceptor]),
        ),
        provideAnimationsAsync(),
        provideToastr({ positionClass: 'toast-bottom-right' }),
        provideRouter(
            opts.routes,
            withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
            withComponentInputBinding(),
        ),
        { provide: RouteReuseStrategy, useClass: KeepAliveRouteReuseStrategy },
        { provide: DateAdapter, useClass: LuxonDateAdapter },
        { provide: MAT_DATE_FORMATS, useValue: SHELL_DATE_FORMATS },
        { provide: MAT_DIALOG_DEFAULT_OPTIONS, useFactory: () => ({ ...new MatDialogConfig(), ariaModal: true }) },
    ];
}
