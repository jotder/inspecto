import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { FormControl, ReactiveFormsModule } from '@angular/forms';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { SessionService } from 'app/inspecto/api';
import { ObjectsService } from 'app/inspecto/api/objects.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { LinkAnalysisCaseFieldComponent } from './link-analysis-case-field.component';
import { LinkAnalysisSnapshotsService } from './link-analysis-snapshots.service';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [ReactiveFormsModule, LinkAnalysisCaseFieldComponent],
    template: `<inspecto-link-analysis-case-field
        [formControl]="control"
        [required]="required()"
    ></inspecto-link-analysis-case-field>`,
})
class Host {
    readonly control = new FormControl('', { nonNullable: true });
    readonly required = signal(false);
}

function create(opsEnabled: boolean, list: () => unknown) {
    const store = new LinkAnalysisSnapshotsService();
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: LinkAnalysisSnapshotsService, useValue: store },
            { provide: SessionService, useValue: { opsEnabled: () => opsEnabled } },
            { provide: ObjectsService, useValue: { list } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return { fixture, store, el: fixture.nativeElement as HTMLElement };
}

const REAL = () => of([{ id: 'CASE-9', title: 'Real case' }]);
const DOWN = () => throwError(() => new Error('down'));

describe('LinkAnalysisCaseFieldComponent — three states, kept distinguishable', () => {
    it('ops present: offers the real Cases from the objects store', () => {
        const { fixture, el } = create(true, REAL);
        const field = fixture.debugElement.children[0].componentInstance as LinkAnalysisCaseFieldComponent;
        expect(field.cases()).toEqual([{ id: 'CASE-9', title: 'Real case' }]);
        expect(field.caseOptions()).toEqual([{ value: 'CASE-9', label: 'CASE-9 · Real case' }]);
        expect(el.textContent).toContain('Open Cases from the objects store');
        expect(el.querySelector('inspecto-option-picker')).not.toBeNull();
    });

    it('ops absent: offers the placeholders, and SAYS they are placeholders', () => {
        const { fixture, el, store } = create(false, REAL);
        const field = fixture.debugElement.children[0].componentInstance as LinkAnalysisCaseFieldComponent;
        expect(field.cases().map((c) => c.id)).toEqual(store.mockCases.map((c) => c.id));
        expect(field.loadError()).toBe('');
        expect(el.textContent).toContain('ops module is not installed');
    });

    it('lookup failed: offers NOTHING, and never falls back to the placeholders', async () => {
        const { fixture, el, store } = create(true, DOWN);
        const field = fixture.debugElement.children[0].componentInstance as LinkAnalysisCaseFieldComponent;

        expect(field.cases()).toEqual([]);
        expect(field.caseOptions()).toEqual([]);
        expect(field.loadError()).not.toBe('');
        // The distinction this component exists for: a down/unauthorised ops service must NOT render
        // like an edition that simply has no ops module.
        for (const mock of store.mockCases) expect(el.textContent).not.toContain(mock.id);
        expect(el.textContent).not.toContain('ops module is not installed');
        expect(el.querySelector('inspecto-option-picker')).toBeNull();
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('no Case can be offered');
        await expectNoA11yViolations(el);
    });

    it('writes the pick back to the bound control, and takes a host patch without echoing it', () => {
        const { fixture } = create(true, REAL);
        const host = fixture.componentInstance;
        const field = fixture.debugElement.children[0].componentInstance as LinkAnalysisCaseFieldComponent;
        const seen = vi.fn();
        host.control.valueChanges.subscribe(seen);

        // a user pick propagates out
        field.control.setValue('CASE-9');
        expect(host.control.value).toBe('CASE-9');

        // A host patch (the `?case=` deep link) lands on the picker but is NOT echoed back out as a
        // user change. Driving `writeValue` directly is the only way to see the echo on its own: setting
        // the host's control would emit on the host's own valueChanges whether the field echoed or not.
        seen.mockClear();
        field.writeValue('CASE-7');
        expect(field.control.value).toBe('CASE-7');
        expect(seen).not.toHaveBeenCalled();
    });
});
