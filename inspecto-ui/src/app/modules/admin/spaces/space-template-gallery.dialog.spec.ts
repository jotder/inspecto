import { TestBed } from '@angular/core/testing';
import { MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CreateSpaceRequest, Space, SpacesService, SpaceTemplateInfo } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { SpaceTemplateGalleryDialog } from './space-template-gallery.dialog';

const TEMPLATES: SpaceTemplateInfo[] = [
    {
        id: 'telecom-ra',
        name: 'Telecom Revenue Assurance',
        tagline: 'Find leakage.',
        description: 'RA.',
        icon: 'heroicons_outline:banknotes',
        contents: ['2 pipelines'],
    },
    {
        id: 'fraud-mgmt',
        name: 'Fraud Management',
        tagline: 'Score traffic.',
        description: 'FMS.',
        icon: 'heroicons_outline:shield-exclamation',
        contents: ['1 pipeline'],
    },
];

function create(existingIds: string[] = ['default'], templates: SpaceTemplateInfo[] = TEMPLATES) {
    const created = vi.fn((req: CreateSpaceRequest) =>
        of({
            id: req.id,
            displayName: req.display_name ?? req.id,
            description: req.description ?? '',
            createdAt: '',
        } as Space),
    );
    const closed = vi.fn();
    TestBed.configureTestingModule({
        imports: [SpaceTemplateGalleryDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close: closed } },
            { provide: MAT_DIALOG_DATA, useValue: { existingIds } },
            { provide: SpacesService, useValue: { templates: () => of(templates), create: created } },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {} } },
        ],
    });
    const fixture = TestBed.createComponent(SpaceTemplateGalleryDialog);
    fixture.detectChanges();
    return { fixture, created, closed };
}

/** The `<mat-form-field>` hosting `control` inside `scope`, so a mat-error assertion is scoped to that one field. */
function fieldOf(el: HTMLElement, scope: string, control: string): HTMLElement {
    return el.querySelector(`${scope} [formControlName="${control}"]`)!.closest('mat-form-field') as HTMLElement;
}

describe('SpaceTemplateGalleryDialog', () => {
    it('renders the template cards, then pre-fills the naming step from the chosen template', () => {
        const { fixture } = create();
        const cards = Array.from(fixture.nativeElement.querySelectorAll('mat-dialog-content button'));
        expect(cards.length).toBe(2);
        expect(fixture.nativeElement.textContent).toContain('Telecom Revenue Assurance');

        const c = fixture.componentInstance;
        c.choose(TEMPLATES[0]);
        fixture.detectChanges();
        expect(c.form.getRawValue()).toEqual({
            id: 'telecom-ra',
            display_name: 'Telecom Revenue Assurance',
            description: 'Find leakage.',
        });
        expect(c.form.valid).toBe(true);
    });

    it('disables a template this edition cannot create and says which feature it lacks', async () => {
        const locked: SpaceTemplateInfo = {
            ...TEMPLATES[0],
            creatable: false,
            missingFeatures: [{ feature: 'alert.dispatch', message: 'Alert Rules is a Professional+ feature' }],
        };
        const { fixture } = create(['default'], [locked, TEMPLATES[1]]);
        const cards = Array.from(
            fixture.nativeElement.querySelectorAll('mat-dialog-content button'),
        ) as HTMLButtonElement[];
        expect(cards[0].disabled).toBe(true);
        expect(cards[1].disabled).toBe(false);
        expect(cards[0].querySelector('[data-testid="template-unavailable"]')!.textContent).toContain(
            'Professional+',
        );
        fixture.componentInstance.choose(locked);
        expect(fixture.componentInstance.selected()).toBeNull();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('blocks a duplicate space id inline (create-only product rule)', () => {
        const { fixture } = create(['default', 'telecom-ra']);
        const c = fixture.componentInstance;
        c.choose(TEMPLATES[0]); // pre-fills id 'telecom-ra' — already taken
        expect(c.form.get('id')!.hasError('duplicate')).toBe(true);
        c.form.patchValue({ id: 'telecom-ra-2' });
        expect(c.form.valid).toBe(true);
    });

    it('submits POST /spaces with the template id and closes with the created space', () => {
        const { fixture, created, closed } = create();
        const c = fixture.componentInstance;
        c.choose(TEMPLATES[1]);
        c.submit();
        expect(created).toHaveBeenCalledWith({
            id: 'fraud-mgmt',
            display_name: 'Fraud Management',
            description: 'Score traffic.',
            template: 'fraud-mgmt',
        });
        expect(closed).toHaveBeenCalledWith(expect.objectContaining({ id: 'fraud-mgmt' }));
    });

    it('has no a11y violations (gallery step)', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the id pattern error only after submit — never before (the error lands in the subscript)', () => {
        const { fixture, created } = create();
        const c = fixture.componentInstance;
        c.choose(TEMPLATES[0]);
        c.form.patchValue({ id: 'Bad Id' });
        fixture.detectChanges();
        const field = fieldOf(fixture.nativeElement, 'form', 'id');
        // Untouched: no error. The old outer "@if (…; as c)" wrapper mis-projected the <mat-error> into
        // the form field's DEFAULT slot, so it rendered beside the input before any touch/submit.
        expect(field.querySelector('mat-error')).toBeNull();
        expect(field.textContent).not.toContain('Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.');
        c.submit();
        fixture.detectChanges();
        const err = field.querySelector('mat-error');
        expect(err?.textContent).toContain('Use a–z, 0–9, hyphen; start with a letter or digit; max 63 chars.');
        expect(err?.closest('.mat-mdc-form-field-subscript-wrapper')).not.toBeNull();
        expect(created).not.toHaveBeenCalled();
    });
});
