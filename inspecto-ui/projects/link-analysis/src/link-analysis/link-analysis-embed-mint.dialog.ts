import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { apiErrorMessage } from '@inspecto/core/api';
import { apiUrl } from '@inspecto/core/api/api-base';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InvService } from '@inspecto/link-analysis/api/inv.service';

export interface EmbedMintData {
    investigationId: string;
    defaultTtlHours: number;
    maxTtlHours: number;
    /** The Space's masking mode in force, for the plain statement of what the link shows. */
    maskingMode: string;
}

/** A refused embed call in an analyst's words; the server's own reason is always kept. */
export function embedErrorMessage(err: unknown, fallback: string): string {
    const status = (err as { status?: number } | null)?.status;
    const server = apiErrorMessage(err, fallback);
    switch (status) {
        case 503:
            return 'Embed links are switched off on this host. ' + server;
        case 403:
            return 'Only a lead of this Investigation who holds canShareInvestigation can do that. Server: ' + server;
        case 404:
            return 'Not available - the Investigation or the link is gone or not visible to you. Server: ' + server;
        case 409:
            return 'Refused as it stands now - ' + server;
        case 422:
            return 'The server refused the request: ' + server;
        default:
            return server;
    }
}

/** The absolute link: the host origin + the API prefix + the answer's public path (the SPA's own `apiUrl`). */
export function embedAbsoluteLink(publicPath: string): string {
    const abs = apiUrl(publicPath);
    return /^https?:\/\//.test(abs) ? abs : location.origin + abs;
}

/**
 * **Mint an embed link** (LA-EMBED-SPA-1). The token is answered once, so the full link lives ONLY in this dialog's
 * `link` signal while it is open: it is cleared when the dialog is dismissed or destroyed, and it is never written to
 * a URL, to storage or to the host's state - the dialog closes with a plain `true` ("a link was minted") only.
 */
@Component({
    selector: 'inspecto-link-analysis-embed-mint-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDialogModule, InspectoAlertComponent],
    template: `
        <h2 mat-dialog-title>Share an Investigation by link</h2>
        <mat-dialog-content class="flex w-[34rem] max-w-full flex-col gap-3 text-sm">
            <p class="m-0">
                The link shows a <strong>read-only, sealed view</strong>; ids are masked per this Space's masking mode:
                <strong>{{ data.maskingMode }}</strong
                >. Anyone with the link can read it until it expires or you revoke it; it cannot be framed on other
                sites.
            </p>
            @if (error()) {
                <inspecto-alert variant="error" title="Share">{{ error() }}</inspecto-alert>
            }
            @if (link(); as l) {
                <div class="flex flex-col gap-1">
                    <label for="embed-link-field" class="font-semibold">Your link</label>
                    <input
                        id="embed-link-field"
                        class="rounded border px-1 py-1"
                        style="border-color: var(--gamma-border)"
                        readonly
                        [value]="l"
                        (focus)="$any($event.target).select()"
                    />
                    <p class="m-0 font-semibold">Copy now - it is not shown again.</p>
                    <div class="flex items-center gap-2">
                        <button mat-flat-button color="primary" type="button" (click)="copy()">Copy</button>
                        <span role="status" aria-live="polite" class="text-secondary">{{ copied() }}</span>
                    </div>
                </div>
            } @else {
                <div class="flex flex-col gap-1">
                    <label for="embed-label" class="font-semibold">Label (optional)</label>
                    <input
                        id="embed-label"
                        class="rounded border px-1 py-1"
                        style="border-color: var(--gamma-border)"
                        maxlength="200"
                        [value]="label()"
                        (input)="label.set($any($event.target).value)"
                    />
                </div>
                <div class="flex flex-col gap-1">
                    <label for="embed-ttl" class="font-semibold">Lifetime (hours)</label>
                    <input
                        id="embed-ttl"
                        type="number"
                        class="rounded border px-1 py-1"
                        style="border-color: var(--gamma-border)"
                        min="1"
                        [max]="data.maxTtlHours"
                        step="1"
                        [attr.aria-invalid]="ttlError() ? 'true' : null"
                        [attr.aria-describedby]="ttlError() ? 'embed-ttl-error' : null"
                        [value]="ttl()"
                        (input)="ttl.set($any($event.target).value)"
                    />
                    @if (ttlError()) {
                        <span id="embed-ttl-error" role="alert" class="text-xs">{{ ttlError() }}</span>
                    }
                </div>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            @if (link()) {
                <button mat-flat-button type="button" (click)="close()">Done</button>
            } @else {
                <button mat-button type="button" (click)="close()">Cancel</button>
                <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    [disabled]="busy() || !!ttlError()"
                    (click)="mint()"
                >
                    Create link
                </button>
            }
        </mat-dialog-actions>
    `,
})
export class LinkAnalysisEmbedMintDialog implements OnDestroy {
    readonly data = inject<EmbedMintData>(MAT_DIALOG_DATA);
    private readonly ref = inject(MatDialogRef<LinkAnalysisEmbedMintDialog, boolean>);
    private readonly inv = inject(InvService);

    readonly label = signal('');
    readonly ttl = signal(String(this.data.defaultTtlHours));
    readonly busy = signal(false);
    readonly error = signal('');
    readonly copied = signal('');
    /** The full link - set once on a successful mint, cleared on close / destroy. Nowhere else holds it. */
    readonly link = signal('');
    private minted = false;

    readonly ttlError = computed(() => {
        const raw = this.ttl().trim();
        const v = Number(raw);
        if (raw === '' || !Number.isInteger(v) || v < 1 || v > this.data.maxTtlHours)
            return `Enter a whole number of hours from 1 to ${this.data.maxTtlHours} (this Space's maximum).`;
        return '';
    });

    async mint(): Promise<void> {
        if (this.ttlError()) return;
        this.busy.set(true);
        this.error.set('');
        try {
            const body: { label?: string; ttl_hours: number } = { ttl_hours: Number(this.ttl()) };
            const label = this.label().trim();
            if (label) body.label = label;
            const m = await firstValueFrom(this.inv.mintInvestigationEmbed(this.data.investigationId, body));
            this.minted = true;
            this.link.set(embedAbsoluteLink(m.url));
        } catch (err) {
            this.error.set(embedErrorMessage(err, 'Could not create the link.'));
        } finally {
            this.busy.set(false);
        }
    }

    async copy(): Promise<void> {
        const text = this.link();
        try {
            await navigator.clipboard.writeText(text);
            this.copied.set('Copied to the clipboard.');
        } catch {
            const field = document.getElementById('embed-link-field') as HTMLInputElement | null;
            field?.select();
            let ok: boolean;
            try {
                ok = document.execCommand('copy');
            } catch {
                ok = false;
            }
            this.copied.set(ok ? 'Copied to the clipboard.' : 'Copy failed - select the link and copy it by hand.');
        }
    }

    close(): void {
        this.link.set('');
        this.ref.close(this.minted);
    }

    ngOnDestroy(): void {
        this.link.set('');
    }
}
