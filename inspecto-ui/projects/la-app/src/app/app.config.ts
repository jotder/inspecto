import { ApplicationConfig, inject, provideAppInitializer } from '@angular/core';
import { provideGamma } from '@gamma';
import { provideIcons } from 'app/core/icons/icons.provider';
import { SessionService } from '@inspecto/core/auth/session.service';
import { provideInspectoShell } from '@inspecto/core/shell/provide-inspecto-shell';
import { environment } from 'environments/environment';
import { laAppRoutes } from './app.routes';
import { provideLaAppHostServices } from './la-host.providers';
import { PRODUCT } from './product.generated';

/**
 * Inspecto LA's provider set (D-5 step 6; PRODUCT-ASSEMBLY-1 PA-4). The shell everything shares comes from
 * `provideInspectoShell` - with the Product's brand from `product.generated.ts` - so this host adds only what is its own:
 * the routes, the bootstrap initialiser (skipped by the anonymous embed viewer), the ten Link Analysis host tokens, icons
 * and the theme. No `registerLinkAnalysisViz` / `registerGeoMapViz`: la-app has no dashboards to embed a widget in.
 */
export const appConfig: ApplicationConfig = {
    providers: [
        ...provideInspectoShell({ environment, product: PRODUCT, routes: laAppRoutes }),
        // `GET /bootstrap` once, before routing: the auth mode, the module flags (`LA_FEATURES` reads them), the Product and its
        // nav and, under OIDC, a session resumed from the refresh cookie. Never rejects.
        // The shared-Investigation viewer (`/embed/...`) is anonymous and talks to nothing but its one public read, so it skips the
        // bootstrap, the session refresh and the Space bootstrap: a stranger's browser makes no session call at all (LA-EMBED-SPA-1).
        provideAppInitializer(() =>
            location.pathname.startsWith('/embed/') ? undefined : inject(SessionService).init(),
        ),
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
