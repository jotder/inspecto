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
    'Link Analysis / Geo must not import host features (dynamic import): inject a token from @inspecto/link-analysis (la-host) instead (D-5 prep).';
const EDGE_MSG =
    'Tags / Transfer / AI assist / Cases are host edges (dynamic import): inject LA_TAGS / LA_TRANSFER / LA_AI_ASSIST / LA_CASES from @inspecto/link-analysis (la-host) instead (D-5 prep).';
const FEATURES_MSG =
    'Host module flags (ops / exchange / geoLink) are a host edge: inject LA_FEATURES from @inspecto/link-analysis (la-host) instead, not SessionService (D-5 prep).';
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
        ['app/inspecto', 'src/app/inspecto', '@inspecto/core'].flatMap((root) =>
            exactOrUnder(`${root}/${m}`, EDGE_MSG),
        ),
    ),
    ...['app/inspecto', 'src/app/inspecto', '@inspecto/core'].flatMap((root) => [
        ...exactOrUnder(`${root}/api/objects.service`, EDGE_MSG),
        ...exactOrUnder(`${root}/api/session.service`, FEATURES_MSG),
        // D-5 step 5: the OIDC client code (SessionService included) lives in `inspecto/auth`; the barrel still re-exports it.
        ...exactOrUnder(`${root}/auth/session.service`, FEATURES_MSG),
    ]),
    {
        selector: 'ImportExpression > :not(Literal).source',
        message:
            'Dynamic import() in Link Analysis / Geo needs a string-literal specifier so the separation rule can check it (D-5 prep).',
    },
];

// D-5 step 1: the folders under `app/inspecto` that ARE the Link Analysis / Geo library half, and the slice of
// `graph/` that stays core (Decision 2a). `coreGraphFiles` is an ALLOW-list, so a new graph file defaults to library.
const coreGraphFiles = [
    'graph-types',
    'graph-source',
    'entity-key',
    'branching-stage',
    'catalog-graph',
    'graph-view.component',
    'graph-export',
];
const CORE_MSG =
    'Core (app/inspecto/**) must not import Link Analysis / Geo library code: keep the type or function core needs in a core file, or inject it (D-5 step 1).';
// D-5 step 3: the library now lives in projects/link-analysis and is reached through its alias; core must never use it.
const LIB_ALIAS_MSG =
    'Core (app/inspecto/**) must not import @inspecto/link-analysis: the arrow runs core <- library <- shells (D-5 step 3).';
