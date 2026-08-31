import { describe, expect, it } from 'vitest';

import ATTRIBUTE_CONTRACT from 'app/inspecto/contracts/attribute-spec.contract.json';

import { ATTRIBUTE_KEYS, ATTRIBUTE_TIERS, ATTRIBUTE_TYPES } from './attribute-spec';

const sorted = (xs: readonly string[]): string[] => [...xs].sort();

/**
 * The client half of the `AttributeSpec` vocabulary contract (BACKLOG §6, D6 residual).
 * `FindingsSpecContractTest` (Java) compares the same committed JSON against `FindingsSpec.TYPES`,
 * `TIERS` and the section keys its parser tolerates, so neither side can move alone.
 *
 * `AttributeSpec` is deliberately the canonical shape — a `findings-spec` is authored in it and served
 * verbatim. The cost is that the backend mirrors it by hand, and drift is silent in the worse direction:
 * a member added only here makes the server 422 a section this renderer could have drawn.
 */
describe('AttributeSpec vocabulary contract', () => {
    it('publishes exactly the disclosure tiers the backend accepts', () => {
        expect(sorted(ATTRIBUTE_TIERS)).toEqual(ATTRIBUTE_CONTRACT.tiers);
        // Pinned so a silently-emptied contract file cannot turn both assertions into no-ops.
        expect(ATTRIBUTE_CONTRACT.tiers).toEqual(['advanced', 'optional', 'required']);
    });

    it('publishes exactly the control types the backend accepts', () => {
        expect(sorted(ATTRIBUTE_TYPES)).toEqual(ATTRIBUTE_CONTRACT.types);
        expect(ATTRIBUTE_CONTRACT.types).toHaveLength(8);
    });

    /**
     * The exhaustiveness half. `ATTRIBUTE_KEYS` is compiler-checked against `keyof AttributeSpec`, so
     * this is what forces a NEW field to be a decision: either a findings section may author it
     * (`sectionKeys`, mirrored by `FindingsSpec.SECTION_KEYS`) or it is frontend-only — never neither,
     * which is how it would otherwise land as a 422 discovered by an author.
     */
    it('classifies every AttributeSpec field as authorable or frontend-only', () => {
        expect(sorted([...ATTRIBUTE_CONTRACT.sectionKeys, ...ATTRIBUTE_CONTRACT.frontendOnlyKeys])).toEqual(
            sorted(ATTRIBUTE_KEYS),
        );
    });

    it('keeps the two classes disjoint', () => {
        const overlap = ATTRIBUTE_CONTRACT.sectionKeys.filter((k) =>
            (ATTRIBUTE_CONTRACT.frontendOnlyKeys as string[]).includes(k),
        );
        expect(overlap).toEqual([]);
    });

    /** ⚠ The deliberate asymmetry, stated rather than implied — `group`, `secret` and `tab` are not
     *  authorable on a findings section, and the Java side asserts the parser really refuses them. */
    it('records group, secret and tab as the frontend-only keys', () => {
        expect(ATTRIBUTE_CONTRACT.frontendOnlyKeys).toEqual(['group', 'secret', 'tab']);
    });
});
