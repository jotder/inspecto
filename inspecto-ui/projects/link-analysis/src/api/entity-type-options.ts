import type { PickerOption } from '@inspecto/core/components/option-picker.component';
import { sampleFits, shapeSpecificity } from '@inspecto/core/graph/value-shape';
import { type RecordRoleHints, recordPhrase } from './column-roles';

/**
 * The "what does this column hold" choices (ENTITY-TYPE-CATALOG-1 P0): the Space's Entity Types grouped by their neutral
 * family, each with its definition and a made-up example, so a reader who does not know a domain word ("MSISDN") picks by
 * family and example. Shared by the Data page and Add data; the families and their order are the server's
 * (`GET /inv/entity-types` → `families`).
 *
 * P2: with the Dataset's record type the types such a record usually holds come first, under *Likely in a <record type>*;
 * with the column's sampled values, a type whose value shape every sampled value fits is marked "Shape matches" (first in
 * its group, and in a *Shape matches* group of its own when no record type is known). Narrowing only orders and groups:
 * every type stays offered, and an id means the same Entity Type in every Dataset.
 */

/** One family of the closed set the server serves, in picker order. */
export interface EntityFamily {
    id: string;
    label: string;
    definition: string;
}

/** What the picker reads of an Entity Type. */
export interface EntityTypeGuide {
    id: string;
    label: string;
    family?: string | null;
    definition?: string | null;
    example?: string | null;
    /** The value shape, from the closed vocabulary of `inspecto/graph/value-shape.ts` (absent = free). */
    shape?: string | null;
}

/** What the picker reads of a record type (`GET /inv/entity-types` → `recordTypes`). */
export interface RecordTypeGuide {
    id: string;
    label: string;
    definition?: string | null;
    /** The Entity Type ids such a record usually holds, most telling first. */
    likelyTypes: string[];
    /** Its usual Time, Measure and Category columns and its parties (ENTITY-TYPE-RECORD-ROLES-1); absent from an older server. */
    roleHints?: RecordRoleHints;
}

/** What narrows one column's choices: the Dataset's record type and the column's sampled values. */
export interface HoldsContext {
    recordType?: RecordTypeGuide | null;
    sample?: readonly (string | null | undefined)[] | null;
    /**
     * The types whose shape the column's values fit, read on the server over an unmasked sample when the screen has none
     * of its own (the Data page: `GET /inv/datasets/{id}/shapes`, ENTITY-TYPE-RECORD-ROLES-1); id → whether every value
     * fits strongly. Ignored when `sample` is given.
     */
    fits?: ReadonlyMap<string, { strong: boolean }> | null;
}

/** The group of the types whose shape the sample fits, when the Dataset has no record type. */
export const SHAPE_MATCHES = 'Shape matches';

/** The group heading of a record type's likely types: "Likely in a call record", "Likely in an SMS record". */
export function likelyGroup(recordType: RecordTypeGuide): string {
    return `Likely in ${recordPhrase(recordType.label)}`;
}

/** The group of a type whose family is unstated or not one the server listed. */
export const OTHER_FAMILY = 'Other';

/** The one line under a choice: its definition, then `e.g. <example>`; undefined when neither is stated. */
export function entityTypeHint(t: EntityTypeGuide): string | undefined {
    const parts = [t.definition?.trim(), t.example?.trim() ? `e.g. ${t.example.trim()}` : ''].filter((p) => !!p);
    return parts.length ? parts.join(' ') : undefined;
}

/**
 * The types as picker options, grouped by family in the families' order (the types' own order within a family), an
 * unknown or unstated family last under {@link OTHER_FAMILY}. With no families at all (an older server), no family groups.
 * `context` puts the record type's likely types (or, without one, the shape-matching types) first in a group of their own.
 */
export function entityTypeOptions(
    types: readonly EntityTypeGuide[],
    families: readonly EntityFamily[],
    context: HoldsContext = {},
): PickerOption[] {
    const rank = new Map(families.map((f, i) => [f.id, i] as const));
    const label = new Map(families.map((f) => [f.id, f.label] as const));
    const of = (t: EntityTypeGuide): number => (t.family && rank.has(t.family) ? rank.get(t.family)! : families.length);
    const sample = context.sample ?? [];
    const served = context.fits;
    const fitOf = (t: EntityTypeGuide): { all: boolean; strong: boolean } | null =>
        sample.length > 0
            ? sampleFits(t.shape, sample)
            : served
              ? { all: served.has(t.id), strong: !!served.get(t.id)?.strong }
              : null;
    const matches = (t: EntityTypeGuide): boolean => !!fitOf(t)?.all;
    // a strong fit (every value fits the narrow reading) ranks before a weak one: 15 digits are a card identity before a phone number
    const rankFit = (t: EntityTypeGuide): number => (fitOf(t)?.strong ? 0 : matches(t) ? 1 : 2);
    const option = (t: EntityTypeGuide, group: string | undefined): PickerOption => {
        const hint = [matches(t) ? 'Shape matches.' : '', entityTypeHint(t) ?? ''].filter((p) => !!p).join(' ');
        return { value: t.id, label: t.label || t.id, ...(group ? { group } : {}), ...(hint ? { hint } : {}) };
    };

    // the lead group: the record type's likely types in its order, else the types the sample's shape fits
    const byId = new Map(types.map((t) => [t.id, t] as const));
    const rt = context.recordType;
    const lead = rt
        ? rt.likelyTypes.map((id) => byId.get(id)).filter((t): t is EntityTypeGuide => !!t)
        : types.filter(matches);
    const leadGroup = rt ? likelyGroup(rt) : SHAPE_MATCHES;
    // equal fits: the narrower shape first, as Add data proposes it (ValueShapes.specificity)
    const narrow = (t: EntityTypeGuide): number => (matches(t) ? -shapeSpecificity(t.shape) : 0);
    const ordered = lead
        .map((t, i) => ({ t, i, m: rankFit(t), n: narrow(t) }))
        .sort((a, b) => a.m - b.m || a.n - b.n || a.i - b.i);
    const led = new Set(lead.map((t) => t.id));

    const rest = types
        .filter((t) => !led.has(t.id))
        .map((t, i) => ({ t, i, r: of(t), m: rankFit(t), n: narrow(t) }))
        .sort((a, b) => a.r - b.r || a.m - b.m || a.n - b.n || a.i - b.i)
        .map(({ t }) => option(t, families.length ? (t.family && label.get(t.family)) || OTHER_FAMILY : undefined));
    return [...ordered.map(({ t }) => option(t, leadGroup)), ...rest];
}
