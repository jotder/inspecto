import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { signal } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it } from 'vitest';
import { AccessService, LensService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { AccessPageComponent } from './access.page';

describe('AccessPageComponent (the roles editor on its own page)', () => {
    let last: ComponentFixture<AccessPageComponent> | undefined;
    async function render(
        canConfigure: boolean,
        extra: { name: string; capabilities: string[]; source: string }[] = [],
    ) {
        TestBed.configureTestingModule({
            providers: [
                provideNoopAnimations(),
                { provide: ToastrService, useValue: { success: () => undefined, error: () => undefined } },
                { provide: MatDialog, useValue: { open: () => undefined } },
                { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(false) } },
                { provide: LensService, useValue: { canConfigureAccess: signal(canConfigure) } },
                {
                    provide: AccessService,
                    useValue: {
                        roles: () =>
                            of({
                                roles: [
                                    { name: 'fraud-analyst', capabilities: ['canWorkIncidents'], source: 'authored' },
                                    ...extra,
                                ],
                                products: { 'link-analysis': ['canWorkIncidents', 'canRevealLinkEntities'] },
                            }),
                        profiles: () => of([]),
                        saveRoles: () => of({ roles: [] }),
                    },
                },
            ],
        });
        const fixture = TestBed.createComponent(AccessPageComponent);
        last = fixture;
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        return fixture.nativeElement as HTMLElement;
    }

    it('shows a plain label for pipeline-developer, never the Pipeline role id', async () => {
        const el = await render(true, [
            { name: 'pipeline-developer', capabilities: ['canWorkIncidents'], source: 'seed' },
        ]);
        expect(el.textContent).toContain('Data builder');
        expect(el.textContent).not.toContain('pipeline-developer');
    });

    it('shows only Link Analysis capabilities, in its words, until Show all', async () => {
        const el = await render(true, [
            { name: 'admin', capabilities: ['canRevealLinkEntities', 'canOnboardConnections'], source: 'seed' },
        ]);
        expect(el.textContent).toContain('See unmasked ids');
        expect(el.textContent).toContain('Acknowledge and resolve Alerts');
        expect(el.textContent).not.toContain('canOnboardConnections');
        expect(el.textContent).toContain('+1 used elsewhere');

        (el.querySelector('mat-slide-toggle button') as HTMLButtonElement).click();
        last!.detectChanges();
        expect(el.textContent).toContain('canOnboardConnections'); // an unlabelled id shows as itself
        expect(el.textContent).not.toContain('used elsewhere');
    });

    it('names its region, owns the one h1 and lists the roles', async () => {
        const el = await render(true);
        expect(el.querySelector('section')?.getAttribute('aria-label')).toBe('Access');
        expect(el.querySelectorAll('h1').length).toBe(1);
        expect(el.textContent).toContain('fraud-analyst');
        await expectNoA11yViolations(el);
    });

    it('has no axe violations without the capability to configure access (read-only)', async () => {
        await expectNoA11yViolations(await render(false));
    });
});
