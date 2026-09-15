import { type AttributeSpec } from 'app/inspecto/component-model';
import NODE_ATTRIBUTE_CONTRACT from 'app/inspecto/contracts/node-attributes.contract.json';

/**
 * Per-node-type config attribute schemas for the generic {@link NodeConfigDialog} — the fallback the
 * drawer uses before `GET /pipelines/node-types` has answered, and what the offline build runs on.
 *
 * <p>**This is the served contract, not a copy of it** (`NODE-TYPE-MIRRORS-1`, 2026-09-15). Until then this
 * file held a hand-typed TypeScript mirror of the Java `NodeAttributes` table, kept equal to
 * `node-attributes.contract.json` by `node-attributes.spec.ts` — a fallback that had to be hand-synced,
 * which is a second source, not a fallback. The Java table is the source; `NodeAttributesContractTest`
 * regenerates the JSON from it (`-Dnode.attributes.write=true`); this module imports that JSON.
 *
 * <p>⚠ **A key here IS the config key** — `AttributeSpec.key` is written verbatim into `node.config`
 * (`node-config.dialog`, no case-conversion layer anywhere in the app), so it must equal the string the
 * engine reads. That contract is enforced on the Java side (`NodeConfigNameContractTest` drives every key
 * through the editor's real save path), which is one more reason the vocabulary is authored there once.
 *
 * <p>A node type absent from the contract has **no** schema and falls back to the dialog's free-form
 * key/value editor — and every type keeps that editor as a collapsed "Additional config" escape hatch.
 * The design notes that used to live in this file (the two filtering moments of `transform.filter`, why
 * `acquisition` reuses the shared collector table, why a connector's own options are not node config)
 * now live with the source: `NodeAttributes.java` and `okf/frontend/features/pipeline-editor.md`.
 */
const NODE_ATTRIBUTES = NODE_ATTRIBUTE_CONTRACT as Record<string, AttributeSpec[]>;

/** The declared attribute schema for a node type, or `undefined` when it has none (free-form only). */
export function nodeAttributesFor(type: string | undefined): AttributeSpec[] | undefined {
    return type ? NODE_ATTRIBUTES[type] : undefined;
}

/** Every node type the contract speccs. */
export function speccedNodeTypes(): string[] {
    return Object.keys(NODE_ATTRIBUTES).filter((k) => !k.startsWith('_'));
}
