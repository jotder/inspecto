import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { ApplicationConfig, inject, provideAppInitializer, provideZoneChangeDetection } from '@angular/core';
import { LuxonDateAdapter } from '@angular/material-luxon-adapter';
import { DateAdapter, MAT_DATE_FORMATS } from '@angular/material/core';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideGamma } from '@gamma';
import { appRoutes } from 'app/app.routes';
import { provideIcons } from 'app/core/icons/icons.provider';
import { provideToastr } from 'ngx-toastr';
import { errorInterceptor as inspectoErrorInterceptor } from './inspecto/api/error.interceptor';
import { spaceInterceptor } from './inspecto/api/space.interceptor';
import { authInterceptor } from './inspecto/api/auth.interceptor';
import { v1Interceptor } from './inspecto/api/v1.interceptor';
import { SessionService } from './inspecto/api/session.service';
import { mockApiInterceptor } from './inspecto/mock';

export const appConfig: ApplicationConfig = {
    providers: [
        // Zone.js change detection (explicit opt-in for Angular 21). The zoneless flip was reverted:
        // it shipped without a component-by-component audit and froze eight surfaces whose state is a
        // plain field written from an async callback (sidebar, Connections Test, Spaces expand, …).
        // Re-attempt it only alongside that audit — see docs/BACKLOG.md.
        provideZoneChangeDetection({ eventCoalescing: true }),

        // Main HttpClient. The Personal/core edition is auth-free; the Standard edition adds OIDC via
        // the authInterceptor (W6d), which is a no-op unless SessionService.authMode === 'oidc'. Order:
        // v1 envelope unwrap (W7 — first, so it also sees mock short-circuit responses) → mock
        // (offline, short-circuits) → space scope rewrite → auth bearer/refresh → error tracker.
        provideHttpClient(
            withXhr(),
            // mockApiInterceptor is THE unified mock backend (inspecto/mock/): a persistent, per-space
            // MockStore behind framework-free domain handlers (auth/bootstrap, demo, connections,
            // components, pipelines, ops, jobs) plus the liveness simulator. It runs before the space
            // rewrite; per-domain environment.mock* flags gate each handler.
            withInterceptors([
                v1Interceptor,
                mockApiInterceptor,
                spaceInterceptor,
                authInterceptor,
                inspectoErrorInterceptor,
            ]),
        ),

        // Angular 21: use async animations provider for better performance
        provideAnimationsAsync(),
        provideToastr({ positionClass: 'toast-bottom-right' }),
        provideRouter(
            appRoutes,
            withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
            withComponentInputBinding(),
        ),

        // Material Date Adapter
        {
            provide: DateAdapter,
            useClass: LuxonDateAdapter,
        },
        {
            provide: MAT_DATE_FORMATS,
            useValue: {
                parse: {
                    dateInput: 'D',
                },
                display: {
                    dateInput: 'DDD',
                    monthYearLabel: 'LLL yyyy',
                    dateA11yLabel: 'DD',
                    monthYearA11yLabel: 'LLLL yyyy',
                },
            },
        },

        // Read GET /bootstrap once to learn the edition's authMode and (under OIDC) resume a session
        // from the refresh cookie, before routing runs. Never rejects — a Personal/offline backend just
        // reports authMode:'none' and this is a no-op, so the auth-free boot path is unchanged (W6d).
        provideAppInitializer(() => inject(SessionService).init()),

        // Gamma
        // provideAuth(),
        provideIcons(),
        provideGamma({
            gamma: {
                layout: 'classic',
                scheme: 'dark',
                screens: {
                    sm: '600px',
                    md: '960px',
                    lg: '1280px',
                    xl: '1440px',
                },
                theme: 'theme-default',
                themes: [
                    {
                        id: 'theme-default',
                        name: 'Default',
                    },
                    {
                        id: 'theme-brand',
                        name: 'Brand',
                    },
                    {
                        id: 'theme-teal',
                        name: 'Teal',
                    },
                    {
                        id: 'theme-rose',
                        name: 'Rose',
                    },
                    {
                        id: 'theme-purple',
                        name: 'Purple',
                    },
                    {
                        id: 'theme-amber',
                        name: 'Amber',
                    },
                ],
            },
        }),
    ],
};
