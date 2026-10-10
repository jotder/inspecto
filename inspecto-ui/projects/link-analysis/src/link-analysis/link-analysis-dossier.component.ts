import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { AbstractControl, FormControl, ReactiveFormsModule, ValidationErrors } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import {
    Dossier,
    DossierBundleVerifyResult,
    DossierQuery,
    DossierVerifyResult,
    InvService,
    SealedSnapshotIds,
} from '@inspecto/link-analysis/api/inv.service';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { investigationErrorMessage } from './investigation-state';

/** `DossierRoutes.MAX_SNAPSHOTS` — the server refuses more with a 422. */
export const DOSSIER_MAX_SNAPSHOTS = 20;
/** How many sealed ids the picker lists (`GET /inv/snapshots` defaults to 100, clamps to 1 000). */
const SNAPSHOT_LIST_LIMIT = 100;

/** Trigger a client-side download of an already-fetched Blob (the bearer travelled with the fetch). */
function saveBlob(blob: Blob, name: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
}

/**
 * **Link Analysis — Dossier** (LA-12, SPA half) over `DossierRoutes`: build the Investigation's dossier from the
 * sealed log, read its summary / topology / ledger / score tables, download the steps and method renderings, and
 * verify a manifest (the one just issued, or one uploaded as JSON) against the store as it is NOW. The dossier can
 * cover a PREFIX of the log (`at`, 0..steps; blank = the head) and embed up to 20 sealed snapshots' score tables
 * (`snapshots`). Nothing here persists — the routes are read-shaped and audited server-side.
 */
