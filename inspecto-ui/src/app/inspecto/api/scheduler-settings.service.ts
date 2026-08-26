import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';

/** Where a scheduler cap's effective value came from — the provenance the routes report so two
 *  declarations (file vs `-D` bootstrap default) can never leave the operator guessing which won. */
export type SchedulerSource = 'file' | 'property' | 'default';

/** One tier of the Consignment concurrency hierarchy. `maxConcurrentConsignments` is the EFFECTIVE
 *  value (stored → `-D` flag → 0 = unbounded); `source` is the cap's own provenance, keyed on the
 *  stored value — a document storing only other keys leaves the cap owned by the flag. */
export interface SchedulerTier {
    maxConcurrentConsignments: number;
    source: SchedulerSource;
    /** Server-wide tier only: the stored IntakeGovernor globals (`null` = inherit `-Dingest.*`)
     *  and the thresholds actually in force on the running governor. */
    intakeMaxFilesPerCycle?: number | null;
    intakeMinFilesPerCycle?: number | null;
    intakeAdaptive?: boolean | null;
    intakeSource?: SchedulerSource;
    effectiveIntake?: { maxFilesPerCycle: number; minFilesPerCycle: number; adaptive: boolean; active: boolean };
    /** Server-wide tier only — the resource pair (BACKLOG D11). `duckdbMemoryLimit` is a DuckDB size
     *  string (`2GB`) or `null` when nothing is stored and no `-D` flag is set, in which case DuckDB's
     *  own ~80%-of-RAM-per-instance default applies. `maxConcurrentJobRuns` is `0` for unbounded and
     *  always carries a value, because the bound now ships on. */
    duckdbMemoryLimit?: string | null;
    duckdbMemoryLimitSource?: SchedulerSource;
    maxConcurrentJobRuns?: number;
    maxConcurrentJobRunsSource?: SchedulerSource;
}

/** The resource-pair fields of a save. `null` CLEARS the stored value (reverting to the `-D` bootstrap
 *  default); the two are saved together because either alone leaves total exposure unbounded — total
 *  = `memoryLimit` x concurrent runs. */
export interface SchedulerResourceCaps {
    duckdbMemoryLimit: string | null;
    maxConcurrentJobRuns: number | null;
}

/** The intake-global fields of a save; each `null` CLEARS the stored value (revert to `-Dingest.*`). */
export interface SchedulerIntakeGlobals {
    maxFilesPerCycle: number | null;
    minFilesPerCycle: number | null;
    adaptive: boolean | null;
}

/** The bound space's tier (`GET|PUT /settings/scheduler`). The stored cadences are `null` when the
 *  space inherits the launch (`-D`) defaults; the `effective*` values are what the running timers
 *  actually use right now. */
export interface SchedulerSpaceTier extends SchedulerTier {
    id: string | null;
    pollSeconds?: number | null;
    acquirePollSeconds?: number | null;
    effectivePollSeconds?: number;
    effectiveAcquirePollSeconds?: number;
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
        /** Free execution slots right now; `null` when the tier is unbounded (never a fake 0). */
        system_free: number | null;
        space_in_flight: Record<string, number>;
        pipelines: Record<string, { space: string; in_flight: number; waiting: number; priority: number }>;
        /** Pipelines the IntakeGovernor has throttled below their base cap (S8) — bounded list plus
         *  the true total, so a large fleet cannot turn a diagnostic read into an export. */
        throttled: {
            pipelines: { pipeline: string; cap: number; baseCap: number; floor: number }[];
            total: number;
            truncated: boolean;
        };
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

    /** Save the server-wide tier; hot-applies. The server merges per key, so every `null` — the cap
     *  included — is an explicit clear (revert to the `-D` bootstrap default), never an omission. */
    saveSystem(
        maxConcurrentConsignments: number | null,
        intake?: SchedulerIntakeGlobals,
        resources?: SchedulerResourceCaps,
    ): Observable<SchedulerView> {
        const body: Record<string, unknown> = { maxConcurrentConsignments };
        if (intake) {
            body['intakeMaxFilesPerCycle'] = intake.maxFilesPerCycle;
            body['intakeMinFilesPerCycle'] = intake.minFilesPerCycle;
            body['intakeAdaptive'] = intake.adaptive;
        }
        if (resources) {
            body['duckdbMemoryLimit'] = resources.duckdbMemoryLimit;
            body['maxConcurrentJobRuns'] = resources.maxConcurrentJobRuns;
        }
        return this.http.put<SchedulerView>(apiUrl('/system/scheduler'), body);
    }

    /** Save the bound space's tier; hot-applies onto the running timers. A `null` — cap or cadence —
     *  CLEARS the stored value, reverting live to the launch default (the server merges per key). */
    saveSpace(
        maxConcurrentConsignments: number | null,
        pollSeconds: number | null,
        acquirePollSeconds: number | null,
    ): Observable<SchedulerSpaceTier> {
        return this.http.put<SchedulerSpaceTier>(apiUrl('/settings/scheduler'), {
            maxConcurrentConsignments,
            pollSeconds,
            acquirePollSeconds,
        });
    }
}
