import { AuthoredNode } from 'app/inspecto/api';
import { AttributeSpec, KEY_SEP, flattenBlock, mergeBlock, nestKeys } from 'app/inspecto/component-model';

/**
 * The split/build halves of editing one {@link AuthoredNode}'s config against an attribute schema —
 * extracted from `node-config.dialog.ts` so the definition drawer (definition-surface plan P1) and the
 * dialog run the SAME subtle logic instead of a copy. Pure functions, no Angular.
 */

/** `use: connection/<name>` prefix — the one place the binding's shape is spelled. */
export const CONNECTION_REF = 'connection/';

/**
 * How a non-string config value is rendered into a free-form row's text box. ⚠ ONE function, used both
 * to seed the row and to recognise an untouched one on the way back out — spelled twice, the two would
 * drift and every seeded row would read as edited, quietly reinstating the stringify-on-save data loss.
 */
function stringify(value: unknown): string {
    return typeof value === 'string' ? value : JSON.stringify(value);
}

export interface NodeConfigSplit {
    /** Schema-form seed: the node's config entries whose key the schema knows (flat `__` spelling). */
    schemaInitial: Record<string, unknown>;
    /** Keys outside the schema, as literal free-form rows (values stringified when not strings). */
    extraRows: { key: string; value: string }[];
}

/**
 * Split the stored config: schema-known keys seed the schema-form; the rest become free-form rows.
 *
 * ⚠ Spec keys are FLAT (`__` = nesting) while the stored config is NESTED, so the split has to
 * compare FLATTENED keys. Comparing raw top-level keys meant a real `duplicate: {mode: …}` block
 * matched no spec, fell into the free-form editor as a JSON *string*, and then — free-form being
 * applied last on save — overwrote the schema form's own nested value. D4, load half.
 */
export function splitNodeConfig(node: AuthoredNode, specs: AttributeSpec[], isAcquisition: boolean): NodeConfigSplit {
    const schemaKeys = new Set(specs.map((s) => s.key));
    const schemaInitial: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(flattenBlock(node.config))) {
        if (schemaKeys.has(key)) schemaInitial[key] = value;
    }
    // An acquisition node's Connection lives on `use:`, never in cfg (a binding, not config —
    // D3-remainder, 2026-08-04), so it is seeded from there — the loop above can never find it.
    if (isAcquisition && (node.use ?? '').startsWith(CONNECTION_REF)) {
        schemaInitial['connection'] = node.use!.slice(CONNECTION_REF.length);
    }
    // A top-level key is schema-owned when a spec names it or names a leaf beneath it (`duplicate` is
    // owned by `duplicate__mode`). Owned roots are seeded above and must NOT also appear as free-form
    // rows; sub-keys the schema does not model survive via the merge in `buildConfiguredNode`.
    const ownsRoot = (root: string): boolean =>
        schemaKeys.has(root) || specs.some((s) => s.key.startsWith(root + KEY_SEP));
    const extraRows: { key: string; value: string }[] = [];
    for (const [key, value] of Object.entries(node.config ?? {})) {
        if (ownsRoot(key)) continue;
        // `connector` is DERIVED, never asked — a free-form row for it would be a second control
        // writing a field the save already computes, and free-form wins last.
        if (isAcquisition && key === 'connector') continue;
        extraRows.push({ key, value: stringify(value) });
    }
    return { schemaInitial, extraRows };
}

export interface BuildConfiguredNodeInput {
    /** The node being edited — identity (`id`/`type`) and the prior config to merge over. */
    node: AuthoredNode;
    specs: AttributeSpec[];
    /** The config surface's flat values (`configForm.value()`), or `null` when none rendered. */
    formValues: Record<string, unknown> | null;
    /** Literal free-form key/value rows — the escape hatch for keys outside the schema. */
    freeRows: { key: string; value: string }[];
    name?: string;
    description?: string;
    /** The raw `use` field value (ignored for acquisition, which derives it from `connection`). */
    use?: string;
    isAcquisition: boolean;
    /** The resolved collector connector — REQUIRED (non-null) when `isAcquisition`. */
    connector?: string | null;
}

