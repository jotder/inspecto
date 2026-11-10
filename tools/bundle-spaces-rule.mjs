// The ONE rule for which Spaces a bundle may carry under spaces/ (used by tools/check-bundle-spaces.mjs).
//
// BUNDLES SHIP NO SPACES (operator 2026-09-25), EXCEPT a demo bundle with a pre-seeded demo Space (operator 2026-10-12, then the
// Link Analysis demo's `la-showcase`): since PRODUCT-ASSEMBLY-1 PA-2 the Space is the Product's `demoSpace`, carried by the
// Assembly Plan as `seedSpace` (tools/assemble.mjs sets it only for a demo build). A bundle built with a seedSpace may carry
// exactly that one Space; every other bundle (any edition, any Product, production or a demo without a demoSpace) still
// ships spaces/_templates only.
export const TEMPLATE_GALLERY = '_templates';

/** @param entries top-level names under the bundle's spaces/  @param flavor { seedSpace: string|null } (the plan's seedSpace) */
export function bundleSpacesVerdict(entries, { seedSpace = null } = {}) {
  const spaces = entries.filter((n) => n !== TEMPLATE_GALLERY);
  if (spaces.length === 0) return { ok: true };
  if (seedSpace && spaces.length === 1 && spaces[0] === seedSpace) return { ok: true };
  const allowed = seedSpace ? `only '${seedSpace}' (the plan's seedSpace)` : 'none (bundles ship no Spaces)';
  return { ok: false, reason: `bundle carries Space(s) ${spaces.map((s) => `'${s}'`).join(', ')}; allowed: ${allowed}.` };
}
