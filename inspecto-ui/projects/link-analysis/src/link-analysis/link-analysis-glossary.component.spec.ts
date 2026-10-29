import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';

import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { GENERIC_PROFILE, installDomainProfiles, profileFromPack } from '@inspecto/link-analysis/graph/domain-profile';
import { LinkAnalysisGlossaryComponent, glossaryEntries } from './link-analysis-glossary.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisGlossaryComponent],
    template: `<inspecto-link-analysis-glossary />`,
})
class HostComponent {}

const pack = (terms: { key: string; label: string; abbreviation: string; definition: string }[]) =>
    profileFromPack({
        id: 'p',
        name: 'P',
        description: '',
        spaceTemplates: [],
        patternPacks: [],
        profile: { measureHints: [], timeHints: [], suggestedTools: [] },
        entityTypes: [],
        linkKinds: [],
        terms,
    } as never);

describe('glossaryEntries', () => {
    it('keeps only term.<id> entries, strips the key, and sorts by label', () => {
        const p = pack([
            { key: 'term.b', label: 'Wangiri', abbreviation: '', definition: 'd2' },
            { key: 'entity_type.account', label: 'Wallet', abbreviation: '', definition: '' },
            { key: 'term.a', label: 'IRSF', abbreviation: 'IRS', definition: 'd1' },
        ]);
        expect(glossaryEntries(p).map((e) => [e.id, e.label])).toEqual([
            ['a', 'IRSF'],
            ['b', 'Wangiri'],
        ]);
        expect(glossaryEntries(GENERIC_PROFILE)).toEqual([]);
    });
});

describe('LinkAnalysisGlossaryComponent', () => {
    it('is absent for a pack with no typology terms', () => {
        installDomainProfiles([GENERIC_PROFILE, pack([{ key: 'entity_type.account', label: 'W', abbreviation: '', definition: '' }])], 'p');
        try {
            TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
            const fixture = TestBed.createComponent(HostComponent);
            fixture.detectChanges();
            expect(fixture.nativeElement.querySelector('[data-testid="la-glossary"]')).toBeNull();
        } finally {
            installDomainProfiles([], null);
        }
    });

    it('opens a popover of the active pack terms with abbreviation and definition, axe-clean', async () => {
        installDomainProfiles(
            [
                GENERIC_PROFILE,
                pack([
                    { key: 'term.simbox', label: 'SIM box', abbreviation: '', definition: 'A gateway of many SIMs.' },
                    { key: 'term.irsf', label: 'IRSF', abbreviation: 'IRS', definition: '' },
                ]),
            ],
            'p',
        );
        try {
            TestBed.configureTestingModule({ imports: [HostComponent], providers: [provideNoopAnimations()] });
            const fixture = TestBed.createComponent(HostComponent);
            fixture.detectChanges();
            const trigger = fixture.nativeElement.querySelector('[data-testid="la-glossary"]') as HTMLButtonElement;
            expect(trigger.getAttribute('aria-label')).toBe('Industry pack glossary');
            trigger.click();
            fixture.detectChanges();
            const panel = document.querySelector('[role="dialog"][aria-label="Industry pack glossary"]') as HTMLElement;
            expect(panel).not.toBeNull();
            const terms = Array.from(panel.querySelectorAll('dt')).map((t) => t.textContent?.replace(/\s+/g, ' ').trim());
            expect(terms).toEqual(['IRSF (IRS)', 'SIM box']);
            expect(panel.textContent).toContain('A gateway of many SIMs.');
            expect(panel.textContent).toContain('No definition stated.');
            await expectNoA11yViolations(panel);
        } finally {
            installDomainProfiles([], null);
        }
    });
});
