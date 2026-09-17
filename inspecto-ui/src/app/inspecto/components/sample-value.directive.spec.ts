import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { MatInputModule } from '@angular/material/input';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it } from 'vitest';
import { extractSampleValue, InspectoSampleValueDirective } from './sample-value.directive';

@Component({
    standalone: true,
    imports: [ReactiveFormsModule, MatInputModule, InspectoSampleValueDirective],
    template: `
        <form [formGroup]="form">
            <input id="prefixed-input" matInput formControlName="channel" placeholder="e.g. ops_email" />
            <input
                id="parentheses-input"
                matInput
                formControlName="job"
                placeholder="e.g. cdr_ingest (comma-separate for several)"
            />
            <input
                id="alternatives-input"
                matInput
                formControlName="event"
                placeholder="e.g. BATCH_FAILED or job.custom"
            />
            <input id="literal-input" matInput formControlName="email" placeholder="ops@example.com" />
            <input id="instructional-input" matInput formControlName="search" placeholder="Search By MSISDN" />
            <input id="ellipsis-input" matInput formControlName="filter" placeholder="Filter pipelines…" />
            <input id="number-input" matInput type="number" formControlName="count" placeholder="42" />
            <textarea
                id="textarea-input"
                matInput
                formControlName="notes"
                placeholder="e.g. nightly sync batch"
            ></textarea>
            <input
                id="override-input"
                matInput
                formControlName="custom"
                placeholder="e.g. ignored"
                [inspectoSampleValue]="'custom-value'"
            />
            <input id="plain-input" inspectoSampleValue formControlName="plain" placeholder="e.g. plain_sample" />
            <input
                id="draft-input"
                matInput
                [value]="draftValue"
                placeholder="e.g. draft_sample"
                (input)="draftValue = $any($event.target).value"
            />
        </form>
    `,
})
class TestFormComponent {
    draftValue = '';
    form = new FormGroup({
        channel: new FormControl(''),
        job: new FormControl(''),
        event: new FormControl(''),
        email: new FormControl(''),
        search: new FormControl(''),
        filter: new FormControl(''),
        count: new FormControl<number | null>(null),
        notes: new FormControl(''),
        custom: new FormControl(''),
        plain: new FormControl(''),
    });
}

describe('extractSampleValue', () => {
    it('extracts sample from e.g. prefixes', () => {
        expect(extractSampleValue('e.g. ops_email')).toBe('ops_email');
        expect(extractSampleValue('eg: retail_orders')).toBe('retail_orders');
        expect(extractSampleValue('example: orders_rollup')).toBe('orders_rollup');
        expect(extractSampleValue('ex: 123')).toBe('123');
    });

    it('strips trailing parenthetical remarks', () => {
        expect(extractSampleValue('e.g. cdr_ingest (comma-separate for several)')).toBe('cdr_ingest');
        expect(extractSampleValue('e.g. daily_export (optional)')).toBe('daily_export');
    });

    it('picks the first option when alternatives are listed with or', () => {
        expect(extractSampleValue('e.g. BATCH_FAILED or job.custom')).toBe('BATCH_FAILED');
    });

    it('accepts clean literal values', () => {
        expect(extractSampleValue('ops@example.com')).toBe('ops@example.com');
        expect(extractSampleValue('0 0 6 * * *')).toBe('0 0 6 * * *');
        expect(extractSampleValue('2026-08-06')).toBe('2026-08-06');
        expect(extractSampleValue('ROUND(amount * 100)')).toBe('ROUND(amount * 100)');
        expect(extractSampleValue('42')).toBe('42');
    });

    it('rejects instructions and search prompts', () => {
        expect(extractSampleValue('Search By MSISDN')).toBeNull();
        expect(extractSampleValue('Search pipelines…')).toBeNull();
        expect(extractSampleValue('Find a field…')).toBeNull();
        expect(extractSampleValue('Filter items…')).toBeNull();
        expect(extractSampleValue('Choose a format')).toBeNull();
        expect(extractSampleValue('leave blank to delete')).toBeNull();
        expect(extractSampleValue("Defaults to the widget's name")).toBeNull();
        expect(extractSampleValue('when …')).toBeNull();
        expect(extractSampleValue('(optional)')).toBeNull();
        expect(extractSampleValue('')).toBeNull();
        expect(extractSampleValue('   ')).toBeNull();
        expect(extractSampleValue(null)).toBeNull();
        expect(extractSampleValue(undefined)).toBeNull();
    });
});

