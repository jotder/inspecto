import { FindingsSection, FindingsSpecDef } from 'app/inspecto/api';
import { AttributeTier, AttributeType } from 'app/inspecto/component-model';

/**
 * The Findings-field editor's model (findings-spec authoring UI, slice S1 —
 * `docs/superpower/findings-spec-authoring-ui-design.md`). Framework-free: the dialog holds a list of
 * {@link FieldDraft}s and every rule lives here.
 *
 * The analyst never types a key, a choice value, a tier or a `dependsOn` object (design §3):
 * - a field's **key** and a choice's **value** are derived from the label the first time it is saved, and
 *   are **frozen** from then on (`key`/`value` non-null) — stored Findings values are keyed by them, so a
 *   rename must never orphan data (§5.4);
 * - "Show only when" points at another field (and, for a choice list, at one of its choices) by a local
 *   `uid`, so renaming the target — even an unsaved one whose key is still derived — never breaks it.
 *
 * Vocabulary (GLOSSARY *Findings field*): the UI says **Findings field**; `section` is the wire word only.
 */

/** One choice of a "Choose one from a list" (or "Suggested values") field. */
export interface ChoiceDraft {
    uid: string;
    label: string;
    /** The stored value — frozen once saved; `null` = new, derived from the label on save. */
    value: string | null;
}

/** "Show only when [field] [is / is not] [value]". */
export interface ShowWhenDraft {
    targetUid: string;
    negated: boolean;
    /** For a choice-list target: the {@link ChoiceDraft.uid}. Otherwise the literal value (`'true'`/`'false'` for Yes/No). */
    value: string;
}

/** One Findings field as the editor holds it. */
export interface FieldDraft {
    uid: string;
    /** The stored key — frozen once saved; `null` = new, derived from the label on save. */
    key: string | null;
    label: string;
    type: AttributeType;
    tier: AttributeTier;
    required: boolean;
    help: string;
    placeholder: string;
    pattern: string;
    min: number | null;
    max: number | null;
    choices: ChoiceDraft[];
    showWhen: ShowWhenDraft | null;
    /** An authored `default`, carried through untouched (the editor does not offer it). */
    defaultValue?: unknown;
}

/** A problem that blocks Save, phrased in the analyst's words against the field's label. */
export interface DraftProblem {
    fieldUid: string | null;
    message: string;
}

/**
 * The control types in plain words (design §5.2). A `Record<AttributeType, …>` so a type added to the
 * contract fails to compile here until it gets a plain label. `technical` ones sit under the collapsed
 * "Technical details" section only (D6).
 */
const CONTROL_LABELS: Record<AttributeType, { label: string; hint: string; technical: boolean }> = {
    select: { label: 'Choose one from a list', hint: 'A fixed set of choices you define', technical: false },
    string: { label: 'Short text', hint: 'One line of free text', technical: false },
    multiline: { label: 'Long text', hint: 'Several lines of free text', technical: false },
    number: { label: 'Number', hint: 'A number, optionally with a smallest and largest value', technical: false },
    boolean: { label: 'Yes / No', hint: 'A single yes-or-no answer', technical: false },
    identifier: { label: 'ID-style text', hint: 'Letters, digits, - and _ only', technical: true },
    list: { label: 'List of values', hint: 'Several short values', technical: true },
    autocomplete: { label: 'Suggested values', hint: 'Free text with suggestions you define', technical: true },
};

export interface ControlChoice {
    type: AttributeType;
    label: string;
    hint: string;
    technical: boolean;
}

/** Every control type, plain ones first, in the order the editor offers them. */
export const CONTROL_CHOICES: ControlChoice[] = (Object.keys(CONTROL_LABELS) as AttributeType[]).map((type) => ({
    type,
    ...CONTROL_LABELS[type],
}));

export function controlLabel(type: AttributeType): string {
    return CONTROL_LABELS[type]?.label ?? type;
}

/** Types whose value comes from {@link FieldDraft.choices}. */
export function usesChoices(type: AttributeType): boolean {
    return type === 'select' || type === 'autocomplete';
}

/** Types a `pattern` can apply to (a free-text value). */
export function usesPattern(type: AttributeType): boolean {
    return type === 'string' || type === 'multiline' || type === 'identifier' || type === 'autocomplete';
}

/** The key whose flat copy the Case analytics roll-up sums (design §5.4). A Case's money is NOT a Findings
 *  field any more — it is the typed impact (`PUT /objects/{id}/impact`, WS-10). */
export const ANALYTICS_KEYS: readonly string[] = ['recordsAffected'];

let seq = 0;
const nextUid = (prefix: string): string => `${prefix}${++seq}`;

export function newChoice(label = ''): ChoiceDraft {
    return { uid: nextUid('c'), label, value: null };
}

export function newField(label = ''): FieldDraft {
    return {
        uid: nextUid('f'),
        key: null,
        label,
        type: 'string',
        tier: 'required',
        required: false,
        help: '',
        placeholder: '',
        pattern: '',
        min: null,
        max: null,
        choices: [],
        showWhen: null,
    };
}

