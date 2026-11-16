/**
 * The data-layer **screen gates** of `DATA-TIERS-1` (P2): `/bootstrap` `features.<flag>` says whether this edition offers
 * the capability, and `featureReasons.<flag>` says why it is locked and which edition has it. Screen gates only: the server
 * runs any configuration it is given (plan §5.3, DT-9 narrowed); the screens show the control locked with the reason
 * (DT-13, `<inspecto-locked-action>`). The console ships with its server, so a flag the server does not report is offered.
 */
export type ScreenFlag = 'pipelineAutomation' | 'pipelineTransform' | 'pipelineEgress' | 'remoteSystems';

/** What a lock says when the server reports the flag false without a `featureReasons` line. */
export const SCREEN_LOCK_FALLBACK: Readonly<Record<ScreenFlag, string>> = {
    pipelineAutomation: 'Keeping a Dataset up to date on its own, and retention policies, are in Standard and above.',
    pipelineTransform:
        'Steps that compute, join, enrich or summarise are in Standard and above; Basic shapes columns and filters rows.',
    pipelineEgress:
        'Delivering data out of Inspecto (object storage export, publish to Postgres) is in Standard and above.',
    remoteSystems:
        'Collecting from remote systems (SFTP, FTP, object stores, databases, Kafka) is in Standard and above; Basic adds files and copies Postgres tables.',
};

/** Why `flag` is locked here (`/bootstrap` reports it false), or null when it is offered. The server's line wins. */
export function screenLock(
    flag: string,
    features: Readonly<Record<string, boolean>>,
    reasons: Readonly<Record<string, string>>,
): string | null {
    if (features[flag] !== false) return null;
    return reasons[flag] || SCREEN_LOCK_FALLBACK[flag as ScreenFlag] || 'Not in this edition.';
}

/** Every withheld screen flag with its reason: `{flag: reason}` (empty when the edition offers them all). */
export function screenLocks(
    features: Readonly<Record<string, boolean>>,
    reasons: Readonly<Record<string, string>>,
): Readonly<Record<string, string>> {
    const out: Record<string, string> = {};
    for (const flag of Object.keys(SCREEN_LOCK_FALLBACK)) {
        const why = screenLock(flag, features, reasons);
        if (why) out[flag] = why;
    }
    return out;
}
