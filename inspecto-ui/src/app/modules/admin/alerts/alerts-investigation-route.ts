import { InjectionToken } from '@angular/core';

/**
 * Where *Open Investigation* goes: the host's Link Analysis route. The console's default is the Studio route; the
 * standalone Link Analysis app provides its own `/link-analysis`.
 *
 * Its own module so a host's route table can provide it without statically importing the Alerts page (ag-Grid, the
 * SQL editor): la-app's `app.routes.ts` is in the initial bundle (`tools/bundle-budget.json`).
 */
export const ALERTS_INVESTIGATION_ROUTE = new InjectionToken<string>('ALERTS_INVESTIGATION_ROUTE', {
    providedIn: 'root',
    factory: () => '/studio/link-analysis',
});
