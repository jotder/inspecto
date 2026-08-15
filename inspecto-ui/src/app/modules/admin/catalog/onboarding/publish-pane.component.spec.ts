import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { InboxStatus, RunsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingPublishPaneComponent } from './publish-pane.component';

const PENDING: InboxStatus = { pipeline: 'x', inbox: 'spaces/demo/data/inbox/orders_feed', pending: 2, running: false };

/**
 * The pane is pure since D2: the stage-model facts it only renders (`lifecycle`, `blockedOn`) are
 * INPUTS — their derivation is pinned in `onboarding-state.service.spec.ts`, and the writes they
 * gate are asserted on what this pane EMITS. The Dataset registration that go-live implies moved to
 * the shell with the activation write, and is covered by `onboarding-shell.component.spec.ts`.
 */
function create(
    config: Record<string, unknown>,
    opts: {
        confirm?: boolean;
        runsApi?: Partial<RunsService>;
        lifecycle?: 'Draft' | 'Ready' | 'Live';
        blockedOn?: string[];
    } = {},
) {
    const confirmFn = vi.fn(() => Promise.resolve(opts.confirm ?? true));
    TestBed.configureTestingModule({
        imports: [OnboardingPublishPaneComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: RunsService, useValue: { pending: vi.fn(() => of(PENDING)), ...opts.runsApi } },
            {
                provide: InspectoConfirmService,
                useValue: { confirm: confirmFn, confirmDestructive: vi.fn(() => Promise.resolve(true)) },
            },
        ],
    });
    const fixture = TestBed.createComponent(OnboardingPublishPaneComponent);
    const name = String(config['name'] ?? '');
    fixture.componentRef.setInput('config', config);
    fixture.componentRef.setInput('kind', config['produces'] === 'reference' ? 'reference' : 'stream');
    fixture.componentRef.setInput('name', name);
    fixture.componentRef.setInput('pipelineId', name);
    fixture.componentRef.setInput('lifecycle', opts.lifecycle ?? (config['active'] === true ? 'Live' : 'Draft'));
    fixture.componentRef.setInput('blockedOn', opts.blockedOn ?? []);
    const applied: Record<string, unknown>[] = [];
    const activeChanges: boolean[] = [];
    const dirty: boolean[] = [];
    fixture.componentInstance.applied.subscribe((v) => applied.push(v));
    fixture.componentInstance.activeChange.subscribe((v) => activeChanges.push(v));
    fixture.componentInstance.dirtyChange.subscribe((v) => dirty.push(v));
    fixture.detectChanges();
    /** What the host does after a successful stage write: hand the advanced draft back. */
    const hostSaved = (next: Record<string, unknown>): void => {
        fixture.componentRef.setInput('config', next);
        fixture.componentRef.setInput('lifecycle', next['active'] === true ? 'Live' : 'Ready');
        fixture.detectChanges();
    };
    return { fixture, applied, activeChanges, dirty, confirmFn, hostSaved };
}

const READY_CONFIG = {
    name: 'orders_feed',
    active: false,
    collector: { connector: 'local' },
    parsing: { frontend: 'delimited' },
    processing: { schema_file: 'x_schema.toon' },
    output: { format: 'PARQUET' },
};

