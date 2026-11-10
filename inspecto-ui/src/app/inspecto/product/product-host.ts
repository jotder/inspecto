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

/** A host's `product.generated.ts` - written by `tools/render-product-hosts.mjs`, never by hand. */
export interface GeneratedProduct {
    readonly id: string;
    readonly title: string;
    readonly brand: { readonly name: string; readonly signInPitch?: { readonly title: string; readonly body: string } };
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
