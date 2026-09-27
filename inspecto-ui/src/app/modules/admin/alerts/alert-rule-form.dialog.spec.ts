import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { AlertRule, AlertsService } from 'app/inspecto/api';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { AlertRuleFormData, AlertRuleFormDialog } from './alert-rule-form.dialog';

const RULE: AlertRule = {
    name: 'high_error_rate',
    metric: 'error_rate',
    comparator: 'gt',
    threshold: 0.1,
    window: '15m',
    severity: 'CRITICAL',
    onPipeline: 'cdr_ingest',
};

/** A BI-5 measure rule as GET /alerts/rules serves it (the telco demo's shape) + a key this form does not model. */
const MEASURE_RULE = {
    name: 'fraud_exposure_high',
    description: 'Open fraud exposure is too high',
    dataset: 'fraud_cases_open',
    measure: 'sum(exposure_sar)',
    comparator: 'gt',
    threshold: 298668,
    severity: 'CRITICAL',
    futureKey: { kept: true },
} as unknown as AlertRule;

function create(data: AlertRuleFormData, save = vi.fn(() => of(RULE))) {
    const ref = { close: vi.fn() };
    TestBed.configureTestingModule({
        imports: [AlertRuleFormDialog],
        providers: [
            provideNoopAnimations(),
            provideHttpClient(withXhr()), // the autocomplete option loaders inject root HTTP services
            { provide: MAT_DIALOG_DATA, useValue: data },
            { provide: MatDialogRef, useValue: ref },
            {
                provide: AlertsService,
                useValue: { createRule: save, updateRule: save },
            },
            { provide: ToastrService, useValue: { error: vi.fn() } },
        ],
    });
    const fixture = TestBed.createComponent(AlertRuleFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, ref, save };
}

/** The `<mat-form-field>` hosting `control` inside `scope`, so a mat-error assertion is scoped to that one field. */
function fieldOf(el: HTMLElement, scope: string, control: string): HTMLElement {
    return el.querySelector(`${scope} [formControlName="${control}"]`)!.closest('mat-form-field') as HTMLElement;
}

/** The schema-form `<mat-form-field>` labelled `label` (its controls bind `formControlName` as a property, so no attribute). */
function fieldByLabel(el: HTMLElement, label: string): HTMLElement | null {
    return (
        (Array.from(el.querySelectorAll('inspecto-schema-form mat-form-field')).find(
            (f) => f.querySelector('mat-label')?.textContent?.trim() === label,
        ) as HTMLElement | undefined) ?? null
    );
}

