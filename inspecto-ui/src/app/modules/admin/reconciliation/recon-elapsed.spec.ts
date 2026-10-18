import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ElapsedTimer } from './recon-elapsed';

describe('ElapsedTimer', () => {
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('counts whole seconds from start and stops counting on stop', () => {
        const t = new ElapsedTimer();
        t.start();
        vi.advanceTimersByTime(3000);
        expect(t.seconds()).toBe(3);
        t.stop();
        vi.advanceTimersByTime(5000);
        expect(t.seconds()).toBe(3);
    });

    it('restarts from zero', () => {
        const t = new ElapsedTimer();
        t.start();
        vi.advanceTimersByTime(2000);
        t.start();
        expect(t.seconds()).toBe(0);
        vi.advanceTimersByTime(1000);
        expect(t.seconds()).toBe(1);
        t.stop();
    });
});
