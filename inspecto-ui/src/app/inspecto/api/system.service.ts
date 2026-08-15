import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** Where a family's effective URL came from — mirrors the backend `OperationalDb.Source`. */
export type OperationalDbSource =
    | 'BACKEND_PROPERTY'
    | 'FAMILY_PROPERTY'
    | 'SHARED_PROPERTY'
    | 'SPACE_DEFAULT'
    | 'DISABLED';

/** One operational store family's effective configuration. ⛔ Never carries a password, in any form. */
export interface OperationalDbFamily {
    family: string;
    label: string;
    enabled: boolean;
    source: OperationalDbSource;
    /** Null exactly when `source` is `DISABLED`; any embedded credentials are stripped server-side. */
    url: string | null;
    user: string | null;
    backendProperty: string;
    urlProperty: string;
    /** Null for the families that open with a URL and no credentials at all. */
    userProperty: string | null;
    passwordProperty: string | null;
}

/** `GET /system/operational-db` — what this deployment is actually using. */
export interface OperationalDbReport {
    engine: 'duckdb' | 'postgres';
    engineProperty: string;
    driverAvailable: boolean;
    families: OperationalDbFamily[];
}

/** `POST /system/operational-db/test` — a real JDBC round-trip, named rather than boolean. */
export interface OperationalDbTestResult {
    url: string;
    outcome: 'OK' | 'DRIVER_MISSING' | 'AUTH_FAILED' | 'UNREACHABLE';
    detail: string;
}

/**
 * System-level diagnostics (PG-1 Open 2, Stage 1).
 *
 * <p>⚠ **Read and validate only, and deliberately so.** The UI is served by the process that needs the
 * operational database, so it cannot configure its own dependency and no change could take effect
 * without a restart; persisting a selection here would create a second declaration of the same fact
 * beside `-D`. There is no write method to add — the operator applies the flags through their own
 * deployment tooling.
 *
 * <p>⛔ `password` is a secret REFERENCE (`${ENV:…}` / `${KEYSTORE:…}` / `${FILE:…}`), never a literal:
 * the server 422s a literal, because it would be a credential in transit and in every access log.
 */
@Injectable({ providedIn: 'root' })
export class SystemService {
    private http = inject(HttpClient);

    operationalDb(): Observable<OperationalDbReport> {
        return this.http.get<OperationalDbReport>(apiUrl('/system/operational-db'));
    }

    testOperationalDb(body: { url: string; user?: string; password?: string }): Observable<OperationalDbTestResult> {
        return this.http.post<OperationalDbTestResult>(apiUrl('/system/operational-db/test'), body);
    }
}
