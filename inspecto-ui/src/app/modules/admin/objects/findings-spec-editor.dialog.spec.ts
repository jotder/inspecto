import { signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { ToastrService } from 'ngx-toastr';
import { EMPTY, Observable, of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentDef, ComponentsService, FindingsSpecDef, LensService, ObjectsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { FindingsSpecEditorDialog } from './findings-spec-editor.dialog';

const BUILT_IN: FindingsSpecDef = {
    objectType: 'case',
    sections: [
        {
            key: 'disposition',
            label: 'Disposition',
            type: 'select',
            tier: 'required',
            required: false,
            options: [
                { value: 'CONFIRMED', label: 'Confirmed' },
                { value: 'FALSE_POSITIVE', label: 'False positive' },
                { value: 'RECOVERED', label: 'Recovered' },
            ],
        },
        { key: 'impactAmount', label: 'Impact amount', type: 'string', tier: 'required', required: false },
        { key: 'recordsAffected', label: 'Records affected', type: 'string', tier: 'required', required: false },
        { key: 'summary', label: 'Summary', type: 'multiline', tier: 'required', required: false },
    ],
};

const AUTHORED: ComponentDef = {
    type: 'findings-spec',
    name: 'case',
    ref: 'findings-spec/case',
    content: { name: 'case', objectType: 'case', sections: BUILT_IN.sections },
    contentHash: 'abc123',
};

const notFound = (): Observable<never> => throwError(() => new HttpErrorResponse({ status: 404 }));

interface Opts {
    authored?: ComponentDef | null;
    canEdit?: boolean;
    confirm?: boolean;
    create?: () => Observable<ComponentDef>;
    update?: () => Observable<ComponentDef>;
    list?: ComponentDef[];
    spec?: FindingsSpecDef;
}

async function create(opts: Opts = {}) {
    const ref = { close: vi.fn(), keydownEvents: () => EMPTY, backdropClick: () => EMPTY, disableClose: false };
    const components = {
        get: vi.fn(() => (opts.authored ? of(opts.authored) : notFound())),
        create: vi.fn(opts.create ?? (() => of(AUTHORED))),
        update: vi.fn(opts.update ?? (() => of(AUTHORED))),
        remove: vi.fn(() => of({})),
        list: vi.fn(() => of(opts.list ?? [])),
    };
    const objects = { findingsSpec: vi.fn(() => of(opts.spec ?? BUILT_IN)) };
    const confirm = { confirmDestructive: vi.fn(async () => opts.confirm ?? true), confirm: vi.fn(async () => true) };
    const toastr = { success: vi.fn(), error: vi.fn() };
    TestBed.configureTestingModule({
        imports: [FindingsSpecEditorDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MAT_DIALOG_DATA, useValue: { objectType: 'CASE', typeLabel: 'Case' } },
            { provide: MatDialogRef, useValue: ref },
            { provide: ObjectsService, useValue: objects },
            { provide: ComponentsService, useValue: components },
            { provide: LensService, useValue: { canManageIncidents: signal(opts.canEdit ?? true) } },
            { provide: InspectoConfirmService, useValue: confirm },
            { provide: ToastrService, useValue: toastr },
        ],
    });
    const fixture = TestBed.createComponent(FindingsSpecEditorDialog);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const c = fixture.componentInstance;
    const render = async (): Promise<void> => {
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    };
    const saveButton = (): HTMLButtonElement | undefined =>
        Array.from(el.ownerDocument.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Save') as
            | HTMLButtonElement
            | undefined;
    return { fixture, el, c, ref, objects, components, confirm, toastr, render, saveButton };
}

describe('FindingsSpecEditorDialog', () => {
    it('opens on the built-in fields, badged Built-in, with the real form as the preview', async () => {
        const { el, c } = await create();
        expect(el.textContent).toContain('Built-in');
        expect(c.fields().map((f) => f.label)).toEqual(['Disposition', 'Impact amount', 'Records affected', 'Summary']);
        // The preview is the shipped <inspecto-schema-form>, fed the draft.
        expect(el.querySelector('inspecto-schema-form')).not.toBeNull();
        expect(c.previewSpecs().map((s) => s.key)).toEqual([
            'disposition',
            'impactAmount',
            'recordsAffected',
            'summary',
        ]);
        await expectNoA11yViolations(el);
    });

    it('badges an authored spec Customised', async () => {
        const { el } = await create({ authored: AUTHORED });
        expect(el.textContent).toContain('Customised');
    });

    it('is view-only without canManageIncidents: no Save, no Add, inputs disabled', async () => {
        const { el, c, saveButton } = await create({ canEdit: false });
        expect(saveButton()).toBeUndefined();
        expect(el.textContent).toContain('View only');
        expect(el.textContent).not.toContain('Add field');
        expect(c.fieldForm.disabled).toBe(true);
        await expectNoA11yViolations(el);
    });

    it('editing the built-in POSTs a new component; the saved key survives a rename', async () => {
        const { c, components, ref, render, saveButton } = await create();
        c.fieldForm.controls.label.setValue('Outcome');
        await render();
        expect(saveButton()!.disabled).toBe(false);
        c.save();
        expect(components.create).toHaveBeenCalledTimes(1);
        expect(components.update).not.toHaveBeenCalled();
        const [kind, sent] = components.create.mock.calls[0] as unknown as [
            string,
            { sections: { key: string; label: string }[] },
        ];
        expect(kind).toBe('findings-spec');
        expect(sent.sections[0]).toMatchObject({ key: 'disposition', label: 'Outcome' });
        expect(ref.close).toHaveBeenCalledWith(true);
    });

    it('editing an authored spec PUTs with If-Match from its contentHash', async () => {
        const { c, components } = await create({ authored: AUTHORED });
        c.fieldForm.controls.help.setValue('Pick the final call');
        c.save();
        expect(components.update).toHaveBeenCalledTimes(1);
        const call = components.update.mock.calls[0] as unknown as [string, string, unknown, { ifMatch: string }];
        expect(call[0]).toBe('findings-spec');
        expect(call[1]).toBe('case');
        expect(call[3]).toEqual({ ifMatch: 'abc123' });
    });

    it('a 409 shows the someone-else-changed alert and never closes or overwrites', async () => {
        const { c, el, ref, render } = await create({
            authored: AUTHORED,
            update: () => throwError(() => new HttpErrorResponse({ status: 409 })),
        });
        c.fieldForm.controls.label.setValue('Outcome');
        c.save();
        await render();
        expect(c.conflict()).toBe(true);
        expect(el.textContent).toContain('Someone else changed these fields');
        expect(ref.close).not.toHaveBeenCalled();
    });

    it("shows a server 422's message verbatim", async () => {
        const { c, el, render } = await create({
            create: () =>
                throwError(
                    () =>
                        new HttpErrorResponse({
                            status: 422,
                            error: { error: { message: "section 'x' has min > max" } },
                        }),
                ),
        });
        c.fieldForm.controls.label.setValue('Outcome');
        c.save();
        await render();
        expect(el.textContent).toContain("section 'x' has min > max");
    });

    it('a new field gets a derived key; Save stays disabled while it has problems, listed in plain words', async () => {
        const { c, el, render, saveButton } = await create();
        c.addField();
        c.fieldForm.controls.label.setValue('Root cause category');
        c.fieldForm.controls.type.setValue('select');
        await render();
        expect(el.textContent).toContain('“Root cause category” needs at least one choice.');
        expect(saveButton()!.disabled).toBe(true);
        await expectNoA11yViolations(el);

        c.addChoice();
        c.choiceControls.at(0).setValue('Card not present');
        await render();
        expect(c.problems()).toEqual([]);
        expect(saveButton()!.disabled).toBe(false);
        c.showTechnical.set(true);
        await render();
        expect(el.textContent).toContain('Will be saved as rootCauseCategory');
        expect(el.textContent).toContain('CARD_NOT_PRESENT');
    });

    /**
     * Negative with a probe that would otherwise succeed: `Summary` sits BELOW `Records affected` and would be a
     * legal forward reference to the server — it must not be offered.
     */
    it('"Show only when" offers only the fields above, and writes a dependsOn', async () => {
        const { c, el, components, render } = await create();
        const records = c.fields()[2];
        c.select(records.uid);
        c.fieldForm.controls.showWhenOn.setValue(true);
        await render();
        const offered = c.targetOptions(records.uid).map((o) => o.label);
        expect(offered).toEqual(['Disposition', 'Impact amount']);
        expect(offered).not.toContain('Summary');

        const disposition = c.fields()[0];
        c.fieldForm.controls.showWhenTarget.setValue(disposition.uid);
        await render();
        expect(c.valueOptions(disposition.uid)!.map((o) => o.label)).toEqual([
            'Confirmed',
            'False positive',
            'Recovered',
        ]);
        c.fieldForm.controls.showWhenValue.setValue(disposition.choices[2].uid);
        await render();
        await expectNoA11yViolations(el);
        c.save();
        const sent = (
            components.create.mock.calls[0] as unknown as [string, { sections: Record<string, unknown>[] }]
        )[1];
        expect(sent.sections[2]['dependsOn']).toEqual({ key: 'disposition', equals: 'RECOVERED' });
    });

    it('removing Records affected warns that Case analytics stops receiving it (D7 generic warning)', async () => {
        const { c, confirm } = await create();
        await c.removeField(c.fields()[2]);
        const message = (confirm.confirmDestructive.mock.calls[0] as unknown as [string])[0];
        expect(message).toContain('keep the value, but it will no longer be shown');
        expect(message).toContain('Case analytics');
        expect(c.fields().map((f) => f.label)).not.toContain('Records affected');
    });

    /** WS-10: a Case's money is its typed impact, so an `impactAmount` Findings field feeds no analytics. */
    it('removing an Impact amount field no longer claims Case analytics reads it', async () => {
        const { c, confirm } = await create();
        await c.removeField(c.fields()[1]);
        const message = (confirm.confirmDestructive.mock.calls[0] as unknown as [string])[0];
        expect(message).not.toContain('Case analytics');
    });

    /** FINDINGS-EDITOR-PER-CASE-TYPE-1 (operator, 2026-10-06): the Case's Impact is a built-in, renameable field. */
    it('locks the built-in Impact field except for its label, and never removes it', async () => {
        const spec: FindingsSpecDef = {
            ...BUILT_IN,
            sections: [
                ...BUILT_IN.sections,
                { key: 'impact', label: 'Confirmed impact', type: 'number', tier: 'required', required: false },
            ],
        };
        const { el, c, components, render } = await create({ spec });
        const impact = c.fields().find((f) => f.key === 'impact')!;
        c.select(impact.uid);
        await render();
        expect(el.textContent).toContain("Built-in: the Case's Impact");
        expect(c.fieldForm.controls.label.enabled).toBe(true);
        for (const name of ['type', 'tier', 'required', 'help', 'min', 'max', 'showWhenOn'] as const)
            expect(c.fieldForm.controls[name].disabled, name).toBe(true);
        expect(el.querySelector('[aria-label="Remove Confirmed impact"]')).toBeNull();
        expect(el.querySelector('[aria-label="Remove Summary"]')).not.toBeNull();
        await c.removeField(impact);
        expect(c.fields().some((f) => f.key === 'impact')).toBe(true);

        c.fieldForm.controls.label.setValue('Confirmed loss');
        await render();
        c.save();
        const sent = (
            components.create.mock.calls[0] as unknown as [string, { sections: Record<string, unknown>[] }]
        )[1];
        expect(sent.sections.find((s) => s['key'] === 'impact')).toEqual({
            key: 'impact',
            label: 'Confirmed loss',
            type: 'number',
            tier: 'required',
            required: false,
        });
        await expectNoA11yViolations(el);
    });

    it('keeps a field when the removal is declined', async () => {
        const { c } = await create({ confirm: false });
        await c.removeField(c.fields()[3]);
        expect(c.fields()).toHaveLength(4);
    });

    it('Restore built-in asks first, then DELETEs the component', async () => {
        const declined = await create({ authored: AUTHORED, confirm: false });
        await declined.c.restoreBuiltIn();
        expect(declined.confirm.confirmDestructive).toHaveBeenCalledTimes(1);
        expect(declined.components.remove).not.toHaveBeenCalled();

        declined.confirm.confirmDestructive.mockImplementation(async () => true);
        await declined.c.restoreBuiltIn();
        expect(declined.components.remove).toHaveBeenCalledWith('findings-spec', 'case');
        expect(declined.ref.close).toHaveBeenCalledWith(true);
    });

    it('guards an unsaved draft on close', async () => {
        const { c, confirm, ref } = await create({ confirm: false });
        await c.requestClose();
        expect(confirm.confirmDestructive).not.toHaveBeenCalled(); // pristine closes at once
        expect(ref.close).toHaveBeenCalledTimes(1);

        c.fieldForm.controls.label.setValue('Outcome');
        await c.requestClose();
        expect(confirm.confirmDestructive).toHaveBeenCalledTimes(1);
        expect(ref.close).toHaveBeenCalledTimes(1); // declined — still open
    });

    /**
     * FINDINGS-EDITOR-CHOICE-BINDING-1 + FINDINGS-EDITOR-ADD-CHOICE-FOCUS-1, driven through the DOM like the proxy
     * lead: text typed into a choice box that was already on screen before a later *Add choice* must be kept.
     */
    it('keeps what is typed into an earlier choice box after a later Add choice, and focuses each new box', async () => {
        const { c, el, render, saveButton } = await create();
        c.addField();
        c.fieldForm.controls.label.setValue('Recovery route');
        c.fieldForm.controls.type.setValue('select');
        await render();
        const addChoice = (): HTMLButtonElement =>
            Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Add choice')!;
        const boxes = (): HTMLInputElement[] => Array.from(el.querySelectorAll('fieldset input')) as HTMLInputElement[];
        const type = async (box: HTMLInputElement, text: string): Promise<void> => {
            box.value = text;
            box.dispatchEvent(new Event('input'));
            await render();
        };

        addChoice().click();
        await render();
        expect(el.ownerDocument.activeElement).toBe(boxes()[0]);
        await type(boxes()[0], 'bar');

        addChoice().click();
        await render();
        expect(el.ownerDocument.activeElement).toBe(boxes()[1]);
        await type(boxes()[1], 'refund');

        await type(boxes()[0], 'bar sim');

        expect(c.selected()!.choices.map((x) => x.label)).toEqual(['bar sim', 'refund']);
        expect(c.problems()).toEqual([]);
        expect(saveButton()!.disabled).toBe(false);
        await expectNoA11yViolations(el);
    });

    it('keeps what the preview holds across a draft edit', async () => {
        const { c, fixture, render } = await create();
        await render();
        const preview = fixture.debugElement.query((d) => d.name === 'inspecto-schema-form').componentInstance as {
            form: { patchValue(v: Record<string, unknown>): void };
            value(): Record<string, unknown>;
        };
        preview.form.patchValue({ summary: 'ring of 4 cards' });
        c.fieldForm.controls.label.setValue('Outcome');
        await render();
        expect(preview.value()['summary']).toBe('ring of 4 cards');
    });
    it('names the "Where it appears" radios by what they mean, not by their stored values', async () => {
        const { el } = await create();
        const radios = Array.from(el.querySelectorAll<HTMLInputElement>('mat-radio-group input[type="radio"]'));
        // An explicit name on the input itself, so no reader falls back to the stored value.
        const names = radios.map((r) => r.getAttribute('aria-label'));
        expect(names).toEqual(['Always shown', 'Under “More”']);
        expect(names).not.toContain('required');
        expect(names).not.toContain('optional');
        await expectNoA11yViolations(el);
    });

    it('the "Show only when" condition picker shows "is" in full, with room for it', async () => {
        const { c, el, render } = await create();
        c.select(c.fields()[2].uid);
        c.fieldForm.controls.showWhenOn.setValue(true);
        await render();
        const picker = Array.from(el.querySelectorAll('inspecto-option-picker')).find((p) =>
            p.textContent?.includes('Condition'),
        ) as HTMLElement;
        expect(picker.querySelector('button span')?.textContent?.trim()).toBe('is');
        // `w-28` squeezed the value beside its label into "i.".
        expect(picker.classList).not.toContain('w-28');
        expect(picker.classList).toContain('min-w-40');
    });

    it('collapses "Technical details" again when another field is selected', async () => {
        const { c, el, render } = await create();
        c.select(c.fields()[0].uid);
        await render();
        const toggle = (): HTMLButtonElement =>
            Array.from(el.querySelectorAll('button')).find((b) =>
                b.textContent?.includes('Technical details'),
            ) as HTMLButtonElement;
        toggle().click();
        await render();
        expect(toggle().getAttribute('aria-expanded')).toBe('true');
        c.select(c.fields()[0].uid);
        await render();
        expect(toggle().getAttribute('aria-expanded')).toBe('true');
        c.select(c.fields()[1].uid);
        await render();
        expect(toggle().getAttribute('aria-expanded')).toBe('false');
        expect(el.querySelector('#ff-technical')).toBeNull();
    });

    describe('per-Case-type forms (FINDINGS-EDITOR-PER-CASE-TYPE-1)', () => {
        const SIMBOX: ComponentDef = { ...AUTHORED, name: 'case.sim-box', ref: 'findings-spec/case.sim-box' };

        it('says the shared form applies only to Cases without their own, and lists existing types', async () => {
            const { el, c } = await create({ list: [AUTHORED, SIMBOX] });
            expect(el.textContent).toContain('every Case without its own form');
            expect(el.textContent).not.toContain('applies to every Case.');
            expect(c.caseTypes()).toEqual(['sim-box']);
            await expectNoA11yViolations(el);
        });

        it('picking a Case type loads and saves component case.<type>', async () => {
            const { c, objects, components, render } = await create({ list: [AUTHORED, SIMBOX], authored: SIMBOX });
            c.targetCtrl.setValue('sim-box');
            await render();
            expect(objects.findingsSpec).toHaveBeenLastCalledWith('case.sim-box');
            expect(components.get).toHaveBeenLastCalledWith('findings-spec', 'case.sim-box');
            c.addField();
            c.save();
            expect(components.update).toHaveBeenCalledWith(
                'findings-spec',
                'case.sim-box',
                expect.objectContaining({ name: 'case.sim-box', objectType: 'case', caseType: 'sim-box' }),
                { ifMatch: 'abc123' },
            );
        });

        it('a new Case type is created from the shared form; an illegal name is refused', async () => {
            const { el, c, components, render } = await create();
            c.targetCtrl.setValue('#new');
            await render();
            c.newTypeCtrl.setValue('bad type');
            await c.openNewType();
            await render();
            expect(c.caseType()).toBeNull();
            expect(el.textContent).toContain('Letters, digits');
            c.newTypeCtrl.setValue('roaming');
            await c.openNewType();
            await render();
            expect(c.caseType()).toBe('roaming');
            expect(el.textContent).toContain('No own form yet');
            c.addField();
            c.save();
            expect(components.create).toHaveBeenCalledWith(
                'findings-spec',
                expect.objectContaining({ name: 'case.roaming', caseType: 'roaming' }),
            );
        });

        it('keeps the draft when the lead declines to discard it on switch', async () => {
            const { c } = await create({ list: [SIMBOX], confirm: false });
            c.addField();
            c.targetCtrl.setValue('sim-box');
            await Promise.resolve();
            await Promise.resolve();
            expect(c.caseType()).toBeNull();
            expect(c.targetCtrl.value).toBe('');
            expect(c.dirty()).toBe(true);
        });
    });
});
