import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { apiUrl, toParams } from './api-base';

/**
 * Lens access configuration (`/access/*` — design `docs/superpower/lens-access-config-design.md`,
 * vocabulary `docs/GLOSSARY.md` §1-A): the **Access Catalog** (the tree of menus → panes →
 * capability-bound action nodes, derived by the UI and snapshotted to the backend) and one
 * **Access Profile** per subject. Subjects are Lenses today (visibility shaping, honor system);
 * under RBAC the same documents carry `subjectType: 'role'` and are enforced server-side.
 */

export type AccessNodeKind = 'menu' | 'pane' | 'action';
export type AccessGrant = 'allow' | 'deny';
export type AccessSubjectType = 'lens' | 'role';

export interface AccessNode {
    id: string;
    label: string;
    kind: AccessNodeKind;
    icon?: string;
    link?: string;
    /** For `kind: 'action'` — the one LensService capability this functionality binds to. */
    capability?: string;
    children?: AccessNode[];
}

export interface AccessCatalog {
    version: number;
    nodes: AccessNode[];
}

/** Sparse grants: absent nodeId = inherit from the nearest explicit ancestor; root default = allow. */
export interface AccessProfile {
    subjectType: AccessSubjectType;
    subjectId: string;
    label: string;
    grants: Record<string, AccessGrant>;
}

/** The profile's document/URL id — `<subjectType>-<subjectId>` (e.g. `lens-business`). */
export function accessProfileId(p: Pick<AccessProfile, 'subjectType' | 'subjectId'>): string {
    return `${p.subjectType}-${p.subjectId}`;
}

/** One role's grants (RBAC R1 — the authorable `roles.toon` table behind every OIDC subject). */
export interface RoleDef {
    name: string;
    capabilities: string[];
    /** SEC-7d data scoping; absent = the role contributes no scoping (unscoped). */
    dataScopes?: string[];
    /** GET only: whether the row comes from the authored doc or the shipped seed defaults. */
    source?: 'authored' | 'seed';
}

/** `GET /access/roles` — the effective table; `error` set ⇔ the authored doc is unreadable
 *  (all role grants suspended, fail-closed) until fixed or re-saved. */
export interface RolesDoc {
    roles: RoleDef[];
    error?: string;
}

/** One Access Policy (ABAC A2/A3): an allow/deny over subject/resource/environment attributes. */
export interface PolicyDef {
    name: string;
    effect: AccessGrant;
    target?: { actions?: string[]; resourceKinds?: string[] };
    when?: string;
    /** GET only: `authored` = written to the doc; `seed` = an engine-resident built-in (A4 space
     *  isolation) surfaced read-only so operators see the denies they never wrote. */
    source?: 'authored' | 'seed';
}

/** A save-time finding the server does not refuse (policy-authoring §4) — e.g. `unknown-role`,
 *  `unknown-capability`, `unknown-resource-kind`, `resource-ref-at-route-level`, `seed-override`. */
export interface PolicyWarning {
    policy: string;
    code: string;
    message: string;
}

/** `GET /access/policies` — authored policies plus the engine's seed policies (Enterprise only);
 *  `error` set ⇔ the authored doc is unreadable (the engine denies, fail-closed) until fixed.
 *  `etag` is the response header (the `If-Match` a save echoes), not a body field. */
export interface PoliciesDoc {
    policies: PolicyDef[];
    error?: string;
    warnings?: PolicyWarning[];
    /** The resource kinds a row-level policy can target — served (`AccessPolicies.RESOURCE_KINDS`),
     *  never mirrored here; anything else is an `unknown-resource-kind` warning. */
    resourceKinds?: string[];
    etag?: string;
}

/** One cell of `POST /access/policies/preview`: a role × action (× kind) decision before → after. */
export interface PolicyPreviewCell {
    role: string;
    action: 'read' | 'write' | 'operate';
    resourceKind: string | null;
    before: 'ALLOW' | 'DENY' | 'ABSTAIN';
    beforePolicy: string | null;
    after: 'ALLOW' | 'DENY' | 'ABSTAIN';
    afterPolicy: string | null;
    changed: boolean;
}

/** `POST /access/policies/preview` — the draft's impact; `enabled:false` without a policy engine. */
export interface PolicyPreview {
    enabled: boolean;
    reason?: string;
    route?: string;
    roles?: string[];
    actions?: string[];
    kinds?: string[];
    cells?: PolicyPreviewCell[];
    warnings?: PolicyWarning[];
}

/** The PUT/preview body shape of one policy — the GET's read-only `source` stripped (the server
 *  refuses an unknown key, F8). */
