// Deep import on purpose: the segments barrel also exports the editor COMPONENT, and this codec is
// pure — it must not drag an Angular component into whatever imports it.
import { companionSchemaName, portableConfigRef } from '../segments/segment-drafts';
import { hashContent } from './content-hash';

/**
 * Stream configuration transfer — export one onboarded Stream/Reference to a portable JSON file and
 * re-create it elsewhere. Pure and framework-free so the format, the portability rewrites and the
 * import plan are unit-testable (`stream-bundle.spec.ts`).
 *
 * ## Why this is NOT a {@link ../transfer/bundle.ts MetadataBundle} kind
 *
 * The Metadata Bundle carries **Studio component-registry** artifacts (`/components/{type}/{id}`),
 * addressed by **id**. An onboarded Stream lives in the **config** namespace
 * (`ConfigService`, `/config/...`), addressed by **path**, and the two stores collide on the word
 * *schema*: `BundleKind` already has `'schema'` meaning the registry component, while a Stream's
 * schema is a `config`-type TOON at `<base>/config/<pipeline>_schema.toon`. Filing Stream schemas
 * under the existing kind would import them into the wrong store. On top of that, a Stream's
 * satellite references may be **paths that embed the source space** (`spaces/demo/config/...`), so
 * they must be REWRITTEN for the target — something the bundle's id-based `BundleRef` model cannot
 * express. (Since W3 an import writes the PORTABLE bare `<name>.toon` instead, which needs no
 * rewriting at all; the rewrite stays because a bundle exported before W3 still carries long paths.) Hence a sibling format that reuses the proven primitives (`hashContent`, the
 * parse/validate shape, the object-URL download idiom) without contorting `BundleKind`.
 *
 * ## What travels, and what deliberately does not
 *
 * Travels: the pipeline body (parsing/processing/collector/output/…), the main schema, every
 * per-segment schema of a plugin parser, and the `<name>_enrich` sibling.
 * Does NOT travel: `name` (the target names its own), `active` (an import is ALWAYS a draft —
 * importing something as live would start processing on someone else's server), and `dirs` (every
 * path is space- and name-specific; they are re-derived from the target's convention, exactly as the
 * create dialog derives them). A Connection referenced by `collector.connection` cannot travel
 * either — it carries credentials — so it is reported as a **requirement** instead.
 */

export const STREAM_BUNDLE_FORMAT = 'inspecto-stream-config';
export const STREAM_BUNDLE_VERSION = 1;

/** Config keys removed at export because they identify or locate the artifact, not configure it. */
const NON_PORTABLE_KEYS = ['name', 'active', 'dirs'] as const;

/** Something the target instance must already provide — the bundle's contract with it. */
export interface StreamRequirement {
    kind: 'connection';
    id: string;
    /** Why it is needed, in words the operator can act on. */
    reason: string;
}

export interface StreamBundle {
    format: typeof STREAM_BUNDLE_FORMAT;
    version: number;
    exportedAt: string;
    /** Where this came from — for audit and for the import preview's "exported from" line. */
    source: { space: string | null; name: string; contentHash: string };
    kind: 'stream' | 'reference';
    /** The pipeline body, minus {@link NON_PORTABLE_KEYS}, with satellite paths stripped. */
    pipeline: Record<string, unknown>;
    /** `processing.schema_file`'s content, when the Schema stage authored one. */
    schema?: Record<string, unknown>;
    /** Per-segment schema contents for an ingestable plugin parser, keyed by segment key. */
    segments?: Record<string, Record<string, unknown>>;
    /** The `<name>_enrich` companion, when authored (Streams only). */
    enrichment?: Record<string, unknown>;
    requires: StreamRequirement[];
    /** Dotted paths whose literal secret-looking values were replaced with `***` at export. */
    masked?: string[];
}

export interface BuildStreamBundleInput {
    name: string;
    space: string | null;
    kind: 'stream' | 'reference';
    pipeline: Record<string, unknown>;
    schema?: Record<string, unknown> | null;
    segments?: Record<string, Record<string, unknown>> | null;
    enrichment?: Record<string, unknown> | null;
}

const isRecord = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Secret-ish key names. A config should only ever hold `${ENV:…}` references, so a LITERAL here is
 *  an authoring mistake — mask it rather than write a credential into a file that leaves the host. */
const SECRET_KEY_RE = /pass(word)?|secret|token|api_?key|credential/i;

function maskSecrets(node: unknown, path: string, masked: string[]): unknown {
    if (Array.isArray(node)) return node.map((v, i) => maskSecrets(v, `${path}[${i}]`, masked));
    if (!isRecord(node)) return node;
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(node)) {
        const here = path ? `${path}.${k}` : k;
        // A `${ENV:…}`/`${…}` reference is not a secret — it is a pointer, and safe to travel.
        if (SECRET_KEY_RE.test(k) && typeof v === 'string' && v !== '' && !v.startsWith('${')) {
            masked.push(here);
            out[k] = '***';
        } else {
            out[k] = maskSecrets(v, here, masked);
        }
    }
    return out;
}

