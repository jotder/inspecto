import { ChangeDetectionStrategy, Component, Input, ViewChild, computed, inject, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { firstValueFrom } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, ConnectionTestResult, ConnectionsService, LensService } from 'app/inspecto/api';
import { AttributeSpec } from 'app/inspecto/component-model';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoSchemaFormComponent } from 'app/inspecto/components/schema-form.component';
import {
    connectionOptionLoader,
    datasetOptionLoader,
    datasetRefOptionLoader,
} from 'app/inspecto/components/entity-option-loaders';
import { ConnectionFormDialog, ConnectionFormResult } from 'app/inspecto/connections/connection-form.dialog';

/** Collect from the pipeline's local inbox, through a saved Connection, or from a Dataset (UI-S7). */
export type CollectorMode = 'local' | 'connection' | 'dataset';

/**
 * **The** collector-config surface (collector-config unification, 2026-08-04): the where-do-files-come-from
 * chrome shared by the two features that author one `collector:` block — Onboarding's Collection stage and
 * the Pipelines editor's `acquisition` node. Mode toggle, the shared `<inspecto-schema-form>` over
 * {@link COLLECTOR_ATTRIBUTES}, Test connection, create-a-Connection in place, and the derived-connector
 * readout all live here so the two surfaces cannot drift the way their spec tables once did.
 *
 * <p>**No write path.** The component never persists: hosts read {@link value} / {@link resolveConnector}
 * and save through their own validated route (Onboarding `POST /config/patch`, the node dialog through the
 * graph save). That keeps the two genuinely different persistence shapes — a `collector:` block vs. a node's
 * raw config plus a `use: connection/<id>` binding — in the hosts, where they belong.
 *
 * <p>`connector` is never asked: it is DERIVED (local inbox ⇒ `local`, else the picked Connection's own
 * connector), because `CollectorConnectors.forConfig` dispatches on `collector.connector` and hands that
 * factory the profile named by `collector.connection` without checking the two agree.
 */
