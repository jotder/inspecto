// ESLint flat config — the `lint` leg of the angular-ui skill's GAUNTLET, which until 2026-09-17 ran
// NOTHING: `ng lint` failed with "Cannot find 'lint' target" because no builder was registered, and the
// only lint-ish script was `lint:tokens` (the design-token guard, which is not a linter).
//
// Stack is pinned by the framework, not chosen: angular-eslint 22.x matches Angular 22, and
// typescript-eslint 8.x is the only line that accepts TypeScript 6.0.3 (its peer range is
// >=4.8.4 <6.1.0). Do not bump either past what @angular/build peers.
//
// ⚠ RULES ARE THE UPSTREAM RECOMMENDED SETS, DELIBERATELY UNCUSTOMISED. There were no "repo rules" to
// port — no ESLint config had ever been tracked in this repo — so inventing a house ruleset here would
// have been a large unreviewed policy change smuggled inside a "wire up lint" task. The presets are the
// honest starting point; tighten them in a separate, reviewed change.
//
// Scope mirrors what the design-token guard, prettier and the coverage step already treat as OURS.

import eslint from '@eslint/js';
import tseslint from 'typescript-eslint';
import angular from 'angular-eslint';

export default tseslint.config(
    {
        // Same exclusions as .prettierignore: build output, the vendored gamma/Fuse template (the
        // angular-ui skill §3 bans touching it), the Jackson-written contract JSONs that Java tests
        // byte-compare, and fixture payloads.
        ignores: [
            'dist/',
            'coverage/',
            'out-tsc/',
            '.angular/',
            'src/@gamma/',
            'src/assets/',
            'src/app/inspecto/contracts/*.contract.json',
        ],
    },
    {
        files: ['**/*.ts'],
        extends: [
            eslint.configs.recommended,
            ...tseslint.configs.recommended,
            ...angular.configs.tsRecommended,
        ],
        processor: angular.processInlineTemplates,
    },
    {
        files: ['**/*.html'],
        extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
    },
);
