import { Injector, Provider, inject } from '@angular/core';
import { ADD_DATA_LINK_ANALYSIS, AddDataLinkAnalysis } from '@inspecto/core/add-data/add-data-link-analysis';
import { EntityProfileService } from '@inspecto/link-analysis/api/entity-profile.service';
import { GraphRunsService } from '@inspecto/link-analysis/api/graph-runs.service';
import { IndexRebuildComponent } from './index-rebuild.component';
import { datasetIndexStatus } from './index-status';
import { LaLimitsService } from './la-limits.service';

/**
 * What Link Analysis gives the core **Add data** screen and Dataset card (BASIC-ELT-PROFILE-1 UI-0): the Space's Entity
 * Types, the `import.*` limits, the link index's live status and its Rebuild. A page that places Add data or a Dataset card
 * lists this in its own `providers`, so the Link Analysis code stays in that page's lazy chunk (never the shell's eager
 * provider set: `tools/bundle-budget.json` forbids library code there).
 *
 * Each service is resolved on first use, not when the token is: a page that only shows a card never starts the limits
 * read, as before the move.
 */
export function provideAddDataLinkAnalysis(): Provider[] {
    return [
        {
            provide: ADD_DATA_LINK_ANALYSIS,
            useFactory: (): AddDataLinkAnalysis => {
                const injector = inject(Injector);
                const runs = () => injector.get(GraphRunsService);
                return {
                    entityTypes: () => injector.get(EntityProfileService).entityTypes(),
                    limits: () => injector.get(LaLimitsService).items,
                    indexStatus: (dataset) => datasetIndexStatus(runs().indexes(), dataset),
                    loadIndexes: () => runs().loadIndexes(),
                    reloadIndexes: () => runs().reloadIndexes(),
                    rebuild: IndexRebuildComponent,
                };
            },
        },
    ];
}
