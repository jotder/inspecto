import { HttpErrorResponse } from '@angular/common/http';
import { apiErrorMessage } from '@inspecto/core/api';
import type { UploadRecord, UploadTarget } from '@inspecto/link-analysis/api/inv.service';

/** The server's file-name rule (`UploadRoutes`): 1-100 of letters, digits, `.`, `_`, `-`, starting with a letter or digit, ending `.csv`. */
const SAFE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;

/**
 * Why a chosen file cannot be uploaded, in the analyst's words, or '' when it can. The server still decides (it also checks
 * the Collector's file pattern, the content and the quotas); this only saves a round trip for the three things the browser
 * can see: the extension, the name's characters and the size cap.
 */
export function uploadFileProblem(file: { name: string; size: number } | null, target: UploadTarget | undefined): string {
    if (!file) return 'Choose a CSV file first.';
    if (/\.xlsx?$/i.test(file.name))
        return 'A spreadsheet workbook cannot be uploaded. Export the sheet as CSV (UTF-8) in your spreadsheet program and upload that.';
    if (!file.name.toLowerCase().endsWith('.csv')) return 'Only .csv files can be uploaded.';
    if (!SAFE_NAME.test(file.name))
        return 'The file name may hold only letters, digits, dots, underscores and hyphens (up to 100 characters) and must start with a letter or digit. Rename the file and choose it again.';
    if (file.size === 0) return 'That file is empty.';
    if (target && file.size > target.maxBytes)
        return `That file is ${formatBytes(file.size)}, over the ${formatBytes(target.maxBytes)} upload limit. Split it into smaller files.`;
    return '';
}

export function formatBytes(n: number): string {
    if (n < 1024) return `${n} B`;
    if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KiB`;
    return `${(n / (1024 * 1024)).toFixed(1)} MiB`;
}

/**
 * Plain words for a refused upload, delete or status read (DR-D6 conventions: the cause, then the next step; never a
 * capability id). 409 on an upload is "that name is still waiting"; on a delete it is "already picked up" - the caller
 * passes which one it is.
 */
export function uploadErrorMessage(err: unknown, fallback: string, action: 'upload' | 'delete' | 'read' = 'upload'): string {
    const status = err instanceof HttpErrorResponse ? err.status : (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 403:
            return action === 'upload'
                ? 'This upload was refused: ' + server
                : 'You can only delete your own uploads, unless you administer the Space. ' + server;
        case 404:
            return action === 'delete'
                ? 'That upload is no longer listed - it may already have been deleted. Refresh the list.'
                : 'That Collector does not accept uploads here. Choose another, or ask a Space administrator to enable uploads on it.';
        case 409:
            return action === 'delete'
                ? 'The Collector has already picked this file up, so it cannot be withdrawn - its rows are in the Dataset.'
                : "A file with this name is still waiting in the Collector's inbox. If it is your upload, delete or replace it below; otherwise rename your file.";
        case 413:
            return (
                'That upload is over a size limit and nothing was stored. ' +
                server +
                ' Next step: split the file, or ask a Space administrator to raise the upload limit.'
            );
        case 422:
            return 'The file was not accepted: ' + server;
        case 429:
            return 'Too many requests. Wait a few seconds and try again.';
        case 503:
            return 'Uploads are not available here - the link-analysis module or a write root is missing.';
        default:
            return server;
    }
}

/** The status badge value (its colour) and the words for one upload's ingest state. */
export function uploadStateView(u: Pick<UploadRecord, 'state' | 'rejectedRows' | 'quarantineReason'>): { tone: string; label: string } {
    switch (u.state) {
        case 'pending':
            return { tone: 'pending', label: 'Waiting for the Collector' };
        case 'ingested':
            return u.rejectedRows
                ? { tone: 'warning', label: `Ingested, ${u.rejectedRows} ${u.rejectedRows === 1 ? 'row' : 'rows'} rejected` }
                : { tone: 'success', label: 'Ingested' };
        case 'quarantined':
            return { tone: 'quarantined', label: 'Quarantined' + (u.quarantineReason ? ` (${u.quarantineReason})` : '') };
        default:
            return { tone: 'neutral', label: 'Picked up' };
    }
}