// ⚠ Two pattern objects on purpose: a gitignore-style `!` re-include does not work under an excluded PARENT directory,
// so the bare folder names (the barrels: `app/inspecto/graph`) live apart from the `/**` group that carries the exceptions.
const coreMustNotImportLa = [
    {
        group: ['@inspecto/link-analysis', '@inspecto/link-analysis/**', '**/projects/link-analysis/**'],
        message: LIB_ALIAS_MSG,
    },
    {
        // A bare folder specifier is a barrel import. (`group` cannot express "the folder only": gitignore semantics
        // make `**/graph` cover everything under it too, which would defeat the exceptions below - hence a regex.)
        regex: '(^|/)(link-analysis|geo-map|la-host|geo|investigation|graph)$',
        message: CORE_MSG,
    },
    {
        group: [
            '**/link-analysis/**',
            '**/geo-map/**',
            '**/la-host/**',
            '**/geo/**',
            '**/investigation/**',
            '!**/investigation/unique-name',
            '**/graph/**',
            ...coreGraphFiles.map((f) => `!**/graph/${f}`),
        ],
        message: CORE_MSG,
    },
];
// Inside the core slice of graph/ a sibling import is relative (`./x`): only the other core graph files are allowed.
const coreGraphSiblingsOnly = [{ group: ['./*', ...coreGraphFiles.map((f) => `!./${f}`)], message: CORE_MSG }];

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
        extends: [eslint.configs.recommended, ...tseslint.configs.recommended, ...angular.configs.tsRecommended],
        processor: angular.processInlineTemplates,
        rules: {
            // REVIEWED DECISION (operator, 2026-09-17, lint drain): after the mechanical sweep, every remaining
            // `no-unused-vars` finding (25) was an `_`-prefixed INTENT marker: a rest-sibling omission
            // (`const { key: _dropped, ...rest } = obj` - the binding IS the behaviour), a typed mock parameter a
            // spec reads back through `mock.calls[i][k]`, or a non-trailing parameter a caller's arity pins.
            // Deleting them changes behaviour or breaks the type-check (TS2554 surfaced twice trying). The
            // `_` prefix is the conventional way to say "unused on purpose", so the rule is told to honour it.
            '@typescript-eslint/no-unused-vars': [
                'error',
                { argsIgnorePattern: '^_', varsIgnorePattern: '^_', ignoreRestSiblings: true },
            ],
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
        // injected tokens in `@inspecto/link-analysis` (`la-host`), provided by `modules/admin/studio/la-host.providers.ts`.
        // Specs are exempt (they may import host doubles). Dynamic `import()` is not seen by
        // `no-restricted-imports`, so `no-restricted-syntax` below applies the same restrictions to it.
        files: [
            // D-5 step 3: the library folder the LA / Geo code now lives in.
            'projects/link-analysis/**/*.ts',
            // Legacy homes (before step 3), kept so a file re-added there is still linted.
            'src/app/modules/admin/studio/link-analysis/**/*.ts',
            'src/app/modules/admin/studio/geo-map/**/*.ts',
        ],
        ignores: ['**/*.spec.ts'],
        rules: {
            'no-restricted-imports': [
                'error',
                {
                    patterns: [
                        {
                            group: ['app/modules/**', 'src/app/modules/**', '../**'],
                            message:
                                'Link Analysis / Geo must not import host features: inject a token from @inspecto/link-analysis (la-host) instead (D-5 prep).',
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
                                // the spelling library files use since step 3
                                '@inspecto/core/tags',
                                '@inspecto/core/tags/**',
                                '@inspecto/core/transfer',
                                '@inspecto/core/transfer/**',
                                '@inspecto/core/ai-assist',
                                '@inspecto/core/ai-assist/**',
                                '@inspecto/core/api/objects.service',
                            ],
                            message:
                                'Tags / Transfer / AI assist / Cases are host edges: inject LA_TAGS / LA_TRANSFER / LA_AI_ASSIST / LA_CASES from @inspecto/link-analysis (la-host) instead (D-5 prep).',
                        },
                        {
                            // Host module flags are read through LA_FEATURES; `SessionService` is the host's.
                            group: [
                                'app/inspecto/api/session.service',
                                'src/app/inspecto/api/session.service',
                                '@inspecto/core/api/session.service',
                                'app/inspecto/auth/session.service',
                                'src/app/inspecto/auth/session.service',
                                '@inspecto/core/auth/session.service',
                            ],
                            message:
                                'Host module flags (ops / exchange / geoLink) are a host edge: inject LA_FEATURES from @inspecto/link-analysis (la-host) instead, not SessionService (D-5 prep).',
                        },
                    ],
                    paths: [
                        ...['app/inspecto/api', '@inspecto/core/api'].flatMap((name) => [
                            {
                                // `ObjectsService` is also re-exported by the `api` barrel, which LA otherwise uses freely.
                                name,
                                importNames: ['ObjectsService'],
                                message:
                                    'Cases are a host edge: inject LA_CASES from @inspecto/link-analysis (la-host) instead (D-5 prep).',
                            },
                            {
                                // Same barrel re-export: SessionService also comes out of `app/inspecto/api`.
                                name,
                                importNames: ['SessionService'],
                                message:
                                    'Host module flags (ops / exchange / geoLink) are a host edge: inject LA_FEATURES from @inspecto/link-analysis (la-host) instead, not SessionService (D-5 prep).',
                            },
                        ]),
                    ],
                },
            ],
            // Same restrictions for dynamic `import('...')` (lazy routes, `await import(...)`), which
            // `no-restricted-imports` ignores. Keep the two regexes in step with the groups above.
            'no-restricted-syntax': ['error', ...laDynamicImportSelectors],
        },
    },
    {
        // REVIEWED DECISION (D-5 step 1, la-separation-d5-design Decision 2a): the arrow runs core <- library <- shells,
        // never the other way. `app/inspecto/**` is CORE: it must not import the Link Analysis / Geo library code
        // (`link-analysis`, `geo-map`, `geo`, `la-host`, `investigation`, the graph barrel, the graph algorithms). The
        // core slice of the shared folders stays importable: the graph canvas + its types, `entity-key`, the branching
        // stage types, `unique-name`. Relative and `app/...` specifiers are both matched. Second block: the core slice
        // of `graph/` itself must not reach back into the library half. `coreGraphFiles` above is the allow-list.
        // Falsified by tools/core-no-la-imports-lint.test.mjs. Specs are exempt (they may import fixtures).
        files: ['src/app/inspecto/**/*.ts'],
        ignores: ['**/*.spec.ts'],
        rules: { 'no-restricted-imports': ['error', { patterns: coreMustNotImportLa }] },
    },
    {
        files: [
            ...coreGraphFiles.map((f) => `src/app/inspecto/graph/${f}.ts`),
            'src/app/inspecto/investigation/unique-name.ts',
        ],
        rules: {
            'no-restricted-imports': ['error', { patterns: [...coreMustNotImportLa, ...coreGraphSiblingsOnly] }],
        },
    },
    {
        // REVIEWED DECISION (D-5 step 2): the shared API layer reads the build-time environment only through the injected
        // APP_ENVIRONMENT (api/app-environment.ts, provided by the host app.config) - a library cannot import a host file.
        // Falsified by tools/core-no-la-imports-lint.test.mjs. Specs are exempt (they read the same object directly).
        files: ['src/app/inspecto/api/**/*.ts'],
        ignores: ['**/*.spec.ts'],
        rules: {
            'no-restricted-imports': [
                'error',
                {
                    // A later block REPLACES an earlier block's rule options, so the core guard is repeated here.
                    patterns: [
                        ...coreMustNotImportLa,
                        {
                            group: ['environments/**', '**/environments/**'],
                            message:
                                'api/ must not import environments/*: inject APP_ENVIRONMENT (api/app-environment.ts) instead (D-5 step 2).',
                        },
                    ],
                },
            ],
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