@Component({
    selector: 'inspecto-link-analysis-dossier',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatCheckboxModule,
        MatFormFieldModule,
        MatInputModule,
        InspectoAlertComponent,
    ],
    host: { class: 'block' },
    template: `
        <section
            class="flex flex-col gap-2 rounded-md border p-2 text-xs"
            style="border-color: var(--gamma-border)"
            aria-label="Dossier"
        >
            <div class="flex items-center gap-1">
                <h3 class="text-secondary m-0 text-xs font-semibold uppercase tracking-wide">Dossier</h3>
                <button mat-stroked-button class="ml-auto" [disabled]="busy()" (click)="build()">
                    {{ dossier() ? 'Rebuild' : 'Build dossier' }}
                </button>
            </div>
            <div class="flex flex-wrap items-start gap-2" aria-label="Dossier scope">
                <mat-form-field class="w-40" subscriptSizing="dynamic">
                    <mat-label>At step</mat-label>
                    <input matInput type="number" min="0" [attr.max]="steps()" [formControl]="atControl" />
                    <mat-hint>{{ steps() == null ? 'Blank = the head' : 'Blank = the head, 0–' + steps() }}</mat-hint>
                    @if (atControl.hasError('range')) {
                        <mat-error>{{ atError() }}</mat-error>
                    }
                </mat-form-field>
                <div class="flex flex-col gap-1">
                    <button mat-button [disabled]="busy()" (click)="loadSnapshots()">
                        {{ snapshotList() ? 'Reload snapshots' : 'Include snapshots…' }}
                    </button>
                    <span class="text-secondary tabular-nums" aria-live="polite">
                        {{ selected().length }} of at most {{ maxSnapshots }} snapshots included
                    </span>
                </div>
            </div>
            @if (snapshotError()) {
                <inspecto-alert variant="error" title="Snapshots">{{ snapshotError() }}</inspecto-alert>
            }
            @if (snapshotList(); as list) {
                @if (list.ids.length) {
                    <fieldset class="m-0 flex flex-col border-0 p-0">
                        <legend class="text-secondary">
                            Sealed snapshots, newest first — only those anchored to this Investigation are accepted
                            @if (list.truncated) {
                                (showing {{ list.ids.length }} of {{ list.total }})
                            }
                        </legend>
                        @for (id of list.ids; track id) {
                            <mat-checkbox
                                [checked]="selected().includes(id)"
                                [disabled]="!selected().includes(id) && selected().length >= maxSnapshots"
                                (change)="toggleSnapshot(id, $event.checked)"
                                >{{ id }}</mat-checkbox
                            >
                        }
                    </fieldset>
                } @else {
                    <p class="text-secondary m-0">No sealed snapshots in this Space.</p>
                }
            }
            @if (error()) {
                <inspecto-alert variant="error" title="Dossier">{{ error() }}</inspecto-alert>
            }
            @if (dossier(); as d) {
                <p class="m-0 tabular-nums" aria-label="Dossier summary">
                    <strong>{{ d.summary.title || d.summary.investigation }}</strong> over {{ d.summary.dataset }} — at
                    step {{ d.summary.at }} of {{ d.summary.steps }} · {{ d.summary.entities }} entities ·
                    {{ d.summary.links }} links · {{ d.summary.excluded }} excluded · {{ d.summary.hidden }} hidden ·
                    {{ d.summary.kept }} kept
                </p>
                <p class="text-secondary m-0">
                    Topology: {{ d.topology.entities }} entities, {{ d.topology.links }} links,
                    {{ d.topology.degreeTotal }} with at least one link.
                </p>
                @if (d.integrity.intact) {
                    <inspecto-alert variant="success" title="Log intact">
                        All {{ d.integrity.stepsChecked }} recorded hashes still agree with the sealed log.
                    </inspecto-alert>
                } @else {
                    <inspecto-alert variant="error" title="Integrity failure">
                        {{ d.integrity.failures.length }} recorded hash(es) no longer agree with the sealed log — this
                        dossier documents a log that has been altered.
                    </inspecto-alert>
                }
                <table class="w-full" aria-label="Ledger">
                    <thead>
                        <tr class="text-secondary text-left">
                            <th scope="col">Step</th>
                            <th scope="col">Step taken</th>
                            <th scope="col">Entities after</th>
                        </tr>
                    </thead>
                    <tbody>
                        @for (r of d.ledger; track r.step) {
                            <tr>
                                <td class="tabular-nums">{{ r.step }}</td>
                                <td [class.line-through]="r.undoneBy != null">
                                    {{ r.text }}
                                    @if (r.truncated) {
                                        <strong>(read truncated)</strong>
                                    }
                                </td>
                                <td class="tabular-nums">{{ r.entitiesAfter }}</td>
                            </tr>
                        }
                    </tbody>
                </table>
                @if (d.scores.tables.length) {
                    <ul class="m-0 list-none p-0" aria-label="Score tables">
                        @for (t of d.scores.tables; track t.metric + t.snapshot) {
                            <li>{{ t.metric }} — snapshot {{ t.snapshot }} ({{ t.total }} scored)</li>
                        }
                    </ul>
                    <p class="text-secondary m-0">{{ d.scores.computedBy }}</p>
                } @else if (d.scores.note) {
                    <p class="text-secondary m-0">{{ d.scores.note }}</p>
                }
                <p class="text-secondary m-0 break-all">
                    Manifest root: <code>{{ d.manifest.root }}</code>
                </p>
                <div class="flex flex-wrap gap-1">
                    <button mat-stroked-button [disabled]="busy()" (click)="download('steps')">Download steps</button>
                    <button mat-stroked-button [disabled]="busy()" (click)="download('method')">Download method</button>
                    <button mat-stroked-button [disabled]="busy()" (click)="download('html')">Download HTML</button>
                    <button mat-stroked-button (click)="downloadManifest()">Download manifest</button>
                </div>
            }

            <div class="flex flex-wrap items-center gap-1" aria-label="Verify a manifest">
                <button mat-stroked-button [disabled]="busy() || !dossier()" (click)="verifyIssued()">
                    Verify this manifest
                </button>
                <button mat-button [disabled]="busy()" (click)="file.click()">Verify an uploaded manifest…</button>
                <input
                    #file
                    type="file"
                    accept="application/json,.json"
                    class="hidden"
                    aria-label="Manifest or dossier JSON file"
                    (change)="upload($event)"
                />
            </div>
            <div class="flex flex-wrap items-center gap-1" aria-label="Export bundle">
                <button mat-stroked-button [disabled]="busy()" (click)="downloadBundle()">Download sealed bundle</button>
                <button mat-button [disabled]="busy()" (click)="bundleFile.click()">Verify a bundle…</button>
                <input
                    #bundleFile
                    type="file"
                    accept="application/json,.json"
                    class="hidden"
                    aria-label="Dossier bundle JSON file"
                    (change)="uploadBundle($event)"
                />
            </div>
            <p class="text-secondary m-0">
                The bundle is one file for someone outside the system: the Dossier, its references and a seal over both.
                Any edit to the file breaks the seal; verify also asks the server whether the store still agrees.
            </p>
            @if (bundleResult(); as b) {
                @if (b.verified) {
                    <inspecto-alert variant="success" title="Bundle verified">
                        The seal matches the file and the store still agrees with the Dossier.
                        @if (b.referencesAddedSince) {
                            {{ b.referencesAddedSince }} reference(s) were added after export; appending never breaks a
                            bundle.
                        }
                    </inspecto-alert>
                } @else {
                    <inspecto-alert variant="error" title="Bundle NOT verified">
                        <ul class="m-0 pl-4">
                            @for (p of b.problems; track p) {
                                <li>{{ p }}</li>
                            }
                        </ul>
                    </inspecto-alert>
                }
                <dl class="m-0 grid grid-cols-[auto_1fr] gap-x-2" aria-label="Bundle verification detail">
                    <dt class="text-secondary">Seal intact</dt>
                    <dd class="m-0">{{ b.sealIntact ? 'yes' : 'no — edited after export' }}</dd>
                    <dt class="text-secondary">Manifest root matches</dt>
                    <dd class="m-0">{{ b.rootMatches ? 'yes' : 'no' }}</dd>
                    <dt class="text-secondary">References intact</dt>
                    <dd class="m-0">{{ b.referencesIntact ? 'yes' : 'no' }}</dd>
                    <dt class="text-secondary">Store agrees (custody)</dt>
                    <dd class="m-0">{{ b.custody.verified ? 'yes' : 'no' }}</dd>
                </dl>
            }
            @if (verifyResult(); as v) {
                @if (v.verified) {
                    <inspecto-alert variant="success" title="Verified">
                        The manifest matches the store now: root {{ v.currentRoot }}.
                    </inspecto-alert>
                } @else {
                    <inspecto-alert variant="error" title="NOT verified">
                        @if (!v.selfConsistent) {
                            The manifest's root does not match its own body — the manifest itself was edited.
                        }
                        @if (!v.intact) {
                            The store's recorded hashes no longer agree with its sealed log.
                        }
                        @if (v.submittedRoot !== v.currentRoot) {
                            The root differs: submitted {{ v.submittedRoot }}, now {{ v.currentRoot }}.
                        }
                    </inspecto-alert>
                }
                <dl class="m-0 grid grid-cols-[auto_1fr] gap-x-2" aria-label="Verification detail">
                    <dt class="text-secondary">Self-consistent</dt>
                    <dd class="m-0">{{ v.selfConsistent ? 'yes' : 'no' }}</dd>
                    <dt class="text-secondary">Changed</dt>
                    <dd class="m-0 break-all">{{ v.changed.join(', ') || 'none' }}</dd>
                    <dt class="text-secondary">Missing</dt>
                    <dd class="m-0 break-all">{{ v.missing.join(', ') || 'none' }}</dd>
                    <dt class="text-secondary">Added</dt>
                    <dd class="m-0 break-all">{{ v.added.join(', ') || 'none' }}</dd>
                    <dt class="text-secondary">Content changed</dt>
                    <dd class="m-0 break-all">{{ v.contentChanged.join(', ') || 'none' }}</dd>
                </dl>
            }
        </section>
    `,
})
export class LinkAnalysisDossierComponent {
    private inv = inject(InvService);

