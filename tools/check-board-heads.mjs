#!/usr/bin/env node
/**
 * Board-heads guard — a ranked BACKLOG row must not be closed, struck, or ranked twice (BOARD-STALE-HEADS-1).
 *
 * WHY THIS EXISTS. On 2026-09-19 a shift grounded nine rows and found six of them already shipped, blocked
 * or duplicates. The board carried two shapes that make that happen: a closed row whose head stayed
 * unstruck next to a struck copy, and a row still RANKED while its own head said the work was CLOSED or had
 * shipped. The 2026-09-24 consolidation removed both by hand; nothing held them. A reader triaging by
 * headline picks up finished work, and the cost is a whole shift.
 *
 * WHAT A "RANKED ROW" IS. A list line `- **P1|P2|P3** · <head> — <headline> …` (optionally struck:
 * `- ~~**P2** · …`). Its ROW ID is an ALL-CAPS-ID-N token (`DEPLOY-SERVICE-WRAPPER-1`) sitting at the very
 * start of the head, in backticks or bold. Rows whose head is a prose name (`**Postgres multi-user**`) carry
 * no row id and are checked only by (b). A row is its FIRST line — heads never wrap.
 *
 * WHAT IT CHECKS
 *   (a) A row id is the head of a STRUCK row (`- ~~**P2** · \`X-1\`` or `- **P2** · ~~\`X-1\`~~`, anywhere
 *       in the file) AND the head of an unstruck ranked row. The board's rule is "closed rows are DELETED,
 *       not struck"; a struck head next to a live one is exactly the drift that rule forbids.
 *   (b) A ranked row between `## 3.` and `## 6.` whose HEAD CLAUSE carries a closed marker. The head clause
 *       is the row head plus its headline: the bold `**…**` right after the first ` — `, or, when the
 *       headline is not bold, the text up to the first `. `, `; `, ` (` or ` — `. The separator must follow
 *       the name directly — a ` — ` deeper in the row is body prose. A wholly struck ranked row in the span is
 *       itself a finding. The body is NOT read —
 *       open rows legitimately narrate what already shipped around their residual.
 *       CLOSED MARKERS, deliberately narrow (each was run against the live board with zero hits):
 *         1. the uppercase word `CLOSED` — except `fail CLOSED` / `fails-CLOSED` (a design property);
 *         2. `✅` directly followed by SHIPPED / CLOSED / DONE (any case, optional `**`) — the board's
 *            status idiom. `✅ the comparison SHIPPED` does NOT match: that names a shipped PART;
 *         3. `had shipped` / `had closed` / `already shipped` / `already closed` (any case);
 *         4. a headline that IS a status: `**SHIPPED…`, `**DONE…` (uppercase);
 *         5. a strike (`~~`) anywhere in the head clause.
 *       ⛔ Not markers: lowercase `shipped` / `closed` alone ("core shipped 2026-09-16 — one residual"),
 *       `BUILT`, `decided`, `resolved`. Those describe parts of open rows on today's board; matching them
 *       would make this guard mostly exemption, which check-doc-counts.mjs records as worse than none.
 *   (c) A row id is the head of two or more unstruck ranked rows. One id, one row.
 *
 * Emptiness floors (MIN_RANKED, MIN_IDS) stop a parser that silently matches nothing from passing.
 *
 * Usage: `node tools/check-board-heads.mjs [file]` — defaults to docs/BACKLOG.md. The optional path is for
 * falsification against a planted copy; CI and the hook run it with no argument.
 * Exit codes: 0 clean, 1 a finding or a floor breach. Pure Node, no dependencies, ~instant.
 * Run by `.github/workflows/ci.yml` and `.githooks/pre-push`.
 */

import { readFileSync } from 'node:fs';

const FILE = process.argv[2] ?? 'docs/BACKLOG.md';

/**
 * Floors measured on the board of 2026-09-25: 37 ranked rows, 13 of them with a row-id head. Set a little
 * under, so ordinary row deletion does not trip them but a parser that stopped matching does.
 * ⚠ Lower these only alongside a real, deliberate shrink of the board — never to make a red build green.
 */
const MIN_RANKED = 20;
const MIN_IDS = 8;

function fail(message) {
    console.error(`✗ Board-heads guard: ${message}`);
    process.exit(1);
}

const ID = String.raw`[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-\d+`;
// `- ` [~~] **Pn** [~~] ` · ` rest
const RANKED = /^- (~~)?\*\*P([123])\*\*(~~)? · (.*)$/;
// An id at the very start of the head, possibly struck in place: ~~`X-1`~~ or `X-1` or **X-1**.
const HEAD_ID = new RegExp(String.raw`^(~~)?(?:\x60(${ID})\x60|\*\*(${ID})\*\*)(~~)?(?=[\s—]|$)`);
// A struck unranked list row whose head is an id: `- ~~\`X-1\` …`.
const STRUCK_UNRANKED = new RegExp(String.raw`^- ~~(?:\x60(${ID})\x60|\*\*(${ID})\*\*)`);

const MARKERS = [
    { name: 'uppercase CLOSED', re: /(?<!\b[Ff][Aa][Ii][Ll][Ss]?[\s-])\bCLOSED\b/ },
    { name: '✅ status (SHIPPED/CLOSED/DONE)', re: /✅\s*(?:\*\*)?\s*(?:SHIPPED|CLOSED|DONE)\b/i },
    { name: '"had/already shipped|closed"', re: /\b(?:had|already)\s+(?:shipped|closed)\b/i },
    { name: 'status headline (**SHIPPED / **DONE)', re: /\*\*(?:SHIPPED|DONE)\b/ },
    { name: 'strike (~~) in the head', re: /~~/ },
];

