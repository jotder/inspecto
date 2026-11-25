import { ChangeDetectionStrategy, Component, viewChild } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penError, pickerSet } from 'app/inspecto/testing/pen-field';
import { ChartMarker } from 'app/inspecto/viz/chart-markers';
import { ChartMarkersEditorComponent } from './chart-markers-editor.component';

@Component({
    selector: 'spec-markers-host',
    standalone: true,
    imports: [ChartMarkersEditorComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<app-chart-markers-editor [initial]="initial" [kpiBound]="kpiBound" />`,
})
class HostComponent {
    initial: ChartMarker[] = [];
    kpiBound = false;
    readonly editor = viewChild.required(ChartMarkersEditorComponent);
}

function create(initial: ChartMarker[] = [], kpiBound = false) {
    TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.componentInstance.initial = initial;
    fixture.componentInstance.kpiBound = kpiBound;
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    return { fixture, el, editor: fixture.componentInstance.editor() };
}

const rows = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLElement>('[data-testid="marker-row"]'));

describe('ChartMarkersEditorComponent', () => {
    it('round-trips stored markers, keeping only the keys each kind reads', () => {
        const stored: ChartMarker[] = [
            { kind: 'target', value: 95, label: 'SLA' },
            { kind: 'percentile', p: 75 },
            { kind: 'band', from: 90, tone: 'warning' },
            { kind: 'average' },
        ];
        const { el, editor } = create(stored);
        expect(rows(el)).toHaveLength(4);
        expect(editor.markers()).toEqual(stored);
        expect(editor.isDirty()).toBe(false);
    });

    it('adds a target line (the KPI or Widget target) and removes a row', () => {
        const { fixture, el, editor } = create([{ kind: 'average' }]);
        el.querySelector<HTMLButtonElement>('[data-testid="add-marker"]')!.click();
        fixture.detectChanges();
        expect(editor.markers()).toEqual([{ kind: 'average' }, { kind: 'target' }]);
        el.querySelector<HTMLButtonElement>('[aria-label="Remove marker 1"]')!.click();
        fixture.detectChanges();
        expect(editor.markers()).toEqual([{ kind: 'target' }]);
        expect(editor.isDirty()).toBe(true);
    });

    it('a constant line without a value refuses, its error line on screen', () => {
        const { fixture, el, editor } = create();
        el.querySelector<HTMLButtonElement>('[data-testid="add-marker"]')!.click();
        fixture.detectChanges();
        pickerSet(fixture, 'inspecto-option-picker', 'constant');
        expect(editor.markers()).toBeNull();
        fixture.detectChanges();
        const value = penByLabel(rows(el)[0], 'Value')!;
        expect(penError(value)).toBe('Enter the line’s value.');
        penEdit(fixture, value, '42');
        expect(editor.markers()).toEqual([{ kind: 'constant', value: 42 }]);
        fixture.detectChanges();
        expect(penError(value)).toBe('');
    });

    it('a percentile outside 1–99 refuses', () => {
        const { fixture, el, editor } = create([{ kind: 'percentile', p: 100 }]);
        expect(editor.markers()).toBeNull();
        fixture.detectChanges();
        expect(penError(penByLabel(rows(el)[0], 'Percentile')!)).toBe('Enter a whole percentile from 1 to 99.');
    });

    it('a fractional percentile refuses — its words would name another one', () => {
        const { editor } = create([{ kind: 'percentile', p: 12.5 }]);
        expect(editor.markers()).toBeNull();
    });

    it('says a KPI status bands marker needs a KPI while none is chosen', () => {
        const { el } = create([{ kind: 'rag' }]);
        expect(el.querySelector('[data-testid="marker-rag-unbound"]')?.getAttribute('role')).toBe('alert');
    });

    it('says nothing once a KPI is chosen', () => {
        const { el } = create([{ kind: 'rag' }], true);
        expect(el.querySelector('[data-testid="marker-rag-unbound"]')).toBeNull();
    });

    it('renders with no a11y violations', async () => {
        const { el } = create([
            { kind: 'band', from: 1, to: 2, tone: 'success' },
            { kind: 'constant', value: 3 },
        ]);
        await expectNoA11yViolations(el);
    });
});
