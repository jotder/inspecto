/** Framework-free helpers for the Config Preview pane: which draft each preview takes, and parsing the pasted inputs. */
export type PreviewKind = 'parsing' | 'schema' | 'enrichment';

export const PREVIEW_KINDS: { value: PreviewKind; label: string; hint: string }[] = [
    { value: 'parsing', label: 'Parsing', hint: 'Parse raw sample text with a Pipeline draft’s parsing settings.' },
    { value: 'schema', label: 'Schema', hint: 'Cast parsed sample rows against a schema draft’s typed fields.' },
    {
        value: 'enrichment',
        label: 'Enrichment',
        hint: 'Run an enrichment draft’s transform over sample rows (needs Workbench authoring).',
    },
];

/** What the sample box holds: raw text for Parsing, a JSON array of row objects for the other two. */
export const sampleIsText = (kind: PreviewKind): boolean => kind === 'parsing';

/** Parse the draft box: must be a JSON object. Returns the object or a one-sentence reason. */
export function parseDraft(text: string): { value: Record<string, unknown> } | { error: string } {
    let v: unknown;
    try {
        v = JSON.parse(text);
    } catch (e) {
        return { error: `The draft is not valid JSON: ${(e as Error).message}` };
    }
    if (v === null || typeof v !== 'object' || Array.isArray(v)) return { error: 'The draft must be a JSON object.' };
    return { value: v as Record<string, unknown> };
}

/** Parse the sample rows box: must be a non-empty JSON array of objects. */
export function parseRows(text: string): { value: Record<string, unknown>[] } | { error: string } {
    let v: unknown;
    try {
        v = JSON.parse(text);
    } catch (e) {
        return { error: `The sample rows are not valid JSON: ${(e as Error).message}` };
    }
    if (!Array.isArray(v) || v.length === 0 || v.some((r) => r === null || typeof r !== 'object' || Array.isArray(r)))
        return { error: 'The sample rows must be a non-empty JSON array of objects.' };
    return { value: v as Record<string, unknown>[] };
}
