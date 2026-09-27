import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, KpiDefinition, PendingChange } from 'app/inspecto/api';
import { Requirement, RequirementKind } from './requirement-types';

/** `POST /requirements/{id}/kpi` body — what only a Builder knows; the Requirement supplies title/target/unit and
 *  the direction its comparator states. Omitted keys fall back to those (and `id` to the Requirement's id). */
export type RequirementKpiBody = Partial<KpiDefinition> & { id?: string };

/** The KPI written (its stored content, `name` = the KPI id), or the maker-checker hold (202, nothing written). */
export type RequirementKpiResult =
    | (KpiDefinition & { name: string; requirement: string })
    | { status: 'pending'; written: false; pendingChange: PendingChange };

/**
 * Requirement store (UI-6 + SEC-7(c)) — the Business→Builder lifecycle over the dedicated
 * `/requirements*` control-plane routes. Submission is open; the accept/reject (`/decision`) and
 * `/deliver` transitions are enforced server-side on `canTriageRequirements` (a no-op on Personal),
 * so the UI's lens gate (`LensService.canTriageRequirements()`) is convenience, not the boundary.
 */
@Injectable({ providedIn: 'root' })
export class RequirementsService {
    private http = inject(HttpClient);

    list(): Observable<Requirement[]> {
        return this.http.get<Requirement[]>(apiUrl('/requirements'));
    }

    create(r: { id: string; title: string; kind: RequirementKind; description: string }): Observable<Requirement> {
        return this.http.post<Requirement>(apiUrl('/requirements'), {
            id: r.id,
            title: r.title,
            kind: r.kind,
            description: r.description,
        });
    }

    decide(id: string, accept: boolean, note?: string): Observable<Requirement> {
        return this.http.post<Requirement>(apiUrl(`/requirements/${encodeURIComponent(id)}/decision`), {
            accept,
            note,
        });
    }

    deliver(id: string, note?: string): Observable<Requirement> {
        return this.http.post<Requirement>(apiUrl(`/requirements/${encodeURIComponent(id)}/deliver`), { note });
    }

    /** Create a KPI definition from a delivered `kpi` Requirement — server-gated on `canAuthorWorkbench`. */
    createKpi(id: string, body: RequirementKpiBody = {}): Observable<RequirementKpiResult> {
        return this.http.post<RequirementKpiResult>(apiUrl(`/requirements/${encodeURIComponent(id)}/kpi`), body);
    }
}
