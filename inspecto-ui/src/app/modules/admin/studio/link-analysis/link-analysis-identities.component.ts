import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { IdentityAssertion, IdentityGroup, InvService, LensService, SessionService } from 'app/inspecto/api';
import { LinkAnalysisSettingsService } from 'app/inspecto/api/link-analysis-settings.service';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { InspectoSkeletonComponent } from 'app/inspecto/components/skeleton.component';
import { identityErrorMessage, identityKeyOf } from './investigation-state';
import { EntityListReasonData, EntityListReasonDialog } from './link-analysis-entity-lists.dialogs';
import { isUnavailable } from './link-analysis-template.dialogs';

/**
 * **Link Analysis — Identity resolution** (LA-17 slice 2, SPA half) over `/inv/entity-identities*` (entity-model
 * design §8.2): assert that two typed identifiers are one entity (cross-type allowed — `msisdn` ↔ `imsi`), look up
 * the group one key resolves to, list the Space's groups with the assertions that joined them, and retract one.
 *
 * ⚠ Keys are sent NORMALISED (`identityKeyOf` → `typedEntityKey`): the group read matches exactly. Group members
 * arrive masked per `maskingMode` and are rendered verbatim — never unmasked, never sent back as keys.
 */
@Component({
    selector: 'inspecto-link-analysis-identities',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        NgTemplateOutlet,
        ReactiveFormsModule,
        MatButtonModule,
        MatFormFieldModule,
        MatIconModule,
        MatInputModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoEmptyStateComponent,
        InspectoOptionPickerComponent,
        InspectoSkeletonComponent,
    ],
    host: { class: 'block' },
    template: `
        @if (session.geoLinkEnabled()) {
            <section class="flex flex-col gap-2 text-xs" aria-label="Identity resolution">
                <div class="flex items-center gap-1">
                    <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">
                        Identity resolution
                    </h3>
                    @if (canManage()) {
                        <button
                            mat-icon-button
                            class="ml-auto"
                            [disabled]="loading()"
                            (click)="load()"
                            matTooltip="Re-read the identity groups"
                            aria-label="Refresh the identity groups"
                        >
                            <mat-icon class="icon-size-4" svgIcon="heroicons_outline:arrow-path"></mat-icon>
                        </button>
                    }
                </div>
                @if (!canManage()) {
                    <p class="text-secondary m-0">
                        Identity resolution needs the Incident-management capability — reading it too, because a group
                        read would reveal which identifiers belong together.
                    </p>
                } @else {
                    @if (error()) {
                        <inspecto-alert [variant]="unavailable() ? 'info' : 'error'" title="Identity resolution">{{
                            error()
                        }}</inspecto-alert>
                    }
                    @if (notice()) {
                        <inspecto-alert variant="success" title="Identity resolution">{{ notice() }}</inspecto-alert>
                    }
                    @if (!unavailable()) {
                        <form
                            class="flex flex-col gap-1"
                            [formGroup]="assertForm"
                            (ngSubmit)="assertSame()"
                            aria-label="Assert two identifiers are the same entity"
                        >
                            <inspecto-option-picker
                                formControlName="typeA"
                                label="First identifier type"
                                [options]="typeOptions()"
                            ></inspecto-option-picker>
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>First identifier</mat-label>
                                <input matInput formControlName="valueA" />
                                <mat-error>A value is required.</mat-error>
                            </mat-form-field>
                            <inspecto-option-picker
                                formControlName="typeB"
                                label="Second identifier type"
                                [options]="typeOptions()"
                            ></inspecto-option-picker>
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Second identifier</mat-label>
                                <input matInput formControlName="valueB" />
                                <mat-error>A value is required.</mat-error>
                            </mat-form-field>
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Reason</mat-label>
                                <input matInput formControlName="reason" maxlength="1000" />
                                <mat-error>A reason is required.</mat-error>
                            </mat-form-field>
                            @if (assertProblem()) {
                                <p class="text-warn m-0" role="alert">{{ assertProblem() }}</p>
                            }
                            <button mat-stroked-button type="submit" [disabled]="busy()">Assert same entity</button>
                        </form>

                        <form
                            class="flex flex-col gap-1"
                            [formGroup]="lookupForm"
                            (ngSubmit)="lookup()"
                            aria-label="Find the group of an identifier"
                        >
                            <inspecto-option-picker
                                formControlName="type"
                                label="Identifier type"
                                [options]="typeOptions()"
                            ></inspecto-option-picker>
                            <mat-form-field subscriptSizing="dynamic">
                                <mat-label>Identifier</mat-label>
                                <input matInput formControlName="value" />
                            </mat-form-field>
                            @if (lookupProblem()) {
                                <p class="text-warn m-0" role="alert">{{ lookupProblem() }}</p>
                            }
                            <button mat-stroked-button type="submit" [disabled]="busy()">Find group</button>
                        </form>
                        @if (found(); as g) {
                            <div aria-label="Group found">
                                <ng-container *ngTemplateOutlet="groupTpl; context: { $implicit: g }"></ng-container>
                            </div>
                        }

                        @if (groups(); as gs) {
                            @if (gs.length) {
                                <ul class="m-0 flex list-none flex-col gap-1 p-0" aria-label="Identity groups">
                                    @for (g of gs; track g.id) {
                                        <li>
                                            <ng-container
                                                *ngTemplateOutlet="groupTpl; context: { $implicit: g }"
                                            ></ng-container>
                                        </li>
                                    }
                                </ul>
                            } @else {
                                <inspecto-empty-state
                                    title="No identity groups"
                                    message="No identifiers have been asserted to be the same entity in this Space yet."
                                ></inspecto-empty-state>
                            }
                        } @else if (loading()) {
                            <inspecto-skeleton [lines]="3"></inspecto-skeleton>
                        }
                    }
                }
            </section>
        }

        <ng-template #groupTpl let-g>
            <div class="rounded-md border px-2 py-1" style="border-color: var(--gamma-border)">
                <div class="font-semibold tabular-nums">
                    {{ g.members.length }} {{ g.members.length === 1 ? 'identifier' : 'identifiers' }}
                </div>
                <ul class="m-0 list-none p-0 font-mono" aria-label="Members">
                    @for (m of g.members; track m) {
                        <li class="break-all">{{ m }}</li>
                    }
                </ul>
                @if (g.assertions.length) {
                    <ul class="m-0 mt-1 flex list-none flex-col gap-1 p-0" aria-label="Assertions that joined it">
                        @for (x of g.assertions; track x.seq) {
                            <li class="text-secondary flex items-start gap-1">
                                <span class="min-w-0 flex-1 break-all">
                                    #{{ x.seq }} {{ x.a }} = {{ x.b }} — {{ x.reason }} ({{ x.actor || 'unknown' }},
                                    {{ x.via }})
                                </span>
                                <button
                                    mat-button
                                    color="warn"
                                    [disabled]="busy()"
                                    (click)="retract(x)"
                                    [attr.aria-label]="'Retract assertion ' + x.seq"
                                >
                                    Retract
                                </button>
                            </li>
                        }
                    </ul>
                } @else {
                    <p class="text-secondary m-0">Joined by no assertion — the identifier resolves to itself.</p>
                }
            </div>
        </ng-template>
    `,
})
export class LinkAnalysisIdentitiesComponent {
    private inv = inject(InvService);
    private dialog = inject(MatDialog);
    private settings = inject(LinkAnalysisSettingsService);
    private lens = inject(LensService);
    private fb = inject(FormBuilder);
    readonly session = inject(SessionService);

