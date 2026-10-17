import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { LensService } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { DomainProfileService, DomainProfileState } from '@inspecto/link-analysis/api/domain-profile.service';
import { IndustryPackDto } from '@inspecto/link-analysis/graph/domain-profile';
import { TELECOM_PACK_FIXTURE } from '@inspecto/link-analysis/graph/domain-profile.testing';
import { LinkAnalysisDomainProfileComponent } from './link-analysis-domain-profile.component';

const TELECOM: IndustryPackDto = {
    ...TELECOM_PACK_FIXTURE,
    terms: [
        { key: 'entity_type.msisdn', label: 'MSISDN', abbreviation: '', definition: 'The phone number of a line.' },
        { key: 'concept.row', label: 'Call detail records', abbreviation: 'CDR / XDR', definition: 'Usage records.' },
    ],
};
const MONEY: IndustryPackDto = {
    ...TELECOM_PACK_FIXTURE,
    id: 'mobile-money',
    name: 'Mobile Money',
    description: 'Wallets and agents.',
    terms: [{ key: 'entity_type.wallet', label: 'Wallet', abbreviation: '', definition: 'A mobile money account.' }],
};
const state = (active: string, extra: Partial<DomainProfileState> = {}): DomainProfileState => ({
    active,
    stated: active === 'generic' ? null : active,
    profile: active === 'telecom' ? TELECOM : active === 'mobile-money' ? MONEY : null,
    installed: [TELECOM, MONEY],
    refused: [],
    ...extra,
});

function setup(opts: { state?: DomainProfileState; canEdit?: boolean } = {}) {
    const st = signal<DomainProfileState | null>(opts.state ?? state('telecom'));
    const svc = {
        state: st,
        ready: () => Promise.resolve(),
        save: vi.fn((id: string) => {
            const next = state(id);
            st.set(next);
            return of(next);
        }),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [LinkAnalysisDomainProfileComponent],
        providers: [
            provideNoopAnimations(),
            { provide: DomainProfileService, useValue: svc },
            { provide: ToastrService, useValue: toastr },
            { provide: LensService, useValue: { canAuthorWorkbench: () => opts.canEdit !== false } },
        ],
    });
    const fixture = TestBed.createComponent(LinkAnalysisDomainProfileComponent);
    fixture.detectChanges();
    return { fixture, el: fixture.nativeElement as HTMLElement, c: fixture.componentInstance, svc, toastr, st };
}

describe('LinkAnalysisDomainProfileComponent', () => {
    it('shows the active pack, its terms with abbreviations and definitions, and every installed pack (a11y)', async () => {
        const { el, c } = setup();
        expect(c.choice.value).toBe('telecom');
        expect(c.options().map((o) => o.value)).toEqual(['generic', 'telecom', 'mobile-money']);
        const table = el.querySelector('[data-testid="domain-profile-terms"]')!;
        expect(table.textContent).toContain('MSISDN');
        expect(table.textContent).toContain('CDR / XDR');
        expect(table.textContent).toContain('The phone number of a line.');
        await expectNoA11yViolations(el);
    });

    it('switches the terms with the pack when a Space admin applies another one', () => {
        const { el, c, svc, fixture, toastr } = setup();
        c.choice.setValue('mobile-money');
        fixture.detectChanges();
        el.querySelector<HTMLButtonElement>('[data-testid="domain-profile-save"]')!.click();
        fixture.detectChanges();
        expect(svc.save).toHaveBeenCalledWith('mobile-money');
        const table = el.querySelector('[data-testid="domain-profile-terms"]')!;
        expect(table.textContent).toContain('Wallet');
        expect(table.textContent).not.toContain('MSISDN');
        expect(toastr.success).toHaveBeenCalledWith('Mobile Money in force.');
    });

    it('generic shows the neutral vocabulary and no term table', () => {
        const { el } = setup({ state: state('generic') });
        expect(el.querySelector('[data-testid="domain-profile-terms"]')).toBeNull();
        expect(el.textContent).toContain('the neutral vocabulary');
    });

    it('is read-only without the author role: no Apply, nothing saved', async () => {
        const { el, c, svc } = setup({ canEdit: false });
        expect(el.querySelector('[data-testid="domain-profile-save"]')).toBeNull();
        expect(el.textContent).toContain('needs the author role');
        c.save();
        expect(svc.save).not.toHaveBeenCalled();
        await expectNoA11yViolations(el);
    });

    it('says when the stated pack is not installed, and names a refused pack with its reason', () => {
        const { el } = setup({
            state: state('generic', {
                stated: 'atlantis',
                diagnostic: "domain profile 'atlantis' is not installed; the neutral vocabulary is in force",
                refused: [{ id: 'toy', reason: 'term concept.galaxy names no core concept' }],
            }),
        });
        expect(el.textContent).toContain('is not installed');
        expect(el.textContent).toContain('Industry pack toy was refused');
        expect(el.textContent).toContain('names no core concept');
    });
});
