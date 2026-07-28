/**
 * TS mirror of the `/api/v1` envelope contract (`docs/api/openapi-v1.json`, design
 * `docs/superpower/api-contract-design.md` §4–5). The `v1Interceptor` unwraps success envelopes at
 * the HttpClient seam, so feature services keep their plain DTO signatures — these types surface
 * only at the interceptor, `apiErrorMessage`, and the mock layer's response edge.
 */

/** Machine-readable error codes — kept in lockstep with `com.gamma.control.ErrorCodes` (backend-pinned by ApiContractTest). */
export type V1ErrorCode =
    | 'MALFORMED_REQUEST'
    | 'NOT_FOUND'
    | 'METHOD_NOT_ALLOWED'
    | 'PATH_JAIL_VIOLATION'
    | 'CONFLICT'
    | 'CONFLICT_STALE_VERSION'
    | 'CONFIG_VALIDATION_FAILED'
    | 'INTERNAL'
    | 'CONTROL_PLANE_READ_ONLY'
    | 'CAPABILITY_UNAVAILABLE'
    | 'UNAUTHENTICATED'
    | 'PERMISSION_DENIED';

export interface V1EnvelopeMetadata {
    timestamp: string;
    apiVersion: 'v1';
    durationMs?: number;
    etag?: string;
    pagination?: { cursor?: string; nextCursor?: string; pageSize?: number; total?: number };
    warnings?: { code: string; message: string; sunset?: string }[];
}

/** The v1 success envelope. `permissions` is present only when the security module attached a Subject (Standard). */
export interface V1Envelope<T = unknown> {
    data: T;
    metadata: V1EnvelopeMetadata;
    links?: { self?: string; related?: Record<string, string> };
    permissions?: string[];
    diagnostics: { correlationId: string };
}

/** The structured v1 error, carried as `{ error: V1ErrorObject }` in non-2xx bodies. */
export interface V1ErrorObject {
    errorCode: V1ErrorCode;
    message: string;
    technicalMessage?: string;
    recoverable: boolean;
    suggestedAction?: string;
    documentation?: string;
    correlationId: string;
    details?: Record<string, unknown>;
}

/**
 * Shape guard for the unwrap seam: only bodies that are unmistakably a v1 success envelope are
 * unwrapped, so text (Prometheus /metrics), blobs, 304 empty bodies, and legacy JSON pass through.
 */
export function isV1Envelope(body: unknown): body is V1Envelope {
    if (body === null || typeof body !== 'object') return false;
    const b = body as Record<string, unknown>;
    return 'data' in b && (b['metadata'] as V1EnvelopeMetadata | undefined)?.apiVersion === 'v1';
}

/**
 * The `apiVersion` of a body that is **shaped** like an envelope but was not recognised as `v1` —
 * i.e. it carries both `data` and an object `metadata`, yet {@link isV1Envelope} declined it. Returns
 * `null` for everything else, including legacy JSON that merely happens to have a `data` key.
 *
 * This exists because a declined unwrap is otherwise **silent and global**: the raw envelope reaches
 * the feature service, which then runs array/object operations on `{data, metadata}` and fails far
 * from the cause. That is the shape of the still-unexplained non-array `GET /spaces` body
 * (docs/BACKLOG.md §6) — every mechanism in current source was eliminated, so the remaining
 * candidate is an artifact/source **version skew**, which is exactly what this names.
 *
 * ⚠ Deliberately narrow: widening {@link isV1Envelope} itself to unwrap these would defeat its
 * purpose (text, blobs and legacy JSON must pass through), and would silently accept a body whose
 * contract we do not actually know.
 */
export function envelopeVersionSkew(body: unknown): string | null {
    if (body === null || typeof body !== 'object') return null;
    const b = body as Record<string, unknown>;
    if (!('data' in b)) return null;
    const metadata = b['metadata'];
    if (metadata === null || typeof metadata !== 'object') return null;
    const version = (metadata as Record<string, unknown>)['apiVersion'];
    return version === 'v1' ? null : String(version);
}
