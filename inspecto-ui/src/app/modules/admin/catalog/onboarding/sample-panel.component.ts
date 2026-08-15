import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ToastrService } from 'ngx-toastr';
import { ChipComponent } from 'app/inspecto/components/chip.component';
import { DefinitionStateService } from 'app/inspecto/definition/definition-state.service';

/** Session-held sample cap — a preview thread, not a data upload. */
const MAX_SAMPLE_BYTES = 256 * 1024;
const RAW_PREVIEW_LINES = 40;

/**
 * The sample-as-thread strip (design §4.3): ONE captured sample follows the builder through the
 * stages — raw here, parsed once the Parsing stage tests it, cast/mapped in later phases. It is
 * mounted **at the top of the Parsing stage only** (the stage that consumes it: choose the file,
 * see it, then pick a type and options below) — not in the shell, where it would be dead weight on
 * Collection/Publish. The state it reads is session-held in the shared {@link DefinitionStateService}
 * (host-provided — the one sample thread every definition surface shares, D5), so the
 * downstream stages still see the thread without rendering this panel. The header chips summarize
 * the thread (raw → parsed → cast) and the raw preview collapses when the builder is done reading
 * it. The sample is re-capturable (upload or paste) and never becomes part of the config. Capture
 * is allowed in every lens — it changes nothing on the server.
 */
@Component({
    selector: 'app-onboarding-sample-panel',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, MatIconModule, MatTooltipModule, ChipComponent],
    template: `
        <section class="rounded-lg border" aria-label="Sample">
            <div class="flex flex-wrap items-center gap-2 px-3 py-2">
                <mat-icon svgIcon="heroicons_outline:document-text" class="icon-size-4"></mat-icon>
                <h2 class="m-0 text-sm font-semibold">Sample</h2>

                @if (state.sample(); as s) {
                    <span class="min-w-0 max-w-48 truncate text-sm" [matTooltip]="s.name">{{ s.name }}</span>
                    <inspecto-chip variant="soft">{{ lineCount() }} lines</inspecto-chip>
                    @if (state.parseError()) {
                        <inspecto-chip variant="outline" matTooltip="Does not parse — see the Parsing stage">
                            parse ✗
                        </inspecto-chip>
                    } @else if (state.parsePreview(); as p) {
                        <inspecto-chip variant="soft" tone="primary">
                            parsed · {{ p.columns.length }} cols · {{ p.rowCount }} rows{{
                                p.rejectedRows > 0 ? ' · ' + p.rejectedRows + ' rejected' : ''
                            }}
                        </inspecto-chip>
                        @if (state.schemaError()) {
                            <inspecto-chip variant="outline" matTooltip="Does not cast — see the Schema stage">
                                cast ✗
                            </inspecto-chip>
                        } @else if (state.schemaPreview(); as sp) {
                            <inspecto-chip variant="soft" tone="primary">
                                cast · {{ sp.okCount }} ok{{
                                    sp.rejectedCount > 0 ? ' · ' + sp.rejectedCount + ' rejected' : ''
                                }}
                            </inspecto-chip>
                        }
                    } @else {
                        <inspecto-chip variant="outline" matTooltip="Run Test parse in the Parsing stage">
                            not parsed yet
                        </inspecto-chip>
                    }

                    <span class="flex-1"></span>
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
                        Capture one representative sample — it follows you through the stages, so every test shows
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

            @if (state.sample() && expanded()) {
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
export class OnboardingSamplePanelComponent {
    protected readonly state = inject(DefinitionStateService);
    private toastr = inject(ToastrService);

    readonly pasting = signal(false);
    /** Raw preview visibility — collapsible so a long sample doesn't push the stage pane away. */
    readonly expanded = signal(true);
    pasteText = '';

    readonly lineCount = computed(
        () =>
            this.state
                .sample()
                ?.text.split('\n')
                .filter((l) => l.length).length ?? 0,
    );
    readonly rawPreview = computed(() => {
        const text = this.state.sample()?.text ?? '';
        const lines = text.split('\n');
        const head = lines.slice(0, RAW_PREVIEW_LINES).join('\n');
        return lines.length > RAW_PREVIEW_LINES ? `${head}\n…` : head;
    });

    onFile(event: Event): void {
        const input = event.target as HTMLInputElement;
        const file = input.files?.[0];
        input.value = '';
        if (!file) return;
        const truncated = file.size > MAX_SAMPLE_BYTES;
        file.slice(0, MAX_SAMPLE_BYTES)
            .text()
            .then((text) => {
                this.state.captureSample(file.name, text);
                this.pasting.set(false);
                this.expanded.set(true);
                if (truncated) this.toastr.info(`Sample truncated to the first ${MAX_SAMPLE_BYTES / 1024} KB.`);
            })
            .catch(() => this.toastr.error('Could not read the file as text.'));
    }

    usePasted(): void {
        const text = this.pasteText.slice(0, MAX_SAMPLE_BYTES);
        if (!text.trim()) return;
        this.state.captureSample('pasted sample', text);
        this.pasting.set(false);
        this.expanded.set(true);
        this.pasteText = '';
    }

    clear(): void {
        this.state.clearSample();
    }
}
