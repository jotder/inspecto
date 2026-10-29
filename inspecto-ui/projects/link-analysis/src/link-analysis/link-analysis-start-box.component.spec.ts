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
import {
    NewInvestigationInput,
    NewInvestigationRequest,
    StartCatalogue,
    StartRequest,
    newInvestigationDefaults,
} from './start-from';

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
        [graphOnScreen]="graphOnScreen()"
        [canWrite]="canWrite()"
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
    readonly graphOnScreen = signal<{ name: string } | null>(null);
    readonly canWrite = signal(true);
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
    it('T-028: an Entity Types read that answers after the Dataset was picked still defaults the Entity Type', () => {
        // the Datasets arrive first and the Entity Types later: the first default was taken from a catalogue without them
        const partial: StartCatalogue = {
            datasets: CATALOGUE.datasets.map((d) => ({ ...d, primaryEntityType: null, types: [] })),
            types: [],
        };
        TestBed.configureTestingModule({
            imports: [Host],
            providers: [
                provideNoopAnimations(),
                { provide: EntityProfileService, useValue: { suggestEntities: vi.fn(() => of(answer())) } },
                { provide: LensService, useValue: { canRevealLinkEntities: computed(() => false) } },
            ],
        });
        const fixture = TestBed.createComponent(Host);
        fixture.componentInstance.catalogue.set(partial);
        fixture.detectChanges();
        const box = fixture.debugElement.query(By.directive(LinkAnalysisStartBoxComponent)).componentInstance as LinkAnalysisStartBoxComponent;
        expect(box.form.controls.dataset.value).toBe('transfers');
        expect(box.form.controls.entityType.value).toBe('');
        fixture.componentInstance.catalogue.set(CATALOGUE);
        fixture.detectChanges();
        expect(box.form.controls.entityType.value).toBe('account');
        // a type the person chose, and the catalogue still offers, is kept when the catalogue moves again
        box.form.controls.entityType.setValue('msisdn');
        fixture.componentInstance.catalogue.set({ ...CATALOGUE });
        fixture.detectChanges();
        expect(box.form.controls.entityType.value).toBe('msisdn');
    });

    it('preselects the ?dataset Dataset and its primary Entity Type (A-7b), with no error before a submit', async () => {
        const { box, el } = create();
        expect(box.form.controls.dataset.value).toBe('transfers');
        expect(box.form.controls.entityType.value).toBe('account');
        expect(el.textContent).toContain('Start a graph');
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
            'Enter a value to start from.',
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
        expect(el.querySelector('[role="alert"]')?.textContent).toContain('Choose an Entity List.');
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
        expect(q(el, 'start-no-lists')?.textContent).toContain('No Entity List yet. Create one in Settings ▸ Entity Lists');
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
            'Transfers holds about 312 Account entities: only the busiest 200 are drawn.',
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
        expect(q(el, 'start-whole-note')?.textContent).toContain('Draws the whole of Transfers');
    });

    it('draws on every level: its links; on Investigations it offers New Investigation (no step of its own)', () => {
        const { fixture, el } = create();
        expect(q(el, 'start-what')?.textContent).toContain('Draws it and its links');
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Draw');
        expect(q(el, 'start-new-investigation')).toBeNull();
        expect(q(el, 'start-file-hint')?.textContent).toContain('File menu');
        fixture.componentInstance.investigations.set(true);
        fixture.detectChanges();
        expect(q(el, 'start-what')?.textContent).toContain('Draws it and its links');
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Draw');
        expect(q(el, 'start-new-investigation')?.textContent?.trim()).toBe('New Investigation');
        (q(el, 'start-new-investigation') as HTMLButtonElement).click();
        expect(fixture.componentInstance.newClicks).toBe(1);
        // the draw box never asks for a name or a purpose
        expect(q(el, 'new-name')).toBeNull();
        expect(q(el, 'new-purpose')).toBeNull();
    });

    it('New Investigation is disabled with the reason for a role that cannot start one (T-033)', () => {
        const { fixture, el } = create();
        fixture.componentInstance.investigations.set(true);
        fixture.componentInstance.canWrite.set(false);
        fixture.detectChanges();
        const button = q(el, 'start-new-investigation') as HTMLButtonElement;
        expect(button.disabled).toBe(true);
        expect(button.getAttribute('aria-label')).toBe(
            'New Investigation: Read-only: starting an Investigation needs Incident management.',
        );
    });

    it('with no Dataset that has a link: says so, points to File ▸ Explore Dataset and offers Add data', async () => {
        const { fixture, el } = create();
        fixture.componentInstance.catalogue.set({ datasets: [], types: [] });
        fixture.detectChanges();
        expect(q(el, 'start-box')).toBeNull();
        expect(q(el, 'start-no-dataset')?.textContent).toContain('No Dataset in this Space has a link yet');
        expect(q(el, 'start-no-dataset')?.textContent).toContain('File ▸ Explore Dataset.');
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
    const purpose = (el: HTMLElement) => penText(q(el, 'new-purpose')!);

    it('starts with a Start from picker and only that choice’s inputs, then Name and Purpose; passes axe', async () => {
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
        expect(q(el, 'new-purpose')).not.toBeNull();
        expect(penByLabel(el, 'Purpose')).toBeTruthy();
        expect(penByLabel(el, 'Reason')).toBeFalsy(); // G1: the New form says Purpose
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Start Investigation');
        expect(q(el, 'start-new-investigation')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('defaults the Name and Purpose from the input, and re-defaults them when the input changes', async () => {
        const { fixture, box, el } = createNew();
        box.form.controls.value.setValue('MULE-HUB-01');
        await settle();
        fixture.detectChanges();
        expect(name(el)).toBe('Account MULE-HUB-01');
        expect(purpose(el)).toBe('Review of an Account and its links');
        kind(fixture, 'list');
        pickerSet(fixture, '[data-testid="start-list"]', 'suspects');
        expect(name(el)).toBe('Members of “Suspects”');
        expect(purpose(el)).toBe('Review of the members of “Suspects” and their links');
        kind(fixture, 'paste');
        box.form.controls.paste.setValue('a\nb\nc');
        fixture.detectChanges();
        expect(name(el)).toBe('3 pasted Account values');
        expect(purpose(el)).toBe('Review of 3 pasted values and their links');
        kind(fixture, 'dataset');
        expect(name(el)).toBe('Transfers');
        expect(purpose(el)).toBe('Review of the links in Transfers');
    });

    it('keeps the analyst’s own Name and Purpose when the input changes', () => {
        const { fixture, el } = createNew();
        penEdit(fixture, q(el, 'new-name')!, 'Operation Larch');
        penEdit(fixture, penByLabel(el, 'Purpose')!, 'Warrant 7');
        kind(fixture, 'dataset');
        expect(name(el)).toBe('Operation Larch');
        expect(purpose(el)).toBe('Warrant 7');
    });

    it('a masked Space keeps the value out of the default Name (a title is stored and shown like a value)', async () => {
        const { fixture, box, el } = createNew('masked');
        box.form.controls.value.setValue('MULE-HUB-01');
        await settle();
        fixture.detectChanges();
        expect(name(el)).toBe('Account');
        expect(purpose(el)).toBe('Review of an Account and its links');
    });

    it('Start Investigation hands over the start, the name, the purpose and Expand on start in ONE request', async () => {
        const { fixture, box } = createNew();
        box.form.controls.value.setValue('MULE-HUB-01');
        await settle();
        fixture.detectChanges();
        // Expand on start is a − N + stepper from 0 = "Don't expand"
        const degrees = fixture.nativeElement.querySelector('[data-testid="new-degrees"]') as HTMLElement;
        const spin = degrees.querySelector('[role="spinbutton"]') as HTMLElement;
        expect(spin.textContent!.trim()).toBe("Don't expand");
        expect(spin.getAttribute('aria-valuetext')).toBe("Don't expand");
        expect(degrees.querySelector('[data-testid="stepper-decrement"]')!.getAttribute('aria-disabled')).toBe('true');
        for (let i = 0; i < 3; i++) (degrees.querySelector('[data-testid="stepper-increment"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        // Expand on start offers no more than the profile's last degree
        expect(spin.getAttribute('aria-valuenow')).toBe('2');
        expect(spin.getAttribute('aria-valuemax')).toBe('2');
        expect(degrees.querySelector('[data-testid="stepper-reason"]')!.textContent).toContain('2 degrees is the most this Industry pack allows.');
        submit(fixture);
        expect(fixture.componentInstance.starts).toEqual([]); // never the draw
        expect(fixture.componentInstance.creates).toEqual([
            {
                start: { kind: 'entity', datasetId: 'transfers', entityType: 'account', value: 'MULE-HUB-01' },
                title: 'Account MULE-HUB-01',
                purpose: 'Review of an Account and its links',
                timeCol: '',
                timeColZone: '',
                degrees: 2,
            },
        ]);
        expect(box.degreeMax()).toBe(2);
    });

    it('with a graph drawn, Start from offers "Graph on screen": a start over its mapping with no start entities', () => {
        const { fixture, box, el } = createNew();
        expect(box.kindOptions().map((o) => o.value)).toEqual(['entity', 'list', 'paste', 'dataset']);
        fixture.componentInstance.graphOnScreen.set({ name: 'mule_layering_ring' });
        fixture.detectChanges();
        expect(box.kindOptions().map((o) => o.label)).toEqual(['One entity', 'Entity List', 'Pasted values', 'Whole Dataset', 'Graph on screen']);
        // its hint names the drawn graph in Start entity words (it once read "no seed yet" beside a drawn graph)
        expect(box.kindOptions().at(-1)?.hint).toBe('mule_layering_ring, no start entities yet');
        kind(fixture, 'graph');
        expect(q(el, 'start-dataset')).toBeNull(); // the drawn graph names its own Dataset
        expect(q(el, 'new-expand')).toBeNull(); // no seed to expand from
        expect(q(el, 'start-graph-note')?.textContent).toContain('mule_layering_ring');
        expect(name(el)).toBe('mule_layering_ring');
        expect(purpose(el)).toBe('Review of the graph on screen');
        submit(fixture);
        expect(fixture.componentInstance.creates).toEqual([
            {
                start: { kind: 'graph' },
                title: 'mule_layering_ring',
                purpose: 'Review of the graph on screen',
                timeCol: '',
                timeColZone: '',
                degrees: 0,
            },
        ]);
        // the graph leaves the screen: the choice goes with it
        fixture.componentInstance.graphOnScreen.set(null);
        fixture.detectChanges();
        expect(box.form.controls.kind.value).toBe('entity');
    });

    it('the graph on screen is not named in a masked Space', () => {
        const { fixture, el } = createNew('masked');
        fixture.componentInstance.graphOnScreen.set({ name: 'mule_layering_ring' });
        fixture.detectChanges();
        kind(fixture, 'graph');
        expect(name(el)).toBe('Graph on screen');
    });

    it('the draw box never offers the graph on screen', () => {
        const { fixture, box } = create();
        fixture.componentInstance.graphOnScreen.set({ name: 'x' });
        fixture.detectChanges();
        expect(box.kindOptions().map((o) => o.value)).not.toContain('graph');
    });

    it('a cleared Purpose refuses the start and says why on the field', () => {
        const { fixture, el } = createNew();
        kind(fixture, 'dataset');
        penEdit(fixture, penByLabel(el, 'Purpose')!, '   ');
        submit(fixture);
        expect(fixture.componentInstance.creates).toEqual([]);
        expect(penError(q(el, 'new-purpose')!)).toBe('A purpose is required.');
    });

    it('a cleared Name refuses the start too: the same rule as Settings ▸ General (T-034)', () => {
        const { fixture, el } = createNew();
        kind(fixture, 'dataset');
        penEdit(fixture, q(el, 'new-name')!, '   ');
        submit(fixture);
        expect(fixture.componentInstance.creates).toEqual([]);
        expect(penError(q(el, 'new-name')!)).toBe('A name is required.');
    });

    it('without the capability one read-only line replaces the form (T-203)', async () => {
        const { fixture, box, el } = createNew();
        fixture.componentInstance.canWrite.set(false);
        fixture.detectChanges();
        const note = q(el, 'new-locked');
        expect(note?.textContent).toContain('Read-only: starting an Investigation needs Incident management.');
        expect(note?.textContent).toContain('Open existing ones with File ▸ Open…');
        expect(q(el, 'start-box')).toBeNull();
        expect(q(el, 'start-submit')).toBeNull();
        expect(box.form.disabled).toBe(true);
        box.submit();
        expect(fixture.componentInstance.creates).toEqual([]);
        await expectNoA11yViolations(el);

        fixture.componentInstance.canWrite.set(true);
        fixture.detectChanges();
        expect(q(el, 'new-locked')).toBeNull();
        expect(box.form.enabled).toBe(true);
    });

    it('resetNew starts fresh: the start inputs clear, Name and Purpose re-default and follow the input again', async () => {
        const { fixture, box, el } = createNew();
        penEdit(fixture, q(el, 'new-name')!, 'Operation Larch');
        penEdit(fixture, penByLabel(el, 'Purpose')!, 'Warrant 7');
        kind(fixture, 'paste');
        box.form.controls.paste.setValue('a\nb');
        box.resetNew();
        fixture.detectChanges();
        expect(box.form.controls.kind.value).toBe('entity');
        expect(box.form.controls.paste.value).toBe('');
        expect(box.form.controls.name.value).toBe(''); // nothing picked yet: no Name
        expect(purpose(el)).toBe('Review of an Account and its links');
        expect(box.form.controls.name.pristine).toBe(true);
        expect(box.tried()).toBe(false);
        // the "edited" flags are reset, so the defaults follow the input again
        box.form.controls.value.setValue('MULE-HUB-01');
        await settle();
        fixture.detectChanges();
        expect(name(el)).toBe('Account MULE-HUB-01');
    });
});

describe('newInvestigationDefaults: the full matrix (LA-UI-TEXT-PASS-1 X7)', () => {
    const base = { typeLabel: 'Account', value: '', listTitle: '', pasted: 0, datasetName: 'Calls', maskingMode: 'none' };
    const d = (over: Partial<NewInvestigationInput>) => newInvestigationDefaults({ ...base, kind: 'entity', ...over });
    const NO_GAPS = /\s{2}|^\s|\s$|Investigation —|whole the|list an Entity List|\bundefined\b|\bnull\b/;

    it.each<[string, Partial<NewInvestigationInput>, string, string]>([
        // one entity
        ['entity, no value yet', { value: '' }, '', 'Review of an Account and its links'],
        ['entity, a value, Space unmasked', { value: 'AC-1' }, 'Account AC-1', 'Review of an Account and its links'],
        ['entity, a value, Space masked', { value: 'AC-1', maskingMode: 'partial' }, 'Account', 'Review of an Account and its links'],
        ['entity, a value, masking unknown', { value: 'AC-1', maskingMode: null }, 'Account', 'Review of an Account and its links'],
        ['entity, type not picked', { typeLabel: '', value: 'AC-1' }, 'AC-1', 'Review of an entity and its links'],
        ['entity, type not picked and masked', { typeLabel: '', value: 'AC-1', maskingMode: 'full' }, 'Entity', 'Review of an entity and its links'],
        ['entity, consonant type', { typeLabel: 'Party', value: 'P-1' }, 'Party P-1', 'Review of a Party and its links'],
        ['entity, blank value', { value: '   ' }, '', 'Review of an Account and its links'],
        // Entity List
        ['list, none picked', { kind: 'list' }, '', 'Review of the members of an Entity List and their links'],
        ['list, picked', { kind: 'list', listTitle: ' Watch ' }, 'Members of “Watch”', 'Review of the members of “Watch” and their links'],
        // pasted values
        ['paste, none', { kind: 'paste', pasted: 0 }, '', ''],
        ['paste, one', { kind: 'paste', pasted: 1 }, '1 pasted Account value', 'Review of 1 pasted value and its links'],
        ['paste, many', { kind: 'paste', pasted: 1234 }, '1,234 pasted Account values', 'Review of 1,234 pasted values and their links'],
        ['paste, type not picked', { kind: 'paste', pasted: 2, typeLabel: '' }, '2 pasted values', 'Review of 2 pasted values and their links'],
        // whole Dataset
        ['dataset, named', { kind: 'dataset' }, 'Calls', 'Review of the links in Calls'],
        ['dataset, unnamed', { kind: 'dataset', datasetName: '' }, 'Whole Dataset', 'Review of the links in the Dataset'],
        // graph on screen
        ['graph, unmasked', { kind: 'graph', graphName: 'ring' }, 'ring', 'Review of the graph on screen'],
        ['graph, masked', { kind: 'graph', graphName: 'ring', maskingMode: 'partial' }, 'Graph on screen', 'Review of the graph on screen'],
        ['graph, no name', { kind: 'graph', graphName: '' }, 'Graph on screen', 'Review of the graph on screen'],
    ])('%s', (_case, over, name, purpose) => {
        const out = d(over);
        expect(out).toEqual({ name, purpose });
        for (const text of [out.name, out.purpose]) if (text) expect(text).not.toMatch(NO_GAPS);
    });

    it('never puts a raw identifier in the Purpose', () => {
        expect(d({ value: 'AC-1' }).purpose).not.toContain('AC-1');
    });
});
