import { ChangeDetectionStrategy, Component, ElementRef, HostListener, computed, effect, inject, input, model, viewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';

/** One ⚙ Settings section (LA-STUDIO-UI-REORG-1 §5.10). */
export type LaSettingsSection =
    | 'graph-model'
    | 'entity-types'
    | 'display-rules'
    | 'entity-lists'
    | 'identities'
    | 'add-data'
    | 'investigation';

interface NavGroup {
    title: string;
    items: { id: LaSettingsSection; label: string; icon: string; help: string }[];
}

const NAV: NavGroup[] = [
    {
        title: 'This graph',
        items: [
            {
                id: 'graph-model',
                label: 'Graph model',
                icon: 'heroicons_outline:circle-stack',
                help: 'The Dataset, which columns are entities, links and attributes. Run draws it; Refresh re-runs it.',
            },
            {
                id: 'entity-types',
                label: 'Entity Types',
                icon: 'heroicons_outline:tag',
                help: 'The kinds of thing the graph is drawn as, and how their values are compared.',
            },
            {
                id: 'display-rules',
                label: 'Display rules',
                icon: 'heroicons_outline:paint-brush',
                help: 'Colour, shape and icon rules applied to nodes by their attributes.',
            },
        ],
    },
    {
        title: 'Data',
        items: [
            {
                id: 'entity-lists',
                label: 'Entity Lists',
                icon: 'heroicons_outline:queue-list',
                help: 'Named lists of entities, their purpose and members.',
            },
            {
                id: 'identities',
                label: 'Identities',
                icon: 'heroicons_outline:finger-print',
                help: 'Identity resolution facts: which keys are the same entity, and why.',
            },
            {
                id: 'add-data',
                label: 'Add / Upload data',
                icon: 'heroicons_outline:arrow-up-tray',
                help: 'Add a Dataset, or upload a CSV to an upload-enabled Collector.',
            },
        ],
    },
    {
        title: 'This Investigation',
        items: [
            {
                id: 'investigation',
                label: 'Investigation settings',
                icon: 'heroicons_outline:briefcase',
                help: 'The open Investigation: general, Case, members, sharing, evidence, templates.',
            },
        ],
    },
];

const SECTION_KEY = 'inspecto.la.settings.section';

function storedSection(): LaSettingsSection | null {
    try {
        const v = localStorage.getItem(SECTION_KEY);
        return NAV.some((g) => g.items.some((i) => i.id === v)) ? (v as LaSettingsSection) : null;
    } catch {
        return null;
    }
}

/**
 * **⚙ Settings** (LA-STUDIO-UI-REORG-1 §5.10, UIR-D16 + UIR-A1): one modal with a left nav in three groups — This
 * graph, Data, This Investigation — for configuration changed rarely. The sections are PROJECTED by the host
 * (`<div laSection="graph-model">…</div>`) and only hidden, never destroyed, so the host keeps its ViewChild on the
 * query panel and a half-edited form survives closing. It is an in-page overlay, not a MatDialog, for that reason.
 * The last section is remembered per browser; closing with unsaved changes asks first.
 */
@Component({
    selector: 'inspecto-la-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatIconModule, MatTooltipModule],
    template: `
        <!-- eslint-disable-next-line @angular-eslint/template/click-events-have-key-events, @angular-eslint/template/interactive-supports-focus -- a backdrop click is the pointer twin of Esc, which a document listener handles -->
        <div
            class="fixed inset-0 z-[1000] items-center justify-center bg-black/40 p-4"
            [class.flex]="open()"
            [class.hidden]="!open()"
            data-testid="la-settings"
            (click)="$event.target === $event.currentTarget && requestClose()"
        >
            <div
                #panel
                class="bg-card flex h-[85vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl shadow-lg"
                role="dialog"
                aria-modal="true"
                aria-labelledby="la-settings-title"
                tabindex="-1"
            >
                <div class="flex shrink-0 items-center gap-2 border-b px-4 py-2" style="border-color: var(--gamma-border)">
                    <h2 id="la-settings-title" class="m-0 text-lg font-semibold">Settings</h2>
                    <span class="text-secondary truncate text-sm">· {{ current().label }}</span>
                    <button
                        mat-icon-button
                        class="ml-auto"
                        (click)="requestClose()"
                        aria-label="Close settings"
                        data-testid="la-settings-close"
                    >
                        <mat-icon svgIcon="heroicons_outline:x-mark"></mat-icon>
                    </button>
                </div>
                <div class="flex min-h-0 flex-auto max-sm:flex-col">
                    <nav
                        class="w-56 shrink-0 overflow-y-auto border-r p-2 max-sm:w-full max-sm:border-b max-sm:border-r-0"
                        style="border-color: var(--gamma-border)"
                        aria-label="Settings sections"
                    >
                        @for (g of groups(); track g.title) {
                            <h3 class="text-secondary mx-2 mb-1 mt-3 text-xs font-semibold uppercase tracking-wider">
                                {{ g.title }}
                            </h3>
                            @for (i of g.items; track i.id) {
                                <button
                                    type="button"
                                    class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-hover"
                                    [class.font-semibold]="section() === i.id"
                                    [class.text-primary]="section() === i.id"
                                    [attr.aria-current]="section() === i.id ? 'page' : null"
                                    [attr.data-testid]="'la-settings-nav-' + i.id"
                                    (click)="show(i.id)"
                                >
                                    <mat-icon class="icon-size-4" [svgIcon]="i.icon"></mat-icon>
                                    {{ i.label }}
                                </button>
                            }
                        }
                    </nav>
                    <div class="min-h-0 min-w-0 flex-auto overflow-y-auto p-4">
                        <p class="text-secondary mb-3 mt-0 text-xs" [attr.data-testid]="'la-settings-help'">
                            {{ current().help }}
                        </p>
                        <div [hidden]="section() !== 'graph-model'"><ng-content select="[laSection=graph-model]" /></div>
                        <div [hidden]="section() !== 'entity-types'"><ng-content select="[laSection=entity-types]" /></div>
                        <div [hidden]="section() !== 'display-rules'"><ng-content select="[laSection=display-rules]" /></div>
                        <div [hidden]="section() !== 'entity-lists'"><ng-content select="[laSection=entity-lists]" /></div>
                        <div [hidden]="section() !== 'identities'"><ng-content select="[laSection=identities]" /></div>
                        <div [hidden]="section() !== 'add-data'"><ng-content select="[laSection=add-data]" /></div>
                        <div [hidden]="section() !== 'investigation'"><ng-content select="[laSection=investigation]" /></div>
                    </div>
                </div>
            </div>
        </div>
    `,
})
export class LinkAnalysisSettingsComponent {
    private readonly confirm = inject(InspectoConfirmService);
    private readonly dialogs = inject(MatDialog);
    private readonly panel = viewChild<ElementRef<HTMLElement>>('panel');

    readonly open = model(false);
    readonly section = model<LaSettingsSection>(storedSection() ?? 'graph-model');
    /** This Investigation is shown only while one is open (§5.10, UIR-D14). */
    readonly investigationOpen = input(false);
    /** The host's unsaved-changes check, asked before the dialog closes. */
    readonly isDirty = input<() => boolean>(() => false);

    readonly groups = computed(() => NAV.filter((g) => g.title !== 'This Investigation' || this.investigationOpen()));
    readonly current = computed(
        () => NAV.flatMap((g) => g.items).find((i) => i.id === this.section()) ?? NAV[0].items[0],
    );

    private readonly remember = effect(() => {
        const s = this.section();
        try {
            localStorage.setItem(SECTION_KEY, s);
        } catch {
            /* the remembered section is a convenience */
        }
    });
    private readonly focusOnOpen = effect(() => {
        if (this.open()) queueMicrotask(() => this.panel()?.nativeElement.focus());
    });
    /** An Investigation closed while its section is shown falls back to Graph model. */
    private readonly dropInvestigation = effect(() => {
        if (!this.investigationOpen() && this.section() === 'investigation') this.section.set('graph-model');
    });

    /**
     * Esc closes from anywhere while open, not only with focus inside the panel. An Esc another overlay already handled
     * (a mat-select panel, the unsaved-changes confirm) or fired while a dialog is open is not ours
     * (LA-SETTINGS-ESC-REOPENS-CONFIRM-1).
     */
    @HostListener('document:keydown.escape', ['$event'])
    onEscape(event?: Event): void {
        if (event?.defaultPrevented || this.dialogs.openDialogs.length) return;
        if (this.open()) void this.requestClose();
    }

    show(id: LaSettingsSection): void {
        this.section.set(id);
    }

    async requestClose(): Promise<void> {
        if (this.isDirty()() && !(await this.confirm.confirm('Close Settings? The graph model has changes that were not run.')))
            return;
        this.open.set(false);
    }
}
