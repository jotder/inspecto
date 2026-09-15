import { describe, expect, it } from 'vitest';
import NODE_ATTRIBUTE_CONTRACT from 'app/inspecto/contracts/node-attributes.contract.json';
import { nodeAttributesFor, speccedNodeTypes } from './node-attributes';

/**
 * NODE-TYPE-MIRRORS-1 (2026-09-15): this used to diff a hand-typed TS table against the committed
 * contract. The table IS the contract now, so what is left to pin is the shape the dialog relies on —
 * every spec carries the four fields the schema form renders from — and that the module exposes every
 * contract type and nothing else.
 */
describe('node-attributes (the served contract, imported)', () => {
    const contract = NODE_ATTRIBUTE_CONTRACT as Record<string, unknown[]>;
    const types = Object.keys(contract).filter((k) => !k.startsWith('_'));

    it("exposes exactly the contract's node types", () => {
        expect(types.length).toBeGreaterThan(0);
        expect(speccedNodeTypes().sort()).toEqual(types.sort());
        expect(nodeAttributesFor(undefined)).toBeUndefined();
        expect(nodeAttributesFor('transform.split')).toBeUndefined();
    });

    it('every attribute carries key, label, type and tier', () => {
        for (const type of types) {
            const specs = nodeAttributesFor(type);
            expect(specs, type).toBeDefined();
            for (const spec of specs ?? []) {
                for (const field of ['key', 'label', 'type', 'tier'] as const) {
                    expect(spec[field], `${type}.${String(spec.key)} lacks ${field}`).toBeTruthy();
                }
            }
        }
    });
});
