import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LaSettingsSection, LinkAnalysisSettingsComponent } from './link-analysis-settings.component';

@Component({
    standalone: true,
    imports: [LinkAnalysisSettingsComponent],
    changeDetection: ChangeDetectionStrategy.Eager,
    template: `<inspecto-la-settings
        [(open)]="open"
        [(section)]="section"
        [investigationOpen]="investigation"
        [isDirty]="dirty"
    >
        <p laSection="graph-model" data-testid="gm">graph model</p>
        <p laSection="entity-types" data-testid="et">types</p>
        <p laSection="investigation" data-testid="inv">inv</p>
    </inspecto-la-settings>`,
})
class Host {
    readonly open = signal(true);
    readonly section = signal<LaSettingsSection>('graph-model');
    investigation = false;
    isDirtyNow = false;
    readonly dirty = () => this.isDirtyNow;
}

function make(confirmed = true) {
    const confirm = vi.fn(async () => confirmed);
    TestBed.configureTestingModule({
        imports: [Host],
        providers: [{ provide: InspectoConfirmService, useValue: { confirm } }],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const settings = fixture.debugElement.children[0].componentInstance as LinkAnalysisSettingsComponent;
    return { fixture, el, host: fixture.componentInstance, settings, confirm };
}

describe('LinkAnalysisSettingsComponent (⚙ Settings, §5.10)', () => {
    beforeEach(() => localStorage.removeItem('inspecto.la.settings.section'));
    afterEach(() => localStorage.removeItem('inspecto.la.settings.section'));

    it('shows one projected section at a time, keeps the others mounted, and remembers the last', async () => {
        const { fixture, el, host } = make();
        const shown = (id: string) => !(el.querySelector(`[data-testid="${id}"]`)!.parentElement as HTMLElement).hidden;
        expect(shown('gm')).toBe(true);
        expect(shown('et')).toBe(false);
        (el.querySelector('[data-testid="la-settings-nav-entity-types"]') as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(host.section()).toBe('entity-types');
        expect(shown('et')).toBe(true);
        expect(el.querySelector('[data-testid="gm"]')).not.toBeNull(); // hidden, never destroyed
        expect(localStorage.getItem('inspecto.la.settings.section')).toBe('entity-types');
        await expectNoA11yViolations(el);
    });

    it('This Investigation is listed only while one is open', () => {
        const { fixture, el, host } = make();
        expect(el.querySelector('[data-testid="la-settings-nav-investigation"]')).toBeNull();
        host.investigation = true;
        fixture.detectChanges();
        expect(el.querySelector('[data-testid="la-settings-nav-investigation"]')).not.toBeNull();
    });

    it('is really hidden while closed, and closes on the backdrop and on Esc (LA-SETTINGS-OVERLAY-NEVER-HIDES-1)', async () => {
        {
            const { fixture, el, host } = make();
            const overlay = el.querySelector('[data-testid="la-settings"]') as HTMLElement;
            const settle = async () => {
                fixture.detectChanges();
                await fixture.whenStable();
                fixture.detectChanges();
            };
            host.open.set(false);
            await settle();
            // the console shell's Tailwind utilities are !important: `.flex` beats both a bare [hidden] attribute and an
            // inline display (jsdom cascades neither), so closed must drop `flex` and carry `hidden`
            expect(overlay.classList.contains('flex')).toBe(false);
            expect(overlay.classList.contains('hidden')).toBe(true);

            host.open.set(true);
            await settle();
            expect(overlay.classList.contains('flex')).toBe(true);
            expect(overlay.classList.contains('hidden')).toBe(false);
            (el.querySelector('[data-testid="la-settings-close"]') as HTMLElement).click(); // a click inside is not the backdrop
            await settle();
            expect(host.open()).toBe(false);

            host.open.set(true);
            await settle();
            (el.querySelector('[data-testid="gm"]') as HTMLElement).click();
            await settle();
            expect(host.open()).toBe(true); // a click inside the panel does not close
            overlay.click();
            await settle();
            expect(host.open()).toBe(false);

            host.open.set(true);
            await settle();
            document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
            await settle();
            expect(host.open()).toBe(false);
        }
    });

    it('asks before closing over unsaved changes', async () => {
        const declined = make(false);
        declined.host.isDirtyNow = true;
        await declined.settings.requestClose();
        expect(declined.confirm).toHaveBeenCalledTimes(1);
        expect(declined.host.open()).toBe(true);
        declined.host.isDirtyNow = false;
        await declined.settings.requestClose();
        expect(declined.confirm).toHaveBeenCalledTimes(1); // clean: no question
        expect(declined.host.open()).toBe(false);
    });
});
