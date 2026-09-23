import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { firstValueFrom } from 'rxjs';
import { Dossier, DossierVerifyResult, InvService } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { investigationErrorMessage } from './investigation-state';

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
 * verify a manifest (the one just issued, or one uploaded as JSON) against the store as it is NOW. Nothing here
 * persists — both routes are read-shaped and audited server-side.
 */
@Component({
    selector: 'inspecto-link-analysis-dossier',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, InspectoAlertComponent],
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
    readonly busy = signal(false);
    readonly error = signal('');

    constructor() {
        // Another Investigation opened: the dossier on screen documents the previous one — clear it.
        effect(() => {
            this.investigationId();
            untracked(() => {
                this.dossier.set(null);
                this.verifyResult.set(null);
                this.error.set('');
            });
        });
    }

    build(): Promise<void> {
        return this.run('Could not build the dossier.', async () => {
            this.verifyResult.set(null);
            this.dossier.set(await firstValueFrom(this.inv.dossier(this.investigationId())));
        });
    }

    /** The rendering at the SAME step as the dossier on screen, so the file and the view agree. */
    download(format: 'steps' | 'method'): Promise<void> {
        const at = this.dossier()?.summary.at;
        return this.run(`Could not download the ${format} rendering.`, async () => {
            const blob = await firstValueFrom(this.inv.dossierRendering(this.investigationId(), format, { at }));
            saveBlob(blob, `${this.investigationId()}-${format}.txt`);
        });
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
