import { describe, expect, it } from 'vitest';
import { LayoutSuperseded, runSliced } from './graph-sliced-layout';

describe('runSliced (LA-PERF-N4-1)', () => {
    it('runs every step and yields between slices that used up their time', async () => {
        let steps = 0;
        let yields = 0;
        const busy = (): void => {
            const end = performance.now() + 2;
            while (performance.now() < end) {
                /* spin */
            }
            steps++;
        };
        await runSliced(30, busy, 5, () => {
            yields++;
            return Promise.resolve();
        });
        expect(steps).toBe(30);
        expect(yields).toBeGreaterThanOrEqual(5); // ~2 ms a step in 5 ms slices: never all 30 in one task
    });

    it('a run that fits one slice never yields', async () => {
        let yields = 0;
        await runSliced(
            10,
            () => undefined,
            1000,
            () => {
                yields++;
                return Promise.resolve();
            },
        );
        expect(yields).toBe(0);
    });

    it('a run that turns stale stops at the next slice with LayoutSuperseded', async () => {
        let steps = 0;
        let stale = false;
        const run = runSliced(
            100,
            () => {
                steps++;
                const end = performance.now() + 2;
                while (performance.now() < end) {
                    /* spin */
                }
            },
            5,
            () => {
                stale = true; // e.g. another layout was picked while this one ran
                return Promise.resolve();
            },
            () => stale,
        );
        await expect(run).rejects.toBeInstanceOf(LayoutSuperseded);
        expect(steps).toBeLessThan(100);
    });
});