describe('OnboardingPublishPaneComponent', () => {
    beforeEach(() => localStorage.removeItem('inspecto.currentLens'));

    it('emits the output block with the schema-form defaults instead of writing it', () => {
        const { fixture, applied } = create({ name: 'x' });
        fixture.componentInstance.save();
        // W4a: the Parquet suggestion now rides `initial` (new drafts only); the shared
        // OUTPUT_ATTRIBUTES table's own default is the engine truth (CSV).
        expect(applied).toHaveLength(1);
        expect(applied[0]['format']).toBe('PARQUET');
    });

    it('a resumed output block renders verbatim — the Parquet suggestion is for new drafts only', () => {
        const { fixture, applied } = create({ name: 'x', output: { format: 'CSV' } });
        fixture.componentInstance.save();
        expect(applied[0]['format']).toBe('CSV');
    });

    it('names the other incomplete required stages the host reports as blocking', () => {
        const { fixture } = create({ name: 'x' }, { blockedOn: ['Collection', 'Parsing', 'Schema & Mapping'] });
        expect(fixture.nativeElement.textContent).toContain('Collection, Parsing, Schema & Mapping');
    });

    it('asks to save output (not the other stages) when only the output block is missing', () => {
        const { fixture } = create({ ...READY_CONFIG, output: undefined }, { lifecycle: 'Draft', blockedOn: [] });
        expect(fixture.nativeElement.textContent).toContain('Save the output settings above');
    });

    it('activate confirms, then emits the go-live flag', async () => {
        const { fixture, activeChanges, confirmFn } = create(READY_CONFIG, { lifecycle: 'Ready' });
        await fixture.componentInstance.activate();
        expect(confirmFn).toHaveBeenCalled();
        expect(activeChanges).toEqual([true]);
    });

    it('declining the confirm emits nothing', async () => {
        const { fixture, activeChanges } = create(READY_CONFIG, { lifecycle: 'Ready', confirm: false });
        await fixture.componentInstance.activate();
        expect(activeChanges).toEqual([]);
    });

    it('refuses to emit go-live while the host still reports the draft as not Ready', async () => {
        const { fixture, activeChanges } = create(READY_CONFIG, { lifecycle: 'Draft' });
        await fixture.componentInstance.activate();
        expect(activeChanges).toEqual([]);
    });

    it('shows the inbox activity glance once live', () => {
        const { fixture } = create({ ...READY_CONFIG, active: true });
        expect(fixture.componentInstance.activity()).toEqual(PENDING);
        expect(fixture.nativeElement.textContent).toContain('pending in spaces/demo');
    });

    it('take-offline confirms and emits false — the only way off a live pipeline', async () => {
        const { fixture, activeChanges, confirmFn, hostSaved } = create({ ...READY_CONFIG, active: true });
        await fixture.componentInstance.deactivate();
        expect(confirmFn).toHaveBeenCalled();
        expect(activeChanges).toEqual([false]);

        // The glance is meaningless once offline — cleared when the host's draft comes back inactive.
        hostSaved({ ...READY_CONFIG, active: false });
        expect(fixture.componentInstance.activity()).toBeNull();
    });

    it('declining the take-offline confirm emits nothing', async () => {
        const { fixture, activeChanges } = create({ ...READY_CONFIG, active: true }, { confirm: false });
        await fixture.componentInstance.deactivate();
        expect(activeChanges).toEqual([]);
    });

    it('reads the activity glance when the host reports the draft went live', () => {
        const { fixture, hostSaved } = create(READY_CONFIG, { lifecycle: 'Ready' });
        expect(fixture.componentInstance.activity()).toBeNull(); // not live yet — nothing to glance at
        hostSaved({ ...READY_CONFIG, active: true });
        expect(fixture.componentInstance.activity()).toEqual(PENDING);
    });

    it('re-seeding the config input returns the pane to pristine; a failed save leaves it dirty', () => {
        const { fixture, dirty, hostSaved } = create({ name: 'x' });
        fixture.componentInstance.schemaForm.form.get('format')?.setValue('CSV');
        fixture.componentInstance.schemaForm.form.markAsDirty();
        fixture.componentInstance.onInteraction();
        expect(dirty).toEqual([true]);

        // A FAILED save never advances the host's config — nothing re-seeds, the guard still fires.
        fixture.detectChanges();
        expect(dirty).toEqual([true]);

        hostSaved({ name: 'x', output: { format: 'CSV' } });
        expect(dirty).toEqual([true, false]);
    });

    it('offers Take offline while live', () => {
        const { fixture } = create({ ...READY_CONFIG, active: true });
        expect(fixture.nativeElement.textContent).toContain('Take offline');
    });

    // Pairs with the test above: ready-but-not-live must offer Go live INSTEAD, or that one is vacuous.
    it('offers Go live, not Take offline, before activation', () => {
        const { fixture } = create(READY_CONFIG, { lifecycle: 'Ready' });
        expect(fixture.nativeElement.textContent).not.toContain('Take offline');
        expect(fixture.nativeElement.textContent).toContain('Go live');
    });

    it('warns a live author that stage edits take effect immediately', () => {
        const { fixture } = create({ ...READY_CONFIG, active: true });
        expect(fixture.nativeElement.textContent).toContain('every stage edit takes effect immediately');
    });

    it('tells a Reference author the dataset becomes bindable by name', () => {
        const { fixture } = create({ ...READY_CONFIG, produces: 'reference' });
        expect(fixture.nativeElement.textContent).toContain('Reference Dataset');
        expect(fixture.nativeElement.textContent).toContain('ref: orders_feed');
    });

    it('does not emit at all without the workbench lens', () => {
        const { fixture, applied } = create({ name: 'x' });
        vi.spyOn(fixture.componentInstance['lens'], 'canAuthorWorkbench').mockReturnValue(false);
        fixture.componentInstance.save();
        expect(applied).toHaveLength(0);
    });

    it('has no a11y violations', async () => {
        const { fixture } = create({ name: 'x' });
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
