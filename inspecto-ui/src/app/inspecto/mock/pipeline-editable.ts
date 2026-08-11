/**
 * The mock's editable lift/lower — a faithful TS port of the backend `PipelineEditable` (W5). It
 * MUST refuse exactly what the server refuses (UNSUPPORTED_NODE + the completeness set), or the
 * offline preview passes a topology the real backend 422s — the textbook "mock more lenient than the
 * server" hole this project has been bitten by before. Node config is the raw config-file vocabulary
 * end to end, so lift→lower is a verbatim map round-trip.
 *
 * ⚠ `MULTI_SINK` is NOT a refusal (since sinks slice 4, >1 database lowers to a plural `sinks:` block),
 * and neither are `MULTI_JOIN` / `MULTI_DEDUP` / `MULTI_ROUTE` / `MULTI_SUMMARIZE` any more — the
 * multiplicity plan's slice A3 gave the flat file an ordered `steps:` chain, so the single slot those
 * codes protected no longer exists. **The obligation runs both ways:** a mock that still refused a
 * second dedup would be *stricter* than the server, which greys out work the backend would happily
 * save — the same class of bug as being lenient, pointing the other way.
 */
import type { AuthoredEdge, AuthoredNode, AuthoredPipeline } from '../api/pipelines.service';

export interface Refusal {
    code: string;
    nodeId?: string;
    message: string;
}

export const LOWERABLE = new Set([
    'acquisition', 'parser', 'gap', 'transform.dedup.marker',
    'transform.filter', 'transform.map', 'sink.persistent', 'enrichment',
    'transform.route',     // route: block — authoring-only until the executor lands (mirrors backend S3)
    'transform.dedup',     // record-grain dedup → processing.dedup (ELT P2)
    'transform.summarize', // group-by rollup → processing.summarize (ELT P3), authoring-only
    'transform.join',      // reference join → processing.join (ELT P3 S2), authoring-only
]);

type Cfg = Record<string, unknown>;
const asMap = (v: unknown): Cfg => (v && typeof v === 'object' && !Array.isArray(v) ? { ...(v as Cfg) } : {});
const isQuarantine = (n: AuthoredNode): boolean => !!n.config?.['dir'] && n.config?.['database'] == null;

/** The four PRE-parse row-filter lists — regexes/prefixes over one raw column, anchored on
 *  `filter_target_column`. Distinct from the POST-parse `where` predicate; see node-attributes.ts. */
const PRE_PARSE_FILTER_KEYS = ['include_prefixes', 'include_regex', 'exclude_prefixes', 'exclude_regex'];

/**
 * Move the row-filter keys out of a lifted `csv_settings` and return them as a Filter node's config —
 * the TS mirror of `PipelineLift.filterConfig`. Mutates `parserCfg`'s csv_settings clone.
 *
 * Two gating rules copied from the Java, both load-bearing for the verbatim round-trip:
 * `filter_target_column` travels ONLY when a pre-parse list does (it is the index those lists anchor
 * on, so emitting it alone would have lower write a key the file never had), and a blank `where` is
 * not a predicate.
 */
function extractRowFilters(parserCfg: Cfg): Cfg {
    if (parserCfg['csv_settings'] == null) return {};
    const csv = asMap(parserCfg['csv_settings']);
    const out: Cfg = {};
    const hasLists = PRE_PARSE_FILTER_KEYS.some(
        (k) => Array.isArray(csv[k]) && (csv[k] as unknown[]).length > 0,
    );
    for (const k of PRE_PARSE_FILTER_KEYS) {
        if (csv[k] != null) {
            out[k] = csv[k];
            delete csv[k];
        }
    }
    if (csv['filter_target_column'] != null) {
        if (hasLists) out['filter_target_column'] = csv['filter_target_column'];
        delete csv['filter_target_column'];
    }
    if (csv['where'] != null && String(csv['where']).trim()) {
        out['where'] = csv['where'];
        delete csv['where'];
    }
    if (!Object.keys(out).length) return {};
    // csv_settings was cloned by asMap, so write the pruned copy back (dropping it entirely when the
    // filter keys were all it held — lower re-creates it from the Filter node).
    if (Object.keys(csv).length) parserCfg['csv_settings'] = csv;
    else delete parserCfg['csv_settings'];
    return out;
}

