import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { DefinitionStateService } from './definition-state.service';

/** Session-held sample cap — a preview thread, not a data upload. */
const MAX_SAMPLE_BYTES = 256 * 1024;
const RAW_PREVIEW_LINES = 40;

/**
 * The sample-as-thread strip (design §4.3): ONE captured sample follows the builder through a
 * definition surface — raw here, parsed once the parse step tests it, cast/mapped downstream. It is
 * mounted **where the sample is consumed** (the parse surface: choose the file, see it, then pick a
 * type and options below), never on a host chrome where it would be dead weight. The state it reads
 * is session-held in the shared {@link DefinitionStateService} (host-provided — the one sample
 * thread every definition surface shares, D5), so the downstream steps still see the thread without
 * rendering this panel. The header chips summarize the thread (raw → parsed → cast) and the raw
 * preview collapses when the builder is done reading it. The sample is re-capturable (upload or
 * paste) and never becomes part of the config. Capture is allowed in every lens — it changes nothing
 * on the server.
 *
 * <p>Its host since P6-e retired the onboarding wizard is the **pipeline editor's parse drawer**,
 * which holds one thread PER TAB — see {@link state}.
 */
@Component({
    selector: 'inspecto-sample-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, MatIconModule, MatTooltipModule, ChipComponent],
    template: `
        <section class="rounded-lg border" aria-label="Sample">
            <div class="flex flex-wrap items-center gap-2 px-3 py-2">
                <mat-icon svgIcon="heroicons_outline:document-text" class="icon-size-4"></mat-icon>
                <h2 class="m-0 text-sm font-semibold">Sample</h2>

                @if (state().sample(); as s) {
                    <span class="min-w-0 max-w-48 truncate text-sm" [matTooltip]="s.name">{{ s.name }}</span>
                    <inspecto-chip variant="soft">{{ lineCount() }} lines</inspecto-chip>
                    @if (state().parseError()) {
                        <inspecto-chip variant="outline" matTooltip="Does not parse — see Test parse">
                            parse ✗
                        </inspecto-chip>
                    } @else if (state().parsePreview(); as p) {
                        <inspecto-chip variant="soft" tone="primary">
                            parsed · {{ p.columns.length }} cols · {{ p.rowCount }} rows{{
                                p.rejectedRows > 0 ? ' · ' + p.rejectedRows + ' rejected' : ''
                            }}
                        </inspecto-chip>
                        @if (state().schemaError()) {
                            <inspecto-chip variant="outline" matTooltip="Does not cast — see the output schema">
                                cast ✗
                            </inspecto-chip>
                        } @else if (state().schemaPreview(); as sp) {
                            <inspecto-chip variant="soft" tone="primary">
                                cast · {{ sp.okCount }} ok{{
                                    sp.rejectedCount > 0 ? ' · ' + sp.rejectedCount + ' rejected' : ''
                                }}
                            </inspecto-chip>
                        }
                    } @else {
                        <inspecto-chip variant="outline" [matTooltip]="parseLabel() ? 'Run ' + parseLabel() : ''">
                            not parsed yet
                        </inspecto-chip>
                    }

                    <span class="flex-1"></span>
                    <!--
                        S4: the PRIMARY action, beside the chips whose state it changes. Parsing the
                        sample is what derives the schema, and the control used to sit below the option
                        tabs with its only feedback landing silently on another tab. Offered only when a
                        host binds it (the panel itself has no parser).
                    -->
                    @if (parseLabel()) {
                        <button
                            mat-flat-button
                            color="primary"
                            type="button"
                            [disabled]="parseDisabled()"
                            (click)="parse.emit()"
                        >
                            <mat-icon svgIcon="heroicons_outline:bolt" class="icon-size-4"></mat-icon>
                            <span class="ml-1">{{ parseLabel() }}</span>
                        </button>
                    }
                    <button mat-stroked-button type="button" (click)="fileInput.click()">Replace</button>
                    <button mat-stroked-button type="button" (click)="pasting.set(!pasting())">Paste text</button>
                    <button mat-stroked-button type="button" (click)="clear()">Clear</button>
                    <button
                        mat-icon-button
                        type="button"
                        (click)="expanded.set(!expanded())"
                        [attr.aria-label]="expanded() ? 'Collapse the raw preview' : 'Expand the raw preview'"
                        [matTooltip]="expanded() ? 'Collapse preview' : 'Expand preview'"
                    >
                        <mat-icon
                            class="icon-size-5"
                            [svgIcon]="expanded() ? 'heroicons_outline:chevron-up' : 'heroicons_outline:chevron-down'"
                        ></mat-icon>
                    </button>
                } @else {
                    <span class="text-secondary text-sm">
                        Capture one representative sample — it follows you through the definition, so every test shows
                        <em>your</em> data.
                    </span>
                    <span class="flex-1"></span>
                    <button mat-flat-button color="primary" type="button" (click)="fileInput.click()">
                        <mat-icon svgIcon="heroicons_outline:arrow-up-tray" class="icon-size-4"></mat-icon>
                        <span class="ml-1">Choose file</span>
                    </button>
                    <button mat-stroked-button type="button" (click)="pasting.set(!pasting())">Paste text</button>
                }
            </div>

            @if (state().sample() && expanded()) {
                <pre
                    class="bg-default m-0 max-h-96 overflow-auto rounded-b-lg border-t p-3 text-xs leading-relaxed"
                    aria-label="Raw sample preview"
                    >{{ rawPreview() }}</pre
                >
            }

            @if (pasting()) {
                <div class="flex flex-col gap-2 border-t p-3">
                    <textarea
                        class="bg-default min-h-32 w-full rounded border p-2 font-mono text-xs"
                        [(ngModel)]="pasteText"
                        placeholder="Paste a few representative lines…"
                        aria-label="Paste sample text"
                    ></textarea>
                    <button
                        mat-flat-button
                        color="primary"
                        type="button"
                        class="self-start"
                        [disabled]="!pasteText.trim()"
                        (click)="usePasted()"
                    >
                        Use pasted sample
                    </button>
                </div>
            }

            <input #fileInput type="file" class="hidden" (change)="onFile($event)" aria-hidden="true" tabindex="-1" />
        </section>
    `,
})
export class InspectoSamplePanelComponent {
    /**
     * The thread this panel renders — an INPUT, not an injection (P6-e follow-on). Its one host is now
     * the multi-tab pipeline editor, which keeps **one thread per tab**; DI providers are static per
     * component instance, so an injected service could only ever be the one-per-editor instance this
     * panel must not have. Handing the active tab's thread in keeps the panel pure, which is the same
     * D2 rule every definition pane follows.
     */
    readonly state = input.required<DefinitionStateService>();
    /**
     * Label for the host's parse action, e.g. `'Parse sample'` (S4). Empty ⇒ no action is offered:
     * this panel owns the sample, never a parser, so the verb only exists when a host supplies one.
     */
    readonly parseLabel = input('');
    readonly parseDisabled = input(false);
    /** The host should parse the captured sample with the settings currently on screen. */
    readonly parse = output<void>();
    private toastr = inject(ToastrService);

