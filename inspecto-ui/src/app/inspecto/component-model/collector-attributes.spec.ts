import { describe, expect, it } from 'vitest';
import NODE_ATTRIBUTE_CONTRACT from 'app/inspecto/contracts/node-attributes.contract.json';

import { COLLECTOR_ATTRIBUTES, MARKER_DEDUP_ATTRIBUTES, UNPACK_ATTRIBUTES } from './collector-attributes';

/**
 * `collector-attributes.ts` is a HAND-KEPT copy of `NodeAttributes.COLLECTOR` / `MARKER_DEDUP` / `UNPACK`
 * (Java), which the served acquisition spec concatenates in that order (then `TRIGGER`). A hand-kept
 * mirror drifts silently, so this pins it to the committed contract the Java side regenerates.
 */
describe('collector-attributes (mirror of the served acquisition spec)', () => {
    const served = NODE_ATTRIBUTE_CONTRACT['acquisition'] as unknown[];

    it('COLLECTOR_ATTRIBUTES, then marker dedup, then unpack, equal the served acquisition table', () => {
        const mirrored = [...COLLECTOR_ATTRIBUTES, ...MARKER_DEDUP_ATTRIBUTES, ...UNPACK_ATTRIBUTES];
        expect(mirrored).toEqual(served.slice(0, mirrored.length));
    });

    it('carries the Collector retry and circuit-breaker keys (G5, 2026-09-23)', () => {
        expect(COLLECTOR_ATTRIBUTES.map((a) => a.key)).toEqual(
            expect.arrayContaining([
                'retry__count',
                'retry__backoff',
                'retry__initial_delay',
                'retry__max_delay',
                'circuit_breaker__failure_threshold',
                'circuit_breaker__cooldown',
            ]),
        );
    });
});