/** Lift a canonical pipeline config map into the editable graph (topology + verbatim sections). */
export function liftConfig(config: Cfg): AuthoredPipeline {
    const collector = asMap(config['collector'] ?? config['source']);
    const dirs = asMap(config['dirs']);
    const output = asMap(config['output']);
    const processing = asMap(config['processing']);
    const nodes: AuthoredNode[] = [];
    const edges: AuthoredEdge[] = [];

    // acquisition (entry)
    const acqCfg: Cfg = {};
    for (const [k, v] of Object.entries(collector)) {
        // `duplicate`/`incremental` STAY on acquisition (2026-08-04 fold — they execute in the
        // poll cycle); only gap_detection has its own node, and connection rides `use:`.
        if (k === 'gap_detection' || k === 'connection') continue;
        acqCfg[k] = v;
    }
    if (dirs['poll'] != null) acqCfg['poll'] = dirs['poll'];
    if (config['trigger'] != null) acqCfg['trigger'] = config['trigger'];
    if (processing['file_pattern'] != null) acqCfg['file_pattern'] = processing['file_pattern'];
    const acqUse = collector['connection'] ? `connection/${String(collector['connection'])}` : undefined;
    nodes.push({ id: 'acq', type: 'acquisition', name: 'Collect', use: acqUse, config: acqCfg });

    // gap (control)
    const gapDetection = asMap(collector['gap_detection']);
    if (gapDetection['enabled'] === true) {
        const gapCfg: Cfg = {};
        for (const [k, v] of Object.entries(gapDetection)) if (k !== 'enabled') gapCfg[k] = v;
        nodes.push({ id: 'gap', type: 'gap', name: 'Gap detection', config: gapCfg });
        edges.push({ from: 'acq', rel: 'gap', to: 'gap' });
    }

    // dedup prefix
    let upstream = 'acq';
    const dupCheck = asMap(processing['duplicate_check']);
    if (dupCheck['enabled'] === true) {
        const c: Cfg = {};
        if (dupCheck['marker_extension'] != null) c['marker_extension'] = dupCheck['marker_extension'];
        if (dupCheck['retention_days'] != null) c['retention_days'] = dupCheck['retention_days'];
        if (dirs['markers'] != null) c['markers_dir'] = dirs['markers'];
        nodes.push({ id: 'dedup_marker', type: 'transform.dedup.marker', name: 'Dedup (marker)', config: c });
        edges.push({ from: upstream, rel: 'data', to: 'dedup_marker' });
        upstream = 'dedup_marker';
    }

    // parser
    const parserCfg: Cfg = {};
    for (const k of ['csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config']) {
        if (processing[k] != null) parserCfg[k] = processing[k];
    }
    // The row-filter keys inside csv_settings are NOT parser config — they lift to their own Filter
    // node, mirroring `PipelineLift.filterConfig`. Without this split the offline editor showed a
    // `where`/`include_regex` buried in the parser's free-form csv_settings while the real backend put
    // it on a Filter node: the same surface reporting two different graphs for one file.
    const filterCfg = extractRowFilters(parserCfg);
    // The top-level `parsing:` block is parser-owned too, carried verbatim (mirrors
    // `PipelineEditable.editableConfig`). It OVERLAYS processing.csv_settings in the engine, so a
    // parser node that could not see it would edit the losing key.
    if (config['parsing'] != null) parserCfg['parsing'] = config['parsing'];
    // A bound reusable Grammar presents as `use:`, mirroring connection/ on acquisition — never ALSO
    // as a free-text config key.
    let parserUse: string | undefined;
    if (typeof asMap(config['parsing'])['grammar'] === 'string') {
        parserUse = String(asMap(config['parsing'])['grammar']);
        const stripped = asMap(parserCfg['parsing']);
        delete stripped['grammar'];
        if (Object.keys(stripped).length) parserCfg['parsing'] = stripped;
        else delete parserCfg['parsing'];
    }
    nodes.push({ id: 'parse', type: 'parser', name: 'Parser', use: parserUse, config: parserCfg });
    edges.push({ from: upstream, rel: 'data', to: 'parse' });
    let sinkUpstream = 'parse';
    if (Object.keys(filterCfg).length) {
        nodes.push({ id: 'filter', type: 'transform.filter', name: 'Row filter', config: filterCfg });
        edges.push({ from: 'parse', rel: 'data', to: 'filter' });
        sinkUpstream = 'filter';
    }

    // Reference join (processing.join) sits right after the parse/filter stretch — dedup/summarize
    // downstream see the enriched row set (mirrors PipelineLift; authoring-only, like route below).
    const join = asMap(processing['join']);
    if (processing['join'] != null) {
        const jc: Cfg = { reference: join['reference'], on: join['on'] };
        nodes.push({ id: 'join', type: 'transform.join', name: 'Join', config: jc });
        edges.push({ from: sinkUpstream, rel: 'data', to: 'join' });
        sinkUpstream = 'join';
    }

    // Record-grain dedup (processing.dedup) sits between the parse stretch and the sink(s).
    const dedup = asMap(processing['dedup']);
    if (processing['dedup'] != null) {
        const dc: Cfg = { keys: dedup['keys'] };
        if (dedup['order_by'] != null) dc['order_by'] = dedup['order_by'];
        nodes.push({ id: 'dedup', type: 'transform.dedup', name: 'Dedup (record)', config: dc });
        edges.push({ from: sinkUpstream, rel: 'data', to: 'dedup' });
        sinkUpstream = 'dedup';
    }

    // Group-by rollup (processing.summarize) sits after dedup, before any route (authoring-only).
    const summarize = asMap(processing['summarize']);
    if (processing['summarize'] != null) {
        const sc: Cfg = { group_by: summarize['group_by'], measures: summarize['measures'] };
        nodes.push({ id: 'summarize', type: 'transform.summarize', name: 'Summarize', config: sc });
        edges.push({ from: sinkUpstream, rel: 'data', to: 'summarize' });
        sinkUpstream = 'summarize';
    }

    // route: block — lifts as a transform.route node whose route:<key> edges feed the sinks,
    // branch↔sink pairing by the branch's declared destination database (mirrors PipelineLift).
    const routeCfg = config['route'] != null ? structuredClone(asMap(config['route'])) : null;
    if (routeCfg) {
        nodes.push({ id: 'route', type: 'transform.route', name: 'Route', config: routeCfg });
        edges.push({ from: sinkUpstream, rel: 'data', to: 'route' });
        sinkUpstream = 'route';
    }

    // persistent sink(s): the single output:/dirs.database shorthand, plus any extra destinations a
    // plural sinks: block declares (slice 4 lowers >1 destination; the lift must read them back or a
    // routed pipeline loses its branch targets on reopen).
    const sinkCfg: Cfg = {};
    if (output['format'] != null) sinkCfg['format'] = output['format'];
    if (output['compression'] != null) sinkCfg['compression'] = output['compression'];
    if (output['ducklake'] != null) sinkCfg['ducklake'] = output['ducklake'];
    if (dirs['database'] != null) sinkCfg['database'] = dirs['database'];
    if (dirs['backup'] != null) sinkCfg['backup'] = dirs['backup'];
    if (dirs['temp'] != null) sinkCfg['temp'] = dirs['temp'];
    for (const k of ['threads', 'duckdb_threads', 'batch_max_files', 'batch_max_bytes']) {
        if (processing[k] != null) sinkCfg[k] = processing[k];
    }
    const store = String(config['name'] ?? 'out');
    sinkCfg['store'] = store;
    const sinkDefs: { id: string; name: string; cfg: Cfg }[] = [{ id: 'sink', name: store, cfg: sinkCfg }];
    const sinksList = Array.isArray(config['sinks']) ? (config['sinks'] as unknown[]).map(asMap) : [];
    let extra = 2;
    for (const s of sinksList) {
        if (s['database'] == null || String(s['database']) === String(dirs['database'])) continue;   // primary already lifted
        const c: Cfg = { database: s['database'] };
        for (const k of ['format', 'compression', 'ducklake']) if (s[k] != null) c[k] = s[k];
        sinkDefs.push({ id: `sink_${extra}`, name: `out_${extra}`, cfg: c });
        extra++;
    }
    const branchKeyFor = (db: unknown): string | null => {
        if (!routeCfg || db == null || !Array.isArray(routeCfg['branches'])) return null;
        const hit = (routeCfg['branches'] as Cfg[]).find((b) => String(b['database']) === String(db) && b['key'] != null);
        return hit ? String(hit['key']) : null;
    };
    for (const d of sinkDefs) {
        nodes.push({ id: d.id, type: 'sink.persistent', name: d.name, description: 'Persistent store', config: d.cfg });
        const key = branchKeyFor(d.cfg['database']);
        edges.push(key != null
            ? { from: 'route', rel: `route:${key}`, to: d.id }
            : { from: sinkUpstream, rel: 'data', to: d.id });
    }

    return { name: String(config['name'] ?? ''), active: config['active'] === true, nodes, edges };
}

