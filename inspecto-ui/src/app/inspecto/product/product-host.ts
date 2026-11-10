/**
 * The Product a shell is built as, and the nav it draws (PRODUCT-ASSEMBLY-1 PA-4; plan
 * docs/superpower/2026-11-10-product-assembly-plan.md §11, words in docs/GLOSSARY.md §15 "Product assembly").
 *
 * Two sources, one rule. `tools/render-product-hosts.mjs` writes each host's `product.generated.ts` from `products/` and
 * `apps/` at build time (the brand, and every App's nav in drawing order); `GET /bootstrap` returns `product` resolved
 * for THIS caller in THIS Space (PA-3). A shell draws the server's nav when the server resolved one for the same Product,
 * and otherwise the generated nav under the feature rule - so a backend from before PA-3, or a dev server with no
 * descriptor, still gets the right areas.
 */

/** One area of an App, as `app.toon` declares it and `/bootstrap.product.nav` returns it. `path` is relative to the App. */
export interface ProductNavEntry {
    readonly app: string;
    readonly id: string;
    readonly path: string;
    readonly label: string;
    readonly icon?: string;
    readonly blurb?: string;
    /** The `/bootstrap` feature that must be true for the entry to be drawn (generated data only; the server applied it). */
    readonly feature?: string;
    /** The capability a signed-in caller must hold (generated data only; the server applied it). */
    readonly capability?: string;
}

/** One App a Product can ship: its id and the title the App switcher shows. */
export interface ProductApp {
    readonly id: string;
    readonly title: string;
}

/** A host's `product.generated.ts` - written by `tools/render-product-hosts.mjs`, never by hand. */
export interface GeneratedProduct {
    readonly id: string;
    readonly title: string;
    readonly brand: {
        readonly name: string;
        /** The landing page's line under the Product's name (PA-5b). */
        readonly tagline?: string;
        readonly signInPitch?: { readonly title: string; readonly body: string };
    };
    /** Every App the Product can ship, in Product order (required first): the App switcher's fallback (PA-5b). */
    readonly apps: readonly ProductApp[];
    readonly nav: readonly ProductNavEntry[];
}

/** One App of `/bootstrap.product.apps` (PA-3). */
export interface BootstrapProductApp {
    readonly id: string;
    readonly title?: string;
    readonly version?: string;
    readonly installed?: boolean;
    readonly enabled?: boolean;
    readonly missing?: readonly string[];
    readonly switchedOff?: readonly string[];
}

/**
 * `/bootstrap.product` (PA-3): identity always; `apps` and `nav` only where the Space roster is shown (signed in, or no
 * sign-in in this edition). `null` from a server built without a Product descriptor.
 */
export interface BootstrapProduct {
    readonly id: string;
    readonly title: string;
    readonly shape?: string;
    readonly host?: string;
    readonly brand?: { readonly name?: string };
    readonly apps?: readonly BootstrapProductApp[];
    readonly nav?: readonly ProductNavEntry[];
}

/** The icon an area without one is drawn with (an `app.toon` nav entry may omit `icon`). */
export const DEFAULT_AREA_ICON = 'heroicons_outline:squares-2x2';

/** An area's icon, never blank (a `mat-icon [svgIcon]` takes a string). */
export const areaIcon = (n: ProductNavEntry): string => n.icon ?? DEFAULT_AREA_ICON;

/**
 * The areas a shell draws. The server's nav when it resolved one for this host's own Product (it already applied
 * Installed, Enabled, the feature and the capability); otherwise the generated nav, keeping an entry whose `feature` is
 * true in `features` (an entry with no feature is always kept) - the rule `la-nav.ts` applied before PA-4.
 */
export function productNav(
    server: BootstrapProduct | null,
    generated: GeneratedProduct,
    features: Readonly<Record<string, boolean>>,
): readonly ProductNavEntry[] {
    if (server && server.id === generated.id && Array.isArray(server.nav)) return server.nav;
    return generated.nav.filter((n) => !n.feature || features[n.feature] === true);
}

/** One App of the Product with the areas it draws (PA-5b: the App switcher, and the landing page's groups). */
export interface AppAreas {
    readonly id: string;
    readonly title: string;
    readonly areas: readonly ProductNavEntry[];
}

/**
 * The Apps that draw at least one area of `nav`, in Product order (the generated `apps`, required first), each with its
 * areas in drawing order. An App's title is the server's when it resolved this Product, else the generated one.
 */
export function appAreas(
    server: BootstrapProduct | null,
    generated: GeneratedProduct,
    nav: readonly ProductNavEntry[],
): AppAreas[] {
    const served = server && server.id === generated.id ? server.apps : undefined;
    const title = (id: string) =>
        served?.find((a) => a.id === id)?.title ?? generated.apps.find((a) => a.id === id)?.title ?? id;
    const order = [...generated.apps.map((a) => a.id), ...nav.map((n) => n.app)];
    return order
        .filter((id, i) => order.indexOf(id) === i)
        .map((id) => ({ id, title: title(id), areas: nav.filter((n) => n.app === id) }))
        .filter((a) => a.areas.length > 0);
}

/** The App whose area a URL opens (its first path segment is that area's path), or null (the landing page, Settings). */
export function appOfUrl(nav: readonly ProductNavEntry[], url: string): string | null {
    const first = url.replace(/^\/+/, '').split(/[/?#;(]/)[0];
    return nav.find((n) => n.path === first)?.app ?? null;
}
