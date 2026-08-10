import { JobParameterDecl } from 'app/inspecto/api';
import { AttributeOption, AttributeSpec, AttributeTier, AttributeType } from 'app/inspecto/component-model';

/**
 * Maps a Job Type's declared {@link JobParameterDecl}s (from `GET /jobs/types/{id}`, R3) onto
 * {@link AttributeSpec}s so `<inspecto-schema-form>` renders the type's runtime parameters as a typed,
 * labelled, validated form (Workbench → Jobs, job-framework P3c). This is what makes the jobs form
 * descriptor-driven — a new Job Type (e.g. a Job Pack's) surfaces its contract with no UI change.
 *
 * <p><b>This function IS the generation contract</b> (job-parameter-contract §7.4): a deterministic
 * table, not a set of guesses about a parameter's name. Everything it emits is declared by the server;
 * the only inference left is humanising a label when the declaration doesn't carry one.
 */

/**
 * Format presets for the types whose accepted shape an author cannot guess from a bare text box.
 *
 * <p>The patterns restate what `ParameterResolver.matchesType` already refuses at fire time — the point
 * is to move that refusal to the keystroke, not to invent a second rule. A real date-picker widget is a
 * renderer extension, deliberately deferred until a consumer asks for one (§7.4).
 */
const TYPE_PRESETS: Record<string, { pattern: string; placeholder: string }> = {
    DATE: { pattern: '\\d{4}-\\d{2}-\\d{2}', placeholder: '2026-08-06' },
    INSTANT: { pattern: '\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z?', placeholder: '2026-08-06T00:00:00Z' },
    // Deliberately permissive, matching the engine's own: only delivery truly validates an address,
    // and a strict regex rejects valid ones (§7.2-A).
    EMAIL: { pattern: '[^@\\s]+@[^@\\s]+\\.[^@\\s]+', placeholder: 'ops@example.com' },
};

/**
 * The widget for a declaration. Choice and cardinality outrank the type: a declared `options` list is a
 * choice whatever it holds, and `multi` is a list of the type rather than one of it.
 *
 * <p>⚠ There is no `sql` name-sniff here any more — `ParamType.TEXT` replaced it (§7.1), and
 * `SqlTemplateJobType` declares `sql` as `TEXT`. A server that still declares a multiline field as
 * `STRING` now renders single-line, which is the honest reading of what it declared.
 */
function widgetFor(decl: JobParameterDecl): AttributeType {
    if ((decl.options ?? []).length) return 'select';
    if (decl.multi) return 'list';
    switch (decl.type) {
        case 'TEXT':
            return 'multiline';
        case 'INTEGER':
        case 'DECIMAL':
            return 'number';
        case 'BOOLEAN':
            return 'boolean';
        case 'DATASET_REF':
            // Suggestions assist, they never constrain — the server stays the gate on what exists.
            return 'autocomplete';
        default: // STRING | DATE | INSTANT | EMAIL
            return 'string';
    }
}

/** The declared disclosure tier; a server predating the contract falls back to `required`'s meaning. */
function tierOf(decl: JobParameterDecl): AttributeTier {
    switch (decl.tier) {
        case 'REQUIRED':
            return 'required';
        case 'OPTIONAL':
            return 'optional';
        case 'ADVANCED':
            return 'advanced';
        default:
            return decl.required ? 'required' : 'optional';
    }
}

/** Humanise a snake/kebab parameter name for the field label (`event_date` → `Event date`). */
function humanise(name: string): string {
    const words = name.replace(/[_-]+/g, ' ').trim();
    return words ? words.charAt(0).toUpperCase() + words.slice(1) : name;
}

/**
 * The literal default, in the shape the widget holds it.
 *
 * <p>⚠ A `multi` parameter's default is **CSV** on the wire, because that is how `ParameterResolver`
 * reads a list-valued param (§7.5). The chip editor holds a `string[]`, so it must be split here — bound
 * verbatim, a two-item default would render as one chip containing a comma.
 */
function defaultFor(decl: JobParameterDecl): unknown {
    if (!decl.default) return undefined;
    if (!decl.multi) return decl.default;
    const items = decl.default.split(',').map((s) => s.trim()).filter(Boolean);
    return items.length ? items : undefined;
}

/** One declared parameter → an {@link AttributeSpec}, per the §7.4 table. */
export function paramDeclToSpec(decl: JobParameterDecl): AttributeSpec {
    const preset = TYPE_PRESETS[decl.type];
    const options: AttributeOption[] = (decl.options ?? []).map((v) => ({ value: v, label: v }));
    const help = [decl.description, decl.deduce ? `Deduced as ${decl.deduce} when unset` : '']
        .filter(Boolean)
        .join(' · ');
    const pattern = decl.pattern || preset?.pattern;

    const spec: AttributeSpec = {
        key: decl.name,
        label: decl.label || humanise(decl.name),
        type: widgetFor(decl),
        tier: tierOf(decl),
        required: decl.required,
        default: defaultFor(decl),
        help: help || undefined,
        // An explicit hint wins; then the type's format example; then the deduce. The deduce is the LAST
        // resort because `help` already states it in words — spending the one hint slot on a repeat
        // would cost the author the format example, which nothing else tells them.
        placeholder: decl.placeholder || preset?.placeholder || decl.deduce || undefined,
    };
    if (options.length) spec.options = options;
    if (pattern) spec.pattern = pattern;
    if (decl.min != null) spec.min = decl.min;
    if (decl.max != null) spec.max = decl.max;
    if (decl.group) spec.group = decl.group;
    if (decl.secret) spec.secret = true;
    return spec;
}

export function paramDeclsToSpecs(decls: JobParameterDecl[]): AttributeSpec[] {
    return (decls ?? []).map(paramDeclToSpec);
}

/**
 * A stored parameter value in the shape its widget holds.
 *
 * <p>⚠ The two shapes differ for exactly one widget. A `list` holds `string[]`; the API stores the CSV
 * `ParameterResolver` reads. Re-opening a saved Job without this split shows one chip containing commas,
 * and saving it back nests the CSV inside itself — the value degrades a little on every edit.
 */
export function paramValueToForm(spec: AttributeSpec, stored: unknown): unknown {
    if (spec.type !== 'list' || Array.isArray(stored)) return stored;
    const items = String(stored ?? '').split(',').map((s) => s.trim()).filter(Boolean);
    return items.length ? items : null;
}

/** The inverse: a chip list becomes the CSV the resolver reads. */
export function paramValueToApi(value: unknown): unknown {
    return Array.isArray(value) ? value.join(',') : value;
}