/** `Root cause category` → `rootCauseCategory`; a leading digit gets a `field` prefix; a taken key a suffix. */
export function labelToKey(label: string, taken: ReadonlySet<string>): string {
    const words = label
        .normalize('NFKD')
        .replace(/[^A-Za-z0-9]+/g, ' ')
        .trim()
        .split(/\s+/)
        .filter(Boolean)
        .map((w) => w.toLowerCase());
    let base = words.map((w, i) => (i === 0 ? w : w.charAt(0).toUpperCase() + w.slice(1))).join('');
    if (!base) base = 'field';
    else if (/^[0-9]/.test(base)) base = 'field' + base.charAt(0).toUpperCase() + base.slice(1);
    let key = base;
    for (let n = 2; taken.has(key); n++) key = `${base}${n}`;
    return key;
}

/** `Card not present` → `CARD_NOT_PRESENT` (the ladder's own `FALSE_POSITIVE` style); a taken value gets `_2`. */
export function labelToOptionValue(label: string, taken: ReadonlySet<string>): string {
    const base =
        label
            .normalize('NFKD')
            .replace(/[^A-Za-z0-9]+/g, '_')
            .replace(/^_+|_+$/g, '')
            .toUpperCase() || 'CHOICE';
    let value = base;
    for (let n = 2; taken.has(value); n++) value = `${base}_${n}`;
    return value;
}

// ── wire ↔ draft ─────────────────────────────────────────────────────────────────

/** A served (effective) spec as editable drafts. Every loaded key and choice value is frozen. */
export function fromWire(spec: FindingsSpecDef | null): FieldDraft[] {
    const fields = (spec?.sections ?? []).map((s) => sectionToDraft(s));
    const byKey = new Map(fields.map((f) => [f.key!, f]));
    (spec?.sections ?? []).forEach((s, i) => {
        const d = s.dependsOn;
        if (!d?.key) return;
        const target = byKey.get(d.key);
        if (!target) return;
        const negated = 'notEquals' in d && d.notEquals !== undefined;
        const raw = String((negated ? d.notEquals : d.equals) ?? '');
        const choice = usesChoices(target.type) ? target.choices.find((c) => c.value === raw) : undefined;
        fields[i].showWhen = { targetUid: target.uid, negated, value: choice ? choice.uid : raw };
    });
    return fields;
}

function sectionToDraft(s: FindingsSection): FieldDraft {
    const f = newField(s.label || s.key);
    f.key = s.key;
    f.type = s.type as AttributeType;
    f.tier = s.tier as AttributeTier;
    f.required = s.required === true;
    f.help = s.help ?? '';
    f.placeholder = s.placeholder ?? '';
    f.pattern = s.pattern ?? '';
    f.min = typeof s.min === 'number' ? s.min : null;
    f.max = typeof s.max === 'number' ? s.max : null;
    f.choices = (s.options ?? []).map((o) => ({ uid: nextUid('c'), label: o.label || o.value, value: o.value }));
    if (s.default !== undefined) f.defaultValue = s.default;
    return f;
}

/** Each field's key: the frozen one, else derived from its label without colliding. */
function resolveKeys(fields: FieldDraft[]): Map<string, string> {
    const taken = new Set(fields.filter((f) => f.key).map((f) => f.key!));
    const out = new Map<string, string>();
    for (const f of fields) {
        if (f.key) {
            out.set(f.uid, f.key);
            continue;
        }
        const key = labelToKey(f.label, taken);
        taken.add(key);
        out.set(f.uid, key);
    }
    return out;
}

/** Each choice's stored value: the frozen one, else derived from its label without colliding. */
function resolveChoiceValues(field: FieldDraft): Map<string, string> {
    const taken = new Set(field.choices.filter((c) => c.value).map((c) => c.value!));
    const out = new Map<string, string>();
    for (const c of field.choices) {
        if (c.value) {
            out.set(c.uid, c.value);
            continue;
        }
        const value = labelToOptionValue(c.label, taken);
        taken.add(value);
        out.set(c.uid, value);
    }
    return out;
}

/** The key a field will be saved under — shown read-only under "Technical details". */
export function keyOf(fields: FieldDraft[], uid: string): string {
    return resolveKeys(fields).get(uid) ?? '';
}

/** The stored values a field's choices will be saved under, in order. */
export function choiceValuesOf(field: FieldDraft): string[] {
    const values = resolveChoiceValues(field);
    return field.choices.map((c) => values.get(c.uid) ?? '');
}

/** The `findings-spec` component body — `{name, objectType, sections}`, the `FindingsSpec.toMap` shape. */
export function toWire(objectType: string, fields: FieldDraft[]): Record<string, unknown> {
    const type = objectType.toLowerCase();
    return { name: type, objectType: type, sections: toSections(fields) };
}

