import { DOCUMENT } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterOutlet } from '@angular/router';
import { GammaConfigService } from '@gamma/services/config';

/**
 * The root: applies the Gamma colour scheme and theme classes to `<body>` (the gamma host's `LayoutComponent` does this
 * for its layouts; the guest routes here have no layout, so the root owns it) and hosts the router outlet.
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
        inject(GammaConfigService)
            .config$.pipe(takeUntilDestroyed(inject(DestroyRef)))
            .subscribe((config) => {
                const scheme =
                    config.scheme === 'auto'
                        ? doc.defaultView?.matchMedia('(prefers-color-scheme: dark)').matches
                            ? 'dark'
                            : 'light'
                        : config.scheme;
                doc.body.classList.remove('light', 'dark');
                doc.documentElement.classList.remove('light', 'dark');
                doc.body.classList.add(scheme);
                doc.documentElement.classList.add(scheme);
                doc.documentElement.style.colorScheme = scheme;
                doc.body.classList.forEach((c) => {
                    if (c.startsWith('theme-')) doc.body.classList.remove(c, c.split('-')[1]);
                });
                doc.body.classList.add(config.theme);
            });
    }
}
