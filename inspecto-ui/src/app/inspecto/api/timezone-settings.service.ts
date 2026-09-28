import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/**
 * A Space's default timezone — the IANA zone a KPI without its own `timezone` is evaluated in.
 * `timezone` is `null` when unset (UTC); `effectiveTimezone` is the zone in force.
 * Persisted via `GET|PUT /spaces/{id}/settings/timezone`.
 */
export interface TimezoneSettings {
    timezone: string | null;
    effectiveTimezone: string;
}

@Injectable({ providedIn: 'root' })
export class TimezoneSettingsService {
    private http = inject(HttpClient);

    getFor(spaceId: string): Observable<TimezoneSettings> {
        return this.http.get<TimezoneSettings>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/settings/timezone`));
    }

    /** Save a Space's default timezone; `null` clears it (UTC). A name that is not an IANA zone is a 422. */
    saveFor(spaceId: string, timezone: string | null): Observable<TimezoneSettings> {
        return this.http.put<TimezoneSettings>(apiUrl(`/spaces/${encodeURIComponent(spaceId)}/settings/timezone`), {
            timezone,
        });
    }
}
