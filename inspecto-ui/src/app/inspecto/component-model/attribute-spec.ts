import type { ConfigFinding } from './component-kind';

/**
 * The attribute registry (Wave 0, W2 — see docs/superpower/frontend-review-and-completion-plan.md).
 *
 * Every Component Type declares its config attributes as {@link AttributeSpec}s, classified into the
 * three disclosure tiers the product mandates: **required** (always visible, must be filled),
 * **optional** (visible but collapsed), **advanced** (hidden behind the advanced toggle). One shared
 * renderer (`<inspecto-schema-form>`) generates every form from these specs, so the per-pane
 * attribute audit (review step R2) fixes the spec once and every consumer inherits it.
 *
 * Framework-free — validation returns {@link ConfigFinding}s (never throws), like kind validators.
 */

/**
 * Disclosure tiers, as data — the union below is DERIVED from this array so the vocabulary is
 * enumerable at run time and can be pinned by `attribute-spec.contract.json`.
 */
export const ATTRIBUTE_TIERS = ['required', 'optional', 'advanced'] as const;

export type AttributeTier = (typeof ATTRIBUTE_TIERS)[number];

/**
 * Renderer-supported control types, as data (see {@link ATTRIBUTE_TIERS}).
 *
 * - `identifier` — machine name: letters/digits/_-, no spaces.
 * - `autocomplete` — free text + suggestions (suggestions assist, they never constrain the value);
 *   the renderer host supplies the suggestion source via its `optionLoaders` input.
 * - `list` — `string[]` edited as removable chips, for config keys the engine reads as a list
 *   (e.g. `csv_settings.include_regex`). An empty list counts as blank, so a `required` list must
 *   have at least one entry.
 *
 * ⚠ Adding a member here also needs `FindingsSpec.TYPES` (backend) widened, or the server 422s a
 * spec this renderer can actually draw — which is exactly what `attribute-spec.contract.json` and
 * its two tests now catch instead of leaving it to review.
 */
export const ATTRIBUTE_TYPES = [
    'string',
    'identifier',
    'number',
    'boolean',
    'select',
    'autocomplete',
    'multiline',
    'list',
] as const;

export type AttributeType = (typeof ATTRIBUTE_TYPES)[number];

export interface AttributeOption {
    value: string;
    label: string;
}

export interface AttributeSpec {
    key: string;
    label: string;
    type: AttributeType;
    /** Disclosure/visibility bucket: required = always visible, optional = collapsed, advanced = behind the gear. */
    tier: AttributeTier;
    /**
     * Whether the value must be filled. Defaults to `tier === 'required'`. Set explicitly to decouple
     * validation from visibility — e.g. an always-visible field that is optional (`tier: 'required',
     * required: false`), as used by option sheets where every knob shows but none is mandatory.
     */
    required?: boolean;
    default?: unknown;
    /** Choices for `type: 'select'`. */
    options?: AttributeOption[];
    /** Regex the (string) value must fully match. */
    pattern?: string;
    /** Bounds for `type: 'number'`. */
    min?: number;
    max?: number;
    /** Show this attribute only while another attribute holds (`equals`) or doesn't hold (`notEquals`)
     *  a given value — exactly one of the two; `equals` is the common case (a kind-specific param). */
    dependsOn?: { key: string; equals: unknown } | { key: string; notEquals: unknown };
    /** One-line helper text shown under the field. */
    help?: string;
    placeholder?: string;
    /** Section heading this attribute sits under, WITHIN its tier — specs sharing a `group` render
     *  beneath one heading in declaration order; ungrouped specs render bare, as before. Grouping never
     *  crosses a tier: disclosure is the outer structure and a heading only organises what is shown. */
    group?: string;
    /** Mask the value on input (renders a password field). Presentation only — masking what the API
     *  reads BACK is the server's job at the response boundary, never this flag's. */
    secret?: boolean;
}

/**
 * Every field of {@link AttributeSpec}, exhaustively — `Record<keyof Required<…>, true>` makes the
 * compiler reject this object the moment a field is added to the interface without being listed.
 * That is the point: a new field is a **decision** about whether a `findings-spec` may author it
 * (`FindingsSpec.SECTION_KEYS`) or whether it stays frontend-only, and the contract file records
 * which. Silently adding one is how the two sides drift.
 */
const ATTRIBUTE_KEY_SET: Record<keyof Required<AttributeSpec>, true> = {
    key: true,
    label: true,
    type: true,
    tier: true,
    required: true,
    default: true,
    options: true,
    pattern: true,
    min: true,
    max: true,
    dependsOn: true,
    help: true,
    placeholder: true,
    group: true,
    secret: true,
};

/** {@link ATTRIBUTE_KEY_SET} as a list, for the contract test. */
export const ATTRIBUTE_KEYS: readonly string[] = Object.keys(ATTRIBUTE_KEY_SET);

const IDENTIFIER_RE = /^[A-Za-z][A-Za-z0-9_-]*$/;

/** Whether a spec's value must be filled — explicit `required`, else derived from the `required` tier. */
export function isRequired(spec: AttributeSpec): boolean {
    return spec.required ?? spec.tier === 'required';
}

/** The declared defaults, for initialising a new instance's config. */
export function defaultsFor(specs: AttributeSpec[]): Record<string, unknown> {
    const out: Record<string, unknown> = {};
    for (const s of specs) {
        if (s.default !== undefined) out[s.key] = s.default;
    }
    return out;
}

/** Whether a `dependsOn` clause matches `value` — the one place `equals`/`notEquals` is interpreted
 *  (shared by `visibleSpecs` here and `<inspecto-schema-form>`'s live show/hide + enable/disable). */
