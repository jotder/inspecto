import { ParamMap } from '@angular/router';
import { JobSeed } from './job-form.dialog';

/**
 * The create-dialog prefill handshake (`/jobs?create=1&type=recon.run&param.reconciliation=<id>`): another pane links
 * here to schedule something (R: "Schedule this reconciliation"). Pure, so it is spec-able without a router.
 *
 * <p>Deliberately strict, because the values come from a URL: a Job Type id is a dotted/dashed identifier, a parameter
 * name an identifier, a value is capped at {@link MAX_VALUE} characters and carries no control characters. Anything else is
 * dropped (never repaired), and the dialog applies a value only to a parameter the Job Type DECLARES, so a link can
 * neither add an arbitrary parameter nor reach a field the type does not have.
 */
const TYPE_ID = /^[A-Za-z][\w.-]{0,63}$/;
const PARAM_NAME = /^[A-Za-z_][\w]{0,63}$/;
const MAX_VALUE = 200;
const PARAM_PREFIX = 'param.';

/** A query key this handshake owns, so the caller can strip them all after reading. */
export function isSeedKey(key: string): boolean {
    return key === 'type' || key.startsWith(PARAM_PREFIX);
}

/** The seed in {@code q}, or {@code undefined} when there is no (valid) Job Type. */
export function jobSeedFromQuery(q: ParamMap): JobSeed | undefined {
    const type = q.get('type');
    if (!type || !TYPE_ID.test(type)) return undefined;
    const params: Record<string, string> = {};
    for (const key of q.keys) {
        if (!key.startsWith(PARAM_PREFIX)) continue;
        const name = key.slice(PARAM_PREFIX.length);
        const value = q.get(key);
        // eslint-disable-next-line no-control-regex
        if (!PARAM_NAME.test(name) || value == null || value.length > MAX_VALUE || /[\u0000-\u001f]/.test(value))
            continue;
        params[name] = value;
    }
    return { type, ...(Object.keys(params).length ? { params } : {}) };
}