    readonly groups = signal<IdentityGroup[] | null>(null);
    readonly found = signal<IdentityGroup | null>(null);
    readonly loading = signal(false);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly unavailable = signal(false);
    readonly notice = signal('');
    readonly assertProblem = signal('');
    readonly lookupProblem = signal('');

    /** Reads AND writes are gated server-side on `canManageIncidents`; the section asks the same question. */
    readonly canManage = computed(() => this.lens.canManageIncidents());
    readonly typeOptions = computed<PickerOption[]>(() =>
        this.settings.limits().entityTypesInForce.map((t) => ({ value: t.id, label: t.label, hint: t.normaliser })),
    );

    readonly assertForm = this.fb.nonNullable.group({
        typeA: [''],
        valueA: ['', Validators.required],
        typeB: [''],
        valueB: ['', Validators.required],
        reason: ['', [Validators.required, Validators.maxLength(1000)]],
    });
    readonly lookupForm = this.fb.nonNullable.group({ type: [''], value: [''] });

    constructor() {
        // Off the module every route 503s, and without the capability every read 403s — no call for either.
        if (this.session.geoLinkEnabled() && this.canManage()) void this.load();
    }

    async load(): Promise<void> {
        this.loading.set(true);
        try {
            this.groups.set((await firstValueFrom(this.inv.listIdentityGroups())).groups);
        } catch (err) {
            this.groups.set(null);
            this.fail(err, 'Could not read the identity groups.');
        } finally {
            this.loading.set(false);
        }
    }