describe('InspectoSampleValueDirective', () => {
    let fixture: ComponentFixture<TestFormComponent>;
    let component: TestFormComponent;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [TestFormComponent],
            providers: [provideNoopAnimations()],
        }).compileComponents();

        fixture = TestBed.createComponent(TestFormComponent);
        component = fixture.componentInstance;
        fixture.detectChanges();
    });

    function dispatchArrowRight(
        el: HTMLInputElement | HTMLTextAreaElement,
        opts: Partial<KeyboardEventInit> = {},
    ): void {
        el.dispatchEvent(
            new KeyboardEvent('keydown', {
                key: 'ArrowRight',
                bubbles: true,
                cancelable: true,
                ...opts,
            }),
        );
    }

    it('populates sample value on Right Arrow key when input is empty', () => {
        const input = fixture.nativeElement.querySelector('#prefixed-input') as HTMLInputElement;
        expect(input.value).toBe('');

        dispatchArrowRight(input);

        expect(input.value).toBe('ops_email');
        expect(component.form.controls.channel.value).toBe('ops_email');
        expect(component.form.controls.channel.dirty).toBe(true);
    });

    it('strips parentheses and sets cursor at end', () => {
        const input = fixture.nativeElement.querySelector('#parentheses-input') as HTMLInputElement;

        dispatchArrowRight(input);

        expect(input.value).toBe('cdr_ingest');
        expect(component.form.controls.job.value).toBe('cdr_ingest');
        expect(input.selectionStart).toBe('cdr_ingest'.length);
        expect(input.selectionEnd).toBe('cdr_ingest'.length);
    });

    it('handles alternatives by choosing the first token', () => {
        const input = fixture.nativeElement.querySelector('#alternatives-input') as HTMLInputElement;

        dispatchArrowRight(input);

        expect(input.value).toBe('BATCH_FAILED');
        expect(component.form.controls.event.value).toBe('BATCH_FAILED');
    });

    it('populates clean literal samples', () => {
        const input = fixture.nativeElement.querySelector('#literal-input') as HTMLInputElement;

        dispatchArrowRight(input);

        expect(input.value).toBe('ops@example.com');
        expect(component.form.controls.email.value).toBe('ops@example.com');
    });

    it('does not populate instructional prompts or ellipsis placeholders', () => {
        const searchInput = fixture.nativeElement.querySelector('#instructional-input') as HTMLInputElement;
        dispatchArrowRight(searchInput);
        expect(searchInput.value).toBe('');
        expect(component.form.controls.search.value).toBe('');

        const filterInput = fixture.nativeElement.querySelector('#ellipsis-input') as HTMLInputElement;
        dispatchArrowRight(filterInput);
        expect(filterInput.value).toBe('');
        expect(component.form.controls.filter.value).toBe('');
    });

    it('supports type="number" without errors and sets numeric FormControl value', () => {
        const numberInput = fixture.nativeElement.querySelector('#number-input') as HTMLInputElement;

        dispatchArrowRight(numberInput);

        expect(numberInput.value).toBe('42');
        expect(component.form.controls.count.value).toBe(42);
    });

    it('works on textareas', () => {
        const textarea = fixture.nativeElement.querySelector('#textarea-input') as HTMLTextAreaElement;

        dispatchArrowRight(textarea);

        expect(textarea.value).toBe('nightly sync batch');
        expect(component.form.controls.notes.value).toBe('nightly sync batch');
    });

    it('honors inspectoSampleValue input override', () => {
        const customInput = fixture.nativeElement.querySelector('#override-input') as HTMLInputElement;

        dispatchArrowRight(customInput);

        expect(customInput.value).toBe('custom-value');
        expect(component.form.controls.custom.value).toBe('custom-value');
    });

    it('works on plain inputs with [inspectoSampleValue]', () => {
        const plainInput = fixture.nativeElement.querySelector('#plain-input') as HTMLInputElement;

        dispatchArrowRight(plainInput);

        expect(plainInput.value).toBe('plain_sample');
        expect(component.form.controls.plain.value).toBe('plain_sample');
    });

    it('dispatches input event updating non-formControl bindings', () => {
        const draftInput = fixture.nativeElement.querySelector('#draft-input') as HTMLInputElement;
        expect(component.draftValue).toBe('');

        dispatchArrowRight(draftInput);

        expect(draftInput.value).toBe('draft_sample');
        expect(component.draftValue).toBe('draft_sample');
    });

    it('does not overwrite existing text if already filled', () => {
        const input = fixture.nativeElement.querySelector('#prefixed-input') as HTMLInputElement;
        input.value = 'my_custom_channel';
        component.form.controls.channel.setValue('my_custom_channel');

        dispatchArrowRight(input);

        expect(input.value).toBe('my_custom_channel');
        expect(component.form.controls.channel.value).toBe('my_custom_channel');
    });

    it('completes the sample if user typed a prefix and cursor is at the end', () => {
        const input = fixture.nativeElement.querySelector('#prefixed-input') as HTMLInputElement;
        input.value = 'ops_';
        component.form.controls.channel.setValue('ops_');
        input.setSelectionRange(4, 4);

        dispatchArrowRight(input);

        expect(input.value).toBe('ops_email');
        expect(component.form.controls.channel.value).toBe('ops_email');
        expect(input.selectionStart).toBe('ops_email'.length);
    });

    it('ignores ArrowRight if modifier keys are pressed', () => {
        const input = fixture.nativeElement.querySelector('#prefixed-input') as HTMLInputElement;

        dispatchArrowRight(input, { ctrlKey: true });
        expect(input.value).toBe('');

        dispatchArrowRight(input, { altKey: true });
        expect(input.value).toBe('');

        dispatchArrowRight(input, { shiftKey: true });
        expect(input.value).toBe('');
    });

    it('does not trigger on disabled or readonly inputs', () => {
        const input = fixture.nativeElement.querySelector('#prefixed-input') as HTMLInputElement;
        input.disabled = true;

        dispatchArrowRight(input);
        expect(input.value).toBe('');

        input.disabled = false;
        input.readOnly = true;

        dispatchArrowRight(input);
        expect(input.value).toBe('');
    });
});
