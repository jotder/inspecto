import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterOutlet } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';
import { SchemePreferenceService } from '@inspecto/core/shell/scheme-preference.service';

/**
 * The root: applies the Gamma colour scheme and theme classes to `<body>` (the gamma host's `LayoutComponent` does this
 * for its layouts; the guest routes here have no layout, so the root owns it) and hosts the router outlet.
 *
 * <p>LA-FULL-CYCLE-TEST-1 phase 6 (finding 20): the scheme is `auto` (app.config) unless the person chose Light or Dark in
 * the user menu ({@link SchemePreferenceService}, remembered per person); `auto` follows the browser's
 * `prefers-color-scheme`, including a change made while the app is open.
 */
@Component({
    selector: 'app-root',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [RouterOutlet],
    host: { class: 'flex h-full w-full flex-1' },
    template: `<router-outlet></router-outlet>`,
})
export class LaAppComponent {
    constructor() {
        const doc = inject(DOCUMENT);
        // jsdom and very old browsers have no matchMedia: `auto` is then light, the scheme a page has without one
        const query = doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)') ?? null;
        let last: { scheme: string; theme: string } | null = null;
        const paint = (config: { scheme: string; theme: string }): void => {
            last = config;
            const scheme = config.scheme === 'auto' ? (query?.matches ? 'dark' : 'light') : config.scheme;
            doc.body.classList.remove('light', 'dark');
            doc.documentElement.classList.remove('light', 'dark');
            doc.body.classList.add(scheme);
            doc.documentElement.classList.add(scheme);
            doc.documentElement.style.colorScheme = scheme;
            doc.body.classList.forEach((c) => {
                if (c.startsWith('theme-')) doc.body.classList.remove(c, c.split('-')[1]);
            });
            doc.body.classList.add(config.theme);
        };
        const gamma = inject(GammaConfigService);
        gamma.config$.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(paint);
        // The browser switched light/dark while the app is open: when following it, the config is emitted again (same
        // values), so every reader of the scheme repaints - this page, and the graph and map canvases, which read
        // prefers-color-scheme on a config emit and draw with colours they cannot take from CSS.
        const onChange = (): void => {
            if (last?.scheme === 'auto') gamma.config = {};
        };
        query?.addEventListener?.('change', onChange);
        inject(DestroyRef).onDestroy(() => query?.removeEventListener?.('change', onChange));
        inject(SchemePreferenceService).start();
    }
}
