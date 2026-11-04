import type { PickerOption } from '@inspecto/core/components/option-picker.component';

/**
 * The "what does this column hold" choices (ENTITY-TYPE-CATALOG-1 P0): the Space's Entity Types grouped by their neutral
 * family, each with its definition and a made-up example, so a reader who does not know a domain word ("MSISDN") picks by
 * family and example. Shared by the Data page and Add data; the families and their order are the server's
 * (`GET /inv/entity-types` → `families`).
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
 * unknown or unstated family last under {@link OTHER_FAMILY}. With no families at all (an older server), no groups.
 */
export function entityTypeOptions(
    types: readonly EntityTypeGuide[],
    families: readonly EntityFamily[],
): PickerOption[] {
    const rank = new Map(families.map((f, i) => [f.id, i] as const));
    const label = new Map(families.map((f) => [f.id, f.label] as const));
    const of = (t: EntityTypeGuide): number => (t.family && rank.has(t.family) ? rank.get(t.family)! : families.length);
    return types
        .map((t, i) => ({ t, i, r: of(t) }))
        .sort((a, b) => a.r - b.r || a.i - b.i)
        .map(({ t }) => ({
            value: t.id,
            label: t.label || t.id,
            ...(families.length ? { group: (t.family && label.get(t.family)) || OTHER_FAMILY } : {}),
            ...(entityTypeHint(t) ? { hint: entityTypeHint(t) } : {}),
        }));
}
