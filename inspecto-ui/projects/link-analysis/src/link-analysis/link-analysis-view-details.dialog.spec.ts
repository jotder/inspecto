import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penEdit, penError, penText } from '@inspecto/core/testing/pen-field';
import { LinkAnalysisViewDetailsDialog, ViewDetailsDialogData } from './link-analysis-view-details.dialog';
import { LinkAnalysisView } from './link-analysis.service';

const VIEW = {
    id: 'mule_layering_ring',
    name: 'mule_layering_ring',
    description: 'DEMO 2 - a money-mule layering ring',
    sourceId: 'dataset',
    query: { datasetId: 'mule_transfers_dataset' },
    layout: 'force',
    profile: 'finance',
    investigations: [{ id: 'inv-1' }],
} as unknown as LinkAnalysisView;

function create(data: Partial<ViewDetailsDialogData> = {}) {
    const close = vi.fn();
    const history = vi.fn();
    TestBed.configureTestingModule({
        imports: [LinkAnalysisViewDetailsDialog],
        providers: [
            provideNoopAnimations(),
            {
                provide: MatDialogRef,
                useValue: {
                    close,
                    disableClose: false,
                    backdropClick: () => ({ subscribe: () => ({}) }),
                    keydownEvents: () => ({ subscribe: () => ({}) }),
                },
            },
            { provide: InspectoConfirmService, useValue: { confirm: vi.fn(async () => true) } },
            { provide: MAT_DIALOG_DATA, useValue: { view: VIEW, editBlock: '', history, ...data } },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisViewDetailsDialog);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const q = (id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement;
    return { fixture, el, q, close, history };
}

describe('LinkAnalysisViewDetailsDialog (LA-VIEW-DETAILS-1)', () => {
    it('shows the view and its facts, and Save closes with the trimmed new Name and Description; passes axe', async () => {
        const { fixture, el, q, close } = create();
        expect(penText(q('la-view-details-name'))).toBe('mule_layering_ring');
        expect(penText(q('la-view-details-description'))).toContain('money-mule layering ring');
        const facts = q('la-view-details-facts').textContent ?? '';
        expect(facts).toContain('Force');
        expect(facts).toContain('finance');
        expect(facts).toContain('mule_layering_ring');
        await expectNoA11yViolations(el);

        penEdit(fixture, q('la-view-details-name'), '  Mule layering ring ');
        penEdit(fixture, q('la-view-details-description'), ' Twelve smurfs feed one hub. ');
        (q('la-view-details-save') as HTMLButtonElement).click();
        expect(close).toHaveBeenCalledWith({ name: 'Mule layering ring', description: 'Twelve smurfs feed one hub.' });
    });

    it('a blank Name refuses Save and says why on the field', () => {
        const { fixture, q, close } = create();
        penEdit(fixture, q('la-view-details-name'), '   ');
        (q('la-view-details-save') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(penError(q('la-view-details-name'))).toBe('A name is required.');
    });

    it('Save with nothing changed closes without a result, so nothing is written', () => {
        const { q, close } = create();
        (q('la-view-details-save') as HTMLButtonElement).click();
        expect(close).toHaveBeenCalledWith();
    });

    it('a view the caller may not change is shown read-only, with the reason, and no Save', async () => {
        const { el, q } = create({ editBlock: 'Shared with you to view only.' });
        expect(q('la-view-details-locked').textContent).toContain('view only');
        expect(q('la-view-details-name-text').textContent).toContain('mule_layering_ring');
        expect(q('la-view-details-name')).toBeNull();
        expect(q('la-view-details-save')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('Version history opens the view history', () => {
        const { q, history } = create();
        q('la-view-details-history').click();
        expect(history).toHaveBeenCalledWith(VIEW);
    });
});
