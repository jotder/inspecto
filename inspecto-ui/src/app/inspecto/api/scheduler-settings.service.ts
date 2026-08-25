import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** Where a scheduler cap's effective value came from — the provenance the routes report so two
 *  declarations (file vs `-D` bootstrap default) can never leave the operator guessing which won. */
export type SchedulerSource = 'file' | 'property' | 'default';

/** One tier of the Consignment concurrency hierarchy. `0` = unbounded. */
export interface SchedulerTier {
    maxConcurrentConsignments: number;
    source: SchedulerSource;
}

/** The bound space's tier (`GET|PUT /settings/scheduler`). */
export interface SchedulerSpaceTier extends SchedulerTier {
    id: string | null;
}

/** The server-wide view (`GET /system/scheduler`): both tiers, host cores, and the live broker
 *  occupancy snapshot (system/space in-flight, per-pipeline in-flight/waiting/priority). */
export interface SchedulerView {
    system: SchedulerTier;
    space: SchedulerSpaceTier;
    cores: number;
    live: {
        system_cap: number;
        system_in_flight: number;
        space_in_flight: Record<string, number>;
        pipelines: Record<string, { space: string; in_flight: number; waiting: number; priority: number }>;
    };
}

/**
 * The Consignment-concurrency settings routes (scheduler-system-config plan Part B). A PUT persists
 * `scheduler.toon` and **hot-applies** the cap on the running broker — no restart; a shrink drains
 * (in-flight Consignments finish, the new ceiling gates the next admissions).
 */
@Injectable({ providedIn: 'root' })
export class SchedulerSettingsService {
    private http = inject(HttpClient);

    /** Both tiers + live occupancy. */
    view(): Observable<SchedulerView> {
        return this.http.get<SchedulerView>(apiUrl('/system/scheduler'));
    }

    /** Replace the server-wide cap (0 = unbounded); hot-applies. */
    saveSystem(maxConcurrentConsignments: number): Observable<SchedulerView> {
        return this.http.put<SchedulerView>(apiUrl('/system/scheduler'), { maxConcurrentConsignments });
    }

    /** Replace the bound space's cap (0 = unbounded); hot-applies. */
    saveSpace(maxConcurrentConsignments: number): Observable<SchedulerSpaceTier> {
        return this.http.put<SchedulerSpaceTier>(apiUrl('/settings/scheduler'), { maxConcurrentConsignments });
    }
}
