import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatDialog, MatDialogRef } from '@angular/material/dialog';
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
        <p laSection="modules" data-testid="mods">modules</p>
        <form laSection="identities" data-testid="form" (submit)="$event.preventDefault(); submits = submits + 1">
            <button type="submit" data-testid="submit">Assert</button>
        </form>
    </inspecto-la-settings>`,
})
class Host {
    submits = 0;
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

    it('operator 2026-10-29: Installed modules is a section under This Studio, last in the nav, always listed', async () => {
        const { fixture, el, host } = make();
        const nav = el.querySelector('[data-testid="la-settings-nav-modules"]') as HTMLButtonElement;
        expect(nav.textContent!.trim()).toBe('Installed modules');
        const groups = Array.from(el.querySelectorAll('nav[aria-label="Settings sections"] h3')).map((h) => h.textContent!.trim());
        expect(groups.at(-1)).toBe('This Studio');
        nav.click();
        fixture.detectChanges();
        expect(host.section()).toBe('modules');
        expect((el.querySelector('[data-testid="mods"]')!.parentElement as HTMLElement).hidden).toBe(false);
        expect(el.querySelector('[data-testid="la-settings-help"]')!.textContent).toContain('Link Analysis modules on this server');
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

    it('a submit button inside a section still submits: the backdrop listener never cancels a click inside the panel', () => {
        const { fixture, el, host } = make();
        host.section.set('identities');
        fixture.detectChanges();
        const click = new MouseEvent('click', { bubbles: true, cancelable: true });
        (el.querySelector('[data-testid="submit"]') as HTMLButtonElement).dispatchEvent(click);
        expect(click.defaultPrevented).toBe(false);
        expect(host.submits).toBe(1);
        expect(host.open()).toBe(true);
    });

    it('ignores an Esc another overlay handled, or one fired while a dialog is open (LA-SETTINGS-ESC-REOPENS-CONFIRM-1)', async () => {
        const { host, settings, confirm } = make(false);
        host.isDirtyNow = true;
        const handled = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
        handled.preventDefault(); // as the confirm dialog or a mat-select panel does when Esc closes it
        document.dispatchEvent(handled);
        await Promise.resolve();
        expect(confirm).not.toHaveBeenCalled();
        const dialogs = TestBed.inject(MatDialog);
        vi.spyOn(dialogs, 'openDialogs', 'get').mockReturnValue([{} as MatDialogRef<unknown>]);
        document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
        await Promise.resolve();
        expect(confirm).not.toHaveBeenCalled();
        expect(host.open()).toBe(true);
        vi.restoreAllMocks();
        settings.onEscape(new KeyboardEvent('keydown', { key: 'Escape' }));
        expect(confirm).toHaveBeenCalledTimes(1); // a plain Esc still asks
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
