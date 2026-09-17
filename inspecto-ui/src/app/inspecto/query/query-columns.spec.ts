import { describe, expect, it } from 'vitest';

import COLUMN_ROLE_CONTRACT from 'app/inspecto/contracts/column-role.contract.json';

import { dbColumnType } from './query-columns';

describe('dbColumnType', () => {
    it('maps normalized /db types and raw SQL spellings to the query ColumnType', () => {
        expect(dbColumnType('number')).toBe('number'); // /db/table reports normalized names
        expect(dbColumnType('BIGINT')).toBe('number');
        expect(dbColumnType('DECIMAL(18,3)')).toBe('number');
        expect(dbColumnType('date')).toBe('date');
        expect(dbColumnType('TIMESTAMP WITH TIME ZONE')).toBe('date');
        expect(dbColumnType('boolean')).toBe('boolean');
        expect(dbColumnType('VARCHAR')).toBe('string');
        expect(dbColumnType(undefined)).toBe('string');
    });

    /**
     * The query builder's interpreter is the SECOND reader of DuckDB type names — `ResultSetDescriptor
     * .columnType(String)` is the first — so it is pinned to the SAME committed fixture the Java
     * `ColumnRoleContractTest` reads, and the two cannot drift apart silently.
     *
     * 🔴 Until `COLUMN-TYPE-SECOND-INTERPRETER-1` this function tested UNANCHORED regexes over the whole
     * spelling, so `INT` matched inside `INTERVAL` / `BIGINT[]` / `STRUCT(a INTEGER)` / `MAP(VARCHAR,
     * INTEGER)` and all four came back `number` — numeric operators on a value that has none, and
     * `query-sql.ts` emitting a BARE literal for it. That is one substring bug, not four disagreements.
     */
    it('agrees with the committed column-role contract on every published DuckDB type', () => {
        // Pinned so a silently-emptied contract cannot turn this into a vacuous pass.
        expect(COLUMN_ROLE_CONTRACT.duckdbTypeCases.length).toBeGreaterThanOrEqual(20);

        for (const c of COLUMN_ROLE_CONTRACT.duckdbTypeCases)
            expect(dbColumnType(c.duckdbType), `dbColumnType(${c.duckdbType})`).toBe(c.type);
    });

    it('keeps the already-coarse /db names mapping to themselves', () => {
        // `/db/query` normalizes before it answers, so the pass-through is a real input, not a courtesy.
        for (const t of COLUMN_ROLE_CONTRACT.coarseTypes) expect(dbColumnType(t)).toBe(t);
    });

    it('reads the leading token, never a substring of the whole spelling', () => {
        expect(dbColumnType('INTERVAL')).toBe('string'); // a duration is not a number and not a date
        expect(dbColumnType('BIGINT[]')).toBe('string'); // a LIST of a thing is not that thing
        expect(dbColumnType('STRUCT(a INTEGER)')).toBe('string');
        expect(dbColumnType('MAP(VARCHAR, TIMESTAMP)')).toBe('string');
        expect(dbColumnType('  bigint  ')).toBe('number'); // trimmed + case-folded like the Java reader
    });
});
