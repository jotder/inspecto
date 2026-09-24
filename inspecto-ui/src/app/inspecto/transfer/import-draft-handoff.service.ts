import { Injectable, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ImportDraft } from './import-draft';

/**
 * Carries ONE {@link ImportDraft} across the single in-app navigation from the editor it was imported in
 * to the editor that must show it (a different id, or the create route) — the only reason a draft ever
 * leaves the pane that received it. In memory only (D1): a reload drops it, and {@link take} consumes it,
 * so it cannot resurface on a later visit.
 */
@Injectable({ providedIn: 'root' })
export class ImportDraftHandoff {
    private router = inject(Router);
    private pending: ImportDraft | null = null;

    /**
     * Stash the draft and route to `target`. ⚠ Two edit routes of one editor share a route config, so the
     * router would REUSE the component and its `ngOnInit` (where the draft is taken) would never run —
     * that hop bounces through `listUrl` first, off the address bar.
     */
    open(draft: ImportDraft, target: string[], listUrl: string, fromEditRoute: boolean): void {
        this.pending = draft;
        const go = () => void this.router.navigate(target);
        if (fromEditRoute && draft.targetExists) {
            void this.router.navigateByUrl(listUrl, { skipLocationChange: true }).then(go);
        } else {
            go();
        }
    }

    /** The pending draft for this editor (`kind`, and `id` when editing), consumed; otherwise null. */
    take(kind: string, id: string | undefined): ImportDraft | null {
        const d = this.pending;
        if (!d || d.kind !== kind || (id ? d.id !== id : d.targetExists)) return null;
        this.pending = null;
        return d;
    }
}
