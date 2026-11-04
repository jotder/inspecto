/**
 * A Dataset column's **role** as stored (`columns[].role`, LA-COLUMN-ROLES-1; one vocabulary with the Studio Dataset
 * editor's dimension / measure / temporal) and the words the UI shows for it (`docs/GLOSSARY.md` *Column Role*).
 */
export type ColumnRole = 'entity' | 'temporal' | 'measure' | 'dimension' | 'detail';

export const ROLE_LABELS: Record<ColumnRole, string> = {
    entity: 'Entity',
    temporal: 'Time',
    measure: 'Measure',
    dimension: 'Category',
    detail: 'Detail',
};

/** What each role means, under its name in a role picker (the Data page, Add data). */
export const ROLE_HINTS: Record<ColumnRole, string> = {
    entity: 'Holds an Entity Type: links join on it',
    temporal: 'A date or time: to play and filter by',
    measure: 'A number to sum and filter by (an amount, a volume)',
    dimension: 'A few named values: to filter by (a type, a channel)',
    detail: 'Carried along, not filtered on',
};
