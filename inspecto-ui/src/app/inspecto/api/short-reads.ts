import { Injectable } from '@angular/core';
import { defer, Observable, shareReplay, tap } from 'rxjs';

/** How long a finished read is still served, in ms: one landing, not a data cache. */
const FRESH_MS = 2000;

interface Entry {
    obs: Observable<unknown>;
    identity: string;
    done: boolean;
    at: number;
}

/**
 * N5: de-duplicates identical GETs that several services fire at the same moment (the shell's Space switcher and the
 * landing both refresh the Spaces; the Space guide and My work both list the Datasets). One call per key is in flight
 * and its answer is replayed for {@link FRESH_MS} after it lands.
 *
 * Security: an answer is only ever replayed under the identity it was read in. The identity is the access token plus
 * the active Space id, both read when a read starts; a change of either empties the cache first, so another signed-in
 * user, a sign-out or a Space switch never sees an earlier answer. A failed read is never kept. Writers call
 * {@link clear}.
 */
@Injectable({ providedIn: 'root' })
export class ShortReads {
    private readonly entries = new Map<string, Entry>();
    private lastUser = '';
    private user: () => string = () => '';
    private space: () => string = () => '';

    /** The signed-in user (the access token); bound by `SessionService`. */
    bindUser(user: () => string): void {
        this.user = user;
    }

    /** The active Space id; bound by `SpacesService`. */
    bindSpace(space: () => string): void {
        this.space = space;
    }

    /**
     * `make()` runs at most once per `key` while an answer is in flight or fresh; later callers share it. The answer
     * is bound to the user and, unless `bySpace` is false (the URL names its own Space, or is server-global), to the
     * active Space too.
     */
    read<T>(key: string, make: () => Observable<T>, bySpace = true): Observable<T> {
        return defer(() => {
            const user = this.user();
            if (user !== this.lastUser) {
                this.entries.clear();
                this.lastUser = user;
            }
            const identity = `${user}\u0000${bySpace ? this.space() : ''}`;
            const hit = this.entries.get(key);
            if (hit && hit.identity === identity && (!hit.done || Date.now() - hit.at < FRESH_MS)) {
                return hit.obs as Observable<T>;
            }
            const entry: Entry = { obs: undefined as unknown as Observable<unknown>, identity, done: false, at: 0 };
            entry.obs = make().pipe(
                tap({
                    next: () => {
                        entry.done = true;
                        entry.at = Date.now();
                    },
                    error: () => {
                        if (this.entries.get(key) === entry) this.entries.delete(key);
                    },
                }),
                shareReplay({ bufferSize: 1, refCount: false }),
            );
            this.entries.set(key, entry);
            return entry.obs as Observable<T>;
        });
    }

    /** Forget everything: a write changed what the reads would answer. */
    clear(): void {
        this.entries.clear();
    }
}
