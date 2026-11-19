import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penError } from 'app/inspecto/testing/pen-field';
import { ConnectionFieldsComponent } from './connection-fields.component';
import { POSTGRES_CONNECTION_ATTRS, connAttrsGroup } from './connection-types';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ConnectionFieldsComponent],
    template: `<inspecto-connection-fields [group]="form" [attrs]="attrs"></inspecto-connection-fields>`,
})
class HostComponent {
    readonly attrs = POSTGRES_CONNECTION_ATTRS;
    readonly form = connAttrsGroup(POSTGRES_CONNECTION_ATTRS);
}

function create() {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return { fixture, host: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
}

describe('ConnectionFieldsComponent (BASIC-ELT-PROFILE-1 U2)', () => {
    it('draws the postgres rows with no error before a submit (no violations)', async () => {
        const { el } = create();
        for (const label of ['Host', 'Port', 'Database', 'Username']) {
            expect(penByLabel(el, label), label).not.toBeNull();
            expect(penError(penByLabel(el, label)!)).toBe('');
        }
        expect(penByLabel(el, 'Port')!.textContent).toContain('5432');
        expect(el.querySelector('mat-form-field mat-label')!.textContent).toBe('Password');
        expect(el.querySelector('mat-error')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('shows each row’s error once the HOST marks the form touched (OnPush rows re-draw)', async () => {
        const { fixture, host, el } = create();
        host.form.markAllAsTouched();
        fixture.detectChanges();
        expect(penError(penByLabel(el, 'Host')!)).toBe('Host is required.');
        expect(penError(penByLabel(el, 'Username')!)).toBe('Username is required.');
        expect(el.querySelector('mat-error')!.textContent).toContain('Password is required.');
        await expectNoA11yViolations(el);
    });

    it('names an out-of-range port and a password that is not an INSPECTO_PG_ reference', () => {
        const { fixture, host, el } = create();
        penEdit(fixture, penByLabel(el, 'Port')!, '0');
        expect(penError(penByLabel(el, 'Port')!)).toBe('Port is 1 to 65535.');
        host.form.patchValue({ password: '${ENV:HOME}' });
        host.form.markAllAsTouched();
        fixture.detectChanges();
        expect(el.querySelector('mat-error')!.textContent).toContain('Write it as ${ENV:INSPECTO_PG_…}');
        host.form.patchValue({ password: '${ENV:INSPECTO_PG_SALES}' });
        fixture.detectChanges();
        expect(el.querySelector('mat-error')).toBeNull();
    });
});
