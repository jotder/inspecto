import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { InvService } from '@inspecto/link-analysis/api/inv.service';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import {
    INVESTIGATION_PANEL_LABELS,
    InvestigationPanel,
    InvestigationPanelDialog,
    openInvestigationPanel,
} from './investigation-panel.dialog';

function mount(panel: InvestigationPanel) {
    const close = vi.fn();
    const inv = {
        investigationMembers: vi.fn(() =>
            of({ investigationId: 'inv-1', owner: 'alice', members: [], history: [], you: 'lead' }),
        ),
    };
    TestBed.configureTestingModule({
        imports: [InvestigationPanelDialog],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canManageIncidents: signal(true) } },
            { provide: MAT_DIALOG_DATA, useValue: { panel, investigationId: 'inv-1', title: 'Burners' } },
            { provide: MatDialogRef, useValue: { close } },
        ],
    });
    const fixture = TestBed.createComponent(InvestigationPanelDialog);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, close, inv };
}

describe('InvestigationPanelDialog (LA-FULL-CYCLE-TEST-1 phase 5, finding 22)', () => {
    it('the menus name the two panels the same way: Build Dossier… and Members…', () => {
        expect(INVESTIGATION_PANEL_LABELS).toEqual({ dossier: 'Build Dossier…', members: 'Members…' });
    });

    it('Build Dossier… shows the Dossier of the named Investigation, headed with its name', async () => {
        const { el, close } = mount('dossier');
        expect(el.querySelector('h2')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Dossier · Burners');
        expect(
            el.querySelector('[data-testid="investigation-panel-dossier"] inspecto-link-analysis-dossier'),
        ).not.toBeNull();
        expect(el.querySelector('inspecto-link-analysis-members')).toBeNull();
        await expectNoA11yViolations(el);
        (
            Array.from(el.querySelectorAll('button')).find(
                (b) => b.textContent?.trim() === 'Close',
            ) as HTMLButtonElement
        ).click();
        expect(close).toHaveBeenCalled();
    });

    it('Members… shows the Members panel, which reads that Investigation', async () => {
        const { el, inv, fixture } = mount('members');
        await fixture.whenStable();
        expect(el.querySelector('h2')?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Members · Burners');
        expect(
            el.querySelector('[data-testid="investigation-panel-members"] inspecto-link-analysis-members'),
        ).not.toBeNull();
        expect(inv.investigationMembers).toHaveBeenCalledWith('inv-1');
    });

    it('openInvestigationPanel falls back to the id when the Investigation has no name', () => {
        const dialog = { open: vi.fn(() => ({}) as MatDialogRef<InvestigationPanelDialog>) } as unknown as MatDialog;
        openInvestigationPanel(dialog, 'members', 'inv-9', '');
        expect(dialog.open).toHaveBeenCalledWith(
            InvestigationPanelDialog,
            expect.objectContaining({ data: { panel: 'members', investigationId: 'inv-9', title: 'inv-9' } }),
        );
    });
});
