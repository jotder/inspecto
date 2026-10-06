/**
 * Read-only view model of a stored `risk-score` component (ASSURE-RISK-SCORE-RESIDUALS-1 (2), slice S1).
 * Framework-free: the pane renders what this returns and never reads `content` itself.
 */

/** One factor row as the pane shows it. */
export interface RiskFactorView {
    id: string;
    label: string;
    dataset: string;
    key: string;
    measure: string;
    filterCount: number;
    weight: number | null;
    cap: number | null;
    evidence: string[];
}

export interface RiskModelView {
    id: string;
    description: string;
    entityType: string;
    highThreshold: number | null;
    dataScope: string;
    factors: RiskFactorView[];
    watchList: string;
    retention: string;
    /** The two Datasets the Job derives; never authorable. */
    outputs: string[];
    /** Why the stored model cannot be summarised (missing id / factors), or null when it can. */
    invalid: string | null;
}

const str = (v: unknown): string => (typeof v === 'string' ? v : v == null ? '' : String(v));
const num = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

export function riskModelView(name: string, content: Record<string, unknown>): RiskModelView {
    const id = str(content['id']) || name;
    const rawFactors = Array.isArray(content['factors']) ? (content['factors'] as unknown[]) : [];
    const factors = rawFactors
        .filter((f): f is Record<string, unknown> => !!f && typeof f === 'object')
        .map((f) => ({
            id: str(f['id']),
            label: str(f['label']) || str(f['id']),
            dataset: str(f['dataset']),
            key: str(f['key']),
            measure: str(f['measure']),
            filterCount: Array.isArray(f['filters']) ? (f['filters'] as unknown[]).length : 0,
            weight: num(f['weight']),
            cap: num(f['cap']),
            evidence: Array.isArray(f['evidence']) ? (f['evidence'] as unknown[]).map(str) : [],
        }));
    const watch = content['watchList'] as Record<string, unknown> | undefined;
    const days = num(content['retainDays']);
    const runs = num(content['retainRuns']);
    return {
        id,
        description: str(content['description']),
        entityType: str(content['entityType']),
        highThreshold: num(content['highThreshold']),
        dataScope: str(content['dataScope']),
        factors,
        watchList: watch && typeof watch === 'object' ? `${str(watch['list'])} (${str(watch['ttlHours'])} h)` : '',
        retention: days != null ? `${days} days` : runs != null ? `${runs} runs` : 'Kept forever',
        outputs: [`risk_scores_${id}`, `risk_scores_${id}_latest`],
        invalid: factors.length === 0 ? 'No factors — open it in the raw editor' : null,
    };
}

/**
 * The ONE place an entity key is turned into display text (D-RP6, operator 2026-10-06; D-P8 = mask on read).
 * Keeps the last four characters; a key of four or fewer characters is fully masked. A key the server already
 * masked (the Space `masked:` token, for a caller without canRevealLinkEntities) is shown as-is — it holds no raw
 * characters, and bulleting it would pass four hex digits off as the tail of the real key.
 */
export function displayEntityKey(key: string): string {
    if (!key) return '';
    if (key.startsWith('masked:')) return key;
    if (key.length <= 4) return '•'.repeat(key.length);
    return '•'.repeat(key.length - 4) + key.slice(-4);
}