    readonly investigationId = input.required<string>();

    readonly dossier = signal<Dossier | null>(null);
    readonly verifyResult = signal<DossierVerifyResult | null>(null);
    readonly bundleResult = signal<DossierBundleVerifyResult | null>(null);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly maxSnapshots = DOSSIER_MAX_SNAPSHOTS;
    /** The log length, known once a dossier has been built (its `summary.steps`). */
    readonly steps = signal<number | null>(null);
    readonly snapshotList = signal<SealedSnapshotIds | null>(null);
    readonly selected = signal<string[]>([]);
    readonly snapshotError = signal('');
    readonly atControl = new FormControl<number | null>(null, (c) => this.atRange(c));

    constructor() {
        // Another Investigation opened: the dossier on screen documents the previous one — clear it.
        effect(() => {
            this.investigationId();
            untracked(() => {
                this.dossier.set(null);
                this.verifyResult.set(null);
                this.bundleResult.set(null);
                this.error.set('');
                this.steps.set(null);
                this.snapshotList.set(null);
                this.selected.set([]);
                this.snapshotError.set('');
                this.atControl.reset(null);
            });
        });
    }

    build(): Promise<void> {
        if (this.atControl.invalid) {
            this.atControl.markAsTouched();
            return Promise.resolve();
        }
        const q: DossierQuery = { at: this.atControl.value ?? undefined, snapshots: this.selected() };
        return this.run('Could not build the dossier.', async () => {
            this.verifyResult.set(null);
            const d = await firstValueFrom(this.inv.dossier(this.investigationId(), q));
            this.dossier.set(d);
            this.steps.set(d.summary.steps);
            this.atControl.updateValueAndValidity();
        });
    }

