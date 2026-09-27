import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** A Space's Egress Allowlist (`GET|PUT /settings/egress`): host names and CIDR ranges outbound calls may reach
 *  although their private / CGNAT address class is denied by default. The server lowercases and trims each entry. */
export interface EgressAllowlist {
    allow: string[];
}

@Injectable({ providedIn: 'root' })
export class EgressSettingsService {
    private http = inject(HttpClient);

    get(): Observable<EgressAllowlist> {
        return this.http.get<EgressAllowlist>(apiUrl('/settings/egress'));
    }

    /** Replace the whole list — `canAdminister`; 422 names the refused entry, 503 when writes are disabled. */
    save(allow: string[]): Observable<EgressAllowlist> {
        return this.http.put<EgressAllowlist>(apiUrl('/settings/egress'), { allow });
    }
}
