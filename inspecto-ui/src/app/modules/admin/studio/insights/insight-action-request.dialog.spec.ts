import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionsService } from 'app/inspecto/api/connections.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InsightActionRequestDialog } from './insight-action-request.dialog';

function create(connections: { id: string; connector: string }[]) {
    const close = vi.fn();
    TestBed.configureTestingModule({
        imports: [InsightActionRequestDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { title: 'Sales rose.' } },
            {
                provide: MatDialogRef,
                useValue: { close, disableClose: false, backdropClick: () => of(), keydownEvents: () => of() },
            },
            { provide: ConnectionsService, useValue: { list: () => of(connections) } },
            { provide: InspectoConfirmService, useValue: { confirm: () => of(true) } },
        ],
    });
    const fixture = TestBed.createComponent(InsightActionRequestDialog);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement, close };
}

describe('InsightActionRequestDialog', () => {
    it('offers only https Connections and returns the choice', async () => {
        const { fixture, cmp, el, close } = create([
            { id: 'ticketing', connector: 'https' },
            { id: 'files', connector: 'sftp' },
        ]);
        expect(cmp.options().map((o) => o.value)).toEqual(['ticketing']);
        cmp.form.patchValue({ connection: 'ticketing', reason: ' check ' });
        cmp.submit();
        expect(close).toHaveBeenCalledWith({ connection: 'ticketing', reason: 'check' });
        fixture.detectChanges();
        await expectNoA11yViolations(el);
    });

    it('refuses to submit without a Connection, and says so on screen', () => {
        const { fixture, cmp, el, close } = create([]);
        cmp.submit();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('Choose the Connection');
        expect(el.textContent).toContain('No https Connection exists');
    });
});
