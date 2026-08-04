import { describe, expect, it } from 'vitest';
import { byTier, COLLECTOR_ATTRIBUTES, isRequired, OUTPUT_ATTRIBUTES } from 'app/inspecto/component-model';
import { nodeAttributesFor } from './node-attributes';

/**
 * W2/U-D reshaped this: the map is now keyed by the engine's own `BuiltinNodeType` strings, and a type
 * only gets a schema when the backend really reads those keys. The previous suite asserted schemas for
 * `collector.file`/`sink.file`/`sink.database` — types the backend has never had — over keys it never
 * reads, so it passed while the editor authored nodes `PipelineCompiler` silently dropped.
 */
describe('node-attributes', () => {
    it('returns a tiered schema for a known node type', () => {
        const specs = nodeAttributesFor('sink.persistent');
        expect(specs).toBeDefined();
        const grouped = byTier(specs!);
        expect(grouped.required.map((s) => s.key)).toEqual(['format']);
        // W4a moved compression advanced → optional when the table unified with Onboarding's
        // (which always showed it) — a deliberate tier choice, not a regression.
        expect(grouped.optional.map((s) => s.key)).toEqual(['compression']);
    });

    /** W4a: the sink's output block is the SAME shared table Onboarding's publish stage renders. */
    it('authors the output block with the shared OUTPUT_ATTRIBUTES table', () => {
        expect(nodeAttributesFor('sink.persistent')).toBe(OUTPUT_ATTRIBUTES);
    });

    /** The shared table's format default is the ENGINE's absent-key behaviour, not a UX suggestion. */
    it('defaults the output format to CSV, the engine default', () => {
        expect(OUTPUT_ATTRIBUTES.find((s) => s.key === 'format')?.default).toBe('CSV');
    });

    it('classifies every attribute of every known type into a tier', () => {
        for (const type of ['acquisition', 'transform.filter', 'transform.route',
            'sink.persistent', 'sink.materialized', 'sink.view']) {
            for (const s of nodeAttributesFor(type)!) {
                expect(['required', 'optional', 'advanced']).toContain(s.tier);
            }
        }
    });

    /** U-D's whole point: one table per concern, so the acquisition node cannot drift from Onboarding. */
    it('authors the collector block with the SAME shared table Onboarding uses', () => {
        expect(nodeAttributesFor('acquisition')).toBe(COLLECTOR_ATTRIBUTES);
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
     * engine-real and stays. `branches` and `default` are deliberately unspecced — `AttributeSpec` has no
     * list type and the named routes are authored on the canvas edges.
     */
    it('offers only the engine-real scalar for a route node', () => {
        const keys = nodeAttributesFor('transform.route')!.map((s) => s.key);
        expect(keys).toEqual(['mode']);
        expect(keys).not.toContain('route_column');
    });

    /** All three sink kinds write the same `output:` block — the kind is behaviour, not a config shape. */
    it('gives every sink kind the same output-block schema', () => {
        const persistent = nodeAttributesFor('sink.persistent');
        expect(nodeAttributesFor('sink.materialized')).toBe(persistent);
        expect(nodeAttributesFor('sink.view')).toBe(persistent);
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
        for (const fiction of ['collector.file', 'collector.database', 'collector.stream',
            'sink.file', 'sink.database', 'transform.record', 'transform.aggregate', 'transform.alert']) {
            expect(nodeAttributesFor(fiction)).toBeUndefined();
        }
    });
});
