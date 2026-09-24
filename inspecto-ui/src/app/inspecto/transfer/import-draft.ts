import { BundleItem, BundleKind, MetadataBundle, TargetIndex, withDependencies } from './bundle';

/**
 * Bundle "Import as draft" (operator decisions 2026-09-25, `docs/superpower/bundle-load-as-draft-design.md` §7):
 * one bundle item handed to its editor as UNSAVED work. It is held in memory only (D1) — a reload or a
 * navigation away loses it — and it is saved through the editor's OWN route (D2), never `/bundle/import`.
 */
export interface ImportDraft {
    kind: BundleKind;
    id: string;
    /** The incoming config, verbatim from the bundle. */
    content: Record<string, unknown>;
    /** The Space the bundle was exported from (informational, for the banner). */
    sourceSpace: string | null;
    /** True when this Space already holds `kind/id` — the editor opens THAT item with this content as
     *  unsaved edits and shows the diff (D6). */
    targetExists: boolean;
    /** The broken references this draft would introduce, from the read-only `POST /bundle/preview` (D3) —
     *  ADVISORY. `null` = the check could not run (it is never reported as "no findings"). */
    integrity: string[] | null;
    /** `<kind>/<id>` of the prerequisites imported write-through BEFORE the draft opened (D4). */
    prerequisites: string[];
}

/**
 * The other bundle items the draft target needs that this Space does not hold yet — its dependency
 * closure WITHIN the bundle, minus the target itself and minus anything already here. These are what D4
 * imports write-through first. An existing prerequisite is left alone (never silently overwritten), and a
 * reference the bundle does not carry is the `requires` panel's business, not this list's.
 */
export function draftPrerequisites(bundle: MetadataBundle, target: TargetIndex, item: BundleItem): BundleItem[] {
    const { items } = withDependencies([item], bundle.items);
    return items.filter((i) => i !== item && !target.get(i.kind)?.has(i.id));
}

/**
 * Where a draft opens relative to the editor it was imported from: `here` when the editor already shows
 * that item (editing the same id), or is the create route and the id is new; otherwise the editor must
 * route to the right one. Pure, so the three hosts cannot disagree about it.
 */
export function draftPlacement(currentId: string | undefined, draft: ImportDraft): 'here' | 'elsewhere' {
    if (currentId) return currentId === draft.id ? 'here' : 'elsewhere';
    return draft.targetExists ? 'elsewhere' : 'here';
}
