import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { onboardRedirect } from './onboard-redirect';

/**
 * P6-a: the wizard route is retired into a redirect. These pin the TARGET, which the route and the
 * Catalog's two navigation sites both build — a bookmark and a button must not disagree.
 */
describe('onboardRedirect', () => {
    let router: Router;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [provideRouter([])] });
        router = TestBed.inject(Router);
    });

    const url = (name: string, stage?: string): string => router.serializeUrl(onboardRedirect(router, name, stage));

    it('opens the pipeline in the guided editor', () => {
        expect(url('orders_feed')).toBe('/pipelines?guided=1&open=orders_feed');
    });

    it('carries a stage as its checklist chip', () => {
        expect(url('orders_feed', 'parsing')).toContain('stage=parse');
        expect(url('orders_feed', 'collection')).toContain('stage=collect');
        expect(url('orders_feed', 'enrichment')).toContain('stage=enrich');
    });

    /** A Reference's "Keys & Load" stage authors the SAME schema artifact the Schema stage does. */
    it('lands both schema-authoring stages on the Schema chip', () => {
        expect(url('ref_dim', 'schema')).toContain('stage=schema');
        expect(url('ref_dim', 'keys')).toContain('stage=schema');
    });

    it('carries no focus for an unknown or absent stage rather than inventing one', () => {
        expect(url('orders_feed', 'nonsense')).not.toContain('stage=');
        expect(url('orders_feed')).not.toContain('stage=');
    });

    it('escapes a name that would otherwise break the query string', () => {
        expect(url('a b&c')).toContain('open=a%20b%26c');
    });
});
