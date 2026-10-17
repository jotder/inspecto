import { Injectable, effect, inject, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, firstValueFrom, tap } from 'rxjs';
import { SpacesService, apiUrl } from '@inspecto/core/api';
import {
    DomainProfileId,
    IndustryPackDto,
    installDomainProfiles,
    profileFromPack,
} from '@inspecto/link-analysis/graph/domain-profile';

/** `GET|PUT /settings/link-analysis/domain-profile` — the Space's Domain Profile and the installed Industry packs. */
export interface DomainProfileState {
    /** The id in force: a pack id, or `generic` (none stated, or the stated pack is not installed). */
    active: DomainProfileId;
    /** The stored `domain_profile`, or null. */
    stated: string | null;
    /** Set when the stated pack is not installed and the neutral vocabulary is in force. */
    diagnostic?: string;
    /** The active pack in full, or null for `generic`. */
    profile: IndustryPackDto | null;
    installed: IndustryPackDto[];
    /** Packs this build ships that failed validation at load — never applied. */
    refused: { id: string; reason: string }[];
}

/**
 * Loads the Space's Domain Profile and installs the server's Industry packs into the `domain-profile.ts` registry, once
 * per Space (and again after a save). Until the answer arrives, and when it fails, every reader sees `generic` — the
 * neutral vocabulary is always a safe reading.
 */
@Injectable({ providedIn: 'root' })
export class DomainProfileService {
    private readonly http = inject(HttpClient);
    private readonly spaces = inject(SpacesService);

    /** The last answer, or null before the first one (or after a failed read). */
    readonly state = signal<DomainProfileState | null>(null);
    private loaded: Promise<void> = Promise.resolve();

    constructor() {
        effect(() => {
            this.spaces.currentSpaceId(); // track: another Space has another profile
            this.loaded = this.load();
        });
    }

    /** Resolves once the current Space's packs are installed (or the read failed and `generic` stays in force). */
    ready(): Promise<void> {
        return this.loaded;
    }

    get(): Observable<DomainProfileState> {
        return this.http.get<DomainProfileState>(apiUrl('/settings/link-analysis/domain-profile'));
    }

    /** Select a pack (`generic` clears the choice); the answer is installed at once. */
    save(domainProfile: DomainProfileId): Observable<DomainProfileState> {
        return this.http
            .put<DomainProfileState>(apiUrl('/settings/link-analysis/domain-profile'), { domainProfile })
            .pipe(tap((s) => this.apply(s)));
    }

    private async load(): Promise<void> {
        try {
            this.apply(await firstValueFrom(this.get()));
        } catch {
            this.state.set(null);
            installDomainProfiles([], null);
        }
    }

    private apply(s: DomainProfileState): void {
        this.state.set(s);
        installDomainProfiles((s.installed ?? []).map(profileFromPack), s.active);
    }
}
