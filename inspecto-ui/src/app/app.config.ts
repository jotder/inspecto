import { ApplicationConfig, inject, provideAppInitializer } from '@angular/core';
import { provideGamma } from '@gamma';
import { appRoutes } from 'app/app.routes';
import { provideIcons } from 'app/core/icons/icons.provider';
import { registerGeoMapViz, registerLinkAnalysisViz } from '@inspecto/link-analysis';
import { provideLaHostServices } from './modules/admin/studio/la-host.providers';
import { SessionService } from './inspecto/auth/session.service';
import { provideInspectoShell } from './inspecto/shell/provide-inspecto-shell';
import { environment } from 'environments/environment';
import { PRODUCT } from './product.generated';

/**
 * The console's provider set (the Platform product `inspecto`; PRODUCT-ASSEMBLY-1 PA-4). The shell every Inspecto host
 * shares comes from `provideInspectoShell` - environment and brand, change detection, HTTP and its four interceptors, the
 * router, dates, modal dialogs; this host adds only what is its own.
 */
export const appConfig: ApplicationConfig = {
    providers: [
        ...provideInspectoShell({ environment, product: PRODUCT, routes: appRoutes }),

        // Read GET /bootstrap once to learn the edition's authMode and (under OIDC) resume a session
        // from the refresh cookie, before routing runs. Never rejects — a Basic/offline backend just
        // reports authMode:'none' and this is a no-op, so the auth-free boot path is unchanged (W6d).
        provideAppInitializer(() => inject(SessionService).init()),
        // SEP-06: optional features register their own viz-embed render hosts.
        provideAppInitializer(() => {
            registerGeoMapViz();
            registerLinkAnalysisViz();
        }),
        // D-5 prep: the host services the Link Analysis / Geo features reach only through tokens (inspecto/la-host).
        ...provideLaHostServices(),

        // Gamma
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
