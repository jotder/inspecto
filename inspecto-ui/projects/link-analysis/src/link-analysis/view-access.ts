import type { LinkAnalysisView } from './link-analysis.service';

/**
 * Who may write a saved view (Component kind `link-analysis-view`), mirroring the server so the Studio never offers a
 * write the server refuses with a raw 403 (UI test 2026-10-29). The generic `/components` write routes ask
 * `canAuthorWorkbench` (`ComponentRoutes`); a delete of a SHARED view (`shares` present) is owner-only, or an access
 * admin (`ComponentAccess.requireDelete`, `canConfigureAccess`). An unshared view may be deleted by any author.
 */
export const VIEW_AUTHOR_REASON = 'Saving views needs the Workbench author permission.';
export const VIEW_DELETE_AUTHOR_REASON = 'Deleting saved views needs the Workbench author permission.';
export const VIEW_DELETE_OWNER_REASON = 'Only its owner can delete a shared saved view.';

export interface ViewAccessContext {
    /** `LensService.canAuthorWorkbench()`. */
    canAuthor: boolean;
    /** `LensService.canConfigureAccess()`: an access admin owns every Component. */
    canConfigureAccess: boolean;
    /** The signed-in subject (`SessionService.actor()`); null off sign-in, where the server checks no owner. */
    actor: string | null;
}

/** Why a saved view cannot be saved (created or updated) here; '' = it can. */
export function viewSaveBlock(ctx: ViewAccessContext): string {
    return ctx.canAuthor ? '' : VIEW_AUTHOR_REASON;
}

/** Why this saved view cannot be deleted here; '' = it can. */
export function viewDeleteBlock(view: Pick<LinkAnalysisView, 'owner' | 'shared'>, ctx: ViewAccessContext): string {
    if (!ctx.canAuthor) return VIEW_DELETE_AUTHOR_REASON;
    if (!view.shared || !ctx.actor || ctx.canConfigureAccess) return '';
    return view.owner === ctx.actor ? '' : VIEW_DELETE_OWNER_REASON;
}
