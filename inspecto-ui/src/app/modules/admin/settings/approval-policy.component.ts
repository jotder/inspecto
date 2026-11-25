import { ChangeDetectionStrategy, Component, HostListener, OnInit, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ToastrService } from 'ngx-toastr';

import { ApprovalPolicy, ApprovalRule, LensService, PendingChangesService, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';

interface EditableRule {
    required: boolean;
    fourEyes: boolean;
    approverCapability: string;
}

/**
 * Settings ▸ **Approval Policy** (`PIPELINE-SAVE-HELD-TOAST-1` / `ASSURE-MAKER-CHECKER-1`): per-kind
 * maker-checker settings (`GET|PUT /settings/approval`, `canAdminister`). Turning a kind on holds its
 * writes as Pending Changes until an approver accepts them; `fourEyes` requires a different person than
 * the author. The Settings route's leave guard reads {@link hasUnsavedChanges}.
 */
@Component({
    selector: 'inspecto-approval-policy-settings',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatProgressSpinnerModule,
        MatSlideToggleModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoPageHeaderComponent,
    ],
    template: `
        <div class="flex flex-col gap-6 p-6">
            <inspecto-page-header
                title="Approval Policy"
                subtitle="Require approval before configuration changes take effect in this space."
                [inset]="false"
            />

            @if (loading()) {
                <div class="flex items-center gap-2 py-6">
                    <mat-spinner diameter="20"></mat-spinner>
                    <span class="text-secondary text-sm">Reading the approval policy…</span>
                </div>
            } @else if (loadError()) {
                <inspecto-empty-state
                    icon="heroicons_outline:shield-check"
                    title="Approval policy unavailable"
                    [message]="loadError()!"
                ></inspecto-empty-state>
            } @else {
                @if (failedClosed()) {
                    <inspecto-alert variant="warning" title="Policy failed closed">
                        The stored approval policy could not be read cleanly, so every governable kind is currently held
                        for approval. Save a valid policy below to restore normal rules.
                    </inspecto-alert>
                }

                <section class="flex max-w-2xl flex-col gap-3" aria-labelledby="approval-kinds-heading">
                    <h2 id="approval-kinds-heading" class="text-lg font-semibold">Governable kinds</h2>
                    @if (kinds().length === 0) {
                        <p class="text-secondary text-sm">No governable kinds reported by the server.</p>
                    } @else {
                        <ul class="flex flex-col divide-y rounded-lg border">
                            @for (kind of kinds(); track kind) {
                                @let rule = draft()[kind] ?? defaultRule;
                                <li class="flex flex-wrap items-center justify-between gap-4 px-4 py-3">
                                    <span class="font-mono text-sm font-medium">{{ kind }}</span>
                                    <div class="flex flex-wrap items-center gap-6">
                                        <mat-slide-toggle
                                            [checked]="rule.required"
                                            [disabled]="!canEdit() || saving()"
                                            [aria-label]="'Require approval for ' + kind"
                                            (change)="setRequired(kind, $event.checked)"
                                        >
                                            Require approval
                                        </mat-slide-toggle>
                                        <mat-slide-toggle
                                            [checked]="rule.required && rule.fourEyes"
                                            [disabled]="!canEdit() || saving() || !rule.required"
                                            [aria-label]="'Require a different approver (four-eyes) for ' + kind"
                                            (change)="setFourEyes(kind, $event.checked)"
                                        >
                                            Four-eyes
                                        </mat-slide-toggle>
                                    </div>
                                </li>
                            }
                        </ul>
                    }
                </section>

                @if (saveError()) {
                    <inspecto-alert variant="error" title="The approval policy was not saved">{{
                        saveError()
                    }}</inspecto-alert>
                }
                @if (writesDisabled()) {
                    <inspecto-alert variant="warning" title="Changes cannot be saved here">
                        {{ writesDisabled() }}
                    </inspecto-alert>
                }

                @if (canEdit()) {
                    <div class="flex items-center gap-3">
                        <button
                            mat-flat-button
                            color="primary"
                            type="button"
                            [disabled]="!dirty() || saving()"
                            (click)="save()"
                        >
                            Save policy
                        </button>
                        <button mat-button type="button" [disabled]="!dirty() || saving()" (click)="revert()">
                            Discard changes
                        </button>
                    </div>
                } @else {
                    <inspecto-alert variant="warning" title="Administer capability required">
                        Only an administrator of this space can change the approval policy.
                    </inspecto-alert>
                }
            }
        </div>
    `,
})
export class ApprovalPolicySettingsComponent implements OnInit {
    private api = inject(PendingChangesService);
    private toastr = inject(ToastrService);
    private lens = inject(LensService);

