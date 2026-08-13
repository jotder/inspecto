import { Component, ChangeDetectionStrategy } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DefinitionDrawerComponent } from './definition-drawer.component';

/** Host wrapping the drawer the way the pipeline editor does — projected content + bound outputs. */
@Component({
    standalone: true,
    imports: [DefinitionDrawerComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `
        <inspecto-definition-drawer
            [title]="title"
            [kindLabel]="kind"
            [dirty]="dirty"
            (apply)="applied = applied + 1"
            (discard)="discarded = discarded + 1"
            (closed)="closedCount = closedCount + 1"
        >
            <p>projected definition content</p>
        </inspecto-definition-drawer>
    `,
})
class HostComponent {
    title = 'sftp inbox';
    kind = 'Collector';
    dirty = false;
    applied = 0;
    discarded = 0;
    closedCount = 0;
}

async function create(confirmResult = true) {
    const confirm = { confirmDestructive: vi.fn(async () => confirmResult) };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideNoopAnimations(), { provide: InspectoConfirmService, useValue: confirm }],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return { fixture, confirm };
}

function drawer(fixture: { nativeElement: HTMLElement }): HTMLElement {
    return fixture.nativeElement.querySelector('[role="dialog"]') as HTMLElement;
}

function button(fixture: { nativeElement: HTMLElement }, label: string): HTMLButtonElement {
    const all = Array.from(fixture.nativeElement.querySelectorAll('button'));
    return all.find((b) => b.textContent?.trim() === label) as HTMLButtonElement;
}

describe('DefinitionDrawerComponent', () => {
    it('renders title, kind and the projected content as a labelled non-modal dialog', async () => {
        const { fixture } = await create();
        const el = drawer(fixture);
        expect(el.getAttribute('aria-label')).toBe('Define Collector · sftp inbox');
        // Non-modal by design: a dock the canvas stays interactive next to, never an overlay.
        expect(el.getAttribute('aria-modal')).toBeNull();
        expect(el.textContent).toContain('sftp inbox');
        expect(el.textContent).toContain('Collector');
        expect(el.textContent).toContain('projected definition content');
    });

    it('shows the unapplied badge and enables the footer only when dirty', async () => {
        const { fixture } = await create();
        expect(fixture.nativeElement.textContent).not.toContain('unapplied');
        expect(button(fixture, 'Apply').disabled).toBe(true);
        expect(button(fixture, 'Discard').disabled).toBe(true);

        fixture.componentInstance.dirty = true;
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('unapplied');
        expect(button(fixture, 'Apply').disabled).toBe(false);
        expect(button(fixture, 'Discard').disabled).toBe(false);
    });

    it('emits apply and discard from the footer', async () => {
        const { fixture } = await create();
        fixture.componentInstance.dirty = true;
        fixture.detectChanges();
        button(fixture, 'Apply').click();
        button(fixture, 'Discard').click();
        expect(fixture.componentInstance.applied).toBe(1);
        expect(fixture.componentInstance.discarded).toBe(1);
    });

    it('closes without asking when clean', async () => {
        const { fixture, confirm } = await create();
        (fixture.nativeElement.querySelector('button[aria-label^="Close"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(confirm.confirmDestructive).not.toHaveBeenCalled();
        expect(fixture.componentInstance.closedCount).toBe(1);
    });

    it('guards a dirty close behind the destructive confirm — refusal keeps it open', async () => {
        const { fixture, confirm } = await create(false);
        fixture.componentInstance.dirty = true;
        fixture.detectChanges();
        (fixture.nativeElement.querySelector('button[aria-label^="Close"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(confirm.confirmDestructive).toHaveBeenCalled();
        expect(fixture.componentInstance.closedCount).toBe(0);
    });

    it('closes a dirty drawer once the discard is confirmed', async () => {
        const { fixture, confirm } = await create(true);
        fixture.componentInstance.dirty = true;
        fixture.detectChanges();
        (fixture.nativeElement.querySelector('button[aria-label^="Close"]') as HTMLButtonElement).click();
        await fixture.whenStable();
        expect(confirm.confirmDestructive).toHaveBeenCalled();
        expect(fixture.componentInstance.closedCount).toBe(1);
    });

    it('has no a11y violations', async () => {
        const { fixture } = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
