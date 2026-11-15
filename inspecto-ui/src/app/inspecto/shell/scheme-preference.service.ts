import { effect, inject, Injectable, Injector, signal, untracked } from '@angular/core';
import { GammaConfigService } from '@gamma/services/config';
import { SessionService } from '../auth/session.service';

/** The colour scheme a person picks in the user menu: follow the browser (the default), or always light or dark. */
export type SchemeChoice = 'auto' | 'light' | 'dark';

/** The user menu's three choices, in the order shown. */
export const SCHEME_CHOICES: readonly { value: SchemeChoice; label: string; icon: string }[] = [
    { value: 'auto', label: 'Match system', icon: 'heroicons_outline:computer-desktop' },
    { value: 'light', label: 'Light', icon: 'heroicons_outline:sun' },
    { value: 'dark', label: 'Dark', icon: 'heroicons_outline:moon' },
];

/** The browser key the choice is kept under: one per signed-in person, else one for this browser. */
export function schemeStorageKey(actor: string | null): string {
    return actor ? `inspecto.scheme.${actor}` : 'inspecto.scheme';
}

function isChoice(v: unknown): v is SchemeChoice {
    return v === 'auto' || v === 'light' || v === 'dark';
}

/**
 * The colour scheme choice of Inspecto LA (LA-FULL-CYCLE-TEST-1 phase 6, finding 20): `auto` follows the browser's
 * `prefers-color-scheme`, `light` / `dark` hold. Kept in this browser per person (the session's actor; Basic has none,
 * so one choice per browser), and applied through the Gamma config, whose `scheme` the root component paints.
 *
 * <p>A preference, not a setting: nothing reaches the server, and an unreadable store (a private window, blocked site
 * data) just means the default, so every storage call is wrapped.
 */
@Injectable({ providedIn: 'root' })
export class SchemePreferenceService {
    private readonly session = inject(SessionService);
    private readonly injector = inject(Injector);
    readonly choice = signal<SchemeChoice>('auto');
    private started = false;

    /**
     * Apply the remembered choice now and again whenever the person changes (sign-in, a Demo User picked). Called once by
     * the host that offers the choice (la-app's root); a host that never calls it keeps its own configured scheme.
     */
    start(): void {
        if (this.started) return;
        this.started = true;
        effect(
            () => {
                const actor = this.session.actor();
                untracked(() => this.apply(this.read(actor)));
            },
            { injector: this.injector },
        );
    }

    choose(choice: SchemeChoice): void {
        try {
            localStorage.setItem(schemeStorageKey(this.session.actor()), choice);
        } catch {
            // no storage here: the choice holds for this visit only
        }
        this.apply(choice);
    }

    private read(actor: string | null): SchemeChoice {
        try {
            const v = localStorage.getItem(schemeStorageKey(actor));
            return isChoice(v) ? v : 'auto';
        } catch {
            return 'auto';
        }
    }

    private apply(choice: SchemeChoice): void {
        this.choice.set(choice);
        // looked up only when a scheme is applied: a host that never offers the choice needs no Gamma config for it
        this.injector.get(GammaConfigService).config = { scheme: choice };
    }
}