    async assertSame(): Promise<void> {
        this.assertProblem.set('');
        const v = this.assertForm.getRawValue();
        if (this.assertForm.invalid || !v.typeA || !v.typeB) {
            this.assertForm.markAllAsTouched();
            if (!v.typeA || !v.typeB) this.assertProblem.set('Choose an identifier type for both identifiers.');
            return;
        }
        const a = identityKeyOf(this.type(v.typeA), v.valueA);
        const b = identityKeyOf(this.type(v.typeB), v.valueB);
        if (!a || !b) {
            this.assertProblem.set('An identifier is empty once normalised for its type.');
            return;
        }
        if (a === b) {
            this.assertProblem.set(`Both identifiers normalise to ${a} — an identity cannot be asserted with itself.`);
            return;
        }
        await this.write('Could not assert the identity.', async () => {
            const res = await firstValueFrom(this.inv.assertIdentity({ a, b, reason: v.reason.trim() }));
            this.found.set(res.group);
            this.assertForm.reset();
            this.notice.set(
                `Asserted #${res.assertion.seq} — the group now holds ${res.group.members.length} identifiers.`,
            );
        });
    }

    async lookup(): Promise<void> {
        this.lookupProblem.set('');
        const v = this.lookupForm.getRawValue();
        const key = identityKeyOf(this.type(v.type), v.value);
        if (!key) {
            this.lookupProblem.set('Choose an identifier type and enter a value that is not empty once normalised.');
            return;
        }
        this.busy.set(true);
        this.error.set('');
        try {
            this.found.set((await firstValueFrom(this.inv.identityGroup(key))).group);
        } catch (err) {
            this.found.set(null);
            this.fail(err, 'Could not read the group.');
        } finally {
            this.busy.set(false);
        }
    }

    async retract(x: IdentityAssertion): Promise<void> {
        const data: EntityListReasonData = {
            title: 'Retract identity assertion',
            message: `Retract #${x.seq} (${x.a} = ${x.b})? The assertion stays in the log as retracted; the group may split.`,
            confirmLabel: 'Retract',
            destructive: true,
            maxLength: 1000,
        };
        const reason = await firstValueFrom(
            this.dialog
                .open<EntityListReasonDialog, EntityListReasonData, string | undefined>(EntityListReasonDialog, {
                    data,
                    width: '28rem',
                })
                .afterClosed(),
        );
        if (!reason) return;
        await this.write('Could not retract the assertion.', async () => {
            const res = await firstValueFrom(this.inv.retractIdentity(x.seq, reason));
            this.found.set(null);
            this.notice.set(
                `Retracted #${res.retracted}` + (res.groups.length > 1 ? ' — the group split in two.' : '.'),
            );
        });
    }

    private type(id: string) {
        return this.settings.limits().entityTypesInForce.find((t) => t.id === id);
    }

    private async write(fallback: string, body: () => Promise<void>): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        this.unavailable.set(false);
        this.notice.set('');
        try {
            await body();
            await this.load();
        } catch (err) {
            this.fail(err, fallback);
        } finally {
            this.busy.set(false);
        }
    }

    private fail(err: unknown, fallback: string): void {
        this.unavailable.set(isUnavailable(err));
        this.error.set(identityErrorMessage(err, fallback));
    }
}
