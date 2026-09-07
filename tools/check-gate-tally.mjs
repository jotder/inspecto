#!/usr/bin/env node
/**
 * Gate-tally guard — BACKLOG §2's stated numbers must equal what its table actually says.
 *
 * WHY THIS EXISTS. On 2026-09-07 §2 was rewritten so every externally-gated row names a command a shift
 * can run. Hours later the same shift reported "13 of 15 still gated" in a commit message and in the
 * section header. Both numbers were wrong: the table has 16 rows, and 4 of them could not be checked from
 * the checkout at all — so rows with NO EVIDENCE EITHER WAY were summed into a count of gates that
 * "hold". Nobody could have caught that by reading; the numbers looked like evidence.
 *
 * A gate you cannot run is not a gate that holds. This guard makes the distinction structural: the
 * section must state its own arithmetic, and the arithmetic must match the rows.
 *
 * WHAT IT CHECKS
 *   1. §2 contains a table of `| **Item** | … |` rows — at least MIN_ROWS of them (an emptiness floor, so
 *      a parse that silently matches nothing cannot pass; the failure mode this repo has hit repeatedly).
 *   2. The header states `N of the M gates were RUN`, and M equals the row count.
 *   3. It states `The K not run`, and K equals the number of rows carrying a `NOT RUN` marker.
 *   4. N + K equals M — the run and not-run halves account for every row exactly once.
 *
 * ⛔ It deliberately does NOT check whether a gate has FIRED. That is the shift's job and needs judgement.
 * This only refuses a section whose summary contradicts its own contents.
 *
 * Pure Node, no dependencies, ~instant. Run by `.github/workflows/ci.yml` and `.githooks/pre-push`.
 */

import { readFileSync } from 'node:fs';

const FILE = 'docs/BACKLOG.md';

/**
 * The emptiness floor. §2 has had 16 rows since it was consolidated; a run that finds fewer than a dozen
 * has almost certainly stopped matching the table rather than found rows deleted. ⚠ Lower this only
 * alongside a real, deliberate shrink of the section — never to make a red build green.
 */
const MIN_ROWS = 12;

function fail(message) {
    console.error(`✗ Gate-tally guard: ${message}`);
    process.exit(1);
}

const text = readFileSync(FILE, 'utf8');

// §2 runs from its own heading to the next `## ` heading.
const start = text.indexOf('\n## 2. ');
if (start < 0) fail(`${FILE} has no "## 2." section — did the board get renumbered?`);
const rest = text.slice(start + 1);
const end = rest.indexOf('\n## ', 1);
const section = end < 0 ? rest : rest.slice(0, end);

// A row is a table line whose first cell is a bold item name. The header row (`| Item | Remains | …`)
// and the separator (`|---|`) are not bold, so they do not match.
const rows = section.split('\n').filter((line) => /^\|\s*\*\*/.test(line));
if (rows.length < MIN_ROWS) {
    fail(
        `only ${rows.length} gate row(s) parsed out of §2, below the floor of ${MIN_ROWS}. ` +
            `Either the table changed shape (fix this parser) or rows were deleted (lower MIN_ROWS ` +
            `deliberately). A tally over rows nobody matched is the bug this guard exists for.`,
    );
}

const notRun = rows.filter((line) => line.includes('NOT RUN')).length;

const ran = section.match(/(\d+)\s+of the\s+(\d+)\s+gates were RUN/);
if (!ran) {
    fail(
        `§2 must state its own arithmetic as "<N> of the <M> gates were RUN here" — no such sentence ` +
            `found. Without it the section can claim anything and nothing checks it.`,
    );
}
const [, statedRunStr, statedTotalStr] = ran;
const statedRun = Number(statedRunStr);
const statedTotal = Number(statedTotalStr);

const stated = section.match(/The\s+(\d+)\s+not run/);
if (!stated) {
    fail(`§2 must state "The <K> not run, and why" so the unchecked rows are counted, not implied.`);
}
const statedNotRun = Number(stated[1]);

const problems = [];
if (statedTotal !== rows.length)
    problems.push(`says ${statedTotal} gates, table has ${rows.length} rows`);
if (statedNotRun !== notRun)
    problems.push(`says ${statedNotRun} not run, ${notRun} row(s) carry a NOT RUN marker`);
if (statedRun + statedNotRun !== rows.length)
    problems.push(
        `${statedRun} run + ${statedNotRun} not run = ${statedRun + statedNotRun}, but the table has ` +
            `${rows.length} rows — a gate is either run or not run, never both and never neither`,
    );

if (problems.length) {
    fail(
        `BACKLOG §2's summary contradicts its own table:\n  - ${problems.join('\n  - ')}\n` +
            `  Fix the SENTENCE to match the rows, not the rows to match the sentence.`,
    );
}

console.log(
    `✓ Gate-tally guard: BACKLOG §2 — ${rows.length} gate row(s), ${statedRun} run, ` +
        `${notRun} not run; the stated arithmetic matches the table.`,
);
