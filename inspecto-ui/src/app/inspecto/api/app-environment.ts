import { EnvironmentProviders, InjectionToken, makeEnvironmentProviders } from '@angular/core';

/**
 * The slice of the build-time `environments/environment` that `api/` reads. `api/` never imports
 * `environments/*` itself (D-5 step 2): the host hands the object in through {@link provideAppEnvironment},
 * so the shared API layer can move behind a library boundary without dragging a host-owned file along.
 */
export interface AppEnvironment {
    /** The API prefix: `/api` for both dev (behind `proxy.conf.json`) and the packaged same-origin SPA. */
    apiBaseUrl: string;
    appLogo: string;
    footerText: string;
    /** Public PKCE-client OIDC config baked into the bundle (no secret); absent/blank means "use `/bootstrap`". */
    oidc?: { authorizeUrl?: string; clientId?: string; scopes?: string; endSessionUrl?: string; mock?: boolean };
}

// Held on `globalThis`, not in a module variable: the unit-test builder bundles the vitest setup file apart from the
// specs, so a module variable would exist twice and the setup file's value would never reach `apiUrl()`.
const KEY = Symbol.for('inspecto.appEnvironment');
const store = globalThis as unknown as Record<symbol, AppEnvironment | undefined>;

/**
 * The environment in force. Plain functions that cannot `inject()` (`apiUrl`, `spaceScopedUrl`: an
 * `EventSource` URL is built outside any injection context) read it here; classes and interceptors inject
 * {@link APP_ENVIRONMENT}, which resolves to the same object. Throws, naming the fix, rather than
 * producing a `undefined/v1/...` URL when nothing was provided.
 */
export function appEnvironment(): AppEnvironment {
    const current = store[KEY];
    if (!current) {
        throw new Error('APP_ENVIRONMENT is not provided: add provideAppEnvironment(environment) to the app config.');
    }
    return current;
}

/** Test helper / bootstrap hook behind {@link provideAppEnvironment}; not for application code. */
export function setAppEnvironment(env: AppEnvironment | undefined): void {
    store[KEY] = env;
}

/** DI view of {@link appEnvironment}; the default factory means a TestBed that never provides it still resolves. */
export const APP_ENVIRONMENT = new InjectionToken<AppEnvironment>('APP_ENVIRONMENT', {
    providedIn: 'root',
    factory: () => appEnvironment(),
});

/**
 * Provide the environment from the host's `app.config`. The module-level value is set HERE, eagerly, not in
 * an initializer: a plain `apiUrl()` call can run before any initializer would.
 */
export function provideAppEnvironment(env: AppEnvironment): EnvironmentProviders {
    setAppEnvironment(env);
    return makeEnvironmentProviders([{ provide: APP_ENVIRONMENT, useValue: env }]);
}
