import { ChangeDetectionStrategy, Component, OnDestroy, ViewChild, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConnectionTestResult, ConnectionsService, LensService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import { connectionOptionLoader } from 'app/inspecto/components/entity-option-loaders';
import { ConnectionFormDialog, ConnectionFormResult } from 'app/inspecto/connections/connection-form.dialog';
import { COLLECTOR_ATTRIBUTES } from 'app/inspecto/component-model';
import { KEY_SEP, clearMissingRoots, flattenBlock, nestKeys } from 'app/inspecto/component-model';
import { OnboardingStateService } from './onboarding-state.service';

/**
 * Collection stage — authors the Stage-1 `collector:` block. Connection-first: the operator picks
 * WHERE to collect from (the local inbox, or a saved Connection — with test + create-in-place via
 * the shared {@link ConnectionFormDialog}); `collector.connector` is never asked, it is derived at
 * save time (`local`, or the picked Connection's own connector) because the engine dispatches on
 * it without checking it agrees with the Connection.
 */
@Component({
    selector: 'app-onboarding-collection-pane',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        MatButtonModule,
        MatButtonToggleModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatTooltipModule,
        InspectoAlertComponent,
        InspectoSchemaFormComponent,
    ],
    template: `
        <div class="flex max-w-3xl flex-col gap-4">
            <p class="text-secondary m-0">
                Where this {{ state.kind() }}'s files come from — the pipeline's local inbox folder,
                or a saved Connection. A Connection carries its own connector type, so it is never
                asked twice.
            </p>

            <div>
                <div class="text-secondary mb-1 text-xs font-semibold uppercase tracking-wider">Collect from</div>
                <mat-button-toggle-group [value]="mode()" (change)="setMode($event.value)" aria-label="Collect from">
                    <mat-button-toggle value="local" matTooltip="Read files from the pipeline's inbox folder">
                        Local inbox
                    </mat-button-toggle>
                    <mat-button-toggle
                        value="connection"
                        matTooltip="Collect through a saved Connection (SFTP, Azure Blob, Kafka, Database)"
                    >
                        Connection
                    </mat-button-toggle>
                </mat-button-toggle-group>
            </div>

            <inspecto-schema-form
                #sf
                [specs]="specs()"
                [initial]="initial"
                [optionLoaders]="optionLoaders"
                (submitted)="save()"
            />

            @if (mode() === 'connection') {
                <!-- Connection affordances: test the picked profile, or create one in place. -->
                <div class="flex flex-wrap items-center gap-2">
                    <button
                        mat-stroked-button
                        type="button"
                        [disabled]="testing() || !connectionId()"
                        (click)="testConnection()"
                    >
                        @if (testing()) {
                            <mat-progress-spinner diameter="16" mode="indeterminate" class="mr-2" />
                        }
                        Test connection
                    </button>
                    @if (lens.canAuthorWorkbench()) {
                        <button mat-stroked-button type="button" (click)="newConnection()">
                            <mat-icon svgIcon="heroicons_outline:plus" class="icon-size-4" />
                            <span class="ml-1">New connection</span>
                        </button>
                    }
                    @if (derivedConnector(); as dc) {
                        <span class="text-secondary text-sm">
                            Connector: <span class="font-medium">{{ dc }}</span> — from this Connection.
                        </span>
                    }
                </div>
                @if (testResult(); as t) {
                    <inspecto-alert [variant]="t.reachable ? 'success' : 'error'">
                        {{ t.reachable ? 'Reachable' : 'Unreachable' }} — {{ t.detail }}
                        @if (t.latencyMs != null) { ({{ t.latencyMs }} ms) }
                    </inspecto-alert>
                }
            } @else {
                <p class="text-secondary m-0 text-sm">
                    Files are read from the pipeline's inbox folder (the space's <code>dirs.poll</code> convention).
                </p>
            }

            @if (saveError(); as e) {
                <inspecto-alert variant="error">{{ e }}</inspecto-alert>
            }

            <div class="flex items-center gap-3">
                <button
                    mat-flat-button
                    color="primary"
                    [disabled]="saving() || !lens.canAuthorWorkbench()"
                    (click)="save()"
                >
                    Save collection
                </button>
                @if (!lens.canAuthorWorkbench()) {
                    <span class="text-secondary text-sm">Your lens is read-only.</span>
                }
            </div>
        </div>
    `,
})
export class OnboardingCollectionPaneComponent implements OnDestroy {
    protected readonly state = inject(OnboardingStateService);
    protected readonly lens = inject(LensService);
    private connections = inject(ConnectionsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;

    readonly optionLoaders = { connection: connectionOptionLoader() };

    private readonly collectorBlock =
        ((this.state.config() ?? {})['collector'] as Record<string, unknown> | undefined) ?? {};
    /** The stored connector as authored — possibly hand-written TOON this pane must not destroy. */
    private readonly storedConnector = String(this.collectorBlock['connector'] ?? '').trim().toLowerCase();

    /** Collect from the local inbox, or through a saved Connection. */
    readonly mode = signal<'local' | 'connection'>(
        this.collectorBlock['connection'] || (this.storedConnector && this.storedConnector !== 'local')
            ? 'connection'
            : 'local',
    );
    /** Mode switched since load — dirty even before a field is touched. */
    private readonly modeTouched = signal(false);

    readonly specs = computed(() =>
        this.mode() === 'connection'
            ? COLLECTOR_ATTRIBUTES
            : COLLECTOR_ATTRIBUTES.filter((a) => a.key !== 'connection'),
    );
    readonly initial = flattenBlock(this.collectorBlock);

    /** Saved Connection profiles — the lookup that derives the picked Connection's connector. */
    readonly profiles = signal<{ id: string; connector: string }[]>([]);

    readonly saving = signal(false);
    readonly testing = signal(false);
    readonly testResult = signal<ConnectionTestResult | null>(null);
    readonly saveError = signal<string | null>(null);

    private readonly dirtyCheck = (): boolean => (this.schemaForm?.isDirty() ?? false) || this.modeTouched();

    constructor() {
        this.state.registerDirtyCheck(this.dirtyCheck);
        this.connections.list().subscribe({
            next: (list) => this.profiles.set(list.map((p) => ({ id: p.id, connector: p.connector }))),
            error: () => this.profiles.set([]),
        });
    }

    ngOnDestroy(): void {
        this.state.unregisterDirtyCheck(this.dirtyCheck);
    }

    setMode(m: 'local' | 'connection'): void {
        if (m === this.mode()) return;
        this.mode.set(m);
        this.modeTouched.set(true);
        this.saveError.set(null);
        this.testResult.set(null);
    }

    connectionId(): string {
        return String((this.schemaForm?.value() ?? {})['connection'] ?? '').trim();
    }

    /** The connector the save will write — the picked Connection's own type, no second ask. */
    derivedConnector(): string | null {
        const id = this.connectionId();
        if (!id) return null;
        return this.profiles().find((p) => p.id === id)?.connector ?? null;
    }

    testConnection(): void {
        const id = this.connectionId();
        if (!id) return;
        this.testing.set(true);
        this.testResult.set(null);
        this.connections.test(id).subscribe({
            next: (r) => {
                this.testing.set(false);
                this.testResult.set(r);
            },
            error: (e) => {
                this.testing.set(false);
                this.toastr.warning(apiErrorMessage(e, `Could not test "${id}" — is it saved?`));
            },
        });
    }

    async newConnection(): Promise<void> {
        if (!this.lens.canAuthorWorkbench()) return;
        const existingIds = (await firstValueFrom(this.connections.list()).catch(() => [])).map((c) => c.id);
        this.dialog
            .open<ConnectionFormDialog, unknown, ConnectionFormResult>(ConnectionFormDialog, {
                data: { existingIds },
                width: '720px',
                maxWidth: '95vw',
            })
            .afterClosed()
            .subscribe((res) => {
                if (res?.saved) {
                    this.schemaForm.form.get('connection')?.setValue(res.saved.id);
                    this.schemaForm.form.get('connection')?.markAsDirty();
                    this.profiles.update((p) => [...p.filter((x) => x.id !== res.saved!.id), res.saved!]);
                    this.testResult.set(null);
                }
            });
    }

    /** `collector.connector`, derived: local inbox ⇒ `local`; else the picked Connection's own type. */
    private resolveConnector(): string | null {
        if (this.mode() === 'local') return 'local';
        const id = this.connectionId();
        if (id) {
            const known = this.profiles().find((p) => p.id === id)?.connector;
            if (known) return known;
            this.saveError.set(`"${id}" is not a saved Connection — pick one from the list or create it.`);
            return null;
        }
        // Hand-authored TOON (non-local connector, no Connection) is grandfathered, not destroyed.
        if (this.storedConnector && this.storedConnector !== 'local') return this.storedConnector;
        this.saveError.set('Pick a Connection (or create one) — or switch to Local inbox.');
        return null;
    }

    save(): void {
        if (!this.lens.canAuthorWorkbench()) return;
        this.saveError.set(null);
        if (!this.schemaForm.validate()) return;
        const connector = this.resolveConnector();
        if (!connector) return;
        // Cleared fields delete their key (incl. `connection` when collecting locally); keys this
        // form never owned survive the deep merge.
        const roots = new Set(COLLECTOR_ATTRIBUTES.map((a) => a.key.split(KEY_SEP)[0]));
        const collector = clearMissingRoots(nestKeys(this.schemaForm.value()), roots);
        collector['connector'] = connector;
        this.saving.set(true);
        this.state.saveBlock({ collector }).subscribe({
            next: () => {
                this.saving.set(false);
                this.schemaForm.form.markAsPristine();
                this.modeTouched.set(false);
                this.toastr.success('Collection saved');
            },
            error: () => this.saving.set(false),
        });
    }
}
