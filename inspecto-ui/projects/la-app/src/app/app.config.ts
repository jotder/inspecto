import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { ApplicationConfig, inject, provideAppInitializer, provideZoneChangeDetection } from '@angular/core';
import { LuxonDateAdapter } from '@angular/material-luxon-adapter';
import { DateAdapter, MAT_DATE_FORMATS } from '@angular/material/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideGamma } from '@gamma';
import { provideIcons } from 'app/core/icons/icons.provider';
import { provideAppEnvironment } from '@inspecto/core/api/app-environment';
import { authInterceptor } from '@inspecto/core/auth/auth.interceptor';
import { SessionService } from '@inspecto/core/auth/session.service';
import { errorInterceptor } from '@inspecto/core/api/error.interceptor';
import { spaceInterceptor } from '@inspecto/core/api/space.interceptor';
import { v1Interceptor } from '@inspecto/core/api/v1.interceptor';
import { environment } from 'environments/environment';
import { provideToastr } from 'ngx-toastr';
import { laAppRoutes } from './app.routes';
import { provideLaAppHostServices } from './la-host.providers';

/**
 * The Link Analysis application's provider set (D-5 step 6). It mirrors the gamma host's `app.config` for everything
 * the shared core needs (environment, the four interceptors in the same order, Material, Toastr, icons, the Gamma theme
 * config) and differs in three places: the routes, no `registerLinkAnalysisViz` / `registerGeoMapViz` (la-app has no
 * dashboards to embed a widget in), and the ten Link Analysis host tokens come from `provideLaAppHostServices`.
 */
export const appConfig: ApplicationConfig = {
    providers: [
        provideAppEnvironment(environment),
        provideZoneChangeDetection({ eventCoalescing: true }),
        // Same order as the host: v1 envelope unwrap, Space scope rewrite, auth bearer / refresh, error tracker.
        provideHttpClient(
            withXhr(),
            withInterceptors([v1Interceptor, spaceInterceptor, authInterceptor, errorInterceptor]),
        ),
        provideAnimationsAsync(),
        provideToastr({ positionClass: 'toast-bottom-right' }),
        provideRouter(
            laAppRoutes,
            withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
            withComponentInputBinding(),
        ),
        { provide: DateAdapter, useClass: LuxonDateAdapter },
        {
            provide: MAT_DATE_FORMATS,
            useValue: {
                parse: { dateInput: 'D' },
                display: {
                    dateInput: 'DDD',
                    monthYearLabel: 'LLL yyyy',
                    dateA11yLabel: 'DD',
                    monthYearA11yLabel: 'LLLL yyyy',
                },
            },
        },
        // `GET /bootstrap` once, before routing: the auth mode, the module flags (`LA_FEATURES` reads them) and, under
        // OIDC, a session resumed from the refresh cookie. Never rejects.
        provideAppInitializer(() => inject(SessionService).init()),
        ...provideLaAppHostServices(),
        provideIcons(),
        provideGamma({
            gamma: {
                layout: 'classic',
                scheme: 'dark',
                screens: { sm: '600px', md: '960px', lg: '1280px', xl: '1440px' },
                theme: 'theme-default',
                themes: [{ id: 'theme-default', name: 'Default' }],
            },
        }),
    ],
};
