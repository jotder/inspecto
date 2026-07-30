import { AttributeSpec, AttributeType } from './attribute-spec';

/**
 * One SERVER-AUTHORED configuration field — the backend `FieldSpec` vocabulary as served by
 * `GET /parsers` (and, same shape, `GET /config/spec/{type}`). Framework-free so both the
 * onboarding Parsing stage and the Pipelines parser dialog can render served grammars.
 */
export interface ServedFieldSpec {
    path: string;
    label: string;
    description?: string;
    type: string; // STRING | INT | LONG | BOOL | ENUM | FILEPATH | CRON | SQL | MAP | LIST
    required?: boolean;
    defaultValue?: unknown;
    enumValues?: string[];
    pattern?: string | null;
    uiHint?: string | null;
    visibleWhen?: string | null; // "otherPath=value" — a rendering hint only
}

/** Served `FieldType` → the schema-form's control vocabulary. Absent = not generically renderable. */
const TYPE_MAP: Record<string, AttributeType> = {
    STRING: 'string',
    FILEPATH: 'string',
    CRON: 'string',
    SQL: 'multiline',
    INT: 'number',
    LONG: 'number',
    BOOL: 'boolean',
    ENUM: 'select',
};

/**
 * Served `FieldSpec`s → `AttributeSpec`s for `<inspecto-schema-form>` (the `findingsAttributes`
 * idiom: a section whose type the renderer cannot draw is SKIPPED, never guessed — the server may
 * serve field shapes this build doesn't know, e.g. `LIST` composites that need a bespoke editor).
 * Dotted paths become `__` flat keys (a literal `.` collides with Angular form-path semantics);
 * a `visibleWhen "other.path=value"` hint becomes a `dependsOn` equals-clause.
 */
export function fieldSpecsToAttributes(specs: ServedFieldSpec[] | undefined): AttributeSpec[] {
    const out: AttributeSpec[] = [];
    for (const f of specs ?? []) {
        const type = TYPE_MAP[String(f.type ?? '').toUpperCase()];
        if (!type || !f.path) continue;
        out.push({
            key: f.path.replaceAll('.', '__'),
            label: f.label || f.path,
            type,
            tier: f.required ? 'required' : 'optional',
            required: !!f.required,
            default: f.defaultValue ?? undefined,
            options: type === 'select' ? (f.enumValues ?? []).map((v) => ({ value: v, label: v })) : undefined,
            pattern: f.pattern ?? undefined,
            dependsOn: dependsOnOf(f.visibleWhen),
            help: f.description || undefined,
        });
    }
    return out;
}

/** `"other.path=value"` → a `dependsOn` equals-clause on the flattened key; anything else = none. */
function dependsOnOf(visibleWhen: string | null | undefined): AttributeSpec['dependsOn'] {
    const raw = (visibleWhen ?? '').trim();
    const eq = raw.indexOf('=');
    if (eq <= 0) return undefined;
    return { key: raw.slice(0, eq).replaceAll('.', '__'), equals: raw.slice(eq + 1) };
}
