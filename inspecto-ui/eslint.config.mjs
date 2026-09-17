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
        rules: {
            // REVIEWED DECISION (operator, 2026-09-17, lint drain): after the mechanical sweep, every remaining
            // `no-unused-vars` finding (25) was an `_`-prefixed INTENT marker: a rest-sibling omission
            // (`const { key: _dropped, ...rest } = obj` - the binding IS the behaviour), a typed mock parameter a
            // spec reads back through `mock.calls[i][k]`, or a non-trailing parameter a caller's arity pins.
            // Deleting them changes behaviour or breaks the type-check (TS2554 surfaced twice trying). The
            // `_` prefix is the conventional way to say "unused on purpose", so the rule is told to honour it.
            '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true }],
        },
    },
    {
        // REVIEWED DECISION (operator, 2026-09-17, lint drain): every `prefer-on-push` finding in the tree
        // was a test-HOST stub component inside a spec. A test host's change-detection strategy is not
        // production behaviour, and forcing OnPush onto hosts that mutate plain fields would make specs
        // pass or fail for reasons unrelated to the component under test. Production components stay
        // under the rule.
        files: ['**/*.spec.ts'],
        rules: { '@angular-eslint/prefer-on-push-component-change-detection': 'off' },
    },
    {
        // Inline templates reach this block too: `processInlineTemplates` above lifts them out of the .ts
        // file as virtual .html documents.
        files: ['**/*.html'],
        extends: [...angular.configs.templateRecommended, ...angular.configs.templateAccessibility],
        rules: {
            // REVIEWED DECISION (operator, 2026-09-17, lint drain): all eight `template/eqeqeq` findings were
            // `x != null`, the idiom that also catches `undefined` (`latencyMs != null` on an optional field).
            // Rewriting them to `!== null` would have silently broken undefined handling. Strict equality is
            // still required everywhere except the null/undefined check itself.
            '@angular-eslint/template/eqeqeq': ['error', { allowNullOrUndefined: true }],
        },
    },
);
