import { describe, expect, it } from 'vitest';
import { SCREEN_LOCK_FALLBACK, screenLock, screenLocks } from './edition-locks';

describe('edition-locks (DATA-TIERS-1 P2 screen gates)', () => {
    it('locks a flag /bootstrap reports false, with the server line, else the fallback', () => {
        expect(screenLock('pipelineEgress', { pipelineEgress: false }, { pipelineEgress: 'server says' })).toBe('server says');
        expect(screenLock('pipelineEgress', { pipelineEgress: false }, {})).toBe(SCREEN_LOCK_FALLBACK.pipelineEgress);
    });

    it('offers a flag reported true, or not reported at all (the console ships with its server)', () => {
        expect(screenLock('remoteSystems', { remoteSystems: true }, { remoteSystems: 'stale' })).toBeNull();
        expect(screenLock('remoteSystems', {}, {})).toBeNull();
    });

    it('lists every withheld screen flag - a Basic /bootstrap locks all four, a Standard one none', () => {
        const basic = { pipelineAutomation: false, pipelineTransform: false, pipelineEgress: false, remoteSystems: false };
        expect(Object.keys(screenLocks(basic, {})).sort()).toEqual(
            ['pipelineAutomation', 'pipelineEgress', 'pipelineTransform', 'remoteSystems'],
        );
        const standard = { pipelineAutomation: true, pipelineTransform: true, pipelineEgress: true, remoteSystems: true };
        expect(screenLocks(standard, {})).toEqual({});
        // a route feature's false flag (geoMap) is not a screen gate of this list
        expect(screenLocks({ geoMap: false }, { geoMap: 'x' })).toEqual({});
    });
});
