// This file can be replaced during build by using the `fileReplacements` array.
// `ng build --prod` replaces `environment.ts` with `environment.prod.ts`.
// The list of file replacements can be found in `angular.json`.

export const environment = {
    production: false,
    // Inspecto inspector backend (ControlApi). All API calls are prefixed with '/api':
    //  - dev (ng serve): proxy.conf.json forwards '/api' UNCHANGED to :8080 (the backend strips
    //    '/api' and '/api/v1' itself — a rewrite here would 404 the versioned routes).
    //  - packaged (SPA served same-origin by ControlApi via -Dui.dir): same-origin, no proxy.
    // Keep this as '/api' for both modes. (There are no angular.json fileReplacements, so this
    // environment.ts is the one that actually ships.)
    apiBaseUrl: '/api',
    hmr: false,
    // Real Standard-deployment OIDC config (public PKCE client — no secret). Left blank in dev. A real deployment sets the IAM's
    // authorize endpoint + the SPA's public client id here; the SessionService reads bootstrap.auth first
    // and falls back to this. `endSessionUrl` is the provider's `end_session_endpoint` (RP-initiated
    // logout) — declared, never derived from the issuer, same call D15 made for tokenEndpoint. Leave it
    // blank and sign-out ends the Inspecto session only.
    oidc: { authorizeUrl: '', clientId: '', scopes: 'openid profile roles', endSessionUrl: '', mock: false },
    apiVersion: '/api/v1',
    basePath: '/',
    authVersion: '/oauth',
    appName: 'inspecto',
    appLogo: 'assets/images/logo/inspecto-logo.svg',
    gatewayUrl: 'http://localhost:4204/',

    authenticationType: 'token',
    footerText: ' © Powered By Gamma Analytics LLC',
    appLogoutUri: 'logout',
    appClientId: '',
    appLogoutLogo: 'assets/images/logo/inspecto-logo.svg',
    chatLogo: 'assets/images/logos/assistant.png',
    authServerAuthentication: true,
    // iam details. NOTE: no client secret here — this file ships inside the browser bundle, so any
    // value in it is public by construction. A confidential-client secret must live server-side; the
    // SPA is a public PKCE client (see the `oidc` block above).
    iamClientId: '1070682796450139008',
    dataFormat: 'YYYYMMDD',
    notificationSount: 'assets/sound/notification_2.mp3',
};
