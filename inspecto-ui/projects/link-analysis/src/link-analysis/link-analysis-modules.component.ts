import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { GraphRunsService, ModuleView } from '@inspecto/link-analysis/api/graph-runs.service';

/** The modules that make up Link Analysis: the optional geo-link adapters and the `la-*` family. */
export function isLinkAnalysisModule(id: string): boolean {
    return id === 'geo-link' || id.startsWith('la-');
}

/**
 * **Installed Link Analysis modules** (DR-U12): a small read-only view of `GET /modules` filtered to the Link Analysis
 * modules - which are installed, active or inert (and why), switched off in this Space, or not in this bundle at all.
 * A section of ⚙ Settings (LA-INV-EXPAND-1, operator 2026-10-29: the footer strip is gone); the page creates it only
 * while that section is shown, so it reads the list when first shown.
 */
@Component({
    selector: 'inspecto-link-analysis-modules',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    imports: [ChipComponent],
    template: `
            <div class="text-sm">
                @if (error()) {
                    <p class="text-secondary text-sm" role="status" data-testid="modules-error">{{ error() }}</p>
                } @else if (!modules()) {
                    <p class="text-secondary text-sm" role="status">Loading…</p>
                } @else {
                    <ul
                        class="flex flex-col gap-1 text-sm"
                        data-testid="modules-list"
                        aria-label="Link Analysis modules"
                    >
                        @for (m of modules(); track m.id) {
                            <li class="flex flex-wrap items-center gap-2">
                                <!-- the module id is for support only: a tooltip, never body text (T-083) -->
                                <span class="font-medium" [title]="m.id" data-testid="module-name">{{ m.title || m.id }}</span>
                                <inspecto-chip variant="soft" [tone]="tone(m)" [attr.data-testid]="'module-' + m.id">
                                    {{ label(m) }}
                                </inspecto-chip>
                                @if (m.reasons?.length) {
                                    <span class="text-secondary" data-testid="module-reason">{{ reason(m.reasons![0]) }}</span>
                                }
                            </li>
                        } @empty {
                            <li class="text-secondary">No Link Analysis modules are listed.</li>
                        }
                    </ul>
                }
            </div>
    `,
})
export class LinkAnalysisModulesComponent implements OnInit {
    private readonly runs = inject(GraphRunsService);
    readonly modules = signal<ModuleView[] | null>(null);
    readonly error = signal('');
    /** Every listed module's plain name by id (T-083), so a reason naming another module says its name, not its id. */
    private readonly titles = signal(new Map<string, string>());

    ngOnInit(): void {
        this.runs.modules().subscribe({
            next: (r) => {
                this.titles.set(new Map(r.modules.filter((m) => m.title).map((m) => [m.id, m.title])));
                this.modules.set(r.modules.filter((m) => isLinkAnalysisModule(m.id)));
            },
            error: () => this.error.set('The module list could not be read.'),
        });
    }

    /** A server reason with each module id it names replaced by that module's title ("requires la-core" → "requires Link analysis core and ports"). */
    reason(text: string): string {
        // module ids are word characters and hyphens; split on everything else and swap whole ids only
        return text
            .split(/([^\w-]+)/)
            .map((part) => this.titles().get(part) ?? part)
            .join('');
    }

    label(m: ModuleView): string {
        if (m.state === 'not-installed') return 'Not installed';
        if (!m.enabledInSpace) return 'Switched off in this Space';
        return m.state === 'ACTIVE' ? 'Active' : m.state === 'INERT' ? 'Inert' : m.state;
    }

    tone(m: ModuleView): 'primary' | 'warning' | 'neutral' {
        if (m.state === 'ACTIVE' && m.enabledInSpace) return 'primary';
        return m.state === 'INERT' ? 'warning' : 'neutral';
    }
}
