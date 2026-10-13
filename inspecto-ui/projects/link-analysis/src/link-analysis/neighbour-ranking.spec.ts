import { describe, expect, it } from 'vitest';
import { STRONGEST_NEIGHBOURS } from './neighbour-ranking';

describe('Neighbour list constants (LA-EXPAND-PICK-NEIGHBOURS-1)', () => {
    it('adds the ten strongest', () => {
        expect(STRONGEST_NEIGHBOURS).toBe(10);
    });
});
