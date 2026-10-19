// Fixture test for tools/check-backlog-items.mjs. Run: node --test tools/check-backlog-items.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkRows, parseBoard } from './check-backlog-items.mjs';

const known = new Set(['module.etl', 'layer.platform']);
const board = (...rows) => `# Backlog\n\n- **P2** · \`OUTSIDE-1\` — not in §3–§5\n\n## 3. Open\n\n${rows.join('\n')}\n\n## 6. Refusals\n\n- **P3** · \`REFUSED-1\` — x\n`;
const run = (...rows) => checkRows(parseBoard(board(...rows)), known);

test('a tagged row passes and is parsed', () => {
    const rows = parseBoard(board('- **P2** · `FOO-BAR-1` — **do the thing.** Waits. [items: module.etl, layer.platform · kind: defect · hold: demand]'));
    assert.equal(rows.length, 1);
    assert.deepEqual(rows[0], {
        line: 7, priority: 'P2', name: 'FOO-BAR-1', title: 'do the thing.', items: ['module.etl', 'layer.platform'],
        kind: 'defect', hold: 'demand', tagged: true, extra: [],
    });
    assert.deepEqual(checkRows(rows, known), []);
});
test('a row with a bold name and no id keeps the name as title; kind may be omitted', () => {
    const [r] = parseBoard(board('- **P3** · **Queries / BI** — more resolvers. → `x.md` [items: module.etl]'));
    assert.equal(r.name, null);
    assert.equal(r.title, 'Queries / BI');
    assert.equal(r.kind, null);
    assert.equal(r.hold, null);
    assert.deepEqual(checkRows([r], known), []);
});
test('an untagged row is refused', () => {
    assert.match(run('- **P3** · `FOO-1` — x').join(), /no \[items/);
});
test('an empty item list is refused', () => {
    assert.match(run('- **P3** · `FOO-1` — x [items:  · kind: chore]').join(), /names no item/);
});
test('an unknown item id is refused', () => {
    assert.match(run('- **P3** · `FOO-1` — x [items: module.nope · kind: chore]').join(), /'module.nope' is not a Product Map Item/);
});
test('a kind outside the closed list is refused', () => {
    assert.match(run('- **P3** · `FOO-1` — x [items: module.etl · kind: issue]').join(), /kind 'issue'/);
});
test('a backticked name that is not id-shaped is still the name; the title comes from the bold headline', () => {
    const [r] = parseBoard(board('- **P3** · `MODULE-REORG-P3-THIN-JARS` — **thin jars.** x [items: module.etl]'));
    assert.equal(r.name, 'MODULE-REORG-P3-THIN-JARS');
    assert.equal(r.title, 'thin jars.');
});
test('a hold outside the closed list is refused', () => {
    assert.match(run('- **P3** · `FOO-1` — x [items: module.etl · hold: later]').join(), /hold 'later'/);
});
test('an unknown tag field is refused', () => {
    assert.match(run('- **P3** · `FOO-1` — x [items: module.etl · owner: me]').join(), /unknown tag field 'owner'/);
});
test('rows outside §3–§5 are not checked', () => {
    assert.equal(parseBoard(board()).length, 0);
});
