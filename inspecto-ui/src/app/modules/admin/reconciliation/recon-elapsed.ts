import { signal } from '@angular/core';

/**
 * Whole seconds since a long request started — the "still working" cue shown beside a loading skeleton (RECON-UPLIFT-1
 * U4). The caller starts it when a request begins and stops it when the answer lands or the host is destroyed.
 */
export class ElapsedTimer {
    readonly seconds = signal(0);
    private handle: ReturnType<typeof setInterval> | undefined;

    start(): void {
        this.stop();
        this.seconds.set(0);
        this.handle = setInterval(() => this.seconds.update((s) => s + 1), 1000);
    }

    stop(): void {
        if (this.handle !== undefined) clearInterval(this.handle);
        this.handle = undefined;
    }
}
