import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * Where a Regulatory Report is in its life (`REGULATORY-REPORTING-1`). `declined` = the checker refused it;
 * `invalid` = failed its integrity check. (`rejected` is reserved for a regulator's refusal, never sent today.)
 */
export type RegulatoryReportStatus =
    | 'draft'
    | 'pending'
    | 'approved'
    | 'submitted'
    | 'failed'
    | 'declined'
    | 'expired'
    | 'invalid';

/** One output field of a Report Template. */
export interface ReportTemplateField {
    name: string;
    source: string;
    required: boolean;
    maxLength: number;
}

/** A **Report Template** — one regulator format (`GET /regulatory-reports/templates`). */
export interface ReportTemplate {
    id: string;
    title: string;
    description: string | null;
    format: 'xml' | 'csv' | 'json';
    origin: 'built-in' | 'space';
    delivery: { kind: string; dir: string };
    /** The `input.*` keys the template reads — the only values a draft may carry. */
    inputs: string[];
    fields: ReportTemplateField[];
}

/** A template that does not load, and why — it can draft nothing. */
export interface ReportTemplateProblem {
    id: string;
    origin: string;
    problems: string[];
}

/** A Regulatory Report as the list returns it (no content, no inputs). */
export interface RegulatoryReport {
    id: string;
    status: RegulatoryReportStatus;
    template: string;
    templateTitle: string;
    format: string;
    mediaType: string;
    caseId: string | null;
    incidentId: string | null;
    subjectTitle: string | null;
    author: string;
    reason: string | null;
    createdAt: string;
    expiresAt: string;
    contentSha256: string;
    delivery: { kind: string; dir: string; fileName: string };
    requestedBy: string | null;
    requestedAt?: string | null;
    approver: string | null;
    approvedAt?: string | null;
    decidedBy?: string | null;
    decisionReason?: string | null;
    submission: { kind: string; file: string; at: string; alreadyPresent: boolean } | null;
    lastError?: string | null;
    history: { status: string; by: string; at: string }[];
}

/** One report with the exact content that is (or was) submitted, and the approver's live checks. */
export interface RegulatoryReportDetail extends RegulatoryReport {
    content: string;
    inputs: Record<string, unknown>;
    /** Draft / pending only: the Case or Incident changed since the content was rendered. */
    sourceChanged?: boolean;
    sourceError?: string;
    /** Pending only: whether anyone outside the report's makers could approve it. */
    approverCheck?: 'ok' | 'none-eligible' | 'unknown';
}

export interface RegulatoryReportFilter {
    status?: RegulatoryReportStatus;
    template?: string;
    caseId?: string;
    incidentId?: string;
}

/** What a draft carries: a template, exactly one subject, the maker's inputs and an optional reason. */
export interface RegulatoryReportDraft {
    template: string;
    caseId?: string;
    incidentId?: string;
    inputs: Record<string, string>;
    reason?: string;
}

/** The inputs a template REQUIRES — those an `input.*` field reads with `required: true`. */
export function requiredInputs(t: ReportTemplate): Set<string> {
    const out = new Set<string>();
    for (const f of t.fields) if (f.required && f.source.startsWith('input.')) out.add(f.source.slice('input.'.length));
    return out;
}

/** An input key as words for a label: `reportingEntity` → "Reporting entity". */
export function inputLabel(key: string): string {
    const words = key
        .replace(/_/g, ' ')
        .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
        .toLowerCase();
    return words.charAt(0).toUpperCase() + words.slice(1);
}

/** The longest value a template accepts for an input (the tightest `maxLength` of the fields reading it; 0 = none). */
export function inputMaxLength(t: ReportTemplate, key: string): number {
    const limits = t.fields.filter((f) => f.source === `input.${key}` && f.maxLength > 0).map((f) => f.maxLength);
    return limits.length ? Math.min(...limits) : 0;
}

/**
 * Regulatory Reports (`REGULATORY-REPORTING-1`): list templates and reports, draft one from a Case or Incident,
 * send it for approval, and approve, decline or retry it. The server enforces everything — the capabilities,
 * four-eyes (a maker deciding their own report is 403), the render rules and the record's integrity; a decision
 * carries only a reason, never the content.
 */
@Injectable({ providedIn: 'root' })
export class RegulatoryReportsService {
    private http = inject(HttpClient);

    templates(): Observable<{ items: ReportTemplate[]; problems: ReportTemplateProblem[] }> {
        return this.http.get<{ items: ReportTemplate[]; problems: ReportTemplateProblem[] }>(
            apiUrl('/regulatory-reports/templates'),
        );
    }

    list(
        filter: RegulatoryReportFilter = {},
    ): Observable<{ items: RegulatoryReport[]; total: number; truncated: boolean }> {
        return this.http.get<{ items: RegulatoryReport[]; total: number; truncated: boolean }>(
            apiUrl('/regulatory-reports'),
            { params: toParams({ ...filter }) },
        );
    }

    get(id: string): Observable<RegulatoryReportDetail> {
        return this.http.get<RegulatoryReportDetail>(apiUrl(`/regulatory-reports/${encodeURIComponent(id)}`));
    }

    draft(body: RegulatoryReportDraft): Observable<RegulatoryReportDetail> {
        return this.http.post<RegulatoryReportDetail>(apiUrl('/regulatory-reports'), body);
    }

    requestApproval(id: string, reason?: string): Observable<RegulatoryReportDetail> {
        return this.transition(id, 'request-approval', reason);
    }

    approve(id: string, reason?: string): Observable<RegulatoryReportDetail> {
        return this.transition(id, 'approve', reason);
    }

    decline(id: string, reason?: string): Observable<RegulatoryReportDetail> {
        return this.transition(id, 'decline', reason);
    }

    retry(id: string): Observable<RegulatoryReportDetail> {
        return this.transition(id, 'retry');
    }

    private transition(
        id: string,
        verb: 'request-approval' | 'approve' | 'decline' | 'retry',
        reason?: string,
    ): Observable<RegulatoryReportDetail> {
        return this.http.post<RegulatoryReportDetail>(
            apiUrl(`/regulatory-reports/${encodeURIComponent(id)}/${verb}`),
            reason ? { reason } : {},
        );
    }
}
