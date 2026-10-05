import * as axe from 'axe-core';

/**
 * axe-core accessibility assertion for component unit tests (vitest + jsdom).
 *
 * Runs axe against a rendered fixture's DOM and fails with a readable summary on any violation.
 * This is the automated half of the WCAG 2.2 AA work (UI/UX audit — Long-term #2); the manual
 * review lives in `docs/ui/accessibility-audit.md`.
 *
 * Rules disabled below either need real layout/painting (which jsdom does not do) or are
 * page-level checks that are meaningless against an isolated component fixture. Color-contrast in
 * particular cannot run in jsdom — it is covered in the manual audit and enforced for new code by
 * the design-system token guard (`npm run lint:tokens`).
 */
const DISABLED_RULES = [
    'color-contrast', // needs rendered colors/geometry — jsdom has none; see manual audit
    'region', // page-level: "all content in a landmark"
    'landmark-one-main',
    'page-has-heading-one',
    'html-has-lang',
    'document-title',
    'bypass',
] as const;

/**
 * `root` is usually a fixture's element; pass an axe context (e.g. `{ include: [a, b] }`) to scan
 * several regions of a large page in ONE axe pass — each separate run pays axe's setup cost again.
 */
export async function expectNoA11yViolations(root: Element | axe.ElementContext): Promise<void> {
    // axe-core keeps module-global run state and rejects an overlapping run with "Axe is already
    // running". A run orphaned by a timed-out test (slow under suite load) would otherwise poison
    // every later test in the worker, so runs are strictly serialised through this queue.
    const turn = axeQueue;
    let release!: () => void;
    axeQueue = new Promise<void>((resolve) => (release = resolve));
    await turn;
    let results: axe.AxeResults;
    try {
        results = await runAxe(root);
    } finally {
        release();
    }
    reportViolations(results);
}

let axeQueue: Promise<void> = Promise.resolve();

function runAxe(root: Element | axe.ElementContext): Promise<axe.AxeResults> {
    return axe.run(root, {
        rules: Object.fromEntries(DISABLED_RULES.map((id) => [id, { enabled: false }])),
        resultTypes: ['violations'],
    });
}

function reportViolations(results: axe.AxeResults): void {
    if (results.violations.length > 0) {
        const summary = results.violations
            .map(
                (v) =>
                    `  • [${v.impact}] ${v.id} — ${v.help}\n` +
                    `    ${v.nodes.length} node(s); e.g. ${v.nodes[0]?.target.join(' ')}\n` +
                    `    ${v.helpUrl}`,
            )
            .join('\n');
        throw new Error(`axe-core found ${results.violations.length} accessibility violation(s):\n${summary}`);
    }
}
