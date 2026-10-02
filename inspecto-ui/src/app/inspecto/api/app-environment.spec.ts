import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { environment } from 'environments/environment';
import * as viaAlias from '@inspecto/core/api/api-base';
import * as viaApp from 'app/inspecto/api/api-base';
import { APP_ENVIRONMENT, appEnvironment, provideAppEnvironment, setAppEnvironment } from './app-environment';
import { apiUrl } from './api-base';
import { spaceScopedUrl } from './space-scope';

describe('app-environment (D-5 step 2)', () => {
    afterEach(() => setAppEnvironment(environment)); // the vitest setup's value, for the next spec in this file

    it('the test setup provides the host environment (equal by value: the setup file loads it by relative path)', () => {
        expect(appEnvironment()).toEqual(environment);
    });

    it('fails loudly, naming the fix, when nothing was provided', () => {
        setAppEnvironment(undefined);
        expect(() => appEnvironment()).toThrow(/provideAppEnvironment/);
        expect(() => apiUrl('/x')).toThrow(/APP_ENVIRONMENT/);
    });

    it('apiUrl and spaceScopedUrl follow the provided apiBaseUrl', () => {
        setAppEnvironment({ ...environment, apiBaseUrl: '/gateway' });
        expect(apiUrl('/pipelines')).toBe('/gateway/v1/pipelines');
        expect(spaceScopedUrl('/gateway/v1/pipelines', 'demo')).toBe('/gateway/v1/spaces/demo/pipelines');
    });

    describe('provideAppEnvironment', () => {
        beforeEach(() => setAppEnvironment(undefined));

        it('sets the module value and the DI token to the same object', () => {
            const env = { ...environment, apiBaseUrl: '/other' };
            TestBed.configureTestingModule({ providers: [provideAppEnvironment(env)] });
            expect(appEnvironment()).toBe(env);
            expect(TestBed.inject(APP_ENVIRONMENT)).toBe(env);
        });
    });
});

describe('workspace aliases (D-5 step 2)', () => {
    it('@inspecto/core/* resolves to the same module as app/inspecto/*', () => {
        expect(viaAlias.apiUrl).toBe(viaApp.apiUrl);
    });
});