/** Drop the satellite PATH references — import recomputes them for the target's own convention. */
function stripSatellitePaths(pipeline: Record<string, unknown>): Record<string, unknown> {
    const out: Record<string, unknown> = { ...pipeline };
    if (isRecord(out['processing'])) {
        const { schema_file: _dropped, ...rest } = out['processing'];
        out['processing'] = rest;
    }
    if (isRecord(out['parsing']) && isRecord(out['parsing']['plugin'])) {
        const { segments: _dropped, ...restPlugin } = out['parsing']['plugin'];
        out['parsing'] = { ...out['parsing'], plugin: restPlugin };
    }
    return out;
}

function requirementsOf(pipeline: Record<string, unknown>): StreamRequirement[] {
    const collector = pipeline['collector'];
    const id = isRecord(collector) ? String(collector['connection'] ?? '').trim() : '';
    return id
        ? [
              {
                  kind: 'connection',
                  id,
                  reason:
                      'The Collection stage collects through this saved Connection. Connections carry ' +
                      'credentials, so it does not travel in the export — create it on the target first.',
              },
          ]
        : [];
}

export function buildStreamBundle(input: BuildStreamBundleInput, now: Date = new Date()): StreamBundle {
    const masked: string[] = [];
    const body = stripSatellitePaths(input.pipeline);
    for (const k of NON_PORTABLE_KEYS) delete body[k];
    const pipeline = maskSecrets(body, '', masked) as Record<string, unknown>;

    const bundle: StreamBundle = {
        format: STREAM_BUNDLE_FORMAT,
        version: STREAM_BUNDLE_VERSION,
        exportedAt: now.toISOString(),
        source: { space: input.space, name: input.name, contentHash: hashContent(input.pipeline) },
        kind: input.kind,
        pipeline,
        requires: requirementsOf(input.pipeline),
    };
    if (input.schema) bundle.schema = maskSecrets(input.schema, 'schema', masked) as Record<string, unknown>;
    if (input.segments && Object.keys(input.segments).length) bundle.segments = input.segments;
    if (input.enrichment) bundle.enrichment = input.enrichment;
    if (masked.length) bundle.masked = masked;
    return bundle;
}

/** Parse + validate an uploaded stream-config file. Returns the bundle or human-readable errors. */
export function parseStreamBundle(text: string): { bundle?: StreamBundle; errors: string[] } {
    let raw: unknown;
    try {
        raw = JSON.parse(text);
    } catch {
        return { errors: ['Not valid JSON.'] };
    }
    const b = raw as Partial<StreamBundle>;
    const errors: string[] = [];
    if (b?.format !== STREAM_BUNDLE_FORMAT) {
        // Name the neighbouring format explicitly — exporting a metadata bundle here is the likely
        // mistake, and "wrong format" alone would not tell the operator which file to pick instead.
        errors.push(
            (b as { format?: string })?.format === 'inspecto-metadata-bundle'
                ? 'That is a metadata bundle (Studio components), not a Stream configuration. Import it from Studio instead.'
                : `Not an Inspecto Stream configuration (format must be "${STREAM_BUNDLE_FORMAT}").`,
        );
    }
    if (typeof b?.version !== 'number' || b.version > STREAM_BUNDLE_VERSION) {
        errors.push(`Unsupported version "${b?.version}" — this build reads up to ${STREAM_BUNDLE_VERSION}.`);
    }
    if (errors.length) return { errors };
    if (!isRecord(b.pipeline)) errors.push('Missing "pipeline" configuration.');
    if (b.kind !== 'stream' && b.kind !== 'reference') errors.push(`Unknown kind "${b.kind}".`);
    return errors.length ? { errors } : { bundle: b as StreamBundle, errors: [] };
}

export interface StreamImportWrite {
    /** Config name (the `read`/`write` identity), not a path. */
    name: string;
    config: Record<string, unknown>;
}

export interface StreamImportPlan {
    /** Ready to `write('pipeline', …)` — named, drafted, dirs re-derived, satellite paths rewired. */
    pipeline: Record<string, unknown>;
    schema?: StreamImportWrite;
    /** One per segment, in the bundle's own key order. */
    segments: StreamImportWrite[];
    enrichment?: StreamImportWrite;
    /** Operator-facing consequences of the rewrites — shown BEFORE any write happens. */
    notes: string[];
    requires: StreamRequirement[];
}


/**
 * Turn a parsed bundle into the exact set of writes for THIS target, under a (possibly new) name.
 * Pure — the caller performs the writes. Every rewrite that changes behaviour is reported in
 * `notes`, because an import that silently re-pointed directories would be a trap.
 */
