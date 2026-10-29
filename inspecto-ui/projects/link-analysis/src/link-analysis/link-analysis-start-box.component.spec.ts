import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit, penError, penText, pickerSet } from '@inspecto/core/testing/pen-field';
import { EntityProfileService, EntitySuggestions } from '@inspecto/link-analysis/api/entity-profile.service';
import type { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisStartBoxComponent, StartNotice } from './link-analysis-start-box.component';
import { NewInvestigationRequest, StartCatalogue, StartRequest, newInvestigationDefaults } from './start-from';

const CATALOGUE: StartCatalogue = {
    datasets: [
        {
            id: 'calls',
            name: 'Calls',
            primaryEntityType: 'msisdn',
            mapping: { datasetId: 'calls', sourceCol: 'a', targetCol: 'b', entityType: 'msisdn' },
            types: ['msisdn'],
            from: 'default link',
        },
        {
            id: 'transfers',
            name: 'Transfers',
            primaryEntityType: 'account',
            mapping: {
                datasetId: 'transfers',
                sourceCol: 'from_account',
                targetCol: 'to_account',
                entityType: 'account',
            },
            types: ['account'],
            from: 'default link',
        },
    ],
    types: [
        { id: 'msisdn', label: 'Phone number' },
        { id: 'account', label: 'Account' },
    ],
};

const LIST: EntityListSummary = {
    id: 'suspects',
    title: 'Suspects',
    purpose: 'watch',
    entityType: 'account',
    size: 3,
    retired: false,
    createdAt: '2026-10-01T00:00:00Z',
    createdBy: null,
    lastSeq: 1,
};

const answer = (over: Partial<EntitySuggestions> = {}): EntitySuggestions => ({
    type: 'account',
    masked: false,
    datasets: ['transfers'],
    items: [{ value: 'MULE-HUB-01', links: 10, firstSeen: '2026-09-03T08:00:00Z', lastSeen: '2026-09-30T17:00:00Z' }],
    distinct: 312,
    assessed: true,
    reason: null,
    ...over,
});

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisStartBoxComponent],
    template: `<inspecto-link-analysis-start-box
        [catalogue]="catalogue()"
        [lists]="lists()"
        [dataset]="dataset()"
        [investigations]="investigations()"
        [nodeCap]="200"
        [notice]="notice()"
        [addData]="addData()"
        [mode]="mode()"
        [maskingMode]="masking()"
        [maxDegree]="2"
        (start)="starts.push($event)"
        (create)="creates.push($event)"
        (newInvestigation)="newClicks = newClicks + 1"
        (openAddData)="addDataClicks = addDataClicks + 1"
    ></inspecto-link-analysis-start-box>`,
})
class Host {
    readonly catalogue = signal<StartCatalogue>(CATALOGUE);
    readonly lists = signal<EntityListSummary[]>([LIST]);
    readonly dataset = signal<string | null>('transfers');
    readonly investigations = signal(false);
    readonly notice = signal<StartNotice | null>(null);
    readonly addData = signal(true);
    readonly mode = signal<'draw' | 'investigation'>('draw');
    readonly masking = signal<string | null>('none');
    readonly starts: StartRequest[] = [];
    readonly creates: NewInvestigationRequest[] = [];
    addDataClicks = 0;
    newClicks = 0;
}

function create(suggest = vi.fn((..._a: unknown[]) => of(answer())), mayReveal = false) {
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [
            provideNoopAnimations(),
            { provide: EntityProfileService, useValue: { suggestEntities: suggest } },
            { provide: LensService, useValue: { canRevealLinkEntities: computed(() => mayReveal) } },
        ],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const box = fixture.debugElement.query(By.directive(LinkAnalysisStartBoxComponent))
        .componentInstance as LinkAnalysisStartBoxComponent;
    return { fixture, box, suggest, el: fixture.nativeElement as HTMLElement };
}

