import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { ChipComponent } from '@inspecto/core/components/chip.component';
import { GraphRunsService, ModuleView } from '@inspecto/link-analysis/api/graph-runs.service';

/** The modules that make up Link Analysis: the optional geo-link adapters and the `la-*` family. */
export function isLinkAnalysisModule(id: string): boolean {
    return id === 'geo-link' || id.startsWith('la-');
}

/**
 * **Installed Link Analysis modules** (DR-U12): a small read-only view of `GET /modules` filtered to the Link Analysis
 * modules - which are installed, active or inert (and why), switched off in this Space, or not in this bundle at all.
 * Loads when first opened.
 */
@Component({
    selector: 'inspecto-link-analysis-modules',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, ChipComponent],
    template: `
        <button mat-button data-testid="modules-toggle" (click)="toggle()" [attr.aria-expanded]="open()">
            Installed modules
        </button>
        @if (open()) {
            @if (error()) {
                <p class="text-secondary text-sm" role="status" data-testid="modules-error">{{ error() }}</p>
            } @else if (!modules()) {
                <p class="text-secondary text-sm" role="status">Loading...</p>
            } @else {
                <ul class="flex flex-col gap-1 text-sm" data-testid="modules-list" aria-label="Link Analysis modules">
                    @for (m of modules(); track m.id) {
                        <li class="flex flex-wrap items-center gap-2">
                            <span class="font-medium">{{ m.title || m.id }}</span>
                            <span class="text-secondary">{{ m.id }}</span>
                            <inspecto-chip variant="soft" [tone]="tone(m)" [attr.data-testid]="'module-' + m.id">
                                {{ label(m) }}
                            </inspecto-chip>
                            @if (m.reasons?.length) {
                                <span class="text-secondary">{{ m.reasons![0] }}</span>
                            }
                        </li>
                    } @empty {
                        <li class="text-secondary">No Link Analysis modules are listed.</li>
                    }
                </ul>
            }
        }
    `,
})
export class LinkAnalysisModulesComponent {
    private readonly runs = inject(GraphRunsService);
    readonly open = signal(false);
    readonly modules = signal<ModuleView[] | null>(null);
    readonly error = signal('');

    toggle(): void {
        this.open.update((o) => !o);
        if (this.open() && !this.modules()) {
            this.runs.modules().subscribe({
                next: (r) => this.modules.set(r.modules.filter((m) => isLinkAnalysisModule(m.id))),
                error: () => this.error.set('The module list could not be read.'),
            });
        }
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
