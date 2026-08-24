import { inject, Injectable, signal } from '@angular/core';
import { GammaNavigationItem } from '@gamma/components/navigation';
import { AccessGrant, AccessProfile, AccessService } from '../api/access.service';
import { Lens, LensService } from '../api/lens.service';
import {
    CatalogIndex,
    deriveDefaultAccessCatalog,
    filterNavByAccess,
    filterNavByLens,
    indexCatalog,
    resolveGrant,
} from './access-catalog';

/**
 * Applies saved lens Access Profiles at runtime (design `lens-access-config-design.md` §7): filters
 * the sidebar for the current lens and pushes the action-node grants into {@link LensService} so the
 * capability signals re-derive — the RBAC seam exercised with lens subjects. Instantiated by the
 * classic layout; when never instantiated (or the backend has no profiles / is unreachable) nothing
 * changes: no profile ⇒ everything stays allowed, exactly today's behavior.
 */
@Injectable({ providedIn: 'root' })
export class AccessStateService {
    private readonly api = inject(AccessService);
    private readonly lens = inject(LensService);
    private readonly idx: CatalogIndex = indexCatalog(deriveDefaultAccessCatalog());

    /** lens id → its saved grants; empty until profiles load (= nothing denied). */
    private readonly grantsByLens = signal<Partial<Record<Lens, Record<string, AccessGrant>>>>({});

    constructor() {
        this.reload();
    }

    /** (Re)fetch profiles — also called by the Access matrix after a save so the app reflects it live. */
    reload(): void {
        this.api.profiles().subscribe({
            next: (profiles) => this.apply(profiles ?? []),
            error: () => this.apply([]), // unreachable / read-only backend → allow-all fallback
        });
    }

    /**
     * Sidebar for the current lens: the lens-default scope first (finding #10 — a focused subset per
     * lens even with no profiles), then the saved Access Profile denies on top (identity when none).
     */
    filterNav(items: GammaNavigationItem[]): GammaNavigationItem[] {
        const lens = this.lens.currentLens();
        const scoped = filterNavByLens(items, lens);
        const grants = this.grantsByLens()[lens];
        return grants ? filterNavByAccess(scoped, grants, this.idx) : scoped;
    }

    private apply(profiles: AccessProfile[]): void {
        const byLens: Partial<Record<Lens, Record<string, AccessGrant>>> = {};
        for (const p of profiles) {
            if (p.subjectType === 'lens') byLens[p.subjectId as Lens] = p.grants ?? {};
        }
        this.grantsByLens.set(byLens);
        this.lens.setActionGrants(this.actionGrants(byLens));
    }

    /** Per action node: is it effectively allowed for each lens? (No profile for a lens = allowed.) */
    private actionGrants(
        byLens: Partial<Record<Lens, Record<string, AccessGrant>>>,
    ): Record<string, Partial<Record<Lens, boolean>>> {
        const out: Record<string, Partial<Record<Lens, boolean>>> = {};
        for (const node of this.idx.byId.values()) {
            if (node.kind !== 'action') continue;
            const per: Partial<Record<Lens, boolean>> = {};
            for (const l of LensService.LENSES) {
                const grants = byLens[l.id];
                per[l.id] = !grants || resolveGrant(node.id, grants, this.idx).effective === 'allow';
            }
            out[node.id] = per;
        }
        return out;
    }
}
