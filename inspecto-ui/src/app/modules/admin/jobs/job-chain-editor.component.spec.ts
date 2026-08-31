import { describe, expect, it } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JobChainEditorComponent } from './job-chain-editor.component';

/** One `configureTestingModule` per test (house rule) — this helper is called exactly once per `it`. */
function make(): { fixture: ComponentFixture<JobChainEditorComponent>; c: JobChainEditorComponent } {
    TestBed.configureTestingModule({
        imports: [JobChainEditorComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(JobChainEditorComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const ids = (c: JobChainEditorComponent): string[] => c.rows.controls.map((g) => String(g.controls.id.value));

describe('JobChainEditorComponent', () => {
    it('seeds one row per chain step, each with its own config', () => {
        const { fixture, c } = make();
        expect(c.seed('mask,rollup', [{ config: { columns: 'a' } }, { config: { by: 'day' } }])).toBe(true);
        fixture.detectChanges();
        expect(ids(c)).toEqual(['mask', 'rollup']);
        expect(JSON.parse(String(c.rows.at(1).controls.configText.value))).toEqual({ by: 'day' });
        expect(c.validate()).toBe(true);
    });

    it('refuses to seed a chain_config it cannot represent, so the host keeps the raw fields', () => {
        const { c } = make();
        expect(c.seed('mask', 'not json')).toBe(false);
        expect(c.rows.length).toBe(0);
    });

    it('🔴 shows the error for a surplus config row IMMEDIATELY, without the field being visited', async () => {
        const { fixture, c } = make();
        c.seed('mask', [{ config: { columns: 'a' } }, { config: { by: 'day' } }]);
        fixture.detectChanges();
        expect(c.rows.length).toBe(2);
        expect(c.recovered()).toBe(1);
        // The rendered element, not just the control state: a mat-error on an untouched control renders
        // nothing, which is how a save can refuse with an empty screen.
        const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(errors.some((t) => t?.includes('needs the id of a ConsignmentProcessor'))).toBe(true);
        const alert = fixture.nativeElement.querySelector('inspecto-alert');
        expect(alert?.textContent).toContain('had no matching processor');
        expect(c.validate()).toBe(false);
    });

    it('emits the two params aligned, and keeps an unmodelled element key', () => {
        const { fixture, c } = make();
        c.seed('mask,rollup', [{ config: {}, note: 'hand-authored' }, { config: { by: 'day' } }]);
        fixture.detectChanges();
        const out = c.value();
        expect(out.processor).toBe('mask,rollup');
        expect(out.chain_config[0]).toEqual({ note: 'hand-authored', config: {} });
    });

    it('reorders a step and the emitted chain follows', () => {
        const { fixture, c } = make();
        c.seed('mask,rollup,report', []);
        fixture.detectChanges();
        c.move(2, -1);
        fixture.detectChanges();
        expect(ids(c)).toEqual(['mask', 'report', 'rollup']);
        expect(c.value().processor).toBe('mask,report,rollup');
    });

    it('will not move past either end', () => {
        const { fixture, c } = make();
        c.seed('a,b', []);
        fixture.detectChanges();
        c.move(0, -1);
        c.move(1, 1);
        expect(ids(c)).toEqual(['a', 'b']);
    });

    it('a reorder carries each step config with its step', () => {
        const { fixture, c } = make();
        c.seed('mask,rollup', [{ config: { first: true } }, { config: { second: true } }]);
        fixture.detectChanges();
        c.move(0, 1);
        const out = c.value();
        expect(out.processor).toBe('rollup,mask');
        expect(out.chain_config).toEqual([{ config: { second: true } }, { config: { first: true } }]);
    });

    it('adds and removes steps', () => {
        const { fixture, c } = make();
        c.seed('mask', []);
        fixture.detectChanges();
        c.addRow();
        expect(c.rows.length).toBe(2);
        expect(c.validate()).toBe(false); // the new row has no id yet
        c.rows.at(1).controls.id.setValue('rollup');
        expect(c.validate()).toBe(true);
        c.remove(0);
        expect(ids(c)).toEqual(['rollup']);
    });

    it('refuses a comma inside an id — it would split one step into two', () => {
        const { fixture, c } = make();
        c.seed('mask', []);
        fixture.detectChanges();
        c.rows.at(0).controls.id.setValue('mask,rollup');
        expect(c.validate()).toBe(false);
        fixture.detectChanges();
        const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(errors.some((t) => t?.includes('cannot contain a comma'))).toBe(true);
    });

    it('refuses a config that is not a JSON object, and renders why', () => {
        const { fixture, c } = make();
        c.seed('mask', []);
        fixture.detectChanges();
        c.rows.at(0).controls.configText.setValue('[1]');
        expect(c.validate()).toBe(false);
        fixture.detectChanges();
        const errors = Array.from(fixture.nativeElement.querySelectorAll('mat-error')).map((e) =>
            (e as HTMLElement).textContent?.trim(),
        );
        expect(errors.some((t) => t?.includes('must be a JSON object'))).toBe(true);
    });

    it('renders an empty state before any step exists', () => {
        const { fixture } = make();
        expect(fixture.nativeElement.textContent).toContain('No steps yet');
    });

    it('has no a11y violations with a seeded chain', async () => {
        const { fixture, c } = make();
        c.seed('mask,rollup', [{ config: { columns: 'a' } }, { config: {} }]);
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
