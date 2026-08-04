import { AttributeSpec, COLLECTOR_ACQUISITION_ATTRIBUTES, OUTPUT_ATTRIBUTES } from 'app/inspecto/component-model';
import { ParsingFrontend, parsingAttributesFor } from './parsing-attributes';
import { OnboardingStageId } from './onboarding-state.service';

/**
 * ONE lookup: the `AttributeSpec[]` table a guided stage renders — the Onboarding analogue of the
 * Pipelines editor's `nodeAttributesFor(type)` (W4a/U-B). For the node types both features author,
 * this resolves to the SAME shared component-model tables (collection/`acquisition` →
 * `COLLECTOR_ACQUISITION_ATTRIBUTES`, publish/`sink.*` → `OUTPUT_ATTRIBUTES`) — pinned by spec on
 * both sides, so the two surfaces cannot drift the way the pre-U-D palettes did.
 *
 * <p>Collection deliberately uses the acquisition **subset** (collector-config unification,
 * 2026-08-04): the `duplicate__*` keys are owned by the `transform.dedup.fingerprint` node, so
 * Onboarding no longer offers a dedup surface — the engine default (`duplicate.mode: path`)
 * applies until the operator configures that node in the Pipelines editor; stored `duplicate:`
 * keys survive collection saves untouched.
 *
 * <p>`undefined` = the stage is a bespoke nested-list pane (`schema`/`keys` field rows, `enrichment`
 * references) that `FieldSpec` cannot express (plan §7) — those render their own editors, exactly as
 * an unspecced node type falls back to the free-form editor in the Pipelines dialog.
 */
export function stageAttributesFor(
    id: OnboardingStageId,
    ctx?: { frontend?: ParsingFrontend },
): AttributeSpec[] | undefined {
    switch (id) {
        case 'collection':
            return COLLECTOR_ACQUISITION_ATTRIBUTES;
        case 'parsing':
            return parsingAttributesFor(ctx?.frontend ?? 'delimited');
        case 'publish':
            return OUTPUT_ATTRIBUTES;
        default:
            return undefined;
    }
}