    readonly canEdit = computed(() => this.lens.canAdminister());
    readonly loading = signal(true);
    readonly saving = signal(false);
    readonly failedClosed = signal(false);
    readonly loadError = signal<string | null>(null);
    readonly saveError = signal<string | null>(null);
    readonly writesDisabled = signal<string | null>(null);

    readonly kinds = signal<string[]>([]);
    private defaultApproverCapability = 'canApproveChanges';
    private expiresAfterHours = 168;
    readonly defaultRule: EditableRule = {
        required: false,
        fourEyes: true,
        approverCapability: 'canApproveChanges',
    };

    private readonly saved = signal<Record<string, EditableRule>>({});
    readonly draft = signal<Record<string, EditableRule>>({});
    readonly dirty = computed(() => JSON.stringify(this.draft()) !== JSON.stringify(this.saved()));

    ngOnInit(): void {
        this.api.policy().subscribe({
            next: (p) => {
                this.loading.set(false);
                this.apply(p);
            },
            error: (err) => {
                this.loading.set(false);
                this.loadError.set(apiErrorMessage(err, 'The approval policy route did not answer.'));
            },
        });
    }

    hasUnsavedChanges(): boolean {
        return this.dirty();
    }

    @HostListener('window:beforeunload', ['$event'])
    onBeforeUnload(event: BeforeUnloadEvent): void {
        if (this.dirty()) event.preventDefault();
    }

    setRequired(kind: string, required: boolean): void {
        this.draft.update((d) => {
            const cur = d[kind] ?? {
                required: false,
                fourEyes: true,
                approverCapability: this.defaultApproverCapability,
            };
            return { ...d, [kind]: { ...cur, required } };
        });
        this.saveError.set(null);
    }

    setFourEyes(kind: string, fourEyes: boolean): void {
        this.draft.update((d) => {
            const cur = d[kind] ?? {
                required: false,
                fourEyes: true,
                approverCapability: this.defaultApproverCapability,
            };
            return { ...d, [kind]: { ...cur, fourEyes } };
        });
        this.saveError.set(null);
    }

    revert(): void {
        this.draft.set(structuredClone(this.saved()));
        this.saveError.set(null);
    }

    save(): void {
        this.saving.set(true);
        this.saveError.set(null);
        const approval: Record<string, Partial<ApprovalRule>> = {};
        for (const [kind, rule] of Object.entries(this.draft())) {
            if (rule.required) {
                approval[kind] = {
                    required: true,
                    fourEyes: rule.fourEyes,
                    approverCapability: rule.approverCapability || this.defaultApproverCapability,
                };
            }
        }
        this.api.savePolicy({ approval, expiresAfterHours: this.expiresAfterHours }).subscribe({
            next: (p) => {
                this.saving.set(false);
                this.writesDisabled.set(null);
                this.apply(p);
                this.toastr.success('Approval policy saved.');
            },
            error: (err) => {
                this.saving.set(false);
                const message = apiErrorMessage(err, 'Saving the approval policy failed.');
                if (err?.status === 422) this.saveError.set(message);
                else if (err?.status === 503) this.writesDisabled.set(message);
                else this.toastr.error(message);
            },
        });
    }

    private apply(p: ApprovalPolicy): void {
        this.failedClosed.set(!!p.failedClosed);
        this.defaultApproverCapability = p.defaultApproverCapability || 'canApproveChanges';
        this.expiresAfterHours = p.expiresAfterHours || 168;
        const allKinds = Array.from(new Set([...(p.governable ?? []), ...Object.keys(p.approval ?? {})])).sort();
        this.kinds.set(allKinds);
        const map: Record<string, EditableRule> = {};
        for (const kind of allKinds) {
            const r = p.approval?.[kind];
            map[kind] = {
                required: !!r?.required,
                fourEyes: r ? !!r.fourEyes : true,
                approverCapability: r?.approverCapability || this.defaultApproverCapability,
            };
        }
        this.saved.set(structuredClone(map));
        this.draft.set(structuredClone(map));
    }
}
