import { HttpInterceptorFn, HttpResponse } from '@angular/common/http';
import { map } from 'rxjs/operators';
import { envelopeVersionSkew, isV1Envelope } from './v1';

/**
 * Unwraps the `/api/v1` success envelope (`{data, metadata, diagnostics}` → `data`) at the one
 * HttpClient seam, so all feature services keep their plain DTO signatures (W7 — mirror of the
 * backend's `ApiContext.respondJson` branch point). Response-side only: `apiUrl()` already builds
 * the `/api/v1` request path.
 *
 * Shape-guarded via `isV1Envelope`, so non-envelope replies — Prometheus text, blobs, 304 empty
 * bodies, legacy JSON — pass through untouched. Error bodies stay wrapped (`{error: {…}}`) and are
 * parsed by `apiErrorMessage`. MUST be first in the interceptor chain: the mock backend
 * short-circuits, and only upstream interceptors see its (enveloped) responses.
 */
export const v1Interceptor: HttpInterceptorFn = (req, next) =>
    next(req).pipe(
        map((event) => {
            if (!(event instanceof HttpResponse)) return event;
            if (isV1Envelope(event.body)) return event.clone({ body: event.body.data });
            // An envelope-shaped body we did NOT unwrap is a version skew between this bundle and the
            // server that answered. Say so here, once, naming the request — otherwise the raw envelope
            // reaches a feature service and fails far from the cause. See `envelopeVersionSkew`.
            const skew = envelopeVersionSkew(event.body);
            if (skew !== null)
                console.error(
                    `[api] ${req.method} ${req.url} returned an envelope with apiVersion=${skew}; this bundle unwraps only 'v1'. ` +
                        `The body was passed through UNWRAPPED — expect shape errors downstream. Rebuild/redeploy so bundle and server agree.`,
                );
            return event;
        }),
    );
