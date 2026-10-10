import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from 'app/inspecto/api/components.service';
import { LensService } from 'app/inspecto/api/lens.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { RunbooksComponent } from './runbooks.component';

const STORED = {
    type: 'runbook',
    name: 'irsf',
    ref: 'runbook/irsf',
    content: {
        name: 'irsf',
        title: 'IRSF runbook',
        owner: 'alice',
        'x-note': 'kept',
        steps: [{ text: 'Open the evidence', link: { kind: 'dataset', id: 'fraud_irsf' } }],
    },
};

function mount() {
    const api = {
        list: vi.fn(() => of([STORED])),
        create: vi.fn(() => of(STORED)),
        update: vi.fn(() => of(STORED)),
        remove: vi.fn(() => of({})),
    };
    TestBed.configureTestingModule({
        imports: [RunbooksComponent],
        providers: [
            provideRouter([]),
            provideNoopAnimations(),
            { provide: ComponentsService, useValue: api },
            { provide: ToastrService, useValue: { success: vi.fn(), error: vi.fn() } },
            { provide: InspectoConfirmService, useValue: { confirmDestructive: vi.fn(async () => true) } },
            { provide: LensService, useValue: { canAuthorWorkbench: signal(true) } },
        ],
    });
    const fixture = TestBed.createComponent(RunbooksComponent);
    fixture.detectChanges();
    return { fixture, api, c: fixture.componentInstance };
}

describe('RunbooksComponent', () => {
    it('lists Runbooks, refuses an invalid new one, and creates a valid one with its ordered steps', async () => {
        const { fixture, api, c } = mount();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('nav li')?.textContent).toContain('IRSF runbook');

        c.startNew();
        fixture.detectChanges();
        c.save();
        fixture.detectChanges();
        expect(api.create).not.toHaveBeenCalled();
        expect(el.querySelectorAll('mat-error').length).toBeGreaterThan(0);

        c.form.patchValue({ id: 'wangiri', title: 'Wangiri runbook', tags: 'fraud, telco' });
        c.steps.at(0).patchValue({ text: 'Block the number', linkKind: 'case', linkId: 'c-1' });
        c.addStep('Warn the callers');
        c.save();
        expect(api.create).toHaveBeenCalledWith('runbook', {
            id: 'wangiri',
            title: 'Wangiri runbook',
            steps: [{ text: 'Block the number', link: { kind: 'case', id: 'c-1' } }, { text: 'Warn the callers' }],
            tags: ['fraud', 'telco'],
        });
        fixture.detectChanges();
        await expectNoA11yViolations(el);
    });

    it('an edit keeps the keys it does not model and saves through update', () => {
        const { fixture, api, c } = mount();
        c.edit(STORED as never);
        fixture.detectChanges();
        expect(c.steps.length).toBe(1);
        c.form.patchValue({ title: 'IRSF v2' });
        c.save();
        expect(api.update).toHaveBeenCalledWith(
            'runbook',
            'irsf',
            expect.objectContaining({ title: 'IRSF v2', owner: 'alice', 'x-note': 'kept' }),
        );
    });
});
