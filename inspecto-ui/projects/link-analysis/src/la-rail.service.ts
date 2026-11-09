import { Injectable, signal } from '@angular/core';

/** localStorage key remembering whether the navigation rail is expanded ('1') or slim (anything else). */
export const LA_RAIL_EXPANDED_KEY = 'la-app.rail-expanded';

function readExpanded(): boolean {
    try {
        return localStorage.getItem(LA_RAIL_EXPANDED_KEY) === '1';
    } catch {
        return false;
    }
}

/**
 * The la-app navigation rail's expanded/slim state, shared so the Link Analysis header can carry the toggle while the
 * shell draws the rail. `available` stays false in a host without a rail (the full console), which hides the toggle.
 */
@Injectable({ providedIn: 'root' })
export class LaRailService {
    readonly available = signal(false);
    readonly expanded = signal(readExpanded());

    toggle(): void {
        const next = !this.expanded();
        this.expanded.set(next);
        try {
            localStorage.setItem(LA_RAIL_EXPANDED_KEY, next ? '1' : '0');
        } catch {
            /* storage blocked: the choice just does not persist */
        }
    }
}
