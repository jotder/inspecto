import { describe, expect, it } from 'vitest';
import {
    STREAM_BUNDLE_FORMAT,
    buildStreamBundle,
    parseStreamBundle,
    planStreamImport,
    streamBundleFileName,
} from './stream-bundle';

const PIPELINE = {
    name: 'orders_feed',
    active: true,
    description: 'Nightly orders',
    dirs: {
        poll: 'spaces/demo/data/inbox/orders_feed',
        database: 'spaces/demo/data/orders_feed/database',
        status_dir: 'spaces/demo/data/orders_feed/status',
    },
    processing: {
        threads: 1,
        schema_file: 'spaces/demo/config/orders_feed_schema.toon',
        duplicate_check: { enabled: true },
    },
    parsing: { delimited: { delimiter: ',', has_header: true } },
    collector: { discovery: 'poll', connector: 'sftp', connection: 'prod_sftp' },
};

const SCHEMA = {
    partitionKey: 'ORDER_DATE',
    raw: { name: 'orders_feed_schema', format: 'CSV', fields: [{ name: 'ORDER_ID', selector: '0', type: 'VARCHAR' }] },
};

const build = (over: Record<string, unknown> = {}) =>
    buildStreamBundle(
        {
            name: 'orders_feed',
            space: 'demo',
            kind: 'stream',
            pipeline: PIPELINE as Record<string, unknown>,
            schema: SCHEMA,
            ...over,
        },
        new Date('2026-07-31T10:00:00Z'),
    );

describe('stream-bundle — export', () => {
    it('drops the keys that identify or locate the artifact', () => {
        const b = build();
        expect(b.pipeline['name']).toBeUndefined();
        expect(b.pipeline['active']).toBeUndefined();
        expect(b.pipeline['dirs']).toBeUndefined();
        // …but keeps the portable configuration.
        expect(b.pipeline['description']).toBe('Nightly orders');
        expect(b.pipeline['parsing']).toEqual(PIPELINE.parsing);
    });

    it('strips the satellite PATH but carries its content', () => {
        const b = build();
        expect((b.pipeline['processing'] as Record<string, unknown>)['schema_file']).toBeUndefined();
        expect((b.pipeline['processing'] as Record<string, unknown>)['threads']).toBe(1);
        expect(b.schema).toEqual(SCHEMA);
    });

    it('does not mutate the caller’s config', () => {
        const original = JSON.parse(JSON.stringify(PIPELINE));
        build();
        expect(PIPELINE).toEqual(original);
    });

    it('reports a referenced Connection as a requirement rather than exporting it', () => {
        const b = build();
        expect(b.requires).toHaveLength(1);
        expect(b.requires[0]).toMatchObject({ kind: 'connection', id: 'prod_sftp' });
        expect(b.requires[0].reason).toContain('credentials');
    });

    it('has no requirements when collecting from a local inbox', () => {
        const local = { ...PIPELINE, collector: { discovery: 'poll', connector: 'local' } };
        expect(build({ pipeline: local }).requires).toEqual([]);
    });

    it('masks a LITERAL secret but leaves an ${ENV:…} reference alone', () => {
        const withSecrets = {
            ...PIPELINE,
            collector: { connector: 'sftp', password: 'hunter2', token: '${ENV:SFTP_TOKEN}' },
        };
        const b = build({ pipeline: withSecrets });
        const collector = b.pipeline['collector'] as Record<string, unknown>;
        expect(collector['password']).toBe('***');
        expect(collector['token']).toBe('${ENV:SFTP_TOKEN}'); // a pointer, safe to travel
        expect(b.masked).toEqual(['collector.password']);
    });

    it('carries per-segment schemas for a plugin parser', () => {
        const plugin = {
            ...PIPELINE,
            parsing: {
                frontend: 'plugin',
                plugin: {
                    ingester: 'com.gamma.ingester.Asn1RecordIngester',
                    segments: { moCallRecord: 'spaces/demo/config/orders_feed_moCallRecord.toon' },
                    ingester_config: { root_type: 'Record' },
                },
            },
        };
        const segs = { moCallRecord: { raw: { name: 'orders_feed_moCallRecord', fields: [] } } };
        const b = build({ pipeline: plugin, segments: segs });
        const built = (b.pipeline['parsing'] as Record<string, unknown>)['plugin'] as Record<string, unknown>;
        expect(built['segments']).toBeUndefined(); // path stripped
        expect(built['ingester_config']).toEqual({ root_type: 'Record' }); // inline config kept
        expect(b.segments).toEqual(segs);
    });

    it('records provenance so an import can show where it came from', () => {
        const b = build();
        expect(b.format).toBe(STREAM_BUNDLE_FORMAT);
        expect(b.source.space).toBe('demo');
        expect(b.source.name).toBe('orders_feed');
        expect(b.source.contentHash).toMatch(/^[0-9a-f]{64}$/);
        expect(b.exportedAt).toBe('2026-07-31T10:00:00.000Z');
    });
});

