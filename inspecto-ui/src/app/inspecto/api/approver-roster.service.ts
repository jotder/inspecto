import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** A Space's approver roster (`GET|PUT /settings/approvers`): who may decide a four-eyes item when the sign-in
 *  provider has no user directory. `applies` = the active Authenticator is such a provider (OIDC). */
export interface ApproverRoster {
    users: string[];
    groups: string[];
    applies: boolean;
}

@Injectable({ providedIn: 'root' })
export class ApproverRosterService {
    private http = inject(HttpClient);

    get(): Observable<ApproverRoster> {
        return this.http.get<ApproverRoster>(apiUrl('/settings/approvers'));
    }

    /** Replace both lists — `canAdminister`; 422 names the refused entry, 503 when writes are disabled. */
    save(users: string[], groups: string[]): Observable<ApproverRoster> {
        return this.http.put<ApproverRoster>(apiUrl('/settings/approvers'), { users, groups });
    }
}