function policyBody(p: PolicyDef): PolicyDef {
    const target: PolicyDef['target'] = {};
    if (p.target?.actions?.length) target.actions = p.target.actions;
    if (p.target?.resourceKinds?.length) target.resourceKinds = p.target.resourceKinds;
    return {
        name: p.name,
        effect: p.effect,
        ...(target.actions || target.resourceKinds ? { target } : {}),
        ...(p.when?.trim() ? { when: p.when.trim() } : {}),
    };
}

/** One policy's contribution to an explain trace. A policy decides only when both are true. */
export interface PolicyEvaluation {
    name: string;
    effect: string;
    source: string;
    targeted: boolean;
    conditionHeld: boolean;
}

/** `GET /access/explain` — a "why denied?" dry-run for the caller's own session. `enabled:false`
 *  when there is no policy engine (Personal/Professional) or no authenticated subject. */
export interface ExplainResult {
    enabled: boolean;
    reason?: string;
    subject?: string;
    action?: string;
    route?: string;
    resourceKind?: string;
    decision?: 'ALLOW' | 'DENY' | 'ABSTAIN';
    matchedPolicy?: string | null;
    trace?: PolicyEvaluation[];
}

@Injectable({ providedIn: 'root' })
export class AccessService {
    private http = inject(HttpClient);

    catalog(): Observable<AccessCatalog> {
        return this.http.get<AccessCatalog>(apiUrl('/access/catalog'));
    }

    saveCatalog(catalog: AccessCatalog): Observable<AccessCatalog> {
        return this.http.put<AccessCatalog>(apiUrl('/access/catalog'), catalog);
    }

    profiles(): Observable<AccessProfile[]> {
        return this.http.get<AccessProfile[]>(apiUrl('/access/profiles'));
    }

    saveProfile(profile: AccessProfile): Observable<AccessProfile> {
        return this.http.put<AccessProfile>(
            apiUrl(`/access/profiles/${encodeURIComponent(accessProfileId(profile))}`),
            profile,
        );
    }

    /** The effective policies — authored rows tagged `source:authored`, plus the engine's seed
     *  policies tagged `source:seed` (Enterprise only). */
    policies(): Observable<PoliciesDoc> {
        return this.http
            .get<PoliciesDoc>(apiUrl('/access/policies'), { observe: 'response' })
            .pipe(map((res) => ({ ...(res.body as PoliciesDoc), etag: res.headers.get('ETag') ?? undefined })));
    }

    /** Full replace of the AUTHORED policies (settings-doc discipline). `etag` (from {@link policies})
     *  rides as `If-Match`, so a concurrent save is a `409 CONFLICT_STALE_VERSION`, never a silent
     *  overwrite. A 422 names the policy and the failed check — including `would-lock-out` (F7). */
    savePolicies(authored: PolicyDef[], etag?: string): Observable<PoliciesDoc> {
        return this.http
            .put<PoliciesDoc>(
                apiUrl('/access/policies'),
                { policies: authored.map(policyBody) },
                { observe: 'response', ...(etag ? { headers: { 'If-Match': etag } } : {}) },
            )
            .pipe(map((res) => ({ ...(res.body as PoliciesDoc), etag: res.headers.get('ETag') ?? undefined })));
    }

    /** The impact of an unsaved authored list (S4) — nothing is written. */
    previewPolicies(authored: PolicyDef[]): Observable<PolicyPreview> {
        return this.http.post<PolicyPreview>(apiUrl('/access/policies/preview'), {
            policies: authored.map(policyBody),
        });
    }

    /** "Why denied?" dry-run for the current session against a hypothetical route/method/resource. */
    explain(p: { route: string; method?: string; resourceKind?: string }): Observable<ExplainResult> {
        return this.http.get<ExplainResult>(apiUrl('/access/explain'), {
            params: toParams({ route: p.route, method: p.method, resourceKind: p.resourceKind }),
        });
    }

    roles(): Observable<RolesDoc> {
        return this.http.get<RolesDoc>(apiUrl('/access/roles'));
    }

    /** Full replace of the AUTHORED overlay (settings-doc discipline): roles named here override
     *  their seed entry (an empty capability list revokes); seed roles not named keep their
     *  defaults. Returns the resulting effective table. */
    saveRoles(authored: RoleDef[]): Observable<RolesDoc> {
        return this.http.put<RolesDoc>(apiUrl('/access/roles'), {
            roles: authored.map((r) => ({
                name: r.name,
                capabilities: r.capabilities,
                ...(r.dataScopes?.length ? { dataScopes: r.dataScopes } : {}),
            })),
        });
    }
}
