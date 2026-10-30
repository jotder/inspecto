import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penEdit, penError, penText } from '@inspecto/core/testing/pen-field';
import { LinkAnalysisSaveInvestigationDialog, SaveInvestigationDialogData } from './link-analysis-save-investigation.dialog';

function create(data: Partial<SaveInvestigationDialogData> = {}) {
    const close = vi.fn();
    const keep = vi.fn();
    TestBed.configureTestingModule({
        imports: [LinkAnalysisSaveInvestigationDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            {
                provide: MAT_DIALOG_DATA,
                useValue: { name: 'Account', purpose: 'Review of an Account and its links', steps: 3, keep, ...data },
            },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisSaveInvestigationDialog);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const q = (id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement;
    return { fixture, el, q, close, keep };
}

describe('LinkAnalysisSaveInvestigationDialog (LA-UNSAVED-INVESTIGATION-1)', () => {
    it('opens with the defaults, keeps every edit, and Save closes with the trimmed Name and Purpose; passes axe', async () => {
        const { fixture, el, q, close, keep } = create();
        expect(penText(q('la-save-name'))).toBe('Account');
        expect(penText(q('la-save-purpose'))).toBe('Review of an Account and its links');
        expect(q('la-save-what').textContent).toContain('records the 3 steps taken so far, in order');
        penEdit(fixture, q('la-save-name'), '  Operation Larch ');
        expect(keep.mock.calls.at(-1)![0]).toContain('Operation Larch');
        expect(keep.mock.calls.at(-1)![1]).toBe('Review of an Account and its links');
        await expectNoA11yViolations(el);
        (q('la-save-go') as HTMLButtonElement).click();
        expect(close).toHaveBeenCalledWith({ name: 'Operation Larch', purpose: 'Review of an Account and its links' });
    });

    it('a blank Purpose refuses Save and says why on the field', () => {
        const { fixture, q, close } = create({ purpose: '' });
        (q('la-save-go') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(penError(q('la-save-purpose'))).toBe('A purpose is required.');
    });

    it('opens with the last edits of a cancelled Save when the screen hands them back', () => {
        const { q } = create({ name: 'Operation Larch', purpose: 'Warrant 7' });
        expect(penText(q('la-save-name'))).toBe('Operation Larch');
        expect(penText(q('la-save-purpose'))).toBe('Warrant 7');
    });
});
