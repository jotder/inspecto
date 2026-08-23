import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';
import { ProblemFilesPage, ServiceReport, StatusReport, ReportWindow } from './models';

/** Service-wide status snapshot + batch-audit rollup (CONTROL scope). */
@Injectable({ providedIn: 'root' })
export class ReportsService {
    private http = inject(HttpClient);

    status(): Observable<StatusReport> {
        return this.http.get<StatusReport>(apiUrl('/status'));
    }
    /**
     * Problem files across EVERY pipeline (`?limit=`, `?since=`) — files that failed whole
     * (quarantined) or ingested with rejected rows. The file-grain companion to {@link status}'s
     * pipeline-grain rollup; bounded server-side, with `truncated` reporting the true total.
     */
    problemFiles(opts?: { limit?: number; since?: string }): Observable<ProblemFilesPage> {
        return this.http.get<ProblemFilesPage>(apiUrl('/status/problem-files'), { params: toParams({ ...opts }) });
    }
    serviceReport(window?: ReportWindow): Observable<ServiceReport> {
        return this.http.get<ServiceReport>(apiUrl('/report'), { params: toParams({ ...window }) });
    }
}
