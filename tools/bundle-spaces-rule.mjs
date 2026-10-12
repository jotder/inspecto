// The ONE rule for which Spaces a bundle may carry under spaces/ (used by tools/check-bundle-spaces.mjs).
//
// BUNDLES SHIP NO SPACES (operator 2026-09-25), EXCEPT the Link Analysis demo (operator 2026-10-12): the demo-auth build
// with the la-app UI flavor ships exactly ONE pre-seeded Space, `la-showcase`, so a presenter unzips, runs and signs in.
// Every other bundle shape (any edition, the gamma UI, a non-demo la-app bundle) still ships spaces/_templates only.
export const DEMO_SPACE = 'la-showcase';
export const TEMPLATE_GALLERY = '_templates';

/** @param entries top-level names under the bundle's spaces/  @param flavor { demoAuth: boolean, ui: string } */
export function bundleSpacesVerdict(entries, { demoAuth = false, ui = 'gamma' } = {}) {
  const spaces = entries.filter((n) => n !== TEMPLATE_GALLERY);
  const demoLa = demoAuth === true && ui === 'la-app';
  if (spaces.length === 0) return { ok: true };
  if (demoLa && spaces.length === 1 && spaces[0] === DEMO_SPACE) return { ok: true };
  const allowed = demoLa ? `only '${DEMO_SPACE}' (the demo-auth la-app flavor)` : 'none (bundles ship no Spaces)';
  return { ok: false, reason: `bundle carries Space(s) ${spaces.map((s) => `'${s}'`).join(', ')}; allowed: ${allowed}.` };
}
