import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { penByLabel, penEdit, penError, pickerSet } from 'app/inspecto/testing/pen-field';
import { ReconToolbarComponent } from './recon-toolbar.component';

function create(inputs: Record<string, unknown> = {}) {
    TestBed.configureTestingModule({ imports: [ReconToolbarComponent], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(ReconToolbarComponent);
    fixture.componentRef.setInput('availableDays', ['2026-11-01', '2026-11-02']);
    fixture.componentRef.setInput('day', '2026-11-02');
    for (const [k, v] of Object.entries(inputs)) fixture.componentRef.setInput(k, v);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    const days: string[] = [];
    const filters: string[] = [];
    const samples: (number | null)[] = [];
    c.dayChange.subscribe((d) => days.push(d));
    c.filterChange.subscribe((f) => filters.push(f));
    c.sampleChange.subscribe((s) => samples.push(s));
    return { fixture, c, days, filters, samples };
}

describe('ReconToolbarComponent', () => {
    it('re-requests only for a different day or filter: a re-pick of the shown one emits nothing', () => {
        const { fixture, days, filters } = create();
        const [dayPicker, filterPicker] = Array.from(
            (fixture.nativeElement as HTMLElement).querySelectorAll('inspecto-option-picker'),
        );
        dayPicker.setAttribute('data-test', 'day');
        filterPicker.setAttribute('data-test', 'filter');
        pickerSet(fixture, '[data-test=day]', '2026-11-02');
        pickerSet(fixture, '[data-test=filter]', 'all');
        expect(days).toEqual([]);
        expect(filters).toEqual([]);
        pickerSet(fixture, '[data-test=day]', '2026-11-01');
        expect(days).toEqual(['2026-11-01']);
    });

    it('applies the key sample when its row commits', () => {
        const { fixture, samples } = create();
        penEdit(fixture, penByLabel(fixture.nativeElement, 'Sample keys')!, '25');
        expect(samples).toEqual([25]);
    });

    it('names an out-of-range band under its row only once the bands were saved', () => {
        const { fixture, c } = create({ canEditBands: true });
        c.editing.set(true);
        fixture.detectChanges();
        const ok = penByLabel(fixture.nativeElement, 'OK below')!;
        c.bandsForm.controls.okBelow.setValue(140);
        fixture.detectChanges();
        expect(penError(ok)).toBe('');
        (fixture.nativeElement as HTMLElement).querySelector('form')!.dispatchEvent(new Event('submit'));
        fixture.detectChanges();
        expect(penError(ok)).toBe('A percentage from 0 to 100.');
    });
});
