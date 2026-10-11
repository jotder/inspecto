import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-base';
import { ComponentDef, ComponentsService } from './components.service';

/** One baseline of a Feature's explanation (self or peer), as `AnomalyScorer.FeatureResult#toMap` serves it. */
export interface AnomalyBaseline {
    kind?: string;
    median: number | null;
    mad: number | null;
    points?: number;
    season?: string;
    basis?: string;
    fellBack?: boolean;
}

/** One Peer Group baseline: the cohort's median/MAD of the scored-day observations. */
export interface AnomalyPeerBaseline {
    cohort: string | null;
    basis: string | null;
    median: number | null;
    mad: number | null;
    size: number;
    fellBack: boolean;
    insufficient: boolean;
}

/** One Feature of an Anomaly Score: observed vs its baselines, the z values and the `reason` line. */
export interface AnomalyFeatureResult {
    feature: string;
    label?: string;
    bucket?: string;
    observed: number | null;
    baseline: AnomalyBaseline;
    peerBaseline?: AnomalyPeerBaseline;
    cohortShift?: number;
    zPeer?: number | null;
    peersOnly?: boolean;
    zSelf: number | null;
    deviation: number;
    direction: string;
    weight: number;
    contribution: number;
    share: number;
    insufficient: boolean;
    reason: string;
}

/** `POST /anomaly-scores/preview` — one entity scored now, nothing written; the key may come back masked. */
export interface AnomalyScorePreview {
    model: string;
    entityType: string;
    entityKey: string;
    keyMasked?: boolean;
    found: boolean;
    periodStart: string;
    score: number | null;
    band: string | null;
    raw: number | null;
    elevatedThreshold: number;
    highThreshold: number;
    insufficientCount: number | null;
    saved: boolean;
    features: AnomalyFeatureResult[];
}

/**
 * Anomaly Model authoring (ANOMALY-DETECTION-1 S5 lane A): the `anomaly-model` component CRUD over the generic
 * `/components/anomaly-model` routes and the preview. The entity read (`GET /anomaly-scores/{model}/{key}`) belongs
 * to `anomaly-scores.service.ts`, not here.
 */
@Injectable({ providedIn: 'root' })
export class AnomalyModelsService {
    private http = inject(HttpClient);
    private components = inject(ComponentsService);

    list(): Observable<ComponentDef[]> {
        return this.components.list('anomaly-model');
    }

    get(id: string): Observable<ComponentDef> {
        return this.components.get('anomaly-model', id);
    }

    create(content: Record<string, unknown>): Observable<ComponentDef> {
        return this.components.create('anomaly-model', content);
    }

    /** `ifMatch` = the bare `contentHash` of the read the edit started from. */
    update(id: string, content: Record<string, unknown>, ifMatch?: string): Observable<ComponentDef> {
        return this.components.update('anomaly-model', id, content, { ifMatch });
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('anomaly-model', id);
    }

    /** Preview a SAVED model for one entity (needs only `canWorkIncidents`); `asOf` is YYYY-MM-DD, UTC. */
    preview(model: string, entityKey: string, asOf?: string): Observable<AnomalyScorePreview> {
        return this.http.post<AnomalyScorePreview>(apiUrl('/anomaly-scores/preview'), {
            model,
            entityKey,
            ...(asOf ? { asOf } : {}),
        });
    }

    /**
     * Preview UNSAVED model content for one entity — the same route with `content` instead of `model`. The server
     * also requires `canAuthorWorkbench` and runs the save-time checks (422 with the field-led message).
     */
    previewContent(
        content: Record<string, unknown>,
        entityKey: string,
        asOf?: string,
    ): Observable<AnomalyScorePreview> {
        return this.http.post<AnomalyScorePreview>(apiUrl('/anomaly-scores/preview'), {
            content,
            entityKey,
            ...(asOf ? { asOf } : {}),
        });
    }
}
