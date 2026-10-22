import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit, penError, pickerSet } from '@inspecto/core/testing/pen-field';
import {
    EntityTypeConfig,
    LinkAnalysisLimits,
    LinkAnalysisSettingsService,
} from '@inspecto/link-analysis/link-analysis/link-analysis-settings.service';
import { NewEntityTypeComponent } from './new-entity-type.component';

@Component({
    standalone: true,
    imports: [NewEntityTypeComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-new-entity-type
        (added)="added.push($event)"
        (cancelled)="cancelled = true"
    ></inspecto-la-new-entity-type>`,
})
class Host {
    readonly added: string[] = [];
    cancelled = false;
}

const ACCOUNT: EntityTypeConfig = {
    id: 'account',
    label: 'Account',
    normaliser: 'upper-trim',
    masked: true,
    classifications: ['ACCOUNT'],
};
const SERVED: LinkAnalysisLimits = {
    projectionNodeCap: 500,
    analysisNodeCap: null,
    suspicionNodeCap: null,
    maskingMode: 'none',
    entityTypes: null,
    entityTypesInForce: [ACCOUNT],
    index: { enabled: false, maxDiskBytes: null, keepVersions: null },
};

function make(save = vi.fn((b: unknown) => of(b as LinkAnalysisLimits))) {
    const limits = signal<LinkAnalysisLimits>(SERVED);
    const get = vi.fn(() => of(SERVED));
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [provideNoopAnimations(), { provide: LinkAnalysisSettingsService, useValue: { limits, get, save } }],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const submit = () => {
        (el.querySelector('[data-testid=net-save]') as HTMLButtonElement).click();
        fixture.detectChanges();
    };
    return { fixture, el, host: fixture.componentInstance, get, save, submit };
}

describe('NewEntityTypeComponent', () => {
    it('adds the type to the types in force (the stated list), round-tripping the other settings', () => {
        const { fixture, el, host, get, save, submit } = make();
        penEdit(fixture, penByLabel(el, 'Name')!, 'Vehicle plate');
        pickerSet(fixture, '[data-testid=net-normaliser]', 'digits');
        expect(el.querySelector('[data-testid=net-id]')!.textContent).toContain('VEHICLE_PLATE');
        submit();
        expect(get).toHaveBeenCalled();
        expect(save).toHaveBeenCalledWith({
            maskingMode: 'none',
            entityTypes: [
                ACCOUNT,
                {
                    id: 'vehicle_plate',
                    label: 'Vehicle plate',
                    normaliser: 'digits',
                    masked: false,
                    classifications: ['VEHICLE_PLATE'],
                },
            ],
            index: { enabled: false },
        });
        expect(host.added).toEqual(['vehicle_plate']);
    });

    it('shows how the example value is kept under the chosen rule', () => {
        const { fixture, el } = make();
        penEdit(fixture, penByLabel(el, 'Example value')!, ' ab  12 ');
        expect(el.querySelector('[data-testid=net-kept]')!.textContent).toContain('Kept as AB 12');
        pickerSet(fixture, '[data-testid=net-normaliser]', 'digits');
        expect(el.querySelector('[data-testid=net-kept]')!.textContent).toContain('Kept as 12');
        penEdit(fixture, penByLabel(el, 'Example value')!, 'abc');
        expect(el.querySelector('[data-testid=net-kept]')!.textContent).toContain('would be empty');
    });

    it('refuses an empty name or one the Space has, without writing', () => {
        const { fixture, el, save, submit } = make();
        submit();
        expect(penError(penByLabel(el, 'Name')!)).toBe('Give the Entity Type a name.');
        penEdit(fixture, penByLabel(el, 'Name')!, 'account');
        expect(penError(penByLabel(el, 'Name')!)).toContain('already an Entity Type called');
        submit();
        expect(save).not.toHaveBeenCalled();
    });

    it('states a refusal from the server', () => {
        const save = vi.fn(() =>
            throwError(
                () =>
                    new HttpErrorResponse({
                        status: 422,
                        error: { error: { message: 'at most 1 entity types, got 2' } },
                    }),
            ),
        );
        const { fixture, el, host, submit } = make(save);
        penEdit(fixture, penByLabel(el, 'Name')!, 'Plate');
        submit();
        expect(el.querySelector('[data-testid=net-error]')!.textContent).toContain('at most 1 entity types');
        expect(host.added).toEqual([]);
    });

    it('Cancel closes without writing', () => {
        const { el, host, save } = make();
        (el.querySelector('[data-testid=net-cancel]') as HTMLButtonElement).click();
        expect(host.cancelled).toBe(true);
        expect(save).not.toHaveBeenCalled();
    });

    it('has no axe violations', async () => {
        const { el } = make();
        await expectNoA11yViolations(el);
    }, 30_000);
});