/**
 * Lower the editable graph back onto `existing` (verbatim sections; unmodeled keys preserved).
 * Returns `{ config }` on success or `{ refusals }` when the topology cannot be represented.
 * `strict` (active save, or a brand-new file) additionally requires completeness.
 */
export function lowerGraph(g: AuthoredPipeline, existing: Cfg, strict: boolean): { config: Cfg } | { refusals: Refusal[] } {
    const refusals: Refusal[] = [];
    let acq: AuthoredNode | undefined, parser: AuthoredNode | undefined, gap: AuthoredNode | undefined;
    let marker: AuthoredNode | undefined;
    let primarySink: AuthoredNode | undefined, quarantine: AuthoredNode | undefined;
    // The transform chain in authored order — node order, exactly as PipelineEditable.lower reads it.
    const chain: AuthoredNode[] = [];
    // Distinct output destinations keyed by database dir (order-preserving). One => the single
    // output:/dirs.database shorthand; more than one => a plural sinks: block (slice 4).
    const destByDatabase = new Map<string, AuthoredNode>();

    for (const n of g.nodes) {
        if (!LOWERABLE.has(n.type)) {
            refusals.push({ code: 'UNSUPPORTED_NODE', nodeId: n.id, message: `the flat pipeline config has no home for a '${n.type}' node` });
            continue;
        }
        if (n.type === 'acquisition') acq = n;
        else if (n.type === 'parser') parser = n;
        else if (n.type === 'gap') gap = n;
        else if (n.type === 'transform.dedup.marker') marker = n;
        // The five chain kinds. They briefly refused a second node (MULTI_DEDUP / MULTI_ROUTE /
        // MULTI_SUMMARIZE / MULTI_JOIN, 2026-08-11) while the flat file still had one slot per kind;
        // the file now holds an ordered steps: chain, so the refusals are gone on both sides. They had
        // to flip together in one commit — a preview that refuses what the backend accepts is the same
        // bug as one that accepts what the backend refuses, just pointing the other way.
        else if (STEP_KIND[n.type]) chain.push(n);
        else if (n.type === 'sink.persistent') {
            if (isQuarantine(n)) quarantine = n;
            else {
                const db = n.config?.['database'];
                if (db != null && !destByDatabase.has(String(db))) destByDatabase.set(String(db), n);
                if (!primarySink || (primarySink.config?.['database'] == null && n.config?.['database'] != null)) primarySink = n;
            }
        }
    }
    // >1 distinct database is no longer a refusal — it lowers to a plural sinks: block (slice 4);
    // a transform.route node lowers to the route: block below (backend route lowering, S3).

    // One spelling or the other, never both — the server's parser refuses a file carrying steps: next
    // to a singular transform block, so a legacy-shaped chain keeps the singular keys verbatim and only
    // a chain they cannot hold becomes a steps: list (mirrors PipelineEditable.isLegacyShaped).
    const legacyShaped = isLegacyShaped(chain);
    const recordDedup = legacyShaped ? chain.find((n) => n.type === 'transform.dedup') : undefined;
    const routeNode = legacyShaped ? chain.find((n) => n.type === 'transform.route') : undefined;
    const summarizeNode = legacyShaped ? chain.find((n) => n.type === 'transform.summarize') : undefined;
    const joinNode = legacyShaped ? chain.find((n) => n.type === 'transform.join') : undefined;
    const filters = legacyShaped ? chain.filter((n) => n.type === 'transform.filter') : [];

    if (strict) {
        if (!acq) refusals.push({ code: 'NO_ACQUISITION', message: 'an active pipeline needs an acquisition node' });
        if (!parser) refusals.push({ code: 'NO_PARSER', message: 'an active pipeline needs a parser node' });
        if (!primarySink || primarySink.config?.['database'] == null)
            refusals.push({ code: 'NO_PERSISTENT_SINK', message: 'an active pipeline needs a persistent sink with a database dir' });
        const schemaKeys = ['parsing', 'csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config'];
        const grammarBound = !!parser?.use?.startsWith('grammar/');
        if (parser && !grammarBound && !schemaKeys.some((k) => parser!.config?.[k] != null))
            refusals.push({ code: 'PARSER_NO_SCHEMA', nodeId: parser.id, message: 'the parser names no Grammar / parsing: block / schema_file / schemas / segments' });
    }
    if (refusals.length) return { refusals };

    const out: Cfg = structuredClone(existing);
    if (typeof out['name'] !== 'string' || String(out['name']).toLowerCase() !== g.name.toLowerCase()) out['name'] = g.name;
    out['active'] = g.active;
    const colKey = 'source' in out && !('collector' in out) ? 'source' : 'collector';
    const collector = asMap(out[colKey]); out[colKey] = collector;
    const dirs = asMap(out['dirs']); out['dirs'] = dirs;
    const output = asMap(out['output']); out['output'] = output;
    const processing = asMap(out['processing']); out['processing'] = processing;

    if (acq) {
        for (const k of Object.keys(collector)) if (k !== 'gap_detection') delete collector[k];
        for (const [k, v] of Object.entries(acq.config ?? {})) if (!['poll', 'trigger', 'file_pattern'].includes(k)) collector[k] = v;
        delete collector['connection'];
        if (acq.use?.startsWith('connection/')) collector['connection'] = acq.use.slice('connection/'.length);
        setOrDel(dirs, 'poll', acq.config?.['poll']);
        setOrDel(out, 'trigger', acq.config?.['trigger']);
        setOrDel(processing, 'file_pattern', acq.config?.['file_pattern']);
    }
    overlay(collector, 'gap_detection', gap ? { enabled: true, ...(gap.config ?? {}) } : undefined, strict);

    if (marker) {
        const dc: Cfg = { enabled: true };
        if (marker.config?.['marker_extension'] != null) dc['marker_extension'] = marker.config['marker_extension'];
        if (marker.config?.['retention_days'] != null) dc['retention_days'] = marker.config['retention_days'];
        processing['duplicate_check'] = dc;
        setOrDel(dirs, 'markers', marker.config?.['markers_dir']);
    } else if (strict) {
        delete processing['duplicate_check'];
        delete dirs['markers'];
    }

    // record-grain dedup → processing.dedup ({keys, order_by} — the QUALIFY the engine applies)
    if (recordDedup) {
        const dd: Cfg = {};
        if (recordDedup.config?.['keys'] != null) dd['keys'] = recordDedup.config['keys'];
        if (recordDedup.config?.['order_by'] != null) dd['order_by'] = recordDedup.config['order_by'];
        processing['dedup'] = dd;
    } else if (strict) {
        delete processing['dedup'];
    }

    // reference join → processing.join ({reference, on}) — authoring-only (ELT P3 S2, D-4)
    if (joinNode) {
        const jn: Cfg = {};
        if (joinNode.config?.['reference'] != null) jn['reference'] = joinNode.config['reference'];
        if (joinNode.config?.['on'] != null) jn['on'] = joinNode.config['on'];
        processing['join'] = jn;
    } else if (strict) {
        delete processing['join'];
    }

    // group-by rollup → processing.summarize ({group_by, measures}) — authoring-only (ELT P3)
    if (summarizeNode) {
        const sm: Cfg = {};
        if (summarizeNode.config?.['group_by'] != null) sm['group_by'] = summarizeNode.config['group_by'];
        if (summarizeNode.config?.['measures'] != null) sm['measures'] = summarizeNode.config['measures'];
        processing['summarize'] = sm;
    } else if (strict) {
        delete processing['summarize'];
    }

    // route: block — node config verbatim, each branch stamped with the destination database its
    // route:<key> edge feeds (mirrors PipelineEditable.routeSection; edges don't survive the flat file).
    if (routeNode) {
        out['route'] = routeSection(g, routeNode);
    } else if (strict) {
        delete out['route'];
    }

    if (parser) {
        for (const k of ['csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config']) delete processing[k];
        for (const k of ['csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config'])
            if (parser.config?.[k] != null) processing[k] = parser.config[k];
        if (filters.length) {
            const csv = asMap(processing['csv_settings']); processing['csv_settings'] = csv;
            for (const f of filters) Object.assign(csv, f.config ?? {});
        }
        // …and the top-level parsing: block it owns. Removed only in strict mode: a partial merge
        // must not drop a block it was never given (mirrors `overlayOwned`).
        const parsingBlock = asMap(parser.config?.['parsing'] ?? out['parsing']);
        if (parser.config?.['parsing'] == null && strict) for (const k of Object.keys(parsingBlock)) delete parsingBlock[k];
        // …and the Grammar binding it carries on use:. Without this the ref rides the graph model and
        // is silently dropped on the way to disk (mirrors PipelineEditable.lower).
        if (parser.use?.startsWith('grammar/')) parsingBlock['grammar'] = parser.use;
        else if (strict) delete parsingBlock['grammar'];
        if (Object.keys(parsingBlock).length) out['parsing'] = parsingBlock;
        else delete out['parsing'];
    }

    // ── the ordered chain (mirrors PipelineEditable.lower) ────────────────────────────
    if (legacyShaped) {
        // A file that had grown a steps: block and was edited back to a legacy shape loses it, or the
        // two spellings collide on the next load.
        delete out['steps'];
    } else {
        out['steps'] = chain.map((n) => ({ [STEP_KIND[n.type]]: stepConfig(g, n) }));
        // Every singular transform key must go, in BOTH modes — not the usual strict-only rule. The
        // server's parser rejects steps: alongside a legacy block outright, so leaving one behind
        // writes config that can never be read back.
        delete processing['dedup'];
        delete processing['join'];
        delete processing['summarize'];
        delete out['route'];
        // `where` is the legacy spelling of a filter step and lives inside the parser's own
        // csv_settings block, so it survives the deletions above. The pre-parse include/exclude lists
        // in that block are not chain steps and stay where they are.
        const csv = processing['csv_settings'];
        if (csv && typeof csv === 'object') {
            delete (csv as Cfg)['where'];
            if (!Object.keys(csv as Cfg).length) delete processing['csv_settings'];
        }
    }

    if (primarySink) {
        for (const k of Object.keys(output)) delete output[k];
        setOrDel(output, 'format', primarySink.config?.['format']);
        setOrDel(output, 'compression', primarySink.config?.['compression']);
        setOrDel(output, 'ducklake', primarySink.config?.['ducklake']);
        setOrDel(dirs, 'database', primarySink.config?.['database']);
        setOrDel(dirs, 'backup', primarySink.config?.['backup']);
        setOrDel(dirs, 'temp', primarySink.config?.['temp']);
        for (const k of ['threads', 'duckdb_threads', 'batch_max_files', 'batch_max_bytes']) setOrDel(processing, k, primarySink.config?.[k]);
    }
    // Multi-destination: emit a plural sinks: list of the distinct destinations (the single output:/
    // dirs.database above stays the shorthand). One destination => no sinks: block (verbatim round-trip).
    if (destByDatabase.size > 1) {
        out['sinks'] = [...destByDatabase.values()].map((s) => {
            const sink: Cfg = { database: s.config?.['database'] };
            if (s.config?.['format'] != null) sink['format'] = s.config['format'];
            if (s.config?.['compression'] != null) sink['compression'] = s.config['compression'];
            if (s.config?.['ducklake'] != null) sink['ducklake'] = s.config['ducklake'];
            return sink;
        });
    } else {
        delete out['sinks'];
    }
    if (quarantine) setOrDel(dirs, 'quarantine', quarantine.config?.['dir']);

    if (Object.keys(collector).length === 0) delete out[colKey];
    // A file with no output: block must not gain an empty one (mirrors PipelineEditable.lower).
    if (Object.keys(output).length === 0) delete out['output'];
    return { config: out };
}

