import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, switchMap } from 'rxjs';

import { ComponentsService } from './components.service';

/** What {@link DatasetRegistrationService.ensure} did. It never errors — see the service doc. */
export type DatasetEnsure =
    | { status: 'exists'; store: string }
    | { status: 'created'; store: string }
    | { status: 'failed'; store: string };

/**
 * How a host tells the operator to finish the job by hand. Shared so both go-live surfaces say the
 * same thing — the wording is the recovery instruction, not decoration.
 */
export function datasetManualHint(store: string): string {
    return `create it under Catalog ▸ Datasets with physical reference "${store}".`;
}

/**
 * **The Stream→Dataset hop at go-live.** Activating a stream registers a Dataset over the store it
 * lands, so the landed data is queryable without anyone authoring one by hand.
 *
 * <p>Extracted from `onboarding-shell.component` in P6-b, because the Pipelines editor's toolbar
 * activation needs the identical hop and P6-e deletes that shell. ⛔ Do NOT re-inline it into a
 * host: the idempotency rule, the `sourceName` fix and the failure posture below are exactly the
 * kind of subtlety a second hand-written copy loses.
 *
 * <p><b>Idempotent</b> — a Dataset already pointing at the store wins, whatever its id. <b>Never
 * throws and never rejects the activation</b>: by the time this runs the pipeline is already live,
 * so every failure downgrades to a `failed` result the host reports as a warning with
 * {@link datasetManualHint}. Deciding a pipeline is a stream (not a reference) is the HOST's call —
 * the two hosts read that from different places.
 */
@Injectable({ providedIn: 'root' })
export class DatasetRegistrationService {
    private components = inject(ComponentsService);

    /**
     * @param store the normalized pipeline id — the engine's `Identity.pipelineName`, which is also
     *              the landed store's name and the Dataset's `physicalRef`.
     * @param display the human label for the Dataset (identity/display split).
     */
    ensure(store: string, display: string): Observable<DatasetEnsure> {
        return this.components.list('dataset').pipe(
            switchMap((defs) => {
                if (defs.some((d) => d.content['physicalRef'] === store))
                    return of<DatasetEnsure>({ status: 'exists', store });
                return this.components
                    .create('dataset', {
                        id: store,
                        name: display,
                        kind: 'physical',
                        // The store IS the source. Leaving this blank used to fall through to a
                        // sample-source default that names nothing, so the dataset silently read
                        // zero rows everywhere; naming the store is honest whether or not a preview
                        // for it exists yet (BACKLOG §4 split S2, slice B).
                        sourceName: store,
                        physicalRef: store,
                        description: `Landed data of stream "${display}" — registered at go-live.`,
                    })
                    .pipe(
                        map<unknown, DatasetEnsure>(() => ({ status: 'created', store })),
                        catchError(() => of<DatasetEnsure>({ status: 'failed', store })),
                    );
            }),
            catchError(() => of<DatasetEnsure>({ status: 'failed', store })),
        );
    }
}
