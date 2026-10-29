import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';

import { EventsService, LensService } from '@inspecto/core/api';
import { BoundAlertRule, InvService, InvestigationHeader } from '@inspecto/link-analysis/api/inv.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penEdit, pickerSet } from '@inspecto/core/testing/pen-field';
import { LinkAnalysisBoundRulesComponent } from './link-analysis-bound-rules.component';

const HEADER: InvestigationHeader = {
    id: 'inv-1',
    title: null,
    owner: 'ana',
    dataset: 'transfers',
    sourceCol: 'payer',
    targetCol: 'payee',
    linkKindCol: null,
    createdAt: '2026-09-30T00:00:00Z',
    datasetVersion: null,
    parent: null,
};

const VALUE_RULE: BoundAlertRule = {
    rule: {
        name: 'smurfs',
        severity: 'CRITICAL',
        valueMeasure: { name: 'passThrough', valueCol: 'amt', timeCol: 'ts' },
    },
    valueMeasure: true,
    edited: false,
    standingDetection: {
        enabled: true,
        principal: 'sweep:inv-1',
        enabledAt: '2026-10-05T08:00:00Z',
        dataset: 'transfers',
    },
};
const SEALED_RULE: BoundAlertRule = {
    rule: { name: 'big', severity: 'INFO', relation: 'entities', measure: 'count', comparator: 'gte', threshold: 5 },
    valueMeasure: false,
    edited: false,
    standingDetection: { enabled: false },
};

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisBoundRulesComponent],
    template: `<inspecto-link-analysis-bound-rules [investigation]="inv" />`,
})
class HostComponent {
    readonly inv = HEADER;
}

function setup(rules: BoundAlertRule[], canAuthor = true) {
    const inv = {
        boundAlertRules: vi.fn(() => of({ investigation: 'inv-1', rules })),
        editAlertRule: vi.fn(() => of({ rule: {}, current: 1, wouldFire: false, disclosure: 'x' })),
        enableStandingDetection: vi.fn(),
        disableStandingDetection: vi.fn(() =>
            of({ rule: 'smurfs', investigation: 'inv-1', enabled: false, wasEnabled: true }),
        ),
    };
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [
            provideNoopAnimations(),
            { provide: InvService, useValue: inv },
            { provide: LensService, useValue: { canAuthorAlertRules: () => canAuthor } },
            { provide: EventsService, useValue: { search: () => of([]) } },
        ],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const settle = async () => {
        await fixture.whenStable();
        fixture.detectChanges();
    };
    return { fixture, el, inv, settle };
}

describe('LinkAnalysisBoundRulesComponent', () => {
    it('Edit rule is disabled with the reason without Alert Rule authoring, and enabled with it', async () => {
        const ro = setup([VALUE_RULE], false);
        await ro.settle();
        const btn = () => ro.el.querySelector('[data-test=edit-open]') as HTMLButtonElement;
        expect(btn().disabled).toBe(true);
        expect(btn().getAttribute('aria-describedby')).toBe('bound-rules-read-only');
        expect(ro.el.querySelector('[data-test=bound-rules-read-only]')!.textContent).toContain(
            'needs Alert Rule authoring.',
        );
        await expectNoA11yViolations(ro.el);
        TestBed.resetTestingModule();
        const rw = setup([VALUE_RULE], true);
        await rw.settle();
        expect((rw.el.querySelector('[data-test=edit-open]') as HTMLButtonElement).disabled).toBe(false);
        expect(rw.el.querySelector('[data-test=bound-rules-read-only]')).toBeNull();
    });

    it('lists the bound rules and shows an enabled rule as Enabled after a reload (read-back)', async () => {
        const { el, inv, settle } = setup([VALUE_RULE, SEALED_RULE]);
        await settle();
        expect(inv.boundAlertRules).toHaveBeenCalledWith('inv-1');
        const names = Array.from(el.querySelectorAll('[data-test=bound-name]')).map((n) => n.textContent);
        expect(names).toHaveLength(2);
        expect(names[0]).toContain('smurfs');
        expect(el.querySelector('[data-test=standing-status]')!.textContent).toContain('Enabled');
        expect(el.querySelector('[data-test=standing-disable]')).not.toBeNull();
        expect(el.querySelector('[data-test=standing-readback]')!.textContent).toContain('sweep:inv-1');
        // only a value-measure rule has a standing-detection panel
        expect(el.querySelectorAll('[data-test=standing]')).toHaveLength(1);
        await expectNoA11yViolations(el);
    });

    it('says so when nothing is bound', async () => {
        const { el, settle } = setup([]);
        await settle();
        expect(el.querySelector('[data-test=bound-none]')).not.toBeNull();
    });

    it('a rule not enabled reads Not enabled; Disable then switches the read-back state off', async () => {
        const { el, inv, settle } = setup([{ ...VALUE_RULE, standingDetection: { enabled: false } }]);
        await settle();
        expect(el.querySelector('[data-test=standing-status]')!.textContent).toContain('Not enabled');
        expect(el.querySelector('[data-test=standing-disable]')).toBeNull();
        expect(inv.disableStandingDetection).not.toHaveBeenCalled();
    });

    it('edits a value rule in place: severity only, name and valueMeasure unchanged, then re-reads the list', async () => {
        const { fixture, el, inv, settle } = setup([VALUE_RULE]);
        await settle();
        el.querySelector<HTMLButtonElement>('[data-test=edit-open]')!.click();
        await settle();
        expect(el.querySelector('[data-test=edit-threshold]')).toBeNull();
        pickerSet(fixture, '[data-test=edit-severity]', 'INFO');
        await settle();
        el.querySelector<HTMLButtonElement>('[data-test=edit-save]')!.click();
        await settle();
        expect(inv.editAlertRule).toHaveBeenCalledWith('inv-1', 'smurfs', {
            name: 'smurfs',
            valueMeasure: VALUE_RULE.rule['valueMeasure'],
            severity: 'INFO',
        });
        expect(inv.boundAlertRules).toHaveBeenCalledTimes(2);
    });

    it('edits a Working Set rule: the threshold travels with the rest of the rule', async () => {
        const { fixture, el, inv, settle } = setup([SEALED_RULE]);
        await settle();
        el.querySelector<HTMLButtonElement>('[data-test=edit-open]')!.click();
        await settle();
        penEdit(fixture, el.querySelector<HTMLElement>('inspecto-pen-field[data-test=edit-threshold]')!, '9');
        await settle();
        el.querySelector<HTMLButtonElement>('[data-test=edit-save]')!.click();
        await settle();
        expect(inv.editAlertRule).toHaveBeenCalledWith('inv-1', 'big', {
            name: 'big',
            relation: 'entities',
            measure: 'count',
            comparator: 'gte',
            threshold: 9,
            severity: 'INFO',
        });
    });
});
