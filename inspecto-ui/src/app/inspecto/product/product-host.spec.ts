import { describe, expect, it } from 'vitest';
import { appAreas, appOfUrl, BootstrapProduct, GeneratedProduct, productNav } from './product-host';

const GENERATED: GeneratedProduct = {
    id: 'la',
    title: 'Inspecto LA',
    brand: { name: 'Inspecto LA' },
    apps: [{ id: 'link-analysis', title: 'Link Analysis' }],
    nav: [
        { app: 'link-analysis', id: 'linkAnalysis', path: 'link-analysis', label: 'Link Analysis' },
        { app: 'link-analysis', id: 'geoMap', path: 'geo-map', label: 'Geo', feature: 'geoMap' },
        { app: 'link-analysis', id: 'alerts', path: 'alerts', label: 'Alerts', feature: 'investigations' },
    ],
};

const ids = (nav: readonly { id: string }[]): string[] => nav.map((n) => n.id);

describe('productNav (PRODUCT-ASSEMBLY-1 PA-4)', () => {
    it('draws the server-resolved nav as is when the server resolved one for this Product', () => {
        const server: BootstrapProduct = {
            id: 'la',
            title: 'Inspecto LA',
            nav: [{ app: 'link-analysis', id: 'alerts', path: 'alerts', label: 'Alerts' }],
        };
        expect(ids(productNav(server, GENERATED, { geoMap: true }))).toEqual(['alerts']);
    });

    it('an empty server nav is an answer, not a gap: nothing is drawn', () => {
        expect(productNav({ id: 'la', title: 'Inspecto LA', nav: [] }, GENERATED, { geoMap: true })).toEqual([]);
    });

    it('without a server Product it applies the feature rule to the generated nav', () => {
        expect(ids(productNav(null, GENERATED, { geoMap: true }))).toEqual(['linkAnalysis', 'geoMap']);
        expect(ids(productNav(null, GENERATED, {}))).toEqual(['linkAnalysis']);
    });

    it('before sign-in the server sends identity only, so the generated nav applies', () => {
        expect(ids(productNav({ id: 'la', title: 'Inspecto LA' }, GENERATED, { investigations: true }))).toEqual([
            'linkAnalysis',
            'alerts',
        ]);
    });

    it("another Product's nav is never drawn by this host", () => {
        const console_: BootstrapProduct = { id: 'inspecto', title: 'Inspecto', nav: [] };
        expect(ids(productNav(console_, GENERATED, {}))).toEqual(['linkAnalysis']);
    });
});

// PRODUCT-ASSEMBLY-1 PA-5b: the App switcher and the landing page's groups.
const SUITE: GeneratedProduct = {
    id: 'telco',
    title: 'Inspecto Telco',
    brand: { name: 'Inspecto Telco' },
    apps: [
        { id: 'telco-ra', title: 'Revenue Assurance' },
        { id: 'case-management', title: 'Case Management' },
        { id: 'link-analysis', title: 'Link Analysis' },
    ],
    nav: [
        { app: 'telco-ra', id: 'reconciliation', path: 'reconciliation', label: 'Reconciliations' },
        { app: 'case-management', id: 'incidents', path: 'incidents', label: 'Incidents' },
        { app: 'case-management', id: 'cases', path: 'cases', label: 'Cases', feature: 'cases' },
        { app: 'link-analysis', id: 'linkAnalysis', path: 'link-analysis', label: 'Link Analysis' },
    ],
};

describe('appAreas / appOfUrl', () => {
    it('groups the drawn areas by App in Product order, and leaves out an App with nothing drawn', () => {
        const nav = productNav(null, SUITE, {}).filter((n) => n.app !== 'link-analysis');
        expect(appAreas(null, SUITE, nav).map((a) => [a.id, a.title, ids(a.areas)])).toEqual([
            ['telco-ra', 'Revenue Assurance', ['reconciliation']],
            ['case-management', 'Case Management', ['incidents']],
        ]);
    });

    it("takes an App's title from the server when it resolved this Product", () => {
        const server: BootstrapProduct = {
            id: 'telco',
            title: 'Inspecto Telco',
            apps: [{ id: 'telco-ra', title: 'RA' }],
        };
        expect(appAreas(server, SUITE, SUITE.nav)[0].title).toBe('RA');
        const other: BootstrapProduct = { id: 'la', title: 'Inspecto LA', apps: [{ id: 'telco-ra', title: 'RA' }] };
        expect(appAreas(other, SUITE, SUITE.nav)[0].title).toBe('Revenue Assurance');
    });

    it('finds the App whose area a URL opens, and none for the landing page or Settings', () => {
        expect(appOfUrl(SUITE.nav, '/reconciliation')).toBe('telco-ra');
        expect(appOfUrl(SUITE.nav, '/cases/c-1?tab=notes')).toBe('case-management');
        expect(appOfUrl(SUITE.nav, '/link-analysis;x=1')).toBe('link-analysis');
        expect(appOfUrl(SUITE.nav, '/')).toBeNull();
        expect(appOfUrl(SUITE.nav, '/settings/access')).toBeNull();
    });
});