describe('AlertRuleFormDialog', () => {
    it('seeds the form from an existing rule (edit) and stays on the config step (id is immutable)', () => {
        const { c } = create({ rule: RULE });
        expect(c.isEdit).toBe(true);
        expect(c.step()).toBe('config');
        expect(c.schemaForm.form.get('metric')?.value).toBe('error_rate');
        expect(c.schemaForm.form.get('window')?.value).toBe('15m');
    });

    it('blocks save while required fields are blank, advances to the save step once valid, then creates', () => {
        const { c, ref, save } = create({});
        c.save();
        expect(c.step()).toBe('config');
        expect(save).not.toHaveBeenCalled();

        c.schemaForm.form.patchValue({ metric: 'duration_ms', threshold: 30000 });
        c.save(); // config valid ⇒ advances to the save step, doesn't call the API yet
        expect(c.step()).toBe('save');
        expect(save).not.toHaveBeenCalled();

        c.saveForm.patchValue({ name: 'slow_batch' });
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                name: 'slow_batch',
                metric: 'duration_ms',
                threshold: 30000,
                window: '15m',
            }),
        );
        expect(ref.close).toHaveBeenCalledWith({ saved: RULE });
    });

    it('blocks save on a duplicate id (case-insensitive) at the save step, then creates once unique', () => {
        const { c, save } = create({ existingNames: ['high_error_rate'] });
        c.schemaForm.form.patchValue({ metric: 'error_rate', threshold: 0.05 });
        c.save();
        expect(c.step()).toBe('save');

        c.saveForm.patchValue({ name: 'HIGH_Error_Rate' });
        c.save();
        expect(save).not.toHaveBeenCalled();
        expect(c.saveForm.get('name')?.hasError('duplicate')).toBe(true);

        c.saveForm.patchValue({ name: 'high_error_rate_2' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ name: 'high_error_rate_2' }));
    });

    it('omits an empty pipeline scope and keeps a set one', () => {
        const { c, save } = create({});
        c.schemaForm.form.patchValue({ metric: 'error_rate', threshold: 0.05, onPipeline: '' });
        c.save();
        c.saveForm.patchValue({ name: 'r1' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.not.objectContaining({ onPipeline: expect.anything() }));

        c.backToConfig();
        c.schemaForm.form.patchValue({ onPipeline: 'cdr_ingest' });
        c.save();
        c.save();
        expect(save).toHaveBeenCalledWith(expect.objectContaining({ onPipeline: 'cdr_ingest' }));
    });

    it('a 503 surfaces the writes-disabled banner instead of a toast', () => {
        const { c, fixture } = create(
            {},
            vi.fn(() => throwError(() => ({ status: 503 }))),
        );
        c.schemaForm.form.patchValue({ metric: 'error_rate', threshold: 0.05 });
        c.save();
        c.saveForm.patchValue({ name: 'r1' });
        c.save();
        fixture.detectChanges();
        expect(c.writesDisabled()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('writes are disabled');
    });

    it('omits when while the condition tree is empty, includes it once a condition is added', () => {
        const { c, save } = create({});
        c.schemaForm.form.patchValue({ metric: 'duration_ms', threshold: 5000 });
        c.save();
        c.saveForm.patchValue({ name: 'scoped' });
        c.save();
        expect(save).toHaveBeenCalledWith(expect.not.objectContaining({ when: expect.anything() }));

        c.backToConfig();
        c.when.items.push({
            kind: 'condition',
            field: 'rejected_count',
            operator: '>',
            value: '0',
        });
        c.save();
        c.save();
        expect(save).toHaveBeenCalledWith(
            expect.objectContaining({
                when: {
                    kind: 'group',
                    op: 'AND',
                    items: [
                        {
                            kind: 'condition',
                            field: 'rejected_count',
                            operator: '>',
                            value: '0',
                        },
                    ],
                },
            }),
        );
    });

    it('round-trips description: seeded on edit, written on save, removed once cleared', () => {
        const { c, save } = create({ rule: { ...RULE, description: 'Error rate spiking' } });
        expect(c.schemaForm.form.get('description')?.value).toBe('Error rate spiking');
        c.save();
        expect(save).toHaveBeenLastCalledWith(
            'high_error_rate',
            expect.objectContaining({ description: 'Error rate spiking', metric: 'error_rate' }),
        );

        c.schemaForm.form.patchValue({ description: '  ' });
        c.save();
        expect(save).toHaveBeenLastCalledWith(
            'high_error_rate',
            expect.not.objectContaining({ description: expect.anything() }),
        );
    });

    it('re-saving a measure rule keeps its dataset, measure and unmodelled keys, and never adds metric/window/when', () => {
        const { c, save, fixture } = create({ rule: MEASURE_RULE });
        expect(c.kind()).toBe('measure');
        expect(c.schemaForm.form.get('kind')?.value).toBe('measure');
        expect(c.schemaForm.form.get('dataset')?.value).toBe('fraud_cases_open');
        expect(c.schemaForm.form.get('measure')?.value).toBe('sum(exposure_sar)');
        expect(c.schemaForm.form.get('metric')?.disabled).toBe(true); // the other kind's field is hidden
        expect(fixture.nativeElement.querySelector('inspecto-query-condition-group')).toBeNull();

        c.schemaForm.form.patchValue({ threshold: 300000 });
        c.save();
        const [name, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(name).toBe('fraud_exposure_high');
        expect(body).toEqual({
            name: 'fraud_exposure_high',
            description: 'Open fraud exposure is too high',
            dataset: 'fraud_cases_open',
            measure: 'sum(exposure_sar)',
            comparator: 'gt',
            threshold: 300000,
            severity: 'CRITICAL',
            futureKey: { kept: true },
        });
    });

    it('creates a per-entity Measure rule in the AlertRule.fromMap shape (no kind, metric, window or when)', () => {
        const { c, save } = create({});
        c.schemaForm.form.get('kind')!.setValue('measure');
        c.schemaForm.form.patchValue({
            dataset: 'usage_ds',
            measure: 'sum(amount)',
            threshold: 5000,
            severity: 'CRITICAL',
            by: ['msisdn'],
            stormCap: 20,
        });
        c.save();
        expect(c.step()).toBe('save');
        expect(c.saveForm.controls.name.value).toBe('usage_ds_sum_amount_gt');
        c.save();
        const [body] = save.mock.lastCall as unknown as [Record<string, unknown>];
        expect(body).toEqual({
            name: 'usage_ds_sum_amount_gt',
            dataset: 'usage_ds',
            measure: 'sum(amount)',
            by: ['msisdn'],
            stormCap: 20,
            comparator: 'gt',
            threshold: 5000,
            severity: 'CRITICAL',
        });
    });

    it('drops stormCap when no by column is given (the engine refuses stormCap without by)', () => {
        const { c, save } = create({});
        c.schemaForm.form.get('kind')!.setValue('measure');
        c.schemaForm.form.patchValue({ dataset: 'usage_ds', measure: 'count', threshold: 10, stormCap: 20 });
        c.save();
        c.save();
        const [body] = save.mock.lastCall as unknown as [Record<string, unknown>];
        expect(body).toEqual(expect.objectContaining({ dataset: 'usage_ds', measure: 'count' }));
        expect(body).not.toHaveProperty('by');
        expect(body).not.toHaveProperty('stormCap');
    });

    it('round-trips a per-entity rule: by/stormCap seeded on edit, edited, and removed once by is cleared', () => {
        const { c, save } = create({
            rule: { ...MEASURE_RULE, by: ['msisdn', 'region'], stormCap: 50 } as AlertRule,
        });
        expect(c.schemaForm.form.get('by')?.value).toEqual(['msisdn', 'region']);
        expect(c.schemaForm.form.get('stormCap')?.value).toBe(50);
        c.save();
        let [, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(body).toEqual(expect.objectContaining({ by: ['msisdn', 'region'], stormCap: 50 }));

        c.schemaForm.form.patchValue({ by: ['msisdn'], stormCap: 5 });
        c.save();
        [, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(body).toEqual(expect.objectContaining({ by: ['msisdn'], stormCap: 5 }));

        c.schemaForm.form.patchValue({ by: null });
        c.save();
        [, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(body).not.toHaveProperty('by');
        expect(body).not.toHaveProperty('stormCap');
    });

    it('re-saves a metric rule unchanged: no kind, dataset, measure, by or stormCap is written', () => {
        const { c, save } = create({ rule: RULE });
        expect(c.kind()).toBe('metric');
        c.save();
        const [name, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(name).toBe('high_error_rate');
        expect(body).toEqual(RULE);
    });

    it('switching kind hides the other kind fields, and a switched edit drops the old kind keys', () => {
        const { c, save, fixture } = create({ rule: RULE });
        const el = fixture.nativeElement as HTMLElement;
        expect(fieldByLabel(el, 'Metric')).not.toBeNull();
        expect(fieldByLabel(el, 'Dataset')).toBeNull();
        expect(el.querySelector('inspecto-query-condition-group')).not.toBeNull();

        c.schemaForm.form.get('kind')!.setValue('measure');
        fixture.detectChanges();
        expect(fieldByLabel(el, 'Metric')).toBeNull();
        expect(fieldByLabel(el, 'Dataset')).not.toBeNull();
        expect(fieldByLabel(el, 'Measure')).not.toBeNull();
        expect(el.querySelector('inspecto-query-condition-group')).toBeNull();

        c.schemaForm.form.patchValue({ dataset: 'usage_ds', measure: 'count' });
        c.save();
        const [, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(body).toEqual(expect.objectContaining({ dataset: 'usage_ds', measure: 'count' }));
        expect(body).not.toHaveProperty('metric');
        expect(body).not.toHaveProperty('window');
        expect(body).not.toHaveProperty('kind');
    });

    it('an invalid measure blocks save and shows the grammar error on screen', () => {
        const { c, save, fixture } = create({});
        c.schemaForm.form.get('kind')!.setValue('measure');
        c.schemaForm.form.patchValue({ dataset: 'usage_ds', measure: 'median(amount)', threshold: 1 });
        c.save();
        fixture.detectChanges();
        expect(c.step()).toBe('config');
        expect(save).not.toHaveBeenCalled();
        const err = fieldByLabel(fixture.nativeElement, 'Measure')!.querySelector('mat-error');
        expect(err?.textContent).toContain('"median" is not an aggregate');
        expect(err?.closest('.mat-mdc-form-field-subscript-wrapper')).not.toBeNull();
    });

    it('an invalid by column blocks save and shows its error line (a list error is never a mat-error)', () => {
        const { c, save, fixture } = create({});
        c.schemaForm.form.get('kind')!.setValue('measure');
        c.schemaForm.form.patchValue({ dataset: 'usage_ds', measure: 'count', threshold: 1, by: ['sum(amount)'] });
        c.save();
        fixture.detectChanges();
        expect(save).not.toHaveBeenCalled();
        const alerts = Array.from(
            (fixture.nativeElement as HTMLElement).querySelectorAll('inspecto-schema-form [role="alert"]'),
        ).map((a) => a.textContent ?? '');
        expect(alerts.some((t) => t.includes('is a column to group by, not an aggregate'))).toBe(true);
    });

    it('shows the server 422 (e.g. a by column not in the Schema) in the dialog', () => {
        const message = "alert.by column(s) [msisdn] are not in the Schema of dataset 'usage_ds' (have: [amount])";
        const { c, fixture, ref } = create(
            { rule: { ...MEASURE_RULE, by: ['msisdn'], stormCap: 100 } as AlertRule },
            vi.fn(() =>
                throwError(
                    () =>
                        new HttpErrorResponse({
                            status: 422,
                            error: { error: { code: 'CONFIG_VALIDATION_FAILED', message } },
                        }),
                ),
            ),
        );
        c.save();
        fixture.detectChanges();
        expect(ref.close).not.toHaveBeenCalled();
        const alert = (fixture.nativeElement as HTMLElement).querySelector('inspecto-alert');
        expect(alert?.textContent).toContain(message);
    });

    it('renders the Measure kind of a new rule with no a11y violations', async () => {
        const { c, fixture } = create({});
        c.schemaForm.form.get('kind')!.setValue('measure');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders a measure rule edit with no a11y violations', async () => {
        const { fixture } = create({ rule: MEASURE_RULE });
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows "A rule id is required." only after save — never when the save step first renders (the error lands in the subscript)', () => {
        const { fixture, c } = create({});
        c.schemaForm.form.patchValue({ metric: 'duration_ms', threshold: 30000 });
        c.save();
        expect(c.step()).toBe('save');
        c.saveForm.controls.name.setValue('');
        fixture.detectChanges();
        const field = fieldOf(fixture.nativeElement, 'form[aria-label="Name this alert rule"]', 'name');
        // Untouched: no error. The old outer "@if (…; as c)" wrapper mis-projected the <mat-error> into
        // the form field's DEFAULT slot, so it rendered beside the input before any touch/submit.
        expect(field.querySelector('mat-error')).toBeNull();
        expect(field.textContent).not.toContain('A rule id is required.');
        c.save();
        fixture.detectChanges();
        const err = field.querySelector('mat-error');
        expect(err?.textContent).toContain('A rule id is required.');
        expect(err?.closest('.mat-mdc-form-field-subscript-wrapper')).not.toBeNull();
    });
    /** The "One Alert per" draft input (the schema-form list field labelled so). */
    function byInput(el: HTMLElement): HTMLInputElement {
        return fieldByLabel(el, 'One Alert per')!.querySelector('input') as HTMLInputElement;
    }

    it('Save changes with the by suggestion panel still open keeps the typed entry (edit)', async () => {
        const { c, save, fixture } = create({ rule: { ...MEASURE_RULE, by: ['region'], stormCap: 50 } as AlertRule });
        c.schemaForm.optionLoaders = {
            ...c.optionLoaders,
            by: async () => [{ value: 'msisdn', label: 'msisdn (varchar)' }],
        };
        c.schemaForm.showOptional.set(true);
        fixture.detectChanges();
        const input = byInput(fixture.nativeElement);
        input.focus(); // loads the suggestions
        await fixture.whenStable();
        fixture.detectChanges();
        input.value = 'msisdn';
        input.dispatchEvent(new Event('input')); // typing opens the panel
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
        expect(document.querySelector('mat-option')?.textContent).toContain('msisdn'); // the panel IS open
        // The Save mousedown blurs the draft while the panel is open (blur-commit deferred to `closed`) …
        input.dispatchEvent(new Event('blur'));
        expect(c.schemaForm.listValue('by')).toEqual(['region']); // blur-commit really was deferred
        // … and the click runs save() before any outside-click closes the panel.
        c.save();
        const [, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(body['by']).toEqual(['region', 'msisdn']);
    });

    it('create: a typed but uncommitted by entry is saved', () => {
        const { c, save } = create({});
        c.schemaForm.form.get('kind')!.setValue('measure');
        c.schemaForm.form.patchValue({ dataset: 'usage_ds', measure: 'count', threshold: 1 });
        c.schemaForm.setListDraft('by', 'msisdn');
        c.save();
        c.save();
        const [body] = save.mock.lastCall as unknown as [Record<string, unknown>];
        expect(body['by']).toEqual(['msisdn']);
    });

    it('a picked by suggestion commits exactly once, even when the panel then closes and Save runs', () => {
        const { c, save, fixture } = create({ rule: MEASURE_RULE });
        c.schemaForm.showOptional.set(true);
        fixture.detectChanges();
        const input = byInput(fixture.nativeElement);
        const spec = c.attributes.find((s) => s.key === 'by')!;
        c.schemaForm.setListDraft('by', 'ms');
        c.schemaForm.addListOption(spec, 'msisdn', input);
        c.schemaForm.commitListDraftIfLeft(spec, input);
        c.save();
        const [, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(body['by']).toEqual(['msisdn']);
    });

    it('threshold has no default and must be greater than 0 (the engine refuses 0)', () => {
        const { c, save, fixture } = create({});
        expect(c.schemaForm.form.get('threshold')?.value ?? null).toBeNull();
        c.schemaForm.form.patchValue({ metric: 'error_rate', threshold: 0 });
        c.save();
        fixture.detectChanges();
        expect(c.step()).toBe('config');
        expect(save).not.toHaveBeenCalled();
        expect(fieldByLabel(fixture.nativeElement, 'Threshold')!.querySelector('mat-error')?.textContent).toContain(
            'Threshold must be greater than 0',
        );
    });

    it('offers only the comparators the engine accepts (no eq)', () => {
        const { c } = create({});
        const comparator = c.attributes.find((s) => s.key === 'comparator')!;
        expect(comparator.options!.map((o) => o.value)).toEqual(['gt', 'gte', 'lt', 'lte']);
    });

    it('a freshness rule hides comparator/threshold and re-saves them as stored', () => {
        const fresh = {
            name: 'sales_fresh',
            dataset: 'sales_ds',
            maximumAge: '6h',
            comparator: 'gt',
            threshold: 0,
            severity: 'WARNING',
        } as AlertRule;
        const { c, save } = create({ rule: fresh });
        expect(c.schemaForm.form.get('threshold')).toBeNull();
        c.schemaForm.form.patchValue({ severity: 'CRITICAL' });
        c.save();
        const [, body] = save.mock.lastCall as unknown as [string, Record<string, unknown>];
        expect(body).toEqual({ ...fresh, severity: 'CRITICAL' });
    });
});