describe('stream-bundle — parse', () => {
    it('rejects non-JSON and non-bundles', () => {
        expect(parseStreamBundle('not json').errors[0]).toContain('valid JSON');
        expect(parseStreamBundle('{"format":"nope"}').errors[0]).toContain(STREAM_BUNDLE_FORMAT);
    });

    it('names the neighbouring format when a metadata bundle is picked by mistake', () => {
        const errs = parseStreamBundle('{"format":"inspecto-metadata-bundle","version":2,"items":[]}').errors;
        expect(errs[0]).toContain('metadata bundle');
        expect(errs[0]).toContain('Studio');
    });

    it('rejects a future version rather than guessing', () => {
        const text = JSON.stringify({ ...build(), version: 99 });
        expect(parseStreamBundle(text).errors[0]).toContain('Unsupported version');
    });

    it('round-trips a built bundle', () => {
        const { bundle, errors } = parseStreamBundle(JSON.stringify(build()));
        expect(errors).toEqual([]);
        expect(bundle?.source.name).toBe('orders_feed');
    });
});

describe('stream-bundle — import plan', () => {
    const planFor = (name = 'orders_copy', space: string | null = 'prod') => planStreamImport(build(), { name, space });

    it('names the target, forces a draft, and re-derives every directory', () => {
        const p = planFor();
        expect(p.pipeline['name']).toBe('orders_copy');
        expect(p.pipeline['active']).toBe(false); // NEVER import as live
        const dirs = p.pipeline['dirs'] as Record<string, string>;
        expect(dirs['poll']).toBe('spaces/prod/data/inbox/orders_copy');
        expect(dirs['database']).toBe('spaces/prod/data/orders_copy/database');
        expect(dirs['status_dir']).toBe('spaces/prod/data/orders_copy/status');
        expect(p.notes.join(' ')).toContain('re-derived');
        expect(p.notes.join(' ')).toContain('inactive draft');
    });

    it('rewires the schema path to the target convention and retargets raw.name', () => {
        const p = planFor();
        expect(p.schema?.name).toBe('orders_copy_schema');
        expect((p.schema?.config['raw'] as Record<string, unknown>)['name']).toBe('orders_copy_schema');
        expect((p.pipeline['processing'] as Record<string, unknown>)['schema_file']).toBe(
            'spaces/prod/config/orders_copy_schema.toon',
        );
    });

    it('handles a single-space (no space id) target', () => {
        const p = planFor('orders_copy', null);
        expect((p.pipeline['dirs'] as Record<string, string>)['poll']).toBe('./data/inbox/orders_copy');
        expect((p.pipeline['processing'] as Record<string, unknown>)['schema_file']).toBe(
            './config/orders_copy_schema.toon',
        );
    });

    it('rewires every segment path and sanitizes the derived names', () => {
        const plugin = {
            ...PIPELINE,
            parsing: { frontend: 'plugin', plugin: { ingester: 'X', segments: { 'mo-call': 'old/path.toon' } } },
        };
        const bundle = build({ pipeline: plugin, segments: { 'mo-call': { raw: { name: 'old' } } } });
        const p = planStreamImport(bundle, { name: 'cdr', space: 'prod' });
        expect(p.segments).toHaveLength(1);
        expect(p.segments[0].name).toBe('cdr_mo_call'); // '-' is not identifier-safe
        const built = (p.pipeline['parsing'] as Record<string, unknown>)['plugin'] as Record<string, unknown>;
        expect(built['segments']).toEqual({ 'mo-call': 'spaces/prod/config/cdr_mo_call.toon' });
    });

    it('carries requirements and masked-secret warnings into the plan notes', () => {
        const p = planFor();
        expect(p.requires[0].id).toBe('prod_sftp');
        expect(p.notes.join(' ')).toContain('1 existing item');
    });

    it('warns about masked values so the operator knows to re-enter them', () => {
        const b = build({ pipeline: { ...PIPELINE, collector: { password: 'literal' } } });
        const p = planStreamImport(b, { name: 'x', space: null });
        expect(p.notes.join(' ')).toContain('masked');
    });

    it('names the enrichment companion by the target convention', () => {
        const b = build({ enrichment: { joins: [] } });
        expect(planStreamImport(b, { name: 'orders_copy', space: 'prod' }).enrichment?.name).toBe('orders_copy_enrich');
    });

    // REGRESSION (live round-trip, 2026-07-31): `ConfigService.write` derives the target file from
    // the config's OWN identity field. Labelling the plan was not enough — the enrichment was
    // written back to the SOURCE's `<source>_enrich`, clobbering an unrelated config and leaving
    // the imported stream with none.
    it('writes each satellite under the TARGET identity, inside the config body', () => {
        const b = build({ enrichment: { name: 'orders_feed_enrich', joins: [] } });
        const p = planStreamImport(b, { name: 'orders_copy', space: 'prod' });
        expect(p.enrichment?.config['name']).toBe('orders_copy_enrich'); // body, not just the label
        expect(p.schema?.config['raw']).toMatchObject({ name: 'orders_copy_schema' });
        expect(p.enrichment?.name).toBe(p.enrichment?.config['name']); // label agrees with body
    });

    it('re-points the enrichment at the imported stream, not the source it came from', () => {
        const b = build({
            enrichment: {
                name: 'orders_feed_enrich',
                input: { database: 'spaces/demo/data/orders_feed/database', format: 'PARQUET' },
                output: { database: 'spaces/demo/data/enriched/orders_feed_enrich', format: 'PARQUET' },
                transform: 'SELECT * FROM input',
                triggers: { on_pipeline: 'orders_feed' },
            },
        });
        const e = planStreamImport(b, { name: 'orders_copy', space: 'prod' }).enrichment!.config;
        expect((e['input'] as Record<string, unknown>)['database']).toBe('spaces/prod/data/orders_copy/database');
        expect((e['output'] as Record<string, unknown>)['database']).toBe(
            'spaces/prod/data/enriched/orders_copy_enrich',
        );
        expect((e['triggers'] as Record<string, unknown>)['on_pipeline']).toBe('orders_copy');
        expect(e['transform']).toBe('SELECT * FROM input'); // the author's logic is untouched
        expect((e['input'] as Record<string, unknown>)['format']).toBe('PARQUET');
    });

    it('omits satellites the source never had', () => {
        const bare = buildStreamBundle({
            name: 'a',
            space: null,
            kind: 'stream',
            pipeline: { name: 'a', processing: { threads: 1 } },
        });
        const p = planStreamImport(bare, { name: 'b', space: null });
        expect(p.schema).toBeUndefined();
        expect(p.segments).toEqual([]);
        expect(p.enrichment).toBeUndefined();
        expect((p.pipeline['processing'] as Record<string, unknown>)['schema_file']).toBeUndefined();
    });
});

describe('streamBundleFileName', () => {
    it('is filesystem-safe and carries the stream name', () => {
        const f = streamBundleFileName('orders_feed', new Date('2026-07-31T10:20:30Z'));
        expect(f).toBe('inspecto-stream-orders_feed-2026-07-31-10-20-30.json');
        expect(f).not.toMatch(/[:]/);
    });
});
