import { Injectable, computed, signal } from '@angular/core';
import { ParsingPreview, SchemaPreview } from 'app/inspecto/api';

/**
 * The sample thread shared by every definition surface (definition-surface-unification D5).
 *
 * One captured sample flows raw → parsed → cast, so each editor tests against the same bytes the
 * previous one did. This is **session state, never persisted**: it is re-capturable, it is not part
 * of the pipeline config, and a new sample invalidates every downstream result.
 *
 * `@Injectable()` with no `providedIn` on purpose — a host **provides** it, so two editors open on
 * different pipelines do not share one sample. Do not make this root-provided. ⚠ Since P6-e retired
 * the onboarding shell (its one provider) the remaining host is the pipeline editor, which is
 * MULTI-TAB: it must provide this **per tab**, not once for the pane, or one sample leaks across
 * every open pipeline.
 *
 * Scope note: lifecycle, step readiness and persistence deliberately do NOT live here — they stay
 * with the surface that owns them. This service holds the thread and nothing else.
 */
@Injectable()
export class DefinitionStateService {
    readonly sample = signal<{ name: string; text: string } | null>(null);
    readonly parsePreview = signal<ParsingPreview | null>(null);
    readonly parseError = signal<string | null>(null);
    readonly schemaPreview = signal<SchemaPreview | null>(null);
    readonly schemaError = signal<string | null>(null);

    /**
     * The rows the parse hop produced — **always an array**, so every consumer asks emptiness the same
     * way. Three surfaces had each reached into `parsePreview()?.rows` themselves and settled on three
     * different absences (`[]`, `null`, `undefined`), which is three emptiness checks for one fact.
     */
    readonly parsedRows = computed<Record<string, unknown>[]>(() => this.parsePreview()?.rows ?? []);

    /** Capture a new sample. Every downstream test result is invalidated — the thread restarts at raw. */
    captureSample(name: string, text: string): void {
        this.sample.set({ name, text });
        this.resetDownstream();
    }

    clearSample(): void {
        this.sample.set(null);
        this.resetDownstream();
    }

    private resetDownstream(): void {
        this.parsePreview.set(null);
        this.parseError.set(null);
        this.schemaPreview.set(null);
        this.schemaError.set(null);
    }
}