@Component({
    selector: 'inspecto-collector-config',
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
        <div class="flex flex-col gap-4">
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
                    <mat-button-toggle
                        value="dataset"
                        matTooltip="Consume another Pipeline's Dataset — its parquet snapshots feed this pipeline's inbox"
                    >
                        Dataset
                    </mat-button-toggle>
                </mat-button-toggle-group>
            </div>

            <inspecto-schema-form
                #sf
                [specs]="visibleSpecs()"
                [initial]="seed()"
                [optionLoaders]="optionLoaders"
                (submitted)="submitted.emit()"
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
                        @if (t.latencyMs != null) {
                            ({{ t.latencyMs }} ms)
                        }
                    </inspecto-alert>
                }
            } @else if (mode() === 'dataset') {
                <p class="text-secondary m-0 text-sm">
                    Each acquire cycle copies the Dataset's new parquet snapshots into this pipeline's inbox — the
                    producer's files are never deleted, whatever the post-action says. Pair it with the
                    <em>Run when — A Dataset is written</em> trigger for event latency.
                </p>
            } @else {
                <p class="text-secondary m-0 text-sm">
                    Files are read from the pipeline's inbox folder (the space's <code>dirs.poll</code> convention).
                </p>
            }

            @if (error(); as e) {
                <inspecto-alert variant="error">{{ e }}</inspecto-alert>
            }
        </div>
    `,
})
export class CollectorConfigComponent {
    protected readonly lens = inject(LensService);
    private connections = inject(ConnectionsService);
    private dialog = inject(MatDialog);
    private toastr = inject(ToastrService);

    @ViewChild('sf') schemaForm!: InspectoSchemaFormComponent;

    readonly optionLoaders = {
        connection: connectionOptionLoader(),
        dataset: datasetOptionLoader(),
        trigger__from: datasetRefOptionLoader(),
    };

    /** The collector table to render — the WHOLE shared one, `connection` included (filtered in local mode). */
    @Input({ required: true }) set specs(specs: AttributeSpec[]) {
        this.allSpecs.set(specs ?? []);
    }

    /** Existing flat values to edit (`__` = nesting, per `flat-keys.ts`). */
    @Input() set initial(value: Record<string, unknown> | null | undefined) {
        this.seed.set({ ...(value ?? {}) });
        this.storedConnection.set(String(this.seed()['connection'] ?? '').trim());
        this.deriveMode();
    }

    /**
     * `collector.connector` **as authored** — possibly a hand-written TOON value this component must not
     * destroy. It picks the initial mode alongside `connection`, and is the grandfathered fallback in
     * {@link resolveConnector} when a non-local connector was authored without a Connection.
     */
    @Input() set storedConnector(value: string | null | undefined) {
        this.stored.set(
            String(value ?? '')
                .trim()
                .toLowerCase(),
        );
        this.deriveMode();
    }

    /** Enter in any field — hosts bind their save action here, exactly as with `<inspecto-schema-form>`. */
    readonly submitted = output<void>();

    private readonly allSpecs = signal<AttributeSpec[]>([]);
    private readonly stored = signal('');
    /**
     * What the embedded form is seeded with. Reassigning `specs` rebuilds every control from its
     * declared default, so the mode toggle folds the live values back in here first — otherwise
     * flipping the toggle silently wiped everything the operator had typed.
     */
    protected readonly seed = signal<Record<string, unknown>>({});

    readonly mode = signal<CollectorMode>('local');
    /** Mode switched since load — dirty even before a field is touched. */
    private readonly modeTouched = signal(false);

    /**
     * `connection` is a Connection-mode question only and `dataset` a Dataset-mode one; the other
     * modes never ask them. Dataset mode also hides the `post_action__*` keys — the dataset connector
     * FORCES post-action to Retain (a consumer never deletes a producer's snapshots), so offering the
     * knob would author config the engine silently ignores.
     */
    readonly visibleSpecs = computed(() => {
        const m = this.mode();
        return this.allSpecs().filter((a) =>
            m === 'connection'
                ? a.key !== 'dataset'
                : m === 'dataset'
                  ? a.key !== 'connection' && !a.key.startsWith('post_action__')
                  : a.key !== 'connection' && a.key !== 'dataset',
        );
    });

    /** Saved Connection profiles — the lookup that derives the picked Connection's connector. */
    readonly profiles = signal<{ id: string; connector: string }[]>([]);
    /**
     * Whether {@link profiles} is ANSWERING or merely EMPTY. An unreachable `ConnectionsService`
     * degrades to `[]`, which is indistinguishable from a loaded list that does not contain the id —
     * and that ambiguity used to refuse a save of an unchanged, previously-valid node. Only `ok`
     * licenses the "not a saved Connection" verdict.
     */
    private readonly profilesState = signal<'loading' | 'ok' | 'failed'>('loading');
    /** The Connection id this node was loaded with — the one value a failed list can still vouch for. */
    private readonly storedConnection = signal('');

    readonly testing = signal(false);
    readonly testResult = signal<ConnectionTestResult | null>(null);
    /** Why the connector could not be resolved — set by {@link resolveConnector}, rendered inline. */
    readonly error = signal<string | null>(null);

    constructor() {
        this.connections.list().subscribe({
            next: (list) => {
                this.profiles.set(list.map((p) => ({ id: p.id, connector: p.connector })));
                this.profilesState.set('ok');
            },
            error: () => {
                this.profiles.set([]);
                this.profilesState.set('failed');
            },
        });
    }

    /** Stored state picks the mode, until the operator says otherwise. */
    private deriveMode(): void {
        if (this.modeTouched()) return;
        const stored = this.stored();
        if (stored === 'dataset' || this.seed()['dataset']) {
            this.mode.set('dataset');
            return;
        }
        this.mode.set(this.seed()['connection'] || (stored && stored !== 'local') ? 'connection' : 'local');
    }

    setMode(m: CollectorMode): void {
        if (m === this.mode()) return;
        this.seed.update((s) => ({ ...s, ...(this.schemaForm?.value() ?? {}) }));
        this.mode.set(m);
        this.modeTouched.set(true);
        this.error.set(null);
        this.testResult.set(null);
    }

    connectionId(): string {
        return String((this.schemaForm?.value() ?? {})['connection'] ?? '').trim();
    }

    /** The connector a save will write — the picked Connection's own type, no second ask. */
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

    /**
     * `collector.connector`, derived: local inbox ⇒ `local`; else the picked Connection's own type.
     * `null` ⇒ refused, with {@link error} explaining why — hosts abort the save on null.
     */
    resolveConnector(): string | null {
        this.error.set(null);
        if (this.mode() === 'local') return 'local';
        if (this.mode() === 'dataset') {
            // Fail-closed like the engine's own pair gate (PipelineConfigParser): a dataset-entry
            // collector with no Dataset is the state that would refuse to load at run time.
            const dataset = String((this.schemaForm?.value() ?? {})['dataset'] ?? '').trim();
            if (dataset) return 'dataset';
            this.error.set('Pick the Dataset to consume — or switch to Local inbox.');
            return null;
        }
        const id = this.connectionId();
        if (id) {
            const known = this.profiles().find((p) => p.id === id)?.connector;
            if (known) return known;
            if (this.profilesState() !== 'ok') {
                // The list never answered, so absence is not evidence. An UNCHANGED id keeps the
                // connector it was authored with; a newly picked one has nothing to derive from.
                const stored = this.stored();
                if (id === this.storedConnection() && stored && stored !== 'local') return stored;
                this.error.set(
                    `Connections could not be loaded, so "${id}" cannot be confirmed — retry once the service is reachable.`,
                );
                return null;
            }
            this.error.set(`"${id}" is not a saved Connection — pick one from the list or create it.`);
            return null;
        }
        // Hand-authored TOON (non-local connector, no Connection) is grandfathered, not destroyed.
        const stored = this.stored();
        if (stored && stored !== 'local') return stored;
        this.error.set('Pick a Connection (or create one) — or switch to Local inbox.');
        return null;
    }

    /** Mark everything touched on invalid submit and report validity (mirrors the schema form). */
    validate(): boolean {
        return this.schemaForm?.validate() ?? true;
    }

    /** Whether anything changed — a mode switch alone counts. */
    isDirty(): boolean {
        return (this.schemaForm?.isDirty() ?? false) || this.modeTouched();
    }

    /** The visible flat field values; hosts nest/merge them into their own persisted shape. */
    value(): Record<string, unknown> {
        return this.schemaForm?.value() ?? {};
    }

    /** Called by hosts after a successful save. */
    markPristine(): void {
        this.schemaForm?.form.markAsPristine();
        this.modeTouched.set(false);
    }
}
