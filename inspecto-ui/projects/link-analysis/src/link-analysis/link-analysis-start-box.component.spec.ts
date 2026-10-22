import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MatAutocompleteTrigger } from '@angular/material/autocomplete';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { pickerSet } from '@inspecto/core/testing/pen-field';
import { EntityProfileService, EntitySuggestions } from '@inspecto/link-analysis/api/entity-profile.service';
import type { EntityListSummary } from '@inspecto/link-analysis/api/inv.service';
import { LinkAnalysisStartBoxComponent, StartNotice } from './link-analysis-start-box.component';
import { StartCatalogue, StartRequest } from './start-from';

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
        (start)="starts.push($event)"
        (openAddData)="addDataClicks = addDataClicks + 1"
        (exploreDataset)="exploreClicks = exploreClicks + 1"
    ></inspecto-link-analysis-start-box>`,
})
class Host {
    readonly catalogue = signal<StartCatalogue>(CATALOGUE);
    readonly lists = signal<EntityListSummary[]>([LIST]);
    readonly dataset = signal<string | null>('transfers');
    readonly investigations = signal(false);
    readonly notice = signal<StartNotice | null>(null);
    readonly addData = signal(true);
    readonly starts: StartRequest[] = [];
    addDataClicks = 0;
    exploreClicks = 0;
}

function create(suggest = vi.fn((..._a: unknown[]) => of(answer()))) {
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [provideNoopAnimations(), { provide: EntityProfileService, useValue: { suggestEntities: suggest } }],
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
function kind(fixture: ReturnType<typeof create>['fixture'], k: string): void {
    const radio = (fixture.nativeElement as HTMLElement).querySelector(`[data-kind="${k}"] input`) as HTMLInputElement;
    radio.click();
    fixture.detectChanges();
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

    it('says what a start does on each level: a ring on the Explorer, an Investigation on Investigations', () => {
        const { fixture, el } = create();
        expect(q(el, 'start-what')?.textContent).toContain('one ring');
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Start');
        fixture.componentInstance.investigations.set(true);
        fixture.detectChanges();
        expect(q(el, 'start-what')?.textContent).toContain('Seeds an Investigation');
        expect(q(el, 'start-submit')?.textContent?.trim()).toBe('Investigate');
        kind(fixture, 'paste');
        expect(q(el, 'start-what')?.textContent).toContain('queued as the first seeds of an Investigation');
    });

    it('with no Dataset that has a link: says so, offers Explore a Dataset and Add data', async () => {
        const { fixture, el } = create();
        fixture.componentInstance.catalogue.set({ datasets: [], types: [] });
        fixture.detectChanges();
        expect(q(el, 'start-box')).toBeNull();
        expect(q(el, 'start-no-dataset')?.textContent).toContain('No Dataset in this Space has a link yet');
        (q(el, 'starter-add-data') as HTMLButtonElement).click();
        (q(el, 'starter-explore') as HTMLButtonElement).click();
        expect(fixture.componentInstance.addDataClicks).toBe(1);
        expect(fixture.componentInstance.exploreClicks).toBe(1);
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
