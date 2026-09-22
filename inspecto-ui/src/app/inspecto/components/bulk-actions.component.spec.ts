import { describe, expect, it } from 'vitest';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { BulkAction, InspectoBulkActionsComponent } from './bulk-actions.component';

@Component({
    standalone: true,
    imports: [InspectoBulkActionsComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-bulk-actions
        [count]="count"
        [actions]="actions"
        (run)="ran.push($event)"
        (clear)="cleared = true"
    />`,
})
class HostComponent {
    count = 0;
    actions: BulkAction[] = [
        { id: 'accept', label: 'Accept', icon: 'heroicons_outline:check' },
        { id: 'escalate', label: 'Escalate', disabled: true },
        { id: 'archive', label: 'Archive', destructive: true },
    ];
    ran: string[] = [];
    cleared = false;
}

describe('InspectoBulkActionsComponent', () => {
    function create(inputs: Partial<HostComponent> = {}) {
        TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(HostComponent);
        Object.assign(fixture.componentInstance, inputs);
        fixture.detectChanges();
        return fixture;
    }

    it('renders NOTHING when no row is selected — no row of disabled buttons', () => {
        const el: HTMLElement = create({ count: 0 }).nativeElement;
        expect(el.querySelector('button')).toBeNull();
        expect(el.textContent?.trim()).toBe('');
    });

    it('renders the count chip and one Actions trigger once rows are selected', () => {
        const el: HTMLElement = create({ count: 3 }).nativeElement;
        expect(el.textContent).toContain('3 selected');
        const triggers = Array.from(el.querySelectorAll('button')).filter((b) => b.textContent?.includes('Actions'));
        expect(triggers.length).toBe(1);
    });

    it('lists ordinary actions, then a divider, then destructive ones, honouring disabled', () => {
        const fixture = create({ count: 1 });
        const trigger = Array.from(
            fixture.nativeElement.querySelectorAll('button') as NodeListOf<HTMLButtonElement>,
        ).find((b) => b.textContent?.includes('Actions'))!;
        trigger.click();
        fixture.detectChanges();
        const items = Array.from(document.querySelectorAll('.mat-mdc-menu-item')) as HTMLButtonElement[];
        expect(items.map((i) => i.textContent?.trim())).toEqual(['Accept', 'Escalate', 'Archive']);
        expect(items[1].disabled).toBe(true);
        expect(items[2].className).toContain('text-warn');
        expect(document.querySelector('.mat-mdc-menu-panel mat-divider')).not.toBeNull();
        items[0].click();
        expect(fixture.componentInstance.ran).toEqual(['accept']);
    });

    it('emits clear from the chip ✕', () => {
        const fixture = create({ count: 2 });
        const remove = fixture.nativeElement.querySelector('inspecto-chip button') as HTMLButtonElement;
        remove.click();
        expect(fixture.componentInstance.cleared).toBe(true);
    });

    it('has no axe violations with a selection', async () => {
        const fixture = create({ count: 2 });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
