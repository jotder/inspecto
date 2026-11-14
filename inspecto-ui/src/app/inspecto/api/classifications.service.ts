import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { apiUrl } from './api-base';
import { SpacesService } from './spaces.service';

/** One column classification the Space knows (`GET /classifications`, UI-CLASSIFICATION-PICKER-1). */
export interface Classification {
    /** Normalised: trimmed, upper case. */
    class: string;
    /** Whether a column of this class is masked for a reader who may not reveal it. */
    masked: boolean;
    source: 'sensitive' | 'entity-type' | 'not-masked';
    /** The Entity Type that claims the class (or whose id it is), when one does. */
    entityType?: string;
    label?: string;
}

/**
 * The Space's column classification vocabulary — the same list a save refuses an unknown class by (422
 * `ERR_UNKNOWN_CLASSIFICATION`). Read once per Space: every classification picker calls {@link ensureLoaded}, and only
 * the first call in a Space fetches. A failed read is not retried in that Space (a 600-row grid must not fire 600
 * reads); pickers then fall back to free text and the save still judges.
 */
@Injectable({ providedIn: 'root' })
export class ClassificationsService {
    private http = inject(HttpClient);
    private spaces = inject(SpacesService);

    readonly items = signal<Classification[]>([]);
    readonly status = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
    private loadedFor: string | null | undefined = undefined;

    ensureLoaded(): void {
        const space = this.spaces.currentSpaceId();
        if (this.loadedFor === space) return;
        this.loadedFor = space;
        this.items.set([]);
        this.status.set('loading');
        this.http.get<{ items: Classification[] }>(apiUrl('/classifications')).subscribe({
            next: (r) => {
                if (this.loadedFor !== space) return;
                this.items.set(r?.items ?? []);
                this.status.set('ready');
            },
            error: () => {
                if (this.loadedFor === space) this.status.set('error');
            },
        });
    }
}
