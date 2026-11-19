import { describe, expect, it } from 'vitest';
import { BASIC_COLUMN_TYPES, BASIC_COLUMN_TYPE_LABELS } from './basic-column-types';
import { SCHEMA_TYPES } from './schema-fields-editor.component';

/**
 * The types an import commit accepts: `OnboardImport.TYPES`, Java
 * (`inspecto/src/main/java/com/gamma/control/OnboardImport.java:93`). Hard-coded because no UI spec reads Java source;
 * change both together. TIME joined 2026-11-19 (operator ruling, BASIC-ELT-PROFILE-1).
 */
const ONBOARD_IMPORT_TYPES = ['VARCHAR', 'BIGINT', 'DOUBLE', 'DATE', 'TIMESTAMP', 'TIME', 'BOOLEAN'];

describe('BASIC_COLUMN_TYPES - Basic column types are the named subset of SCHEMA_TYPES (BASIC-ELT-PROFILE-1 U1-types)', () => {
    it('every Basic type is a type the engine honours', () => {
        for (const t of BASIC_COLUMN_TYPES) expect(SCHEMA_TYPES as readonly string[]).toContain(t);
    });

    it('is exactly the set an import commit accepts (OnboardImport.TYPES)', () => {
        expect([...BASIC_COLUMN_TYPES].sort()).toEqual([...ONBOARD_IMPORT_TYPES].sort());
    });

    it('names every Basic type, and only those', () => {
        expect(Object.keys(BASIC_COLUMN_TYPE_LABELS)).toEqual([...BASIC_COLUMN_TYPES]);
        for (const t of BASIC_COLUMN_TYPES) expect(BASIC_COLUMN_TYPE_LABELS[t].trim()).not.toBe('');
    });
});