    /** The rendering at the SAME step and snapshots as the dossier on screen, so the file and the view agree. */
    download(format: 'steps' | 'method' | 'html'): Promise<void> {
        const d = this.dossier();
        const q: DossierQuery = { at: d?.summary.at, snapshots: d?.summary.snapshots };
        return this.run(`Could not download the ${format} rendering.`, async () => {
            const blob = await firstValueFrom(this.inv.dossierRendering(this.investigationId(), format, q));
            saveBlob(blob, `${this.investigationId()}-${format}.${format === 'html' ? 'html' : 'txt'}`);
        });
    }

    async loadSnapshots(): Promise<void> {
        this.snapshotError.set('');
        try {
            this.snapshotList.set(await firstValueFrom(this.inv.sealedSnapshotIds(SNAPSHOT_LIST_LIMIT)));
        } catch (err) {
            this.snapshotError.set(investigationErrorMessage(err, 'Could not list the sealed snapshots.'));
        }
    }

    toggleSnapshot(id: string, on: boolean): void {
        const cur = this.selected().filter((s) => s !== id);
        if (on && cur.length < DOSSIER_MAX_SNAPSHOTS) cur.push(id);
        this.selected.set(cur);
    }

    atError(): string {
        const s = this.steps();
        return s == null ? 'A whole number, 0 or more.' : `A whole number from 0 to ${s}.`;
    }

    /** `?at=` must be a whole number in 0..steps (the server answers 422 otherwise); blank = the head. */
    private atRange(c: AbstractControl): ValidationErrors | null {
        const v = c.value as number | null;
        if (v == null) return null;
        const s = this.steps();
        return Number.isInteger(v) && v >= 0 && (s == null || v <= s) ? null : { range: true };
    }

    downloadManifest(): void {
        const d = this.dossier();
        if (!d) return;
        saveBlob(
            new Blob([JSON.stringify(d.manifest, null, 2)], { type: 'application/json' }),
            `${this.investigationId()}-manifest.json`,
        );
    }

    verifyIssued(): Promise<void> {
        const d = this.dossier();
        return d ? this.verify(d.manifest) : Promise.resolve();
    }

    /** An uploaded file may hold a bare manifest or a whole dossier; the route accepts both, so send the manifest. */
    async upload(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        let parsed: unknown;
        try {
            parsed = JSON.parse(await file.text());
        } catch {
            this.error.set(`${file.name} is not JSON — upload the manifest (or dossier) file this screen downloaded.`);
            return;
        }
        const obj = parsed as { manifest?: unknown } | null;
        await this.verify(obj && typeof obj === 'object' && obj.manifest ? obj.manifest : parsed);
    }

    /** The bundle at the SAME step and snapshots as the dossier on screen (or the head when none is built). */
    downloadBundle(): Promise<void> {
        const d = this.dossier();
        const q: DossierQuery = { at: d?.summary.at, snapshots: d?.summary.snapshots };
        return this.run('Could not export the bundle.', async () => {
            const bundle = await firstValueFrom(this.inv.dossierBundle(this.investigationId(), q));
            saveBlob(
                new Blob([JSON.stringify(bundle, null, 2)], { type: 'application/json' }),
                `${this.investigationId()}-bundle.json`,
            );
        });
    }

    /** The uploaded file is sent whole: the seal covers all of it, so nothing is picked out or re-shaped here. */
    async uploadBundle(event: Event): Promise<void> {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        let parsed: unknown;
        try {
            parsed = JSON.parse(await file.text());
        } catch {
            this.error.set(`${file.name} is not JSON — upload the bundle file this screen downloaded.`);
            return;
        }
        await this.run('Bundle verification failed.', async () => {
            this.bundleResult.set(await firstValueFrom(this.inv.verifyDossierBundle(this.investigationId(), parsed)));
        });
    }

    private verify(manifest: unknown): Promise<void> {
        return this.run('Verification failed.', async () => {
            this.verifyResult.set(await firstValueFrom(this.inv.verifyDossier(this.investigationId(), manifest)));
        });
    }

    private async run(fallback: string, body: () => Promise<void>): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        try {
            await body();
        } catch (err) {
            this.error.set(investigationErrorMessage(err, fallback));
        } finally {
            this.busy.set(false);
        }
    }
}
