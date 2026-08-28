import { describe, expect, it } from 'vitest';
import {
    byTier,
    COLLECTOR_ATTRIBUTES,
    isRequired,
    MARKER_DEDUP_ATTRIBUTES,
    OUTPUT_ATTRIBUTES,
    UNPACK_ATTRIBUTES,
} from 'app/inspecto/component-model';
import NODE_ATTRIBUTE_CONTRACT from 'app/inspecto/mock/node-attributes.contract.json';
import { nodeAttributesFor, speccedNodeTypes } from './node-attributes';

/**
 * W2/U-D reshaped this: the map is now keyed by the engine's own `BuiltinNodeType` strings, and a type
 * only gets a schema when the backend really reads those keys. The previous suite asserted schemas for
 * `collector.file`/`sink.file`/`sink.database` — types the backend has never had — over keys it never
 * reads, so it passed while the editor authored nodes `PipelineCompiler` silently dropped.
 */
describe('node-attributes', () => {
    /**
     * §3.1, the cross-language half of the contract. The server publishes this vocabulary on
     * `GET /pipelines/node-types` (`NodeAttributes.java`), and since then THIS table is the fallback, not
     * the source. Both sides compare to one committed artifact: `NodeAttributesContractTest` checks the
     * Java table against `node-attributes.contract.json`, and this checks the TS table against the same
     * file — so neither can drift without one of the two suites failing.
     *
     * ⚠ If this fails, decide WHICH side is wrong before touching anything. Regenerating the JSON from
     * Java (`-Dnode.attributes.write=true`) makes this test go green by moving the goalposts; that is
     * correct only when the Java table is the one that changed on purpose.
     *
     * Compared as parsed data rather than text: the JSON omits unset optional keys (matching the wire),
     * so `toEqual` against the spec objects would trip over `undefined`-vs-absent. Serializing the TS side
     * through `JSON.parse(JSON.stringify(...))` drops `undefined` exactly as the server omits it.
     */
    it('matches the vocabulary the server publishes', () => {
        const contract = NODE_ATTRIBUTE_CONTRACT as Record<string, unknown[]>;
        expect(speccedNodeTypes().sort()).toEqual(Object.keys(contract).sort());
        for (const type of Object.keys(contract)) {
            const local = JSON.parse(JSON.stringify(nodeAttributesFor(type)));
            expect(local, `${type} disagrees with the served contract`).toEqual(contract[type]);
        }
    });

    it('returns a tiered schema for a known node type', () => {
        const specs = nodeAttributesFor('sink.persistent');
        expect(specs).toBeDefined();
        const grouped = byTier(specs!);
        // `database` is the key `PipelineEditable.lower` HARD-requires on the primary sink
        // (NO_PERSISTENT_SINK) — required-tier visibility, but `required: false` because a
        // quarantine sink is a `sink.persistent` too and sets `dir`, never `database`.
        expect(grouped.required.map((s) => s.key)).toEqual(['database', 'format']);
        expect(isRequired(grouped.required[0])).toBe(false);
        // W4a moved compression advanced → optional when the table unified with Onboarding's
        // (which always showed it) — a deliberate tier choice, not a regression.
        expect(grouped.optional.map((s) => s.key)).toEqual(['compression']);
    });

    /** W4a: the sink's output block is the SAME shared table Onboarding's publish stage renders —
     *  by element identity, so a forked copy fails even if the keys look the same. The persistent
     *  sink prepends its own destination (`database`) to that shared block. */
    it('authors the output block with the shared OUTPUT_ATTRIBUTES table', () => {
        const specs = nodeAttributesFor('sink.persistent')!;
        expect(specs.slice(1, 1 + OUTPUT_ATTRIBUTES.length)).toEqual(OUTPUT_ATTRIBUTES);
        for (const [i, shared] of OUTPUT_ATTRIBUTES.entries()) expect(specs[i + 1]).toBe(shared);
    });

    /** G3 fix: the persistent sink appends the Consignment Generation caps AFTER the shared block —
     *  they are sink-owned processing.batch keys, not part of the output: vocabulary, so they must
     *  never migrate into OUTPUT_ATTRIBUTES (the other sink kinds have no ConsignmentPlanner). */
    it('appends the consignment grouping caps after the shared output block', () => {
        const keys = nodeAttributesFor('sink.persistent')!.map((s) => s.key);
        expect(keys.slice(-7)).toEqual([
            'batch__max_files',
            'batch__max_bytes',
            'batch__order',
            'priority',
            'intake__max_files_per_cycle',
            'intake__min_files_per_cycle',
            'intake__adaptive',
        ]);
    });

    /** The shared table's format default is the ENGINE's absent-key behaviour, not a UX suggestion. */
    it('defaults the output format to CSV, the engine default', () => {
        expect(OUTPUT_ATTRIBUTES.find((s) => s.key === 'format')?.default).toBe('CSV');
    });

    it('classifies every attribute of every known type into a tier', () => {
        for (const type of [
            'acquisition',
            'transform.filter',
            'transform.route',
            'sink.persistent',
            'sink.materialized',
            'sink.view',
        ]) {
            for (const s of nodeAttributesFor(type)!) {
                expect(['required', 'optional', 'advanced']).toContain(s.tier);
            }
        }
    });

    /**
     * U-D's whole point: one table per concern, so the acquisition node cannot drift from Onboarding —
     * asserted by ELEMENT identity, so a forked copy fails even if the keys look the same. (Whole-array
     * identity stopped being possible in P5-a, when the node's spec became the shared block table PLUS
     * the marker-dedup keys it borrows from `processing:`/`dirs:`.)
     */
    it('authors the collector block from the SAME shared table Onboarding uses, plus the marker keys', () => {
        const acq = nodeAttributesFor('acquisition')!;
        // Shared block + the borrowed marker keys + the trigger cadence keys (top-level `trigger:`
        // map, borrowed like the marker keys — NOT part of the collector block).
        const shared = [...COLLECTOR_ATTRIBUTES, ...MARKER_DEDUP_ATTRIBUTES, ...UNPACK_ATTRIBUTES];
        expect(acq).toHaveLength(shared.length + 5);
        shared.forEach((spec, i) => expect(acq[i]).toBe(spec));
        expect(acq.slice(shared.length).map((s) => s.key)).toEqual([
            'trigger__every',
            'trigger__cron',
            'trigger__on',
            'trigger__from',
            'trigger__coalesce',
        ]);
        // ⚠ and the marker + trigger keys stay OUT of the block table itself — Onboarding's Collection
        // stage renders that one whole, and these are not `collector:` keys.
        expect(COLLECTOR_ATTRIBUTES.map((s) => s.key)).not.toContain('duplicate_check');
        expect(COLLECTOR_ATTRIBUTES.map((s) => s.key)).not.toContain('trigger__every');
    });

    /**
     * Collector-config unification (2026-08-04). D9 had split `duplicate__*` onto a
     * `transform.dedup.fingerprint` node; that node was REMOVED because file duplicate detection
     * executes inside the `CollectorProcessor` poll cycle (`ledgerFilter` reads `collector.duplicate`)
     * — it had no runtime as a transform, so the split told the operator the check happens after
     * collection. The acquisition node now declares the policy, where it actually runs.
     */
    it('declares the duplicate policy on acquisition, where the engine runs it', () => {
        const acq = nodeAttributesFor('acquisition')!.map((s) => s.key);
        expect(acq).toContain('duplicate__mode');
        expect(acq).toContain('duplicate__on_change');
        // The removed node has no schema at all — a stale graph carrying one is refused at save.
        expect(nodeAttributesFor('transform.dedup.fingerprint')).toBeUndefined();
    });

    it('offers the engine-real collector keys, not the old best-guess ones', () => {
        const keys = nodeAttributesFor('acquisition')!.map((s) => s.key);
        expect(keys).toContain('recursive_depth');
        expect(keys).toContain('stability__window');
        // `recursive` (boolean) and `min_age_seconds` are read nowhere in the backend.
        expect(keys).not.toContain('recursive');
        expect(keys).not.toContain('min_age_seconds');
    });

    /**
     * D1 regression (2026-08-03). The key is written verbatim into `node.config`, so it must be the
     * string the engine reads: `RowShaper.filter` → `str(node, "where")`
     * (`inspecto-engine/.../pipeline/exec/RowShaper.java:79`, and the fused path at :261). This shipped
     * as `predicate` — a word that appears in `RowShaper` only as the `requireExpr` error label (:89).
     * Every backend fixture uses `where` (`RowShaperTest`, `PipelineExecutorTest`, `PipelineDryRunTest`,
     * `ComponentPreviewTest`, `ControlApiPipelineRunTest`, …), which is what makes `where` the canonical side.
     *
     * ⚠ This pins the NAMES only. That a filter actually filters is proved on the engine side —
     * `DataTransformerRowPredicateTest` (post-parse `where`) and
     * `PipelineEditableTest.postParsePredicateRoundTripsThroughTheFilterNode` (the lift/lower contract).
     */
    it('names the filter predicate with the key the engine actually reads', () => {
        const keys = nodeAttributesFor('transform.filter')!.map((s) => s.key);
        expect(keys).toContain('where');
        expect(keys).not.toContain('predicate');
    });

    /**
     * D7 (2026-08-03). `transform.filter` has TWO filtering moments on the flat path and the spec must
     * declare both, because they are different capabilities under one node type:
     *
     * - post-parse `where` — SQL over the mapped, typed columns (`DataTransformer.materialize`);
     * - pre-parse `include_*`/`exclude_*` — regex/prefix over ONE raw column inside `read_csv`
     *   (`DuckDbCsvIngester.filterWhere`), anchored on `filter_target_column`.
     *
     * The pre-parse group round-trips through `PipelineLift.filterConfig` and always worked here — it was
     * simply undeclared, so the dialog could not reach it. Collapsing the two (e.g. re-speccing `where` to
     * `include_regex`) is the drift the config-key contract exists to stop: `amount > 0` is not a regex.
     */
    it('declares both the pre-parse and post-parse filtering vocabularies', () => {
        const specs = nodeAttributesFor('transform.filter')!;
        const byKey = new Map(specs.map((s) => [s.key, s]));

        // the pre-parse lists are real, engine-read keys and must be list-typed (not comma-strings)
        for (const k of ['include_regex', 'exclude_regex', 'include_prefixes', 'exclude_prefixes']) {
            expect(byKey.get(k)?.type).toBe('list');
        }
        expect(byKey.get('filter_target_column')?.type).toBe('number');
        expect(byKey.get('where')?.type).toBe('string');

        // Neither moment may be mandatory — a node legitimately uses only one of them.
        for (const s of specs) expect(isRequired(s)).toBe(false);
    });

    /**
     * D2 regression (2026-08-03). `route_column` was read by **nothing** — `RowShaper.route` routes on
     * `branches[]{key, where}` (per-branch SQL predicates), plus `mode` and `default`
     * (`RowShaper.java:99-145`); a repo-wide grep found zero Java readers of `route_column`. `mode` is
     * engine-real and stays. `branches` and `default` are deliberately unspecced — `AttributeSpec` has
     * no map-list type, and branches have their own surface (the Recipe view's branch rows) whose
     * `key`/`database` are derived from the `route:<key>` edge, so speccing them would form-OWN a
     * derived pair and replace it wholesale on save.
     */
    it('offers only the engine-real scalar for a route node', () => {
        const keys = nodeAttributesFor('transform.route')!.map((s) => s.key);
        expect(keys).toEqual(['mode']);
        expect(keys).not.toContain('route_column');
    });

    /** All three sink kinds write the same `output:` block — the kind is behaviour, not a config shape.
     *  Only the persistent sink also owns a destination (`database`); the other two kinds are not
     *  lowerable and keep the bare shared block. */
    it('gives every sink kind the same output-block schema', () => {
        expect(nodeAttributesFor('sink.materialized')).toBe(OUTPUT_ATTRIBUTES);
        expect(nodeAttributesFor('sink.view')).toBe(OUTPUT_ATTRIBUTES);
        expect(nodeAttributesFor('sink.persistent')!.slice(1, 1 + OUTPUT_ATTRIBUTES.length)).toEqual(OUTPUT_ATTRIBUTES);
    });

    it('drops the sink keys the backend never read', () => {
        const keys = nodeAttributesFor('sink.persistent')!.map((s) => s.key);
        for (const dead of ['partition_by', 'table', 'mode', 'key_columns']) {
            expect(keys).not.toContain(dead);
        }
    });

    it('returns undefined for a type with no specced shape (free-form fallback)', () => {
        // Deliberately unspecced rather than guessed — the remaining transform shapes are not
        // specced server-side, and a best-guess table that looks authoritative is what U-D removed.
        expect(nodeAttributesFor('transform.map')).toBeUndefined();
        expect(nodeAttributesFor('parser')).toBeUndefined();
        expect(nodeAttributesFor('alert')).toBeUndefined();
        expect(nodeAttributesFor('acme.custom')).toBeUndefined();
        expect(nodeAttributesFor(undefined)).toBeUndefined();
    });

    /** The retired fiction must not creep back via this map either. */
    it('has no schema under any of the invented type names', () => {
        for (const fiction of [
            'collector.file',
            'collector.database',
            'collector.stream',
            'sink.file',
            'sink.database',
            'transform.record',
            'transform.aggregate',
            'transform.alert',
        ]) {
            expect(nodeAttributesFor(fiction)).toBeUndefined();
        }
    });
});