export function dependsOnMatches(
    dependsOn: NonNullable<AttributeSpec['dependsOn']>,
    value: Record<string, unknown>,
): boolean {
    const v = value[dependsOn.key];
    return 'notEquals' in dependsOn ? v !== dependsOn.notEquals : v === dependsOn.equals;
}

/** The specs visible for `value`, honouring `dependsOn` (hidden attributes are also not validated). */
export function visibleSpecs(specs: AttributeSpec[], value: Record<string, unknown>): AttributeSpec[] {
    return specs.filter((s) => !s.dependsOn || dependsOnMatches(s.dependsOn, value));
}

/** Group specs by tier, preserving declaration order. */
export function byTier(specs: AttributeSpec[]): Record<AttributeTier, AttributeSpec[]> {
    const out: Record<AttributeTier, AttributeSpec[]> = { required: [], optional: [], advanced: [] };
    for (const s of specs) out[s.tier].push(s);
    return out;
}

// An empty list is blank, so `required` on a `list` means "at least one entry" (matching Angular's
// own Validators.required, which treats [] as empty — the two must agree or the form and the
// framework-free validator disagree on the same value).
const isBlank = (v: unknown): boolean =>
    v === undefined || v === null || v === '' || (Array.isArray(v) && v.length === 0);

/**
 * The first `list` entry that breaks the spec's `pattern`, phrased for display — or `null`.
 *
 * On a `list` the value IS the array, never one string, so `pattern` can only mean "every item must
 * match". That is also why the whole-value `pattern` check at the end of {@link validateAttributes} is
 * guarded on `typeof v === 'string'`: unguarded it would test the array's `toString()` (`"a,b"`) and
 * pass or fail for reasons no author could predict from the declaration.
 */
export function listPatternViolation(spec: AttributeSpec, items: readonly string[]): string | null {
    if (!spec.pattern) return null;
    const re = new RegExp(`^(?:${spec.pattern})$`);
    const bad = items.find((e) => !re.test(e));
    return bad === undefined ? null : `${spec.label}: "${bad}" has an invalid format`;
}

/** Validate `value` against the visible specs — the spec-driven half of a kind's `config.validate`. */
export function validateAttributes(specs: AttributeSpec[], value: Record<string, unknown>): ConfigFinding[] {
    const findings: ConfigFinding[] = [];
    for (const s of visibleSpecs(specs, value)) {
        const v = value[s.key];
        if (isBlank(v)) {
            if (isRequired(s)) {
                findings.push({ severity: 'error', path: s.key, message: `${s.label} is required` });
            }
            continue;
        }
        switch (s.type) {
            case 'number': {
                const n = typeof v === 'number' ? v : Number(v);
                if (Number.isNaN(n)) {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be a number` });
                    break;
                }
                if (s.min !== undefined && n < s.min) {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be ≥ ${s.min}` });
                }
                if (s.max !== undefined && n > s.max) {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be ≤ ${s.max}` });
                }
                break;
            }
            case 'boolean':
                if (typeof v !== 'boolean') {
                    findings.push({ severity: 'error', path: s.key, message: `${s.label} must be true or false` });
                }
                break;
            case 'select':
                if (!(s.options ?? []).some((o) => o.value === v)) {
                    findings.push({
                        severity: 'error',
                        path: s.key,
                        message: `${s.label} must be one of the listed options`,
                    });
                }
                break;
            case 'list': {
                if (!Array.isArray(v) || v.some((e) => typeof e !== 'string')) {
                    findings.push({
                        severity: 'error',
                        path: s.key,
                        message: `${s.label} must be a list of text values`,
                    });
                    break;
                }
                const violation = listPatternViolation(s, v as string[]);
                if (violation) findings.push({ severity: 'error', path: s.key, message: violation });
                break;
            }
            case 'identifier':
                if (typeof v !== 'string' || !IDENTIFIER_RE.test(v)) {
                    findings.push({
                        severity: 'error',
                        path: s.key,
                        message: `${s.label} must start with a letter and use only letters, digits, _ or -`,
                    });
                }
                break;
            default: // string / multiline
                break;
        }
        if (s.pattern && typeof v === 'string' && !new RegExp(`^(?:${s.pattern})$`).test(v)) {
            findings.push({ severity: 'error', path: s.key, message: `${s.label} has an invalid format` });
        }
    }
    return findings;
}

/** A ready-made `config.validate` for kinds whose config is fully described by their specs. */
export function attributeValidator(specs: AttributeSpec[]): (config: unknown) => ConfigFinding[] {
    return (config: unknown) => validateAttributes(specs, (config ?? {}) as Record<string, unknown>);
}

/**
 * One offerable **whole-value** token for a field — a placeholder the platform substitutes at run time
 * rather than a literal the author types (reference adopter: the Jobs runtime Expressions,
 * job-parameter-contract §4.3).
 *
 * <p>⚠ Deliberately domain-agnostic: the renderer knows only that a token replaces the field's ENTIRE
 * value — not what a token means, where the vocabulary comes from, or which of them suit a field. The host
 * filters and supplies them per attribute key.
 */
export interface AttributeToken {
    /** The typeable surface — this becomes the field's whole value, never an insertion at the cursor. */
    token: string;
    description?: string;
    /**
     * What it resolves to, for the picker's preview line. **Supplied by whoever owns the vocabulary** —
     * never computed here, because a second implementation of a substitution is a second answer (§4.3).
     */
    preview?: string;
}
