import type { SCHEMA_TYPES } from './schema-fields-editor.component';

/** A type the engine honours: one entry of `SCHEMA_TYPES` (the mirror of the engine's `SchemaFieldTypes.names()`). */
export type SchemaType = (typeof SCHEMA_TYPES)[number];

/**
 * The column types Basic's **Add data** offers: the named subset of `SCHEMA_TYPES` an import commit accepts
 * (`OnboardImport.TYPES`, Java), in the order the type picker lists them (BASIC-ELT-PROFILE-1 U1-types).
 * Basic is the ELT path with options locked, so these are ELT's own type names, never a second vocabulary.
 */
export const BASIC_COLUMN_TYPES = [
    'VARCHAR',
    'BIGINT',
    'DOUBLE',
    'DATE',
    'TIMESTAMP',
    'BOOLEAN',
] as const satisfies readonly SchemaType[];

export type BasicColumnType = (typeof BASIC_COLUMN_TYPES)[number];

/** What Add data calls each Basic column type. */
export const BASIC_COLUMN_TYPE_LABELS: Record<BasicColumnType, string> = {
    VARCHAR: 'Text',
    BIGINT: 'Whole number',
    DOUBLE: 'Decimal number',
    DATE: 'Date',
    TIMESTAMP: 'Date and time',
    BOOLEAN: 'Yes / no',
};
