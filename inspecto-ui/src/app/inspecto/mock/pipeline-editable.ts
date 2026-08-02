/**
 * The mock's editable lift/lower — a faithful TS port of the backend `PipelineEditable` (W5). It
 * MUST refuse exactly what the server refuses (UNSUPPORTED_NODE / MULTI_SINK / the completeness set),
 * or the offline preview passes a topology the real backend 422s — the textbook "mock more lenient
 * than the server" hole this project has been bitten by before. Node config is the raw config-file
 * vocabulary end to end, so lift→lower is a verbatim map round-trip.
 */
import type { AuthoredEdge, AuthoredNode, AuthoredPipeline } from '../api/pipelines.service';

export interface Refusal {
    code: string;
    nodeId?: string;
    message: string;
}

const LOWERABLE = new Set([
    'acquisition', 'parser', 'gap', 'transform.dedup.marker', 'transform.dedup.fingerprint',
    'transform.filter', 'transform.map', 'sink.persistent', 'enrichment',
]);

type Cfg = Record<string, unknown>;
const asMap = (v: unknown): Cfg => (v && typeof v === 'object' && !Array.isArray(v) ? { ...(v as Cfg) } : {});
const isQuarantine = (n: AuthoredNode): boolean => !!n.config?.['dir'] && n.config?.['database'] == null;

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
        if (k === 'duplicate' || k === 'incremental' || k === 'gap_detection' || k === 'connection') continue;
        acqCfg[k] = v;
    }
    if (dirs['poll'] != null) acqCfg['poll'] = dirs['poll'];
    if (config['trigger'] != null) acqCfg['trigger'] = config['trigger'];
    if (processing['file_pattern'] != null) acqCfg['file_pattern'] = processing['file_pattern'];
    const acqUse = collector['connection'] ? `connection/${String(collector['connection'])}` : undefined;
    nodes.push({ id: 'acq', type: 'acquisition', name: 'Acquisition', use: acqUse, config: acqCfg });

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
    if (collector['duplicate'] != null || collector['incremental'] != null) {
        const c: Cfg = {};
        if (collector['duplicate'] != null) c['duplicate'] = collector['duplicate'];
        if (collector['incremental'] != null) c['incremental'] = collector['incremental'];
        nodes.push({ id: 'dedup_fingerprint', type: 'transform.dedup.fingerprint', name: 'Dedup (fingerprint)', config: c });
        edges.push({ from: upstream, rel: 'data', to: 'dedup_fingerprint' });
        upstream = 'dedup_fingerprint';
    }

    // parser
    const parserCfg: Cfg = {};
    for (const k of ['csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config']) {
        if (processing[k] != null) parserCfg[k] = processing[k];
    }
    nodes.push({ id: 'parse', type: 'parser', name: 'Parser', config: parserCfg });
    edges.push({ from: upstream, rel: 'data', to: 'parse' });

    // single persistent sink (the flat config's one primary output)
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
    nodes.push({ id: 'sink', type: 'sink.persistent', name: store, description: 'Persistent store', config: sinkCfg });
    edges.push({ from: 'parse', rel: 'data', to: 'sink' });

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
    let marker: AuthoredNode | undefined, fingerprint: AuthoredNode | undefined;
    let primarySink: AuthoredNode | undefined, quarantine: AuthoredNode | undefined;
    const filters: AuthoredNode[] = [];
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
        else if (n.type === 'transform.dedup.fingerprint') fingerprint = n;
        else if (n.type === 'transform.filter') filters.push(n);
        else if (n.type === 'sink.persistent') {
            if (isQuarantine(n)) quarantine = n;
            else {
                const db = n.config?.['database'];
                if (db != null && !destByDatabase.has(String(db))) destByDatabase.set(String(db), n);
                if (!primarySink || (primarySink.config?.['database'] == null && n.config?.['database'] != null)) primarySink = n;
            }
        }
    }
    // >1 distinct database is no longer a refusal — it lowers to a plural sinks: block (slice 4).
    // Row-routing can't reach here: a transform.route/derive node is not LOWERABLE (UNSUPPORTED_NODE above).

    if (strict) {
        if (!acq) refusals.push({ code: 'NO_ACQUISITION', message: 'an active pipeline needs an acquisition node' });
        if (!parser) refusals.push({ code: 'NO_PARSER', message: 'an active pipeline needs a parser node' });
        if (!primarySink || primarySink.config?.['database'] == null)
            refusals.push({ code: 'NO_PERSISTENT_SINK', message: 'an active pipeline needs a persistent sink with a database dir' });
        const schemaKeys = ['csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config'];
        if (parser && !schemaKeys.some((k) => parser!.config?.[k] != null))
            refusals.push({ code: 'PARSER_NO_SCHEMA', nodeId: parser.id, message: 'the parser names no schema_file / schemas / segments' });
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
        for (const k of Object.keys(collector)) if (!['duplicate', 'incremental', 'gap_detection'].includes(k)) delete collector[k];
        for (const [k, v] of Object.entries(acq.config ?? {})) if (!['poll', 'trigger', 'file_pattern'].includes(k)) collector[k] = v;
        delete collector['connection'];
        if (acq.use?.startsWith('connection/')) collector['connection'] = acq.use.slice('connection/'.length);
        setOrDel(dirs, 'poll', acq.config?.['poll']);
        setOrDel(out, 'trigger', acq.config?.['trigger']);
        setOrDel(processing, 'file_pattern', acq.config?.['file_pattern']);
    }
    overlay(collector, 'gap_detection', gap ? { enabled: true, ...(gap.config ?? {}) } : undefined, strict);
    overlay(collector, 'duplicate', fingerprint?.config?.['duplicate'], strict);
    overlay(collector, 'incremental', fingerprint?.config?.['incremental'], strict);

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

    if (parser) {
        for (const k of ['csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config']) delete processing[k];
        for (const k of ['csv_settings', 'schema_file', 'schemas', 'segments', 'ingester', 'ingester_config'])
            if (parser.config?.[k] != null) processing[k] = parser.config[k];
        if (filters.length) {
            const csv = asMap(processing['csv_settings']); processing['csv_settings'] = csv;
            for (const f of filters) Object.assign(csv, f.config ?? {});
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
    return { config: out };
}

function overlay(section: Cfg, key: string, value: unknown, strict: boolean): void {
    if (value != null) section[key] = value;
    else if (strict) delete section[key];
}
function setOrDel(section: Cfg, key: string, value: unknown): void {
    if (value != null) section[key] = value;
    else delete section[key];
}
