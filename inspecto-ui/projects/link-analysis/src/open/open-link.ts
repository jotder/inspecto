import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from '@inspecto/core/api/api-base';

/**
 * The "Open in Inspecto LA" deep link (LA-EDITIONS-1 section 4, roadmap I5): the URL another tool builds to land a user on an
 * Entity or an Investigation.
 *
 *     <la-app>/open?entity=<type>:<key>[&from=<instant>&to=<instant>][&investigation=<id>]
 *     <console>/studio/open?...   (the same page)
 *
 * `entity` is typed by an Entity Type id; for a type masked under the Space's maskingMode the key is the exploration alias
 * (`masked:<16 hex>`, from `POST /inv/entities/{type}/resolve`) - a raw identifier never travels in a URL. `investigation` is
 * the id `POST /inv/inbound/investigations` answered. `from` / `to` are ISO-8601 instants with a zone.
 */
export interface OpenLink {
    entity?: string;
    investigation?: string;
    window?: { from?: string; to?: string };
}

/** The parameters a link may carry; anything else is refused, so a typo is never silently ignored. */
export const OPEN_LINK_PARAMS = ['entity', 'investigation', 'from', 'to'] as const;

const ENTITY = /^[A-Za-z0-9_.-]{1,64}:\S/;
const INVESTIGATION = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/;
const MAX_KEY = 256;

/** The link's parameters checked in the browser, or the readable reason they cannot be opened. The server re-checks them. */
export function parseOpenLink(params: {
    keys: readonly string[];
    get(name: string): string | null;
}): { link: OpenLink } | { error: string } {
    const unknown = params.keys.filter((k) => !(OPEN_LINK_PARAMS as readonly string[]).includes(k));
    if (unknown.length)
        return {
            error: `This link has a parameter Inspecto LA does not know: ${unknown.join(', ')}. It takes ${OPEN_LINK_PARAMS.join(', ')}.`,
        };
    const entity = (params.get('entity') ?? '').trim();
    const investigation = (params.get('investigation') ?? '').trim();
    const from = (params.get('from') ?? '').trim();
    const to = (params.get('to') ?? '').trim();
    if (!entity && !investigation)
        return { error: 'This link names no entity and no Investigation, so there is nothing to open.' };
    if (entity && (!ENTITY.test(entity) || entity.length > MAX_KEY + 65))
        return { error: 'The entity in this link is not "<type>:<key>" - an Entity Type, a colon and the key.' };
    if (investigation && !INVESTIGATION.test(investigation))
        return { error: 'The Investigation id in this link is not a valid id.' };
    for (const [name, v] of [
        ['from', from],
        ['to', to],
    ] as const)
        if (v && (!INSTANT.test(v) || Number.isNaN(Date.parse(v))))
            return {
                error: `"${name}" in this link is not a date and time with a zone (for example 2026-10-01T00:00:00Z).`,
            };
    if (from && to && Date.parse(from) >= Date.parse(to))
        return { error: 'The window in this link ends before it starts.' };
    const link: OpenLink = {};
    if (entity) link.entity = entity;
    if (investigation) link.investigation = investigation;
    if (from || to) link.window = { ...(from ? { from } : {}), ...(to ? { to } : {}) };
    return { link };
}

/** `POST /inv/deep-links/resolve`: what the link names, as this caller may see it. */
export interface DeepLinkResolution {
    entity?: { type: string; key: string; masked: boolean; revealed?: string };
    investigation?: { id: string; title: string | null };
    window?: { from: string | null; to: string | null };
}

@Injectable({ providedIn: 'root' })
export class DeepLinkService {
    private http = inject(HttpClient);

    /** Validate the link on the server (the parameters travel in the body); `reveal` asks for a masked entity's raw value. */
    resolve(link: OpenLink, reveal = false): Observable<DeepLinkResolution> {
        return this.http.post<DeepLinkResolution>(
            apiUrl('/inv/deep-links/resolve'),
            reveal ? { ...link, reveal } : link,
        );
    }
}