/**
 * Assemble the edited node. Schema-driven values first (numbers coerced per spec), then free-form
 * rows for keys outside the schema.
 *
 * ⚠ Spec keys are FLAT — `__` means nesting (`AttributeSpec.key` convention, `flat-keys.ts`) — so
 * they MUST go through `nestKeys` before they reach `node.config`. The flat pipeline's `collector:`
 * block reads `duplicate`, `stability` and `post_action` as nested MAPS
 * (`PipelineConfigParser.java:450,459,518`), so a literal `duplicate__mode` key is read by nothing.
 * `nestKeys` also splits the LIST_KEYS (`include`/`exclude`) comma-string into a list, which is the
 * shape the seeds use and which `PipelineConfigParser.strList` prefers (it accepts either).
 */
export function buildConfiguredNode(input: BuildConfiguredNodeInput): AuthoredNode {
    const { node, specs, formValues, freeRows, isAcquisition } = input;
    const config: Record<string, unknown> = {};
    const prior = node.config ?? {};
    if (formValues) {
        const flat: Record<string, unknown> = {};
        for (const s of specs) {
            let val = formValues[s.key];
            if (s.type === 'number') val = val === '' || val == null ? null : Number(val);
            // A cleared list is blank, not `[]` — otherwise clearing every chip writes an empty
            // array the engine would read as "a list is configured" instead of dropping the key.
            if (s.type === 'list' && Array.isArray(val) && val.length === 0) val = null;
            if (val !== '' && val != null) flat[s.key] = val;
        }
        // Deep-merge each nested root over what the node already had, so sub-keys the schema does not
        // model survive a guided save — `duplicate.algorithm`, `stability.size_checks`/`ready_marker`/
        // `exclude_temp_patterns`, `post_action.tags`/`on_unsupported` are all real, engine-read keys
        // with no AttributeSpec (`PipelineConfigParser.java:449-470,516-527`). A root the form cleared
        // entirely is absent from `nestKeys` output and so is dropped, which keeps delete-on-clear
        // working at root granularity.
        const plain = (x: unknown): x is Record<string, unknown> =>
            x !== null && typeof x === 'object' && !Array.isArray(x);
        for (const [root, val] of Object.entries(nestKeys(flat))) {
            config[root] = plain(val) && plain(prior[root]) ? mergeBlock(prior[root], val) : val;
        }
    }
    // Free-form rows stay LITERAL — a hand-typed key means exactly what was typed (this is the escape
    // hatch for keys outside the schema), and it keeps overriding the schema as it always did.
    //
    // ⚠ …with one exception: a row the operator never TOUCHED restores the value verbatim. `splitNodeConfig`
    // seeds these rows by `JSON.stringify`ing whatever the config held, so a non-string value — an
    // unmodelled nested block, or `transform.route`'s `branches`, a list of MAPS that deliberately has no
    // spec — came back as a literal JSON string. Opening a route node and pressing Save with no edits at
    // all therefore replaced its routing with text, silently, in flat contradiction of the rule that a
    // node's config is the raw config-file section verbatim and unmodelled keys survive a save.
    //
    // The test is byte-equality against the seed, so it can only ever restore a value the operator left
    // exactly as loaded. Edit one character and the typed text wins, unchanged from before.
    for (const row of freeRows) {
        const key = row.key?.trim();
        if (!key) continue;
        const original = prior[key];
        const untouched = original !== undefined && typeof original !== 'string' && stringify(original) === row.value;
        config[key] = untouched ? original : row.value;
    }
    // An acquisition node's Connection is a binding: move it off cfg (where lower strips it) onto
    // `use: connection/<name>`, which is where the engine reads it from. Clearing it clears the binding.
    let use = input.use?.trim() || undefined;
    if (isAcquisition) {
        const picked = String(config['connection'] ?? '').trim();
        delete config['connection'];
        use = picked ? CONNECTION_REF + picked : undefined;
        // The derived connector is written LAST so it beats a stale value the file already had —
        // `CollectorConnectors.forConfig` dispatches on it and never checks it agrees with the
        // Connection it is handed, so the two must be decided in one place.
        config['connector'] = input.connector;
    }
    return {
        id: node.id,
        type: node.type,
        name: input.name?.trim() || undefined,
        description: input.description?.trim() || undefined,
        use,
        config: Object.keys(config).length ? config : undefined,
    };
}
