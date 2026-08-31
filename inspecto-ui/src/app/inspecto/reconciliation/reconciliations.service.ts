import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
import { CompareColumn, ReconBreak, Reconciliation, ReconciliationConfig } from './reconciliation-types';

/**
 * Reconciliation store — persists {@link Reconciliation}s through the component registry as the
 * `reconciliation` component type (mock-served today). Mirrors `rules.service.ts`/`datasets.service.ts` —
 * a reconciliation is "just a component" whose body carries the match config + the last run's breaks.
 */
@Injectable({ providedIn: 'root' })
export class ReconciliationsService {
    private components = inject(ComponentsService);

    list(): Observable<Reconciliation[]> {
        return this.components
            .list('reconciliation')
            .pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    get(id: string): Observable<Reconciliation> {
        return this.components.get('reconciliation', id).pipe(map((d) => fromContent(d.name, d.content)));
    }

    create(r: Reconciliation): Observable<Reconciliation> {
        return this.components.create('reconciliation', { id: r.id, ...toContent(r) }).pipe(map(() => r));
    }

    save(r: Reconciliation): Observable<Reconciliation> {
        return this.components.update('reconciliation', r.id, { id: r.id, ...toContent(r) }).pipe(map(() => r));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('reconciliation', id);
    }
}

function toContent(r: Reconciliation): Record<string, unknown> {
    return {
        name: r.name,
        leftDataset: r.leftDataset,
        rightDataset: r.rightDataset,
        ...(r.thirdDataset ? { thirdDataset: r.thirdDataset } : {}),
        keyColumns: r.keyColumns,
        compareColumns: r.compareColumns,
        ...(r.bands ? { bands: r.bands } : {}),
        breaks: r.breaks,
        lastRunAt: r.lastRunAt ?? null,
    };
}

function fromContent(name: string, content: Record<string, unknown>): Reconciliation {
    const c = content as Partial<ReconciliationConfig> & { name?: string };
    // 🔴 Dual-read: an AUTHORED reconciliation on disk uses the v2 anchor-first `datasets[]` list —
    // the same shape `serverConfig()` posts to /recon/run — while UI-written ones use the legacy
    // left/right/third fields. Reading only the legacy trio left every authored recon with blank
    // datasets: the grid showed empty Left/Right columns and Run posted `datasets: []`, which the
    // server refused with "expected 2 or 3 datasets … got 0" (BACKLOG MOCK-GONE-1(c)).
    // ⚠ Order matters — the explicit field wins, so a config carrying both is read as authored.
    const list = Array.isArray(c.datasets) ? c.datasets.map((d) => String(d ?? '').trim()) : [];
    return {
        id: name,
        name: c.name ?? name,
        leftDataset: c.leftDataset ?? list[0] ?? '',
        rightDataset: c.rightDataset ?? list[1] ?? '',
        thirdDataset: c.thirdDataset ?? list[2] ?? undefined,
        keyColumns: (c.keyColumns as string[]) ?? [],
        compareColumns: (c.compareColumns as CompareColumn[]) ?? [],
        bands: c.bands,
        breaks: (c.breaks as ReconBreak[]) ?? [],
        lastRunAt: c.lastRunAt ?? null,
    };
}