/** The head clause: leading name token + ` — ` + the headline (bold span, or first clause). */
function headClause(rest) {
    let i = 0;
    let s = rest;
    if (s.startsWith('~~')) i = 2;
    // Skip the leading name token so a ` — ` INSIDE a bold name is not taken as the separator.
    if (s.startsWith('`', i)) {
        const c = s.indexOf('`', i + 1);
        i = c < 0 ? s.length : c + 1;
    } else if (s.startsWith('**', i)) {
        const c = s.indexOf('**', i + 2);
        i = c < 0 ? s.length : c + 2;
    }
    // The separator must FOLLOW the name token directly; a ` — ` further on is body prose. A head with no
    // name token (plain text) takes its first ` — ` only if it comes before the first sentence break.
    const stop = (t) => t.search(/\. |; | \(/);
    let dash;
    if (i > 0) dash = s.startsWith(' — ', i) ? i : -1;
    else {
        const d = s.indexOf(' — ');
        const b = stop(s);
        dash = d >= 0 && (b < 0 || d < b) ? d : -1;
    }
    if (dash < 0) {
        const m = stop(s.slice(i));
        return m < 0 ? s : s.slice(0, i + m);
    }
    const after = s.slice(dash + 3);
    if (after.startsWith('**')) {
        const c = after.indexOf('**', 2);
        return s.slice(0, dash + 3) + (c < 0 ? after : after.slice(0, c + 2));
    }
    const m = after.search(/\. |; | \(| — /);
    return s.slice(0, dash + 3) + (m < 0 ? after : after.slice(0, m));
}

const lines = readFileSync(FILE, 'utf8').split(/\r?\n/);

const s3 = lines.findIndex((l) => l.startsWith('## 3. '));
const s6 = lines.findIndex((l) => l.startsWith('## 6. '));
if (s3 < 0 || s6 < 0 || s6 < s3) fail(`${FILE} has no "## 3." … "## 6." span — did the board get renumbered?`);

const live = new Map(); // id -> [line numbers] of unstruck ranked heads
const struck = new Map(); // id -> [line numbers] of struck heads
const closedHeads = [];
let ranked = 0;

lines.forEach((line, idx) => {
    const n = idx + 1;
    const r = RANKED.exec(line);
    if (!r) {
        const su = STRUCK_UNRANKED.exec(line);
        if (su) {
            const id = su[1] ?? su[2];
            struck.set(id, [...(struck.get(id) ?? []), n]);
        }
        return;
    }
    ranked++;
    const rest = r[4];
    const rowStruck = Boolean(r[1] || r[3]);
    const h = HEAD_ID.exec(rest);
    if (h) {
        const id = h[2] ?? h[3];
        const isStruck = rowStruck || Boolean(h[1] && h[4]);
        const map = isStruck ? struck : live;
        map.set(id, [...(map.get(id) ?? []), n]);
    }
    if (idx > s3 && idx < s6 && rowStruck) {
        closedHeads.push(`line ${n}: the whole ranked row is struck — closed rows are DELETED, not struck`);
    } else if (idx > s3 && idx < s6) {
        const head = headClause(rest);
        for (const { name, re } of MARKERS) {
            if (re.test(head)) {
                closedHeads.push(`line ${n}: ${name} in head "${head.slice(0, 140)}"`);
                break;
            }
        }
    }
});

if (ranked < MIN_RANKED) {
    fail(
        `only ${ranked} ranked row(s) parsed out of ${FILE}, below the floor of ${MIN_RANKED}. Either the ` +
            `row shape changed (fix this parser) or the board shrank (lower MIN_RANKED deliberately).`,
    );
}
const idCount = [...live.values()].reduce((a, v) => a + v.length, 0);
if (idCount < MIN_IDS) {
    fail(
        `only ${idCount} ranked row(s) carry a row-id head, below the floor of ${MIN_IDS}. A check over ` +
            `ids nobody matched is the bug this guard exists for.`,
    );
}

const problems = [];
for (const [id, at] of struck) {
    if (live.has(id))
        problems.push(
            `(a) \`${id}\` is struck at line ${at.join(', ')} and still ranked at line ${live.get(id).join(', ')} ` +
                `— closed rows are DELETED, not struck; delete whichever copy is stale`,
        );
}
for (const c of closedHeads) problems.push(`(b) ranked row carries a closed marker — ${c}`);
for (const [id, at] of live) {
    if (at.length > 1)
        problems.push(`(c) \`${id}\` is ranked ${at.length} times, at lines ${at.join(', ')} — one id, one row`);
}

if (problems.length) {
    fail(
        `${FILE} has stale or duplicated row heads:\n  - ${problems.join('\n  - ')}\n` +
            `  Fix the ROW: delete a closed row (after marking the ship in its OKF concept), or rewrite ` +
            `its headline to name what is still open.`,
    );
}

console.log(
    `✓ Board-heads guard: ${FILE} — ${ranked} ranked row(s), ${idCount} with a row-id head; no id struck ` +
        `and live, no closed marker in a §3–§5 head, no id ranked twice.`,
);
