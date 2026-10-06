import { firstValueFrom } from 'rxjs';
import type { LaTransfer } from '@inspecto/link-analysis/la-host';

/**
 * Import as draft — the reference re-check just before Save (D3; operator 2026-10-06): the content about to
 * be WRITTEN, edits included, judged by the host's read-only preview. Advisory — the Save goes ahead either
 * way. `null` = could not run (no host seam, or the preview failed): "not checked", never clean.
 */
export async function recheckDraft(
    transfer: LaTransfer,
    kind: string,
    id: string,
    content: Record<string, unknown>,
): Promise<string[] | null> {
    if (!transfer.recheck) return null;
    try {
        return await firstValueFrom(transfer.recheck(kind, id, content), { defaultValue: null });
    } catch {
        return null;
    }
}

/** The toast after a draft's Save: findings ⇒ a warning naming them; an unrun re-check ⇒ "could not run". */
export function draftSavedWarning(name: string, integrity: string[] | null): string | null {
    if (integrity === null)
        return `Saved “${name}” — the reference check could not run, so broken references are unknown.`;
    if (!integrity.length) return null;
    return `Saved “${name}” with ${integrity.length} broken reference(s): ${integrity.join('; ')}`;
}
