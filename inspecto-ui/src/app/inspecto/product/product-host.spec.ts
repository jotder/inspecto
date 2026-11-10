import { describe, expect, it } from 'vitest';
import { BootstrapProduct, GeneratedProduct, productNav } from './product-host';

const GENERATED: GeneratedProduct = {
    id: 'la',
    title: 'Inspecto LA',
    brand: { name: 'Inspecto LA' },
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
