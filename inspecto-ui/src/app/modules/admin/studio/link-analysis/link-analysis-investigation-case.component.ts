import { ChangeDetectionStrategy, Component, effect, inject, input, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { Observable, firstValueFrom } from 'rxjs';
import { InvService, InvestigationCaseLink, apiErrorMessage } from 'app/inspecto/api';
import { InspectoAlertComponent } from 'app/inspecto/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from 'app/inspecto/components/option-picker.component';
import { LA_CASES } from 'app/inspecto/la-host';

/**
 * **Link to a Case** (LA-24, decision D-U10) — the Investigation panel's OPTIONAL Case link. Linking shares the
 * Investigation READ-ONLY with the Case's owner and assignee; writes stay with the Investigation's owner.
 *
 * ⚠ Link Analysis does not depend on Case management: without the ops module (`opsEnabled()` false) no Case is
 * offered — never a placeholder, since a link to a fabricated id would read as shared when it grants nothing — and
 * the panel says why. When the caller is reading someone else's Investigation as a Case member, it shows a
 * read-only notice instead of the controls.
 */
@Component({
    selector: 'inspecto-link-analysis-investigation-case',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [FormsModule, MatButtonModule, InspectoAlertComponent, InspectoOptionPickerComponent],
    template: `
        <div
            class="flex flex-col gap-2 rounded-md border p-2 text-xs"
            style="border-color: var(--gamma-border)"
            aria-label="Case link"
        >
            @if (error()) {
                <inspecto-alert variant="error" title="Case link">{{ error() }}</inspecto-alert>
            }
            @if (link(); as l) {
                @if (l.readOnly) {
                    <inspecto-alert variant="info" title="Read-only — shared with your Case">
                        You are a member of Case <code>{{ l.caseRef }}</code
                        >, so you can read this Investigation. Only its owner can change it.
                    </inspecto-alert>
                } @else if (l.caseRef) {
                    <div>
                        <strong>Linked to Case</strong> <code>{{ l.caseRef }}</code>
                        <button mat-button class="!ml-1" [disabled]="busy()" (click)="unlink()">Unlink</button>
                    </div>
                    <p class="text-secondary m-0">{{ l.reason }}</p>
                } @else if (opsEnabled()) {
                    <inspecto-option-picker
                        label="Link to a Case (optional)"
                        [options]="cases()"
                        [ngModel]="picked()"
                        (ngModelChange)="picked.set($event)"
                        placeholder="Select a Case"
                        help="The Case's owner and assignee will be able to read this Investigation; only you can change it."
                    ></inspecto-option-picker>
                    <button mat-stroked-button [disabled]="busy() || !picked()" (click)="linkTo()">Link to Case</button>
                } @else {
                    <p class="text-secondary m-0">
                        Sharing with a Case team needs Case management, which is not installed in this edition — the
                        Investigation stays visible to you only.
                    </p>
                }
            }
        </div>
    `,
})
export class LinkAnalysisInvestigationCaseComponent {
    readonly investigationId = input.required<string>();

    private readonly inv = inject(InvService);
    private readonly caseMgmt = inject(LA_CASES);
    readonly opsEnabled = this.caseMgmt.available;

    readonly link = signal<InvestigationCaseLink | null>(null);
    readonly cases = signal<PickerOption[]>([]);
    readonly picked = signal('');
    readonly busy = signal(false);
    readonly error = signal('');

    constructor() {
        effect(() => {
            const id = this.investigationId();
            untracked(() => this.load(id));
        });
    }

    private async load(id: string): Promise<void> {
        this.link.set(null);
        this.error.set('');
        this.picked.set('');
        try {
            const l = await firstValueFrom(this.inv.investigationCase(id));
            if (id !== this.investigationId()) return;
            this.link.set(l);
            await this.loadCases(l);
        } catch (err) {
            this.error.set(apiErrorMessage(err, 'Could not read the Case link.'));
        }
    }

    /** The picker's options — only for the owner, only while unlinked, only with Case management installed. */
    private async loadCases(l: InvestigationCaseLink): Promise<void> {
        if (l.readOnly || l.caseRef || !this.opsEnabled() || this.cases().length) return;
        const rows = await firstValueFrom(this.caseMgmt.list());
        this.cases.set(rows.map((o) => ({ value: o.id, label: o.id + ' · ' + o.title })));
    }

    async linkTo(): Promise<void> {
        const caseRef = this.picked();
        if (!caseRef) return;
        await this.act(this.inv.linkInvestigationCase(this.investigationId(), caseRef), 'Could not link the Case.');
    }

    async unlink(): Promise<void> {
        await this.act(this.inv.unlinkInvestigationCase(this.investigationId()), 'Could not unlink the Case.');
    }

    private async act(call: Observable<InvestigationCaseLink>, fallback: string): Promise<void> {
        this.busy.set(true);
        this.error.set('');
        try {
            const l = await firstValueFrom(call);
            this.link.set(l);
            this.picked.set('');
            await this.loadCases(l);
        } catch (err) {
            this.error.set(apiErrorMessage(err, fallback));
        } finally {
            this.busy.set(false);
        }
    }
}