export function planStreamImport(bundle: StreamBundle, opts: { name: string; space: string | null }): StreamImportPlan {
    const { name, space } = opts;
    const pipeline: Record<string, unknown> = { ...bundle.pipeline, name, active: false };
    const notes: string[] = [];

    // dirs are always re-derived: the source paths name the source space and the source's own
    // pipeline name, so carrying them over would point the draft at directories that do not exist.
    const home = `${space ? `spaces/${space}` : '.'}/data/${name}`;
    pipeline['dirs'] = {
        poll: `${space ? `spaces/${space}` : '.'}/data/inbox/${name}`,
        database: `${home}/database`,
        backup: `${home}/backup`,
        temp: `${home}/temp`,
        errors: `${home}/errors`,
        quarantine: `${home}/quarantine`,
        markers: `${home}/markers`,
        status_dir: `${home}/status`,
        log_dir: `${home}/logs`,
    };
    notes.push(`Directories are re-derived for "${name}" in this space — the exported paths pointed at the source.`);
    notes.push('Imported as an inactive draft — review the stages, then go live when you are ready.');

    const plan: StreamImportPlan = { pipeline, segments: [], notes, requires: bundle.requires ?? [] };

    if (bundle.schema) {
        const schemaName = `${name}_schema`;
        plan.schema = { name: schemaName, config: { ...bundle.schema, raw: renameRaw(bundle.schema, schemaName) } };
        const processing = isRecord(pipeline['processing']) ? { ...pipeline['processing'] } : {};
        processing['schema_file'] = portableConfigRef(schemaName);
        pipeline['processing'] = processing;
    }

    if (bundle.segments && Object.keys(bundle.segments).length) {
        const paths: Record<string, string> = {};
        for (const [key, config] of Object.entries(bundle.segments)) {
            const segName = companionSchemaName(name, key);
            plan.segments.push({ name: segName, config: { ...config, raw: renameRaw(config, segName) } });
            paths[key] = portableConfigRef(segName);
        }
        const parsing = isRecord(pipeline['parsing']) ? { ...pipeline['parsing'] } : {};
        const plugin = isRecord(parsing['plugin']) ? { ...parsing['plugin'] } : {};
        plugin['segments'] = paths;
        parsing['plugin'] = plugin;
        pipeline['parsing'] = parsing;
    }

    if (bundle.enrichment) {
        plan.enrichment = {
            name: `${name}_enrich`,
            config: retargetEnrichment(bundle.enrichment, bundle.source.name, name, space),
        };
    }

    if (plan.requires.length) {
        notes.push(
            `Needs ${plan.requires.length} existing item on this server (see below) — the draft saves either way.`,
        );
    }
    if (bundle.masked?.length) {
        notes.push(
            `${bundle.masked.length} secret-looking value(s) were masked at export and import as "***" — re-enter them.`,
        );
    }
    return plan;
}

/**
 * ⚠ **A config's OWN identity field decides the file it is written to** — `ConfigService.write`
 * derives the name from the content, it is not passed separately. So every satellite must be
 * retargeted INSIDE its body, not merely labelled in the plan. A schema self-names via `raw.name`;
 * an enrichment via top-level `name`. Getting this wrong is not a cosmetic slip: an import once
 * wrote the enrichment back to the SOURCE's `<source>_enrich` file — clobbering an unrelated config
 * and leaving the imported stream with no enrichment at all (found in the live round-trip,
 * 2026-07-31; pinned by "writes each satellite under the TARGET's identity").
 */
function renameRaw(schema: Record<string, unknown>, name: string): unknown {
    const raw = schema['raw'];
    return isRecord(raw) ? { ...raw, name } : raw;
}

/** Re-root a space-relative config path into the target space, keeping everything after the root. */
function reRoot(path: string, base: string): string {
    const relative = path.replace(/^spaces\/[^/]+\//, '').replace(/^\.\//, '');
    return `${base}/${relative}`;
}

/**
 * Point an enrichment companion at the imported stream instead of the source one: its identity, the
 * pipeline it triggers on, and the data directories it reads/writes. Without this the imported
 * enrichment would silently read the SOURCE stream's output.
 */
function retargetEnrichment(
    enrichment: Record<string, unknown>,
    sourceName: string,
    name: string,
    space: string | null,
): Record<string, unknown> {
    const base = space ? `spaces/${space}` : '.';
    const out: Record<string, unknown> = { ...enrichment, name: `${name}_enrich` };
    if (isRecord(out['input'])) {
        out['input'] = { ...out['input'], database: `${base}/data/${name}/database` };
    }
    if (isRecord(out['output'])) {
        const dbPath = String(out['output']['database'] ?? '');
        out['output'] = {
            ...out['output'],
            // Keep the author's own intermediate layout (…/data/enriched/…), but re-root it in the
            // TARGET space and re-point the stream-specific leaf. Swapping only the leaf would
            // leave the path inside the SOURCE space — an imported enrichment writing into someone
            // else's space (caught by spec, 2026-07-31).
            database: dbPath
                ? reRoot(dbPath, base).split(`${sourceName}_enrich`).join(`${name}_enrich`)
                : `${base}/data/enriched/${name}_enrich`,
        };
    }
    if (isRecord(out['triggers']) && out['triggers']['on_pipeline'] !== undefined) {
        out['triggers'] = { ...out['triggers'], on_pipeline: name };
    }
    return out;
}

/** Suggested file name for a downloaded stream config. */
export function streamBundleFileName(name: string, now: Date = new Date()): string {
    const stamp = now.toISOString().slice(0, 19).replace(/[:T]/g, '-');
    return `inspecto-stream-${name}-${stamp}.json`;
}