/** Node type → the `steps:` kind it lowers to; the five kinds the flat file's chain can hold. */
const STEP_KIND: Record<string, string> = {
    'transform.filter': 'filter',
    'transform.join': 'join',
    'transform.dedup': 'dedup',
    'transform.summarize': 'summarize',
    'transform.route': 'route',
};
/** The chain order the singular keys imply, i.e. the order PipelineLift wires them in. */
const STEP_KINDS = ['filter', 'join', 'dedup', 'summarize', 'route'];

/**
 * Whether `chain` fits the legacy singular keys: at most one of each kind, in the lift's order.
 *
 * ⚠ Order is half the test, and the half that is easy to miss. One dedup and one summarize fit the
 * singular slots whichever way round they are authored — but the flat file stores no order, so an
 * authored `summarize → dedup` would come back from the next lift reversed: a two-node pipeline
 * quietly changing meaning, with nothing over-full about it.
 *
 * Both halves are the one strictly-increasing test: a repeated kind has the same position as the one
 * before it, so it fails `<=` just as an out-of-order kind does. A separate "seen this kind" set was
 * written first and turned out to be dead — removing it changed no test, which is how it was found.
 */
function isLegacyShaped(chain: AuthoredNode[]): boolean {
    let previous = -1;
    for (const n of chain) {
        const position = STEP_KINDS.indexOf(STEP_KIND[n.type]);
        if (position <= previous) return false;
        previous = position;
    }
    return true;
}

