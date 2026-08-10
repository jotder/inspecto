// Direct file imports (NOT the `app/inspecto/query` barrel) — that barrel re-exports `QueryPanelComponent`,
// which imports `DataTableComponent`, which imports `RuleSaveDialog`/`RuleTemplate` from this module;
// going through the barrel here would close a module-init cycle.
import { ConditionGroup, QueryModel } from '../query/query-types';
import { SqlParam } from '../query/query-sql';

/**
 * A saved rule template — the Pro Max artifact. The body IS a {@link QueryModel} (projection + nested
 * AND/OR filter, or a hand-edited SQL override) plus an id/name and the source it queries. The condition
 * values are also surfaced as named **params** (`:fieldValue`) with `paramSql` — at runtime a rule engine /
 * job binds those params and runs it (execution is still to come; persisted as a `rule-template` component). Pure
 * data — no Angular.
 */
export interface RuleTemplate {
    id: string;
    name: string;
    source: string;
    projection: string[] | '*';
    where: ConditionGroup;
    sqlOverride?: string | null;
    /** Named binds derived from the condition values, with their (editable) default values. */
    params?: SqlParam[];
    /** The SQL with `:name` placeholders in place of literals (illustrative; runs on the server once wired). */
    paramSql?: string;
}

/** Build a {@link RuleTemplate} from a finished query (the data-table Pro tier's model) + optional params. */
export function buildRuleTemplate(
    name: string,
    source: string,
    model: QueryModel,
    extras?: { params?: SqlParam[]; paramSql?: string },
): RuleTemplate {
    return {
        id: name,
        name,
        source,
        projection: model.projection,
        where: model.where,
        sqlOverride: model.sqlOverride ?? null,
        params: extras?.params,
        paramSql: extras?.paramSql,
    };
}
