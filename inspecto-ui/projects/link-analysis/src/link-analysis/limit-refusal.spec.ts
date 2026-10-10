import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { standingDetectionErrorMessage } from './standing-detection';
import { entityListErrorMessage, identityErrorMessage, investigationErrorMessage } from './investigation-state';
import { limitRefusalMessage } from './limit-refusal';
import { alertRuleErrorMessage } from './link-analysis-template.dialogs';
import { valueMeasureErrorMessage } from './link-analysis-value-measures.component';

const err = (status: number, message = 'server says') =>
    new HttpErrorResponse({ status, error: { error: { message } } });

describe('limitRefusalMessage (DR-D6)', () => {
    it('413 names the limit the server stated and a next step', () => {
        const m = limitRefusalMessage(
            err(413, "Working Set is larger than this Space's limit of 67108864 bytes (max_set_bytes)"),
        )!;
        expect(m).toContain('over a size limit');
        expect(m).toContain('max_set_bytes');
        expect(m).toContain('Next step');
        expect(m).toContain('nothing was stored');
    });

    it('429 names the per-user rate limit (burst of 20, one per 3 seconds) and says to wait', () => {
        const m = limitRefusalMessage(err(429))!;
        expect(m).toContain('burst of 20');
        expect(m).toContain('every 3 seconds');
        expect(m).toContain('Wait a few seconds');
    });

    it('is silent for any other status, so each surface keeps its own table', () => {
        expect(limitRefusalMessage(err(422))).toBeNull();
        expect(limitRefusalMessage(new Error('x'))).toBeNull();
    });

    it('every Link Analysis message function maps 413 and 429 (not the generic fallback)', () => {
        const fns: ((e: unknown) => string)[] = [
            (e) => investigationErrorMessage(e, 'f'),
            (e) => entityListErrorMessage(e, 'f'),
            (e) => identityErrorMessage(e, 'f'),
            (e) => valueMeasureErrorMessage(e, 'f'),
            (e) => alertRuleErrorMessage(e),
            (e) => standingDetectionErrorMessage(e),
        ];
        for (const fn of fns) {
            expect(fn(err(413))).toContain('size limit');
            expect(fn(err(429))).toContain('3 seconds');
        }
    });
});
