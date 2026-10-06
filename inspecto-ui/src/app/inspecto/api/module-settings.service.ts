import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** One installed Module as `GET /modules` reports it (GLOSSARY §15). Roles are lower-case manifest values. */
export interface InstalledModule {
    id: string;
    title: string;
    buildRole: string;
    offeringRole: 'base' | 'optional' | 'provider' | 'internal' | string;
    bindingTime: string;
    state: 'ACTIVE' | 'INERT';
    reasons: string[];
    provides: { features: string[] };
    requires: { modules: string[] };
    enabledInSpace: boolean;
}

export interface InstalledModules {
    modules: InstalledModule[];
    diagnostics: unknown[];
}

/** A Space's Module settings (`GET|PUT /settings/modules`): the Features switched off in this Space. */
export interface ModuleSettings {
    disabled: string[];
    /** Disabled ids no installed Module declares — kept untouched in the file. */
    inert: string[];
    installed: string[];
    unreadable: boolean;
}

@Injectable({ providedIn: 'root' })
export class ModuleSettingsService {
    private http = inject(HttpClient);

    modules(): Observable<InstalledModules> {
        return this.http.get<InstalledModules>(apiUrl('/modules'));
    }

    get(): Observable<ModuleSettings> {
        return this.http.get<ModuleSettings>(apiUrl('/settings/modules'));
    }

    /** Replace the disabled Feature list — `canAdminister`; 422 names an unknown or base Feature, 503 when writes are disabled. */
    save(disabled: string[]): Observable<ModuleSettings> {
        return this.http.put<ModuleSettings>(apiUrl('/settings/modules'), { disabled });
    }
}
