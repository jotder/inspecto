import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { describe, expect, it, vi } from 'vitest';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { ADD_DATA_ROUTE, AddDataDropComponent, AddDataHandoff } from './add-data-drop.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [AddDataDropComponent],
    template: `<inspecto-add-data-drop class="block" [enabled]="enabled"><p>Pane content</p></inspecto-add-data-drop>`,
})
class HostComponent {
    enabled = true;
}

/** A drag event carrying `files` (jsdom has no DataTransfer). */
function drag(type: string, files: File[] = [], types = files.length ? ['Files'] : ['text/plain']): Event {
    const e = new Event(type, { bubbles: true, cancelable: true });
    Object.defineProperty(e, 'dataTransfer', { value: { types, files, dropEffect: 'none' } });
    return e;
}

function create(o: { lockReason?: string | null; enabled?: boolean } = {}) {
    const load = vi.fn();
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideRouter([]),
            { provide: AddDataAccess, useValue: { lockReason: signal(o.lockReason ?? null), load } },
        ],
    });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.enabled = o.enabled ?? true;
    fixture.detectChanges();
    const zone = fixture.nativeElement.querySelector('inspecto-add-data-drop') as HTMLElement;
    return { fixture, zone, navigate, load, handoff: TestBed.inject(AddDataHandoff) };
}

const FILE = new File(['a,b\n1,2\n'], 'orders.csv', { type: 'text/csv' });

describe('AddDataDropComponent (ONBOARDING-FIRST-RUN-1 B1 drop zones)', () => {
    it('reads who may add data, shows the pane, and says what a drop does while a file is dragged over (no violations)', async () => {
        const { fixture, zone, load } = create();
        expect(load).toHaveBeenCalled();
        expect(zone.textContent).toContain('Pane content');
        const over = drag('dragover', [FILE]);
        zone.dispatchEvent(over);
        fixture.detectChanges();
        expect(over.defaultPrevented).toBe(true);
        const overlay = zone.querySelector('[data-testid="add-data-drop-overlay"]')!;
        expect(overlay.textContent).toContain('Drop the file to add it as data');
        await expectNoA11yViolations(fixture.nativeElement);
        // moving onto the pane's own content is not leaving the pane: the overlay stays (no flicker)
        const intoChild = drag('dragleave');
        Object.defineProperty(intoChild, 'relatedTarget', { value: zone.querySelector('p') });
        zone.dispatchEvent(intoChild);
        fixture.detectChanges();
        expect(zone.querySelector('[data-testid="add-data-drop-overlay"]')).not.toBeNull();
        zone.dispatchEvent(drag('dragleave'));
        fixture.detectChanges();
        expect(zone.querySelector('[data-testid="add-data-drop-overlay"]')).toBeNull();
    });

    it('a dropped file opens Add data with it', () => {
        const { zone, navigate, handoff } = create();
        const drop = drag('drop', [FILE]);
        zone.dispatchEvent(drop);
        expect(drop.defaultPrevented).toBe(true);
        expect(navigate).toHaveBeenCalledWith(ADD_DATA_ROUTE);
        expect(handoff.take()).toBe(FILE);
        expect(handoff.take()).toBeNull();
    });

    it('ignores a drag that carries no file (text or a link from the page)', () => {
        const { fixture, zone } = create();
        const over = drag('dragover');
        zone.dispatchEvent(over);
        fixture.detectChanges();
        expect(over.defaultPrevented).toBe(false);
        expect(zone.querySelector('[data-testid="add-data-drop-overlay"]')).toBeNull();
    });

    it('is not armed when Add data is locked for this person here (no right, or data.self_import off)', () => {
        const { fixture, zone, navigate, handoff } = create({ lockReason: 'Adding data is off in this Space.' });
        const over = drag('dragover', [FILE]);
        zone.dispatchEvent(over);
        fixture.detectChanges();
        expect(over.defaultPrevented).toBe(false);
        expect(zone.querySelector('[data-testid="add-data-drop-overlay"]')).toBeNull();
        zone.dispatchEvent(drag('drop', [FILE]));
        expect(navigate).not.toHaveBeenCalled();
        expect(handoff.take()).toBeNull();
    });

    it('is not armed when the host says so (a Datasets pane that is not empty)', () => {
        const { zone, navigate } = create({ enabled: false });
        zone.dispatchEvent(drag('drop', [FILE]));
        expect(navigate).not.toHaveBeenCalled();
    });
});