    readonly pasting = signal(false);
    /** Raw preview visibility — collapsible so a long sample doesn't push the stage pane away. */
    readonly expanded = signal(true);
    pasteText = '';

    readonly lineCount = computed(
        () =>
            this.state()
                .sample()
                ?.text.split('\n')
                .filter((l) => l.length).length ?? 0,
    );
    readonly rawPreview = computed(() => {
        const text = this.state().sample()?.text ?? '';
        const lines = text.split('\n');
        const head = lines.slice(0, RAW_PREVIEW_LINES).join('\n');
        return lines.length > RAW_PREVIEW_LINES ? `${head}\n…` : head;
    });

    onFile(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        // Binary formats (an .xlsx workbook — multiformat X4) capture BYTES: text() would round-trip
        // the zip through a charset and corrupt it. No truncation either — a sliced zip is unreadable,
        // so an oversized workbook is refused whole rather than silently maimed.
        if (/\.xlsx$/i.test(file.name)) {
            if (file.size > MAX_SAMPLE_BYTES) {
                this.toastr.error(`Workbook too large for a preview sample (max ${MAX_SAMPLE_BYTES / 1024} KB).`);
                return;
            }
            file.arrayBuffer()
                .then((buf) => {
                    let bin = '';
                    for (const b of new Uint8Array(buf)) bin += String.fromCharCode(b);
                    this.state().captureBinarySample(
                        file.name,
                        btoa(bin),
                        `[binary workbook — ${Math.max(1, Math.round(file.size / 1024))} KB]`,
                    );
                    this.pasting.set(false);
                    this.expanded.set(false); // nothing readable to expand
                })
                .catch(() => this.toastr.error('Could not read the file.'));
            return;
        }
        const truncated = file.size > MAX_SAMPLE_BYTES;
        file.slice(0, MAX_SAMPLE_BYTES)
            .text()
            .then((text) => {
                this.state().captureSample(file.name, text);
                this.pasting.set(false);
                this.expanded.set(true);
                if (truncated) this.toastr.info(`Sample truncated to the first ${MAX_SAMPLE_BYTES / 1024} KB.`);
            })
            .catch(() => this.toastr.error('Could not read the file as text.'));
    }

    usePasted(): void {
        const text = this.pasteText.slice(0, MAX_SAMPLE_BYTES);
        if (!text.trim()) return;
        this.state().captureSample('pasted sample', text);
        this.pasting.set(false);
        this.expanded.set(true);
        this.pasteText = '';
    }

    clear(): void {
        this.state().clearSample();
    }
}