const settle = () => new Promise((r) => setTimeout(r, 300));
const q = (el: HTMLElement, id: string) => el.querySelector(`[data-testid="${id}"]`) as HTMLElement | null;
function submit(fixture: ReturnType<typeof create>['fixture']): void {
    (q(fixture.nativeElement, 'start-box') as HTMLFormElement).dispatchEvent(new Event('submit'));
    fixture.detectChanges();
}
/** Start from: a popup picker like the Entity Type (LA-PANELS-REDESIGN-1). */
function kind(fixture: ReturnType<typeof create>['fixture'], k: string): void {
    pickerSet(fixture, '[data-testid="start-kind"]', k);
}

describe('LinkAnalysisStartBoxComponent (LA-ONBOARD-1 phase 6, OB-9)', () => {
    it('preselects the ?dataset Dataset and its primary Entity Type (A-7b), with no error before a submit', async () => {
        const { box, el } = create();
        expect(box.form.controls.dataset.value).toBe('transfers');
        expect(box.form.controls.entityType.value).toBe('account');
        expect(el.textContent).toContain('Start from...');
        expect(el.querySelector('mat-error')).toBeNull();
        await settle();
        await expectNoA11yViolations(el);
    });

    it('one entity: the type-ahead asks for the typed prefix over the picked Dataset and shows link counts', async () => {
        const { fixture, box, suggest, el } = create();
        const input = q(el, 'start-value') as HTMLInputElement;
        input.value = 'MULE';
        input.dispatchEvent(new Event('input'));
        await settle();
        fixture.detectChanges();
        expect(suggest).toHaveBeenLastCalledWith('account', { prefix: 'MULE', dataset: 'transfers', limit: 8 });
        expect(box.suggested().map((s) => s.value)).toEqual(['MULE-HUB-01']);
        fixture.debugElement
            .query(By.directive(MatAutocompleteTrigger))
            .injector.get(MatAutocompleteTrigger)
            .openPanel();
        fixture.detectChanges();
        const option = document.querySelector('mat-option') as HTMLElement;
        expect(option.textContent).toContain('MULE-HUB-01');
        expect(option.textContent).toContain('10 links, first seen 3 Sep 2026, last seen 30 Sep 2026');
        await expectNoA11yViolations(el);
    });

    it('one entity: an empty value is refused inline; a typed one starts with the picked type and Dataset', () => {
        const { fixture, el } = create();
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([]);
        expect(el.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain(
            'Type the Account to start from.',
        );
        const input = q(el, 'start-value') as HTMLInputElement;
        input.value = ' ACC-0042 ';
        input.dispatchEvent(new Event('input'));
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([
            { kind: 'entity', datasetId: 'transfers', entityType: 'account', value: 'ACC-0042' },
        ]);
    });

    it('F-7: an account is not refused as "6 to 15 digits" - any Entity Type can start', () => {
        const { fixture, el } = create();
        const input = q(el, 'start-value') as HTMLInputElement;
        input.value = 'MULE-HUB-01';
        input.dispatchEvent(new Event('input'));
        submit(fixture);
        expect(el.textContent).not.toContain('6 to 15 digits');
        expect(fixture.componentInstance.starts[0]).toMatchObject({ kind: 'entity', value: 'MULE-HUB-01' });
    });

    it('a masked type says nothing is suggested', async () => {
        const { fixture, el } = create(vi.fn(() => of(answer({ masked: true, items: [], assessed: false }))));
        await settle();
        fixture.detectChanges();
        expect(q(el, 'start-suggest-note')?.textContent).toContain('masked: type the whole value');
    });

    it('a masked type offers no reveal to someone who may not reveal', async () => {
        const { fixture, el } = create(vi.fn(() => of(answer({ masked: true, items: [], assessed: false }))));
        await settle();
        fixture.detectChanges();
        expect(q(el, 'start-reveal')).toBeNull();
    });

    /**
     * LA-ONBOARD-DEFECTS-1 (4): a masked type suggested nothing even to someone who may reveal. They are now offered its
     * values; the ask goes to the server as `reveal` (checked and audited there) and is not carried to another type.
     */
    it('a masked type: someone who may reveal asks for its values, and the type-ahead lists them', async () => {
        const suggest = vi.fn((_t: unknown, req: unknown) =>
            of(
                (req as { reveal?: boolean }).reveal
                    ? answer({ masked: true, revealed: true })
                    : answer({ masked: true, items: [], assessed: false }),
            ),
        );
        const { fixture, box, el } = create(suggest, true);
        await settle();
        fixture.detectChanges();
        expect(box.suggested()).toEqual([]);
        (q(el, 'start-reveal') as HTMLButtonElement).click();
        await settle();
        fixture.detectChanges();
        expect(suggest).toHaveBeenLastCalledWith('account', {
            prefix: '',
            dataset: 'transfers',
            limit: 8,
            reveal: true,
        });
        expect(box.suggested().map((s) => s.value)).toEqual(['MULE-HUB-01']);
        expect(q(el, 'start-suggest-note')?.textContent).toContain('shown because you may reveal them');
        expect(q(el, 'start-reveal')).toBeNull();
        await expectNoA11yViolations(el);
        pickerSet(fixture, '[data-testid="start-type"]', 'msisdn');
        await settle();
        expect(suggest).toHaveBeenLastCalledWith('msisdn', { prefix: '', dataset: 'transfers', limit: 8 });
    });

    /**
     * LA-ONBOARD-DEFECTS-1 (3): the type-ahead did not refresh when switching back to *One entity* - the kind was read only
     * when another source emitted, so a Dataset picked while on *A list* left the box with no suggestions on return.
     */
    it('switching back to One entity asks the type-ahead again for the Dataset picked meanwhile', async () => {
        const { fixture, box, suggest } = create();
        await settle();
        kind(fixture, 'list');
        pickerSet(fixture, '[data-testid="start-dataset"]', 'calls');
        await settle();
        suggest.mockClear();
        kind(fixture, 'entity');
        await settle();
        fixture.detectChanges();
        expect(suggest).toHaveBeenLastCalledWith('msisdn', { prefix: '', dataset: 'calls', limit: 8 });
        expect(box.suggested().map((s) => s.value)).toEqual(['MULE-HUB-01']);
    });

    it('a list: picks an Entity List and starts from it; a missing pick is said', async () => {
        const { fixture, el } = create();
        kind(fixture, 'list');
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([]);
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('Pick the Entity List');
        pickerSet(fixture, '[data-testid="start-list"]', 'suspects');
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([
            { kind: 'list', datasetId: 'transfers', listId: 'suspects' },
        ]);
        await expectNoA11yViolations(el);
    });

    it('a list: with no Entity List says how to make one', () => {
        const { fixture, el } = create();
        fixture.componentInstance.lists.set([]);
        kind(fixture, 'list');
        expect(q(el, 'start-no-lists')?.textContent).toContain('No Entity List in this Space yet');
    });

    it('a paste: values split on lines, commas and semicolons, repeats dropped', async () => {
        const { fixture, el } = create();
        kind(fixture, 'paste');
        submit(fixture);
        expect(el.querySelector('.mat-mdc-form-field-subscript-wrapper mat-error')?.textContent).toContain(
            'Paste at least one',
        );
        const area = q(el, 'start-paste') as HTMLTextAreaElement;
        area.value = 'ACC-1\nACC-2, ACC-3;ACC-1\n\n';
        area.dispatchEvent(new Event('input'));
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([
            { kind: 'paste', datasetId: 'transfers', entityType: 'account', values: ['ACC-1', 'ACC-2', 'ACC-3'] },
        ]);
        await expectNoA11yViolations(el);
    });

    it('the whole Dataset: warned above the node cap, and still allowed', async () => {
        const suggest = vi.fn((_t: unknown, req: unknown) =>
            of(answer({ distinct: (req as { prefix?: string }).prefix === undefined ? 312 : 1 })),
        );
        const { fixture, el } = create(suggest);
        kind(fixture, 'dataset');
        await settle();
        fixture.detectChanges();
        expect(suggest).toHaveBeenCalledWith('account', { dataset: 'transfers', limit: 1 });
        expect(q(el, 'start-whole-warning')?.textContent).toContain(
            'Transfers holds about 312 Account entities, more than the 200-node cap',
        );
        expect(q(el, 'start-submit')?.textContent).toContain('Draw the whole Dataset');
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([{ kind: 'dataset', datasetId: 'transfers' }]);
        await expectNoA11yViolations(el);
    });

    it('the whole Dataset within the cap: a quiet note, no warning', async () => {
        const { fixture, el } = create(vi.fn(() => of(answer({ distinct: 40 }))));
        kind(fixture, 'dataset');
        await settle();
        fixture.detectChanges();
        expect(q(el, 'start-whole-warning')).toBeNull();
        expect(q(el, 'start-whole-note')?.textContent).toContain('Draws every link in Transfers');
    });

    it('draws on every level: a ring; on Investigations it offers New Investigation… (no seed step of its own)', () => {
        const { fixture, el } = create();
        expect(q(el, 'start-what')?.textContent).toContain('one ring');
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Start');
        expect(q(el, 'start-new-investigation')).toBeNull();
        expect(q(el, 'start-file-hint')?.textContent).toContain('File menu');
        fixture.componentInstance.investigations.set(true);
        fixture.detectChanges();
        expect(q(el, 'start-what')?.textContent).toContain('one ring');
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Start');
        (q(el, 'start-new-investigation') as HTMLButtonElement).click();
        expect(fixture.componentInstance.newClicks).toBe(1);
        // the draw box never asks for a name or a reason
        expect(q(el, 'new-name')).toBeNull();
        expect(q(el, 'new-reason')).toBeNull();
    });

    it('with no Dataset that has a link: says so, points to File ▸ Explore dataset… and offers Add data', async () => {
        const { fixture, el } = create();
        fixture.componentInstance.catalogue.set({ datasets: [], types: [] });
        fixture.detectChanges();
        expect(q(el, 'start-box')).toBeNull();
        expect(q(el, 'start-no-dataset')?.textContent).toContain('No Dataset in this Space has a link yet');
        expect(q(el, 'start-no-dataset')?.textContent).toContain('File ▸ Explore dataset…');
        expect(q(el, 'starter-explore')).toBeNull();
        (q(el, 'starter-add-data') as HTMLButtonElement).click();
        expect(fixture.componentInstance.addDataClicks).toBe(1);
        fixture.componentInstance.addData.set(false);
        fixture.detectChanges();
        expect(q(el, 'starter-add-data')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('shows the page notice (what was drawn, what was not found)', () => {
        const { fixture, el } = create();
        fixture.componentInstance.notice.set({ variant: 'warning', text: 'Not in Transfers: NOPE-1.' });
        fixture.detectChanges();
        expect(q(el, 'start-notice')?.textContent).toContain('Not in Transfers: NOPE-1.');
    });
});

describe('LinkAnalysisStartBoxComponent - the New Investigation form (LA-PANELS-REDESIGN-1)', () => {
    function createNew(masking: string | null = 'none') {
        const made = create();
        made.fixture.componentInstance.mode.set('investigation');
        made.fixture.componentInstance.masking.set(masking);
        made.fixture.detectChanges();
        return made;
    }
    const name = (el: HTMLElement) => penText(q(el, 'new-name')!);
    const reason = (el: HTMLElement) => penText(q(el, 'new-reason')!);

    it('starts with a Start from picker and only that choice’s inputs, then Name and Reason; passes axe', async () => {
        const { fixture, el } = createNew();
        expect(q(el, 'start-kind')?.tagName.toLowerCase()).toBe('inspecto-option-picker');
        expect(q(el, 'start-type')).not.toBeNull();
        expect(q(el, 'start-value')).not.toBeNull();
        expect(q(el, 'start-paste')).toBeNull();
        kind(fixture, 'paste');
        expect(q(el, 'start-value')).toBeNull();
        expect(q(el, 'start-paste')).not.toBeNull();
        kind(fixture, 'dataset');
        expect(q(el, 'start-paste')).toBeNull();
        expect(q(el, 'new-expand')).toBeNull(); // nothing to expand from the whole Dataset
        expect(q(el, 'new-name')).not.toBeNull();
        expect(q(el, 'new-reason')).not.toBeNull();
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Start Investigation');
        expect(q(el, 'start-new-investigation')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('defaults the Name and Reason from the input, and re-defaults them when the input changes', async () => {
        const { fixture, box, el } = createNew();
        box.form.controls.value.setValue('MULE-HUB-01');
        await settle();
        fixture.detectChanges();
        expect(name(el)).toBe('Investigation — Account MULE-HUB-01');
        expect(reason(el)).toBe('Review of Account MULE-HUB-01 and its links');
        kind(fixture, 'list');
        pickerSet(fixture, '[data-testid="start-list"]', 'suspects');
        expect(name(el)).toBe('Investigation — list Suspects');
        kind(fixture, 'paste');
        box.form.controls.paste.setValue('a\nb\nc');
        fixture.detectChanges();
        expect(name(el)).toBe('Investigation — 3 pasted values');
        kind(fixture, 'dataset');
        expect(name(el)).toBe('Investigation — whole Transfers');
        expect(reason(el)).toBe('Review of the links in Transfers');
    });

    it('keeps the analyst’s own Name and Reason when the input changes', () => {
        const { fixture, el } = createNew();
        penEdit(fixture, q(el, 'new-name')!, 'Operation Larch');
        penEdit(fixture, penByLabel(el, 'Reason')!, 'Warrant 7');
        kind(fixture, 'dataset');
        expect(name(el)).toBe('Operation Larch');
        expect(reason(el)).toBe('Warrant 7');
    });

    it('a masked Space keeps the value out of the default Name (a title is stored and shown like a value)', async () => {
        const { fixture, box, el } = createNew('masked');
        box.form.controls.value.setValue('MULE-HUB-01');
        await settle();
        fixture.detectChanges();
        expect(name(el)).toBe('Investigation — Account');
        expect(reason(el)).toBe('Review of the Account and its links');
    });

    it('Start Investigation hands over the start, the name, the reason and Expand on start in ONE request', async () => {
        const { fixture, box } = createNew();
        box.form.controls.value.setValue('MULE-HUB-01');
        await settle();
        fixture.detectChanges();
        pickerSet(fixture, '[data-testid="new-degrees"]', '2');
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([]); // never the draw
        expect(fixture.componentInstance.creates).toEqual([
            {
                start: { kind: 'entity', datasetId: 'transfers', entityType: 'account', value: 'MULE-HUB-01' },
                title: 'Investigation — Account MULE-HUB-01',
                purpose: 'Review of Account MULE-HUB-01 and its links',
                timeCol: '',
                timeColZone: '',
                degrees: 2,
            },
        ]);
        // Expand on start offers no more than the profile's last degree
        expect(box.degreeOptions().map((o) => o.value)).toEqual(['0', '1', '2']);
    });

    it('a cleared Reason refuses the start and says why on the field', () => {
        const { fixture, el } = createNew();
        kind(fixture, 'dataset');
        penEdit(fixture, penByLabel(el, 'Reason')!, '   ');
        submit(fixture);
        expect(fixture.componentInstance.creates).toEqual([]);
        expect(penError(q(el, 'new-reason')!)).toBe('A reason is required to start an Investigation.');
    });
});

describe('newInvestigationDefaults', () => {
    const base = { typeLabel: 'Phone number', value: '966501', listTitle: '', pasted: 0, datasetName: 'Calls' };
    it('names each kind of start', () => {
        expect(newInvestigationDefaults({ ...base, kind: 'entity', maskingMode: 'none' }).name).toBe(
            'Investigation — Phone number 966501',
        );
        expect(newInvestigationDefaults({ ...base, kind: 'entity', maskingMode: null }).name).toBe(
            'Investigation — Phone number',
        );
        expect(newInvestigationDefaults({ ...base, kind: 'paste', pasted: 1 }).name).toBe('Investigation — 1 pasted value');
        expect(newInvestigationDefaults({ ...base, kind: 'list' }).name).toBe('Investigation — list an Entity List');
    });
});
