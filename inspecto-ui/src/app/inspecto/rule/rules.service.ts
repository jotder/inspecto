import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ComponentsService } from 'app/inspecto/api';
// Direct file import (NOT the `app/inspecto/query` barrel) — see the note in `rule-types.ts`.
import { ConditionGroup, emptyGroup } from '../query/query-types';
import { RuleTemplate } from './rule-types';

/**
 * Rule template store — Pro Max. Persists {@link RuleTemplate}s through the reusable component registry as the
 * `rule-template` component type. Keeps the rest of the app from knowing rule templates are "just components".
 *
 * Named `rule-template`, never bare `rule` (docs/GLOSSARY.md §0 — Rule is always qualified, and this is none
 * of the Expectation / Decision Rule / Alert Rule triad). It was the one UI kind missing from the backend's
 * `ComponentStore.WRITABLE_TYPES` until 2026-07-27, so all three methods here 400'd against a real server.
 */
@Injectable({ providedIn: 'root' })
export class RulesService {
    private components = inject(ComponentsService);

    list(): Observable<RuleTemplate[]> {
        return this.components.list('rule-template').pipe(map((defs) => defs.map((d) => fromContent(d.name, d.content))));
    }

    save(rule: RuleTemplate): Observable<RuleTemplate> {
        return this.components.create('rule-template', { id: rule.id, ...toContent(rule) }).pipe(map(() => rule));
    }

    remove(id: string): Observable<unknown> {
        return this.components.remove('rule-template', id);
    }
}

function toContent(r: RuleTemplate): Record<string, unknown> {
    return { name: r.name, source: r.source, projection: r.projection, where: r.where, sqlOverride: r.sqlOverride ?? null };
}

function fromContent(name: string, content: Record<string, unknown>): RuleTemplate {
    return {
        id: name,
        name: (content['name'] as string) ?? name,
        source: (content['source'] as string) ?? 'data',
        projection: (content['projection'] as string[] | '*') ?? '*',
        where: (content['where'] as ConditionGroup) ?? emptyGroup(),
        sqlOverride: (content['sqlOverride'] as string | null) ?? null,
    };
}
