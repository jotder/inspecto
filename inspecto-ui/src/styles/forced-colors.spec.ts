import { describe, expect, it } from 'vitest';

/** jsdom cannot paint `forced-colors: active`, so this pins that the rules exist; how they paint is a manual check. */
describe('forced-colors rules in styles.scss', () => {
    // no @types/node in the spec tsconfig: reach fs through the runtime
    const fs = (
        globalThis as unknown as {
            process: { getBuiltinModule(n: string): { readFileSync(p: string, e: string): string } };
        }
    ).process.getBuiltinModule('node:fs');
    const css = fs.readFileSync('src/styles/styles.scss', 'utf8');
    const block = css.slice(css.indexOf('@media (forced-colors: active)'));

    it('keeps a visible outline on a selected tab, a selected row and a pressed toggle', () => {
        expect(block).toContain("[role='tab'][aria-selected='true']");
        expect(block).toContain("[role='row'][aria-selected='true']");
        expect(block).toContain("[aria-pressed='true']:not(:focus-visible)");
        expect(block.match(/outline: 2px solid Highlight/g)?.length).toBeGreaterThanOrEqual(2);
    });
});
