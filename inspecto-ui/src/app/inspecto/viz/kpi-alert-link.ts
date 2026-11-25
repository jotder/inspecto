/**
 * BI-INSIGHTS-1 I-17 — "Alert me…" from a KPI-bound Widget. A link, never a write: the query params open the Alerts
 * pane's prefilled create (`?newRule=1`, the same handshake the Risk Scores pane uses, D-RP9), where the user reviews
 * the draft and saves it through the normal save gate (the server's KPI read as the rule's owner, the Measure vetted
 * with no reveal, an approval hold where a policy asks for one). Nothing is armed without that save.
 */
export type KpiAlertWatch = 'value' | 'projection';

/** The `/alerts` query params of a KPI Alert Rule draft: on the KPI's value, or on where its period is projected to land. */
export function kpiAlertRuleParams(kpiId: string, watch: KpiAlertWatch = 'value'): Record<string, string> {
    return { newRule: '1', kpi: kpiId, ...(watch === 'projection' ? { projection: 'value' } : {}) };
}
