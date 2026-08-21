import { describe, expect, it } from 'vitest';
import { AuthoredNode } from 'app/inspecto/api';
import { AttributeSpec } from 'app/inspecto/component-model';
import { buildConfiguredNode, splitNodeConfig } from './node-config-build';

const spec = (key: string, over: Partial<AttributeSpec> = {}): AttributeSpec =>
    ({ key, label: key, type: 'string', tier: 'optional', ...over }) as AttributeSpec;

const node = (config: Record<string, unknown>, over: Partial<AuthoredNode> = {}): AuthoredNode =>
    ({ id: 'n1', type: 'transform.route', config, ...over }) as AuthoredNode;

/** Open the dialog and press Save without touching anything — config must come back unchanged. */
function roundTrip(cfg: Record<string, unknown>, specs: AttributeSpec[], isAcquisition = false) {
    const n = node(cfg);
    const split = splitNodeConfig(n, specs, isAcquisition);
    // The extra-config editor emits untouched entries as their ORIGINAL value reference — modelled
    // here by folding the split's typed rows straight back into a map.
    return buildConfiguredNode({
        node: n,
        specs,
        formValues: split.schemaInitial,
        extras: Object.fromEntries(split.extraRows.map((r) => [r.key, r.value])),
        isAcquisition,
        connector: isAcquisition ? 'local' : undefined,
    }).config;
}

describe('splitNodeConfig / buildConfiguredNode round-trip', () => {
    /**
     * ⚠ The regression this file exists for. A node's config is "the raw config-file section verbatim",
     * so an unmodelled key must survive a save. `transform.route`'s `branches` is a list of MAPS and
     * deliberately has no AttributeSpec (the `list` type is string[]), so it lands in the extra-config
     * editor — where it was once stringified on load and written back as a literal JSON STRING. Opening a
     * route node and pressing Save therefore replaced its routing with text, silently.
     */
    it('preserves an unmodelled list-of-maps instead of saving it as a JSON string', () => {
        const branches = [
            { key: 'premium', where: "tier = 'gold'" },
            { key: 'rest', where: '1=1' },
        ];
        const out = roundTrip({ mode: 'first-match', branches }, [spec('mode')]);
        expect(out!['branches']).toEqual(branches);
        expect(typeof out!['branches']).not.toBe('string');
    });

    it('preserves an unmodelled nested block', () => {
        const out = roundTrip({ retry: { attempts: 3, backoff: 'exp' } }, [spec('other')]);
        expect(out!['retry']).toEqual({ attempts: 3, backoff: 'exp' });
    });

    it('writes a typed extra literally — numbers and booleans stay typed', () => {
        const n = node({});
        const out = buildConfiguredNode({
            node: n,
            specs: [],
            formValues: null,
            extras: { note: 'plain text', retries: 3, enabled: false },
            isAcquisition: false,
        }).config;
        expect(out!['note']).toBe('plain text');
        expect(out!['retries']).toBe(3);
        expect(out!['enabled']).toBe(false);
    });

    /** An EDITED extra is the operator overriding it — the editor's typed value wins over the prior. */
    it('honours an edited extra rather than restoring the original', () => {
        const n = node({ branches: [{ key: 'a' }] });
        const out = buildConfiguredNode({
            node: n,
            specs: [],
            formValues: null,
            extras: { branches: [{ key: 'b', where: '1=1' }] },
            isAcquisition: false,
        }).config;
        expect(out!['branches']).toEqual([{ key: 'b', where: '1=1' }]);
    });

    it('seeds schema-known keys from the flattened config, nested ones included', () => {
        const specs = [spec('mode'), spec('batch__max_files', { type: 'number' })];
        const split = splitNodeConfig(node({ mode: 'first-match', batch: { max_files: 10 } }), specs, false);
        expect(split.schemaInitial).toEqual({ mode: 'first-match', batch__max_files: 10 });
        // A root the schema owns via a leaf must NOT also appear as a free-form row (D4).
        expect(split.extraRows.map((r) => r.key)).not.toContain('batch');
    });

    it('keeps a sub-key the schema does not model when a sibling IS modelled', () => {
        const specs = [spec('duplicate__mode')];
        const out = roundTrip({ duplicate: { mode: 'hash', algorithm: 'sha256' } }, specs);
        expect(out!['duplicate']).toEqual({ mode: 'hash', algorithm: 'sha256' });
    });

    describe('acquisition binding', () => {
        it('reads the Connection off use: and writes it back there, never into config', () => {
            const n = node({ discovery: 'poll' }, { use: 'connection/prod_sftp', type: 'acquisition' });
            const specs = [spec('connection'), spec('discovery')];
            const split = splitNodeConfig(n, specs, true);
            expect(split.schemaInitial['connection']).toBe('prod_sftp');

            const built = buildConfiguredNode({
                node: n,
                specs,
                formValues: split.schemaInitial,
                extras: Object.fromEntries(split.extraRows.map((r) => [r.key, r.value])),
                isAcquisition: true,
                connector: 'sftp',
            });
            expect(built.use).toBe('connection/prod_sftp');
            expect(built.config!['connection']).toBeUndefined();
            expect(built.config!['connector']).toBe('sftp'); // derived, written last
        });

        it('clearing the Connection clears the binding', () => {
            const n = node({}, { use: 'connection/prod_sftp', type: 'acquisition' });
            const built = buildConfiguredNode({
                node: n,
                specs: [spec('connection')],
                formValues: { connection: '' },
                extras: {},
                isAcquisition: true,
                connector: 'local',
            });
            expect(built.use).toBeUndefined();
        });
    });
});
