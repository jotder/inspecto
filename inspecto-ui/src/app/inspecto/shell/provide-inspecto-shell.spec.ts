import { ApplicationInitStatus } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';
import { describe, expect, it } from 'vitest';
import { APP_ENVIRONMENT } from '../api/app-environment';
import { GeneratedProduct } from '../product/product-host';
import { provideInspectoShell } from './provide-inspecto-shell';

const TELCO: GeneratedProduct = {
    id: 'telco',
    title: 'Inspecto Telco',
    brand: { name: 'Inspecto Telco', signInPitch: { title: 'Find the leakage.', body: 'Four Apps in one product.' } },
    apps: [],
    nav: [],
};

// PRODUCT-ASSEMBLY-1 PA-5: one host (la-app) builds several Products, so neither its index.html nor its environment names
// one - the shell takes the name from the Product it is built as.
describe('provideInspectoShell', () => {
    it('names the browser tab and the sign-in screen after the Product the host is built as', async () => {
        TestBed.configureTestingModule({
            providers: provideInspectoShell({
                environment: { apiBaseUrl: '/api', appLogo: 'logo.svg', footerText: 'footer', productName: 'Inspecto LA' },
                product: TELCO,
                routes: [],
            }),
        });
        await TestBed.inject(ApplicationInitStatus).donePromise;
        expect(TestBed.inject(Title).getTitle()).toBe('Inspecto Telco');
        const env = TestBed.inject(APP_ENVIRONMENT);
        expect(env.productName, "the Product's brand wins over the environment's").toBe('Inspecto Telco');
        expect(env.signInPitch).toEqual(TELCO.brand.signInPitch);
    });
});
