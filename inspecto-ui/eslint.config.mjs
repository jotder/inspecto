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

// Dynamic `import('...')` twin of the `no-restricted-imports` block further down (that rule ignores
// ImportExpression). esquery regexes cannot hold `)` or groups, so every restricted module is written as
// an exact match plus a `/`-prefix match. Keep these lists in step with that block's groups.
const HOST_MSG =
    'Link Analysis / Geo must not import host features (dynamic import): inject a token from app/inspecto/la-host instead (D-5 prep).';
const EDGE_MSG =
    'Tags / Transfer / AI assist / Cases are host edges (dynamic import): inject LA_TAGS / LA_TRANSFER / LA_AI_ASSIST / LA_CASES from app/inspecto/la-host instead (D-5 prep).';
const dynSel = (re, message) => ({ selector: `ImportExpression > Literal.source[value=${re}]`, message });
const SL = String.raw`\/`; // an escaped slash inside the selector regex
const exactOrUnder = (mod, message) => [
    dynSel(`/^${mod.replaceAll('/', SL)}$/`, message),
    dynSel(`/^${mod.replaceAll('/', SL)}${SL}/`, message),
];
const laDynamicImportSelectors = [
    dynSel(String.raw`/^\.\.$/`, HOST_MSG),
    dynSel(String.raw`/^\.\.\//`, HOST_MSG),
    ...['app/modules', 'src/app/modules'].flatMap((m) => exactOrUnder(m, HOST_MSG)),
    ...['tags', 'transfer', 'ai-assist'].flatMap((m) =>
        ['app', 'src/app'].flatMap((root) => exactOrUnder(`${root}/inspecto/${m}`, EDGE_MSG)),
    ),
    ...exactOrUnder('app/inspecto/api/objects.service', EDGE_MSG),
    ...exactOrUnder('src/app/inspecto/api/objects.service', EDGE_MSG),
    {
        selector: 'ImportExpression > :not(Literal).source',
        message:
            'Dynamic import() in Link Analysis / Geo needs a string-literal specifier so the separation rule can check it (D-5 prep).',
    },
];

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
        // REVIEWED DECISION (D-5 prep, la-separation-feasibility-plan §7.5/§7.12): the Link Analysis and Geo
        // feature code is the future `projects/link-analysis` library. It must not import a host feature
        // (`app/modules/**`) nor escape its own folder with `../`; everything host-specific enters through the
        // injected tokens in `app/inspecto/la-host`, provided by `modules/admin/studio/la-host.providers.ts`.
        // Specs are exempt (they may import host doubles). Dynamic `import()` is not seen by
        // `no-restricted-imports`, so `no-restricted-syntax` below applies the same restrictions to it.
        files: ['src/app/modules/admin/studio/link-analysis/**/*.ts', 'src/app/modules/admin/studio/geo-map/**/*.ts'],
        ignores: ['**/*.spec.ts'],
        rules: {
            'no-restricted-imports': [
                'error',
                {
                    patterns: [
                        {
                            group: ['app/modules/**', 'src/app/modules/**', '../**'],
                            message:
                                'Link Analysis / Geo must not import host features: inject a token from app/inspecto/la-host instead (D-5 prep).',
                        },
                        {
                            // The four `app/inspecto/**` host edges tokenised in D-5 prep part 2 (§7.12): Tags,
                            // Transfer (import/export), AI assist, Cases. The shared library paths stay allowed.
                            group: [
                                'app/inspecto/tags',
                                'app/inspecto/tags/**',
                                'app/inspecto/transfer',
                                'app/inspecto/transfer/**',
                                'app/inspecto/ai-assist',
                                'app/inspecto/ai-assist/**',
                                'app/inspecto/api/objects.service',
                                'src/app/inspecto/tags/**',
                                'src/app/inspecto/transfer/**',
                                'src/app/inspecto/ai-assist/**',
                                'src/app/inspecto/api/objects.service',
                            ],
                            message:
                                'Tags / Transfer / AI assist / Cases are host edges: inject LA_TAGS / LA_TRANSFER / LA_AI_ASSIST / LA_CASES from app/inspecto/la-host instead (D-5 prep).',
                        },
                    ],
                    paths: [
                        {
                            // `ObjectsService` is also re-exported by the `api` barrel, which LA otherwise uses freely.
                            name: 'app/inspecto/api',
                            importNames: ['ObjectsService'],
                            message:
                                'Cases are a host edge: inject LA_CASES from app/inspecto/la-host instead (D-5 prep).',
                        },
                    ],
                },
            ],
            // Same restrictions for dynamic `import('...')` (lazy routes, `await import(...)`), which
            // `no-restricted-imports` ignores. Keep the two regexes in step with the groups above.
            'no-restricted-syntax': ['error', ...laDynamicImportSelectors],
        },
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