/** A step's config in the same shape its legacy block held (mirrors PipelineEditable.stepConfig). */
function stepConfig(g: AuthoredPipeline, n: AuthoredNode): Cfg {
    const c: Cfg = {};
    const keep = (...keys: string[]) => {
        for (const k of keys) if (n.config?.[k] != null) c[k] = n.config[k];
    };
    switch (STEP_KIND[n.type]) {
        case 'dedup': keep('keys', 'order_by'); return c;
        case 'join': keep('reference', 'on'); return c;
        case 'summarize': keep('group_by', 'measures'); return c;
        case 'route': return routeSection(g, n);
        // filter: verbatim — the post-parse `where` and the pre-parse include/exclude lists together,
        // so the round-trip is lossless. Only `where` has a legacy singular spelling.
        default: return structuredClone(n.config ?? {}) as Cfg;
    }
}

/** A route node's config with each branch stamped with the database its `route:<key>` edge feeds. */
function routeSection(g: AuthoredPipeline, routeNode: AuthoredNode): Cfg {
    const rc = structuredClone(routeNode.config ?? {}) as Cfg;
    if (Array.isArray(rc['branches'])) {
        const byId = new Map(g.nodes.map((n) => [n.id, n]));
        for (const e of g.edges) {
            if (e.from !== routeNode.id || !e.rel.startsWith('route:')) continue;
            const key = e.rel.slice('route:'.length);
            const db = byId.get(e.to)?.config?.['database'];
            if (db == null) continue;
            for (const b of rc['branches'] as Cfg[]) if (String(b['key']) === key) b['database'] = db;
        }
    }
    return rc;
}

function overlay(section: Cfg, key: string, value: unknown, strict: boolean): void {
    if (value != null) section[key] = value;
    else if (strict) delete section[key];
}
function setOrDel(section: Cfg, key: string, value: unknown): void {
    if (value != null) section[key] = value;
    else delete section[key];
}
