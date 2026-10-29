import { describe, expect, it, vi } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import { STALE_WRITE_MESSAGE, apiErrorMessage, apiUrl, isStaleVersionError, needsPermission, plainCapabilityRefusal, toParams } from './api-base';
import { environment } from '../../../environments/environment';

describe('apiUrl', () => {
    it('prefixes the path with the configured base and the v1 segment (W7)', () => {
        expect(apiUrl('/pipelines')).toBe(`${environment.apiBaseUrl}/v1/pipelines`);
    });
});

describe('toParams', () => {
    it('keeps non-empty values', () => {
        const p = toParams({ from: '2020-01-01', to: '2020-02-01' });
        expect(p.get('from')).toBe('2020-01-01');
        expect(p.get('to')).toBe('2020-02-01');
    });

    it('drops null, undefined and empty-string values', () => {
        const p = toParams({ a: null, b: undefined, c: '', d: 'keep' });
        expect(p.has('a')).toBe(false);
        expect(p.has('b')).toBe(false);
        expect(p.has('c')).toBe(false);
        expect(p.get('d')).toBe('keep');
    });

    it('joins array values with commas', () => {
        const p = toParams({ kinds: ['source', 'table'] });
        expect(p.get('kinds')).toBe('source,table');
    });

    it('stringifies numbers and booleans', () => {
        const p = toParams({ depth: 2, overlay: true });
        expect(p.get('depth')).toBe('2');
        expect(p.get('overlay')).toBe('true');
    });
});

/**
 * `CLIENT-HALVES-1` (a) — telling a lost race apart from every other refusal.
 *
 * ⚠ The discrimination is on the ERROR CODE, not the status: `/config/write` and `DELETE /config/...`
 * both answer plain `409 CONFLICT` for entirely different reasons (a non-overwrite write onto an
 * existing file; a delete blocked by dependents). Reporting either as "someone else edited this" would
 * be a lie the author cannot act on.
 */
describe('isStaleVersionError', () => {
    const err = (status: number, errorCode?: string) =>
        new HttpErrorResponse({ status, error: errorCode ? { error: { errorCode } } : {} });

    it('recognises the If-Match refusal', () => {
        expect(isStaleVersionError(err(409, 'CONFLICT_STALE_VERSION'))).toBe(true);
    });

    it('🔴 does NOT treat a plain 409 CONFLICT as a concurrent edit', () => {
        expect(isStaleVersionError(err(409, 'CONFLICT'))).toBe(false);
        expect(isStaleVersionError(err(409))).toBe(false);
    });

    it('ignores other statuses and non-HTTP errors', () => {
        expect(isStaleVersionError(err(422, 'CONFIG_VALIDATION_FAILED'))).toBe(false);
        // ⛔ There is no 412 in this product; a 412 must not be mistaken for the real refusal.
        expect(isStaleVersionError(err(412, 'CONFLICT_STALE_VERSION'))).toBe(false);
        expect(isStaleVersionError(new Error('boom'))).toBe(false);
        expect(isStaleVersionError(undefined)).toBe(false);
    });

    it('has a message that tells the author what to DO, not just that it failed', () => {
        expect(STALE_WRITE_MESSAGE).toMatch(/changed underneath you/i);
        expect(STALE_WRITE_MESSAGE).toMatch(/reload/i);
    });
});

describe('apiErrorMessage — a capability refusal in plain words (LA-UI-TEXT-PASS-1 G14 / X4)', () => {
    it("says the server's `missing capability '…'` 403 as \"Needs <permission>.\", never the id", () => {
        const err = new HttpErrorResponse({
            status: 403,
            error: { error: { errorCode: 'PERMISSION_DENIED', message: "missing capability 'canAuthorWorkbench'" } },
        });
        expect(apiErrorMessage(err, 'Saving the view failed.')).toBe('Needs Workbench author.');
    });

    it('names every Link Analysis capability without its id or the word capability', () => {
        for (const id of ['canShareInvestigation', 'canRunLinkGraphAnalysis', 'canManageIncidents', 'canBuildLinkIndex']) {
            const text = plainCapabilityRefusal(`missing capability '${id}'`);
            expect(text).toMatch(/^Needs [A-Z][a-z ]+[a-z]\.$/);
            expect(text).not.toContain(id);
            expect(text).not.toMatch(/capability/i);
        }
        expect(needsPermission('canManageIncidents')).toBe('Needs Incident management.');
    });

    it('keeps the owning-Space suffix and never shows an unknown id', () => {
        const info = vi.spyOn(console, 'info').mockImplementation(() => undefined);
        expect(plainCapabilityRefusal("missing capability 'canFoo' in the owning space")).toBe(
            'Your role does not have the permission this needs in the owning Space.',
        );
        expect(plainCapabilityRefusal("missing capability 'canAuthorWorkbench' in the owning space")).toBe(
            'Needs Workbench author in the owning Space.',
        );
        expect(info).toHaveBeenCalled();
        info.mockRestore();
    });

    it('leaves every other message as the server wrote it', () => {
        expect(plainCapabilityRefusal('view-only share')).toBe('view-only share');
    });
});
