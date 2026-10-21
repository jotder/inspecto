import { Routes } from '@angular/router';
import { KEEP_ALIVE } from '@inspecto/core/keep-alive.strategy';
import { LinkAnalysisComponent } from './link-analysis.component';

// KEEP_ALIVE: what the analyst has open survives a visit to another page of the same Space (operator 2026-10-21).
export default [{ path: '', component: LinkAnalysisComponent, data: { [KEEP_ALIVE]: true } }] as Routes;