/** The drafts as served sections (what `GET /findings/{type}` would return after a save). */
export function toSections(fields: FieldDraft[]): FindingsSection[] {
    const keys = resolveKeys(fields);
    const byUid = new Map(fields.map((f) => [f.uid, f]));
    return fields.map((f) => {
        const s: FindingsSection = {
            key: keys.get(f.uid)!,
            label: f.label.trim(),
            type: f.type,
            tier: f.tier,
            required: f.required,
        };
        if (f.defaultValue !== undefined) s.default = f.defaultValue;
        if (usesChoices(f.type) && f.choices.length) {
            const values = resolveChoiceValues(f);
            s.options = f.choices.map((c) => ({ value: values.get(c.uid)!, label: c.label.trim() }));
        }
        if (usesPattern(f.type) && f.pattern.trim()) s.pattern = f.pattern.trim();
        if (f.type === 'number' && f.min !== null) s.min = f.min;
        if (f.type === 'number' && f.max !== null) s.max = f.max;
        const target = f.showWhen ? byUid.get(f.showWhen.targetUid) : undefined;
        if (f.showWhen && target) {
            const value = showWhenWireValue(target, f.showWhen.value);
            s.dependsOn = f.showWhen.negated
                ? { key: keys.get(target.uid)!, notEquals: value }
                : { key: keys.get(target.uid)!, equals: value };
        }
        if (f.help.trim()) s.help = f.help.trim();
        if (f.placeholder.trim()) s.placeholder = f.placeholder.trim();
        return s;
    });
}

/** The wire `equals`/`notEquals`. A Yes/No target compares as a real boolean — the renderer uses `===`. */
function showWhenWireValue(target: FieldDraft, value: string): string | boolean {
    if (target.type === 'boolean') return value === 'true';
    if (!usesChoices(target.type)) return value;
    return resolveChoiceValues(target).get(value) ?? value;
}

// ── rules ────────────────────────────────────────────────────────────────────────

/**
 * The fields a "Show only when" may point at: those above `uid` (a form reads top to bottom), and never a
 * Number or List of values — the renderer compares with `===`, so a number target would need a numeric
 * value the server then compares as text (`5` vs `5.0`), and a list has no single value to compare.
 */
export function fieldsAbove(fields: FieldDraft[], uid: string): FieldDraft[] {
    const i = fields.findIndex((f) => f.uid === uid);
    return i < 0 ? [] : fields.slice(0, i).filter((f) => f.type !== 'number' && f.type !== 'list');
}

const quote = (s: string): string => `“${s.trim()}”`;

/**
 * The client-side mirror of `FindingsSpec.fromMap`, one rule for one rule, phrased against labels. Save
 * stays disabled while this is non-empty; a server 422 that gets past it is still shown verbatim.
 */
export function validateDraft(fields: FieldDraft[]): DraftProblem[] {
    const problems: DraftProblem[] = [];
    const add = (f: FieldDraft | null, message: string): void => {
        problems.push({ fieldUid: f?.uid ?? null, message });
    };
    if (!fields.length) add(null, 'Add at least one Findings field.');

    const seen = new Set<string>();
    fields.forEach((f, i) => {
        const name = f.label.trim();
        if (!name) {
            add(f, 'A Findings field has no name.');
        } else if (seen.has(name.toLowerCase())) {
            add(f, `Two fields are called ${quote(name)}.`);
        }
        seen.add(name.toLowerCase());
        const label = quote(name || 'Untitled');

        if (usesChoices(f.type)) {
            if (f.type === 'select' && !f.choices.length) add(f, `${label} needs at least one choice.`);
            const names = new Set<string>();
            for (const c of f.choices) {
                const cl = c.label.trim();
                if (!cl) add(f, `${label} has a choice with no name.`);
                else if (names.has(cl.toLowerCase())) add(f, `${label} has two choices called ${quote(cl)}.`);
                names.add(cl.toLowerCase());
            }
        }
        if (f.type === 'number' && f.min !== null && f.max !== null && f.min > f.max) {
            add(f, `${label}: the smallest allowed is larger than the largest.`);
        }
        if (usesPattern(f.type) && f.pattern.trim()) {
            try {
                new RegExp(f.pattern.trim());
            } catch {
                add(f, `${label}: the pattern is not valid.`);
            }
        }
        if (f.showWhen) {
            const at = fields.findIndex((t) => t.uid === f.showWhen!.targetUid);
            if (at < 0) add(f, `${label}: “Show only when” points at a field that was removed.`);
            else if (at >= i) add(f, `${label}: “Show only when” must point at a field above it.`);
            else if (!f.showWhen.value.trim()) add(f, `${label}: pick a value for “Show only when”.`);
            else if (
                usesChoices(fields[at].type) &&
                fields[at].type === 'select' &&
                !fields[at].choices.some((c) => c.uid === f.showWhen!.value)
            ) {
                add(f, `${label}: “Show only when” points at a choice that was removed.`);
            }
        }
    });
    return problems;
}
