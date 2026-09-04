import { TestBed } from '@angular/core/testing';
import { AbstractControl, ValidatorFn } from '@angular/forms';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { AttributeSpec, AttributeToken } from 'app/inspecto/component-model';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { InspectoSchemaFormComponent } from './schema-form.component';

const SPECS: AttributeSpec[] = [
    { key: 'name', label: 'Name', type: 'identifier', tier: 'required' },
    {
        key: 'type',
        label: 'Type',
        type: 'select',
        tier: 'required',
        options: [
            { value: 'enrich', label: 'Enrich' },
            { value: 'report', label: 'Report' },
        ],
        default: 'enrich',
    },
    { key: 'cron', label: 'Cron', type: 'string', tier: 'optional', dependsOn: { key: 'type', equals: 'report' } },
    { key: 'enabled', label: 'Enabled', type: 'boolean', tier: 'optional', default: true },
    { key: 'threads', label: 'Threads', type: 'number', tier: 'advanced', default: 4, min: 1, max: 64 },
];

/** `type: 'list'` in the always-visible tier so the chips render without expanding a group. */
const LIST_SPECS: AttributeSpec[] = [
    { key: 'patterns', label: 'Patterns', type: 'list', tier: 'required', required: false, placeholder: '^CALL' },
];

describe('InspectoSchemaFormComponent', () => {
    function create(
        specs: AttributeSpec[] = SPECS,
        initial?: Record<string, unknown>,
        extraValidators?: Record<string, ValidatorFn[]>,
        tokens?: { tokens?: Record<string, AttributeToken[]>; tokenSyntax?: RegExp },
    ) {
        TestBed.configureTestingModule({
            imports: [InspectoSchemaFormComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(InspectoSchemaFormComponent);
        // Deliberately BEFORE `specs`: the controls don't exist yet, so this also proves the setter
        // re-applies host validators after a spec (re)build rather than dropping them.
        if (extraValidators) fixture.componentInstance.extraValidators = extraValidators;
        if (tokens?.tokens) fixture.componentInstance.tokens = tokens.tokens;
        if (tokens?.tokenSyntax) fixture.componentInstance.tokenSyntax = tokens.tokenSyntax;
        fixture.componentInstance.specs = specs;
        if (initial) fixture.componentInstance.initial = initial;
        fixture.detectChanges();
        return fixture;
    }

    /**
     * A single choice is asked in a POPUP, not a dropdown (operator ask 2026-08-22). Pinned because
     * every other spec here drives the form through `patchValue`, so nothing else would notice the
     * control type changing under it — the value contract is unaffected either way.
     */
    it('renders a select as the shared popup picker showing the option label', () => {
        const fixture = create();
        expect(fixture.nativeElement.querySelector('mat-select')).toBeNull();
        const picker = fixture.nativeElement.querySelector('inspecto-option-picker');
        expect(picker).toBeTruthy();
        expect(picker.textContent).toContain('Enrich'); // the default's LABEL, not 'enrich'
        expect(fixture.componentInstance.value()['type']).toBe('enrich');
    });

    it('shows required tier; collapses optional; hides advanced behind the gear', () => {
        const fixture = create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Name');
        expect(text).toContain('Optional settings (2)');
        expect(text).not.toContain('Enabled'); // optional collapsed
        expect(text).not.toContain('Threads'); // advanced hidden

        fixture.componentInstance.showOptional.set(true);
        fixture.componentInstance.showAdvanced.set(true);
        fixture.detectChanges();
        const expanded = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(expanded).toContain('Enabled');
        expect(expanded).toContain('Threads');
    });

    it('shows a dependsOn attribute only when its controller matches, and excludes it from value()', () => {
        const fixture = create();
        fixture.componentInstance.showOptional.set(true);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Cron');
        expect(fixture.componentInstance.form.get('cron')?.disabled).toBe(true);
        expect(Object.keys(fixture.componentInstance.value())).not.toContain('cron');

        fixture.componentInstance.form.get('type')?.setValue('report');
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Cron');
        expect(fixture.componentInstance.form.get('cron')?.enabled).toBe(true);
        expect(Object.keys(fixture.componentInstance.value())).toContain('cron');
    });

    it('validate() fails on a missing required value and marks controls touched', () => {
        const fixture = create();
        expect(fixture.componentInstance.validate()).toBe(false);
        expect(fixture.componentInstance.form.get('name')?.touched).toBe(true);

        fixture.componentInstance.form.get('name')?.setValue('daily_kpi');
        expect(fixture.componentInstance.validate()).toBe(true);
    });

    /**
     * An invalid value behind a collapsed disclosure used to block submit with NOTHING on screen to
     * correct — `markAllAsTouched` renders an error only where the control renders. Surfaced by a JSON
     * job parameter (tier ADVANCED), but it was never JSON-specific: any validator on an
     * optional/advanced field had it.
     */
    it('validate() opens the collapsed section that holds an invalid control', () => {
        const fixture = create(SPECS);
        const c = fixture.componentInstance;
        c.form.get('name')?.setValue('daily_kpi');
        c.form.get('threads')?.setValue(999); // advanced tier, over max
        expect(c.showAdvanced()).toBe(false);

        expect(c.validate()).toBe(false);
        fixture.detectChanges();

        expect(c.showAdvanced()).toBe(true);
        // The point is the message reaching the SCREEN, not merely the control being invalid.
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Threads');
    });

    it('validate() leaves collapsed sections shut when only a visible field is invalid', () => {
        const fixture = create(SPECS);
        const c = fixture.componentInstance;
        expect(c.validate()).toBe(false); // `name` (required tier) is empty

        expect(c.showAdvanced()).toBe(false);
        expect(c.showOptional()).toBe(false);
    });

    it('applies declared defaults and patches initial values over them', () => {
        const fixture = create(SPECS, { name: 'weekly', type: 'report' });
        const v = fixture.componentInstance.form.getRawValue();
        expect(v['name']).toBe('weekly');
        expect(v['type']).toBe('report');
        expect(v['enabled']).toBe(true); // default preserved
        expect(v['threads']).toBe(4);
    });

    it('autocomplete loads suggestions via optionLoaders and narrows them by the typed text', async () => {
        const specs: AttributeSpec[] = [
            {
                key: 'kind',
                label: 'Kind',
                type: 'select',
                tier: 'required',
                default: 'a',
                options: [{ value: 'a', label: 'A' }],
            },
            { key: 'target', label: 'Target', type: 'autocomplete', tier: 'required' },
        ];
        const fixture = create(specs);
        const c = fixture.componentInstance;
        c.optionLoaders = {
            // The loader sees the sibling values (here: kind) and returns the suggestion list.
            target: (v) =>
                v['kind'] === 'a'
                    ? [
                          { value: 'cdr_ingest', label: 'cdr_ingest' },
                          { value: 'events_daily', label: 'events_daily' },
                      ]
                    : [],
        };

        c.loadOptionsFor(specs[1]);
        await Promise.resolve(); // loader resolution
        expect(c.filteredOptions(specs[1]).map((o) => o.value)).toEqual(['cdr_ingest', 'events_daily']);

        c.form.get('target')?.setValue('cdr'); // typing narrows, value stays free text
        expect(c.filteredOptions(specs[1]).map((o) => o.value)).toEqual(['cdr_ingest']);
        c.form.get('target')?.setValue('anything_else');
        expect(c.validate()).toBe(true); // suggestions assist — they never constrain
    });

    it('emits real numbers from number fields (NumberValueAccessor via static type="number")', () => {
        const fixture = create();
        fixture.componentInstance.showAdvanced.set(true);
        fixture.detectChanges();

        const input = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>('input[type="number"]')!;
        expect(input).toBeTruthy();
        input.value = '16';
        input.dispatchEvent(new Event('input'));
        expect(fixture.componentInstance.value()['threads']).toBe(16); // number, not "16"
    });

    it('emits submitted on native form submission (Enter in a field) and reports dirtiness', () => {
        const fixture = create();
        let submits = 0;
        fixture.componentInstance.submitted.subscribe(() => submits++);

        expect(fixture.componentInstance.isDirty()).toBe(false);
        fixture.componentInstance.form.get('name')?.setValue('daily_kpi');
        fixture.componentInstance.form.get('name')?.markAsDirty();
        expect(fixture.componentInstance.isDirty()).toBe(true);

        const form = (fixture.nativeElement as HTMLElement).querySelector('form')!;
        form.dispatchEvent(new Event('submit'));
        expect(submits).toBe(1);
    });

    it('has no axe violations with all tiers expanded', async () => {
        const fixture = create();
        fixture.componentInstance.showOptional.set(true);
        fixture.componentInstance.showAdvanced.set(true);
        fixture.componentInstance.form.get('type')?.setValue('report');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    /**
     * `type: 'list'` (D7): the text box is a DRAFT and the control holds the committed `string[]`.
     * Clearing every entry must write `null`, not `[]`, so `required` and the hosts' delete-on-clear
     * both see it as blank.
     */
    it('commits list entries as an array, dedupes, and clears to null', () => {
        const fixture = create(LIST_SPECS);
        const c = fixture.componentInstance;
        const spec = LIST_SPECS[0];

        expect(c.listValue('patterns')).toEqual([]);

        c.setListDraft('patterns', '^CALL');
        c.addListItem(spec);
        c.setListDraft('patterns', '^SMS');
        c.addListItem(spec);
        expect(c.form.get('patterns')?.value).toEqual(['^CALL', '^SMS']);
        expect(c.listDraft('patterns')).toBe(''); // draft cleared after commit
        expect(c.isDirty()).toBe(true);

        c.setListDraft('patterns', '^CALL'); // duplicate ignored
        c.addListItem(spec);
        c.setListDraft('patterns', '   '); // blank ignored
        c.addListItem(spec);
        expect(c.form.get('patterns')?.value).toEqual(['^CALL', '^SMS']);

        c.removeListItem(spec, 0);
        expect(c.form.get('patterns')?.value).toEqual(['^SMS']);
        c.removeListItem(spec, 0);
        expect(c.form.get('patterns')?.value).toBeNull(); // emptied ⇒ null, not []
    });

    it('renders committed list entries as removable chips and loads initial arrays', async () => {
        const fixture = create(LIST_SPECS, { patterns: ['^CALL', '^SMS'] });
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;

        expect(el.textContent).toContain('^CALL');
        expect(el.textContent).toContain('^SMS');
        const removes = Array.from(el.querySelectorAll('button[aria-label^="Remove "]'));
        expect(removes.length).toBe(2);

        (removes[0] as HTMLButtonElement).click();
        fixture.detectChanges();
        expect(fixture.componentInstance.form.get('patterns')?.value).toEqual(['^SMS']);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('Enter in a list field adds an entry instead of submitting the form', () => {
        const fixture = create(LIST_SPECS);
        let submits = 0;
        fixture.componentInstance.submitted.subscribe(() => submits++);

        fixture.componentInstance.setListDraft('patterns', '^CALL');
        const event = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true });
        fixture.componentInstance.addListItem(LIST_SPECS[0], event);

        expect(event.defaultPrevented).toBe(true); // otherwise the sr-only submit button fires
        expect(submits).toBe(0);
        expect(fixture.componentInstance.form.get('patterns')?.value).toEqual(['^CALL']);
    });

    describe('extraValidators (host-supplied domain rules)', () => {
        /** Stands in for the Pipelines measure grammar: rejects any entry containing `!`. */
        const noBang: ValidatorFn = (c: AbstractControl) =>
            (c.value as unknown[] | null)?.some((e) => String(e).includes('!'))
                ? { message: 'Entries may not contain "!"' }
                : null;

        it('applies a host validator to the matching control and renders its message verbatim', () => {
            const fixture = create(LIST_SPECS, undefined, { patterns: [noBang] });
            const control = fixture.componentInstance.form.get('patterns')!;

            control.setValue(['ok', 'bad!']);
            expect(control.invalid).toBe(true);
            // The generic keys can't phrase a domain rule, so `{message}` wins over the fallbacks.
            expect(fixture.componentInstance.errorFor(LIST_SPECS[0])).toBe('Entries may not contain "!"');

            control.setValue(['ok']);
            expect(control.valid).toBe(true);
        });

        it('survives a spec reassignment — a spec swap rebuilds every control', () => {
            const fixture = create(LIST_SPECS, undefined, { patterns: [noBang] });

            fixture.componentInstance.specs = LIST_SPECS; // the collector/grammar-editor spec-swap path
            fixture.detectChanges();

            const control = fixture.componentInstance.form.get('patterns')!;
            control.setValue(['bad!']);
            expect(control.invalid).toBe(true);
        });

        it('RENDERS the message in the DOM, not just from errorFor()', async () => {
            // The gap that let the real defect through: a `list` field's <input> is a draft, never bound
            // with formControlName, so <mat-form-field> has no NgControl and its <mat-error> can never
            // fire. errorFor() returned the right string while nothing reached the screen. Assert the
            // rendered element, and that it announces.
            const fixture = create(LIST_SPECS, undefined, { patterns: [noBang] });
            const control = fixture.componentInstance.form.get('patterns')!;

            control.setValue(['bad!']);
            control.markAsTouched();
            fixture.detectChanges();

            const line = (fixture.nativeElement as HTMLElement).querySelector('[role="alert"]');
            expect(line?.textContent?.trim()).toBe('Entries may not contain "!"');
            await expectNoA11yViolations(fixture.nativeElement);
        });

        it('stays silent until the control is touched, matching Material', () => {
            const fixture = create(LIST_SPECS, undefined, { patterns: [noBang] });

            fixture.componentInstance.form.get('patterns')!.setValue(['bad!']); // untouched
            fixture.detectChanges();

            expect((fixture.nativeElement as HTMLElement).querySelector('[role="alert"]')).toBeNull();
        });

        it('ignores keys absent from the spec set, so one host map can cover several node types', () => {
            const fixture = create(LIST_SPECS, undefined, { nosuchkey: [noBang], patterns: [noBang] });

            expect(fixture.componentInstance.form.get('nosuchkey')).toBeNull();
            expect(fixture.componentInstance.form.get('patterns')?.valid).toBe(true);
        });
    });

    describe('group headings (job-parameter-contract §7.4)', () => {
        const GROUPED: AttributeSpec[] = [
            { key: 'to', label: 'To', type: 'string', tier: 'required', group: 'Recipients' },
            { key: 'cc', label: 'Cc', type: 'string', tier: 'required', group: 'Recipients' },
            { key: 'subject', label: 'Subject', type: 'string', tier: 'required', group: 'Message' },
            { key: 'bare', label: 'Bare', type: 'string', tier: 'required' },
        ];

        const headings = (fixture: { nativeElement: HTMLElement }) =>
            Array.from(fixture.nativeElement.querySelectorAll('[role="heading"]')).map((el) => el.textContent?.trim());

        it('renders one heading per declared group, and none for ungrouped specs', () => {
            const fixture = create(GROUPED);
            expect(headings(fixture)).toEqual(['Recipients', 'Message']);
        });

        it('coalesces a group declared in two places into one heading', () => {
            // Repeating a name means one section, not two — and a repeated @for track key would throw.
            const fixture = create([
                ...GROUPED,
                { key: 'bcc', label: 'Bcc', type: 'string', tier: 'required', group: 'Recipients' },
            ]);
            expect(headings(fixture)).toEqual(['Recipients', 'Message']);
            expect(
                fixture.componentInstance
                    .groupsOf(fixture.componentInstance.tiers().required)
                    .find((g) => g.name === 'Recipients')!
                    .specs.map((s) => s.key),
            ).toEqual(['to', 'cc', 'bcc']);
        });

        it('does not group across tiers — disclosure is the outer structure', () => {
            const fixture = create([
                { key: 'a', label: 'A', type: 'string', tier: 'required', group: 'Shared' },
                { key: 'b', label: 'B', type: 'string', tier: 'advanced', group: 'Shared' },
            ]);
            expect(
                fixture.componentInstance.groupsOf(fixture.componentInstance.tiers().required).map((g) => g.name),
            ).toEqual(['Shared']);
            expect(
                fixture.componentInstance.groupsOf(fixture.componentInstance.tiers().advanced).map((g) => g.name),
            ).toEqual(['Shared']);
        });

        it('has no a11y violations with headings present', async () => {
            const fixture = create(GROUPED);
            await expectNoA11yViolations(fixture.nativeElement);
        });
    });

    describe('secret fields', () => {
        // NB: `formControlName` is a property binding here, so it never reflects to a DOM attribute —
        // an `input[formcontrolname=…]` selector silently matches nothing. Each fixture below declares a
        // single spec, so the sole rendered <input> is unambiguous.
        const soleInput = (fixture: { nativeElement: HTMLElement }) =>
            fixture.nativeElement.querySelector('input') as HTMLInputElement;

        it('masks the input and keeps the value readable to the host', () => {
            const fixture = create([{ key: 'token', label: 'Token', type: 'string', tier: 'required', secret: true }]);

            expect(soleInput(fixture).type).toBe('password');
            fixture.componentInstance.form.get('token')!.setValue('s3cret');
            // Masking is presentation only — the host still reads the real value to save it.
            expect(fixture.componentInstance.value()['token']).toBe('s3cret');
        });

        it('leaves a non-secret string field as a text input', () => {
            const fixture = create([{ key: 'plain', label: 'Plain', type: 'string', tier: 'required' }]);
            expect(soleInput(fixture).type).toBe('text');
        });
    });

    describe('per-item list validation', () => {
        const EMAILS: AttributeSpec[] = [
            {
                key: 'to',
                label: 'To',
                type: 'list',
                tier: 'required',
                required: false,
                pattern: '[^@\\s]+@[^@\\s]+\\.[^@\\s]+',
            },
        ];

        it('applies `pattern` to each entry and names the offending one', () => {
            const fixture = create(EMAILS);
            const control = fixture.componentInstance.form.get('to')!;

            control.setValue(['ops@example.com', 'nope']);
            control.markAsTouched();
            fixture.detectChanges();

            const alert = fixture.nativeElement.querySelector('[role="alert"]');
            expect(alert?.textContent).toContain('"nope"');
            expect(control.valid).toBe(false);
        });

        it('accepts a list whose every entry matches', () => {
            const fixture = create(EMAILS);
            fixture.componentInstance.form.get('to')!.setValue(['a@x.io', 'b@x.io']);
            expect(fixture.componentInstance.form.get('to')!.valid).toBe(true);
        });

        it('does not test the array as one joined string', () => {
            // Validators.pattern on an array tests "a@x.io,b@x.io" — which fails an email pattern even
            // though every entry is valid. This is that regression.
            const fixture = create(EMAILS);
            fixture.componentInstance.form.get('to')!.setValue(['a@x.io', 'b@x.io']);
            expect(fixture.componentInstance.form.get('to')!.errors).toBeNull();
        });
    });

    /**
     * The whole-value token picker. The component stays domain-agnostic here — it is handed tokens and a
     * syntax, and knows only that a token replaces the field's ENTIRE value.
     */
    describe('token picker', () => {
        const TOKEN: AttributeToken = { token: '$today', description: 'The fire date', preview: '2026-08-10' };
        /** `$`-led and not the `$$` escape, as the reference adopter's platform defines a token. */
        const SYNTAX = /^\$(?!\$)/;

        const TEXTS: AttributeSpec[] = [
            { key: 'day', label: 'Day', type: 'string', tier: 'required', pattern: '\\d{4}-\\d{2}-\\d{2}' },
            { key: 'plain', label: 'Plain', type: 'string', tier: 'required' },
        ];
        const EMAIL_LIST: AttributeSpec[] = [
            {
                key: 'to',
                label: 'To',
                type: 'list',
                tier: 'required',
                required: false,
                pattern: '[^@\\s]+@[^@\\s]+\\.[^@\\s]+',
            },
        ];

        const pickers = (fixture: { nativeElement: HTMLElement }): HTMLElement[] =>
            Array.from(fixture.nativeElement.querySelectorAll('button[aria-label^="Insert a runtime token"]'));

        it('renders a picker only where the host offered tokens', () => {
            const fixture = create(TEXTS, undefined, undefined, { tokens: { day: [TOKEN] }, tokenSyntax: SYNTAX });
            const labels = pickers(fixture).map((b) => b.getAttribute('aria-label'));
            expect(labels).toEqual(['Insert a runtime token into Day']);
        });

        it('projects the picker into the form field SUFFIX slot, not beside the input', () => {
            // The regression this pins: as markup inside an `<ng-template>` instantiated by
            // *ngTemplateOutlet, `matSuffix` never matched — Material resolves projection statically at the
            // declaration site — and the button rendered in `…-infix`, next to the text. Asserting the
            // button merely EXISTS passes in both worlds, which is how the first cut looked green.
            const fixture = create(TEXTS, undefined, undefined, { tokens: { day: [TOKEN] }, tokenSyntax: SYNTAX });
            const [picker] = pickers(fixture);
            expect(picker.closest('.mat-mdc-form-field-icon-suffix')).not.toBeNull();
            expect(picker.closest('.mat-mdc-form-field-infix')).toBeNull();
        });

        it('renders no picker for a key whose offer list is empty', () => {
            // How a host says "literal only" — an empty list, not a missing key, is the likelier shape
            // after filtering, and both must draw nothing.
            const fixture = create(TEXTS, undefined, undefined, { tokens: { day: [] }, tokenSyntax: SYNTAX });
            expect(pickers(fixture)).toHaveLength(0);
        });

        it('replaces the whole value rather than inserting into it', () => {
            const fixture = create(TEXTS, { day: '2026-01-01' }, undefined, {
                tokens: { day: [TOKEN] },
                tokenSyntax: SYNTAX,
            });
            const control = fixture.componentInstance.form.get('day')!;
            fixture.componentInstance.applyToken(TEXTS[0], TOKEN);

            // Not "2026-01-01$today", and not "$today" appended anywhere — the value IS the token, which
            // is the only shape a whole-value evaluator resolves.
            expect(control.value).toBe('$today');
            expect(control.dirty).toBe(true);
        });

        it('exempts a token from the field format, so the picker cannot author a value Save refuses', () => {
            // Without the exemption every date/instant/email field marks the token invalid the moment it
            // lands — the picker offers it, the form rejects it, and the feature is decorative.
            const fixture = create(TEXTS, undefined, undefined, { tokens: { day: [TOKEN] }, tokenSyntax: SYNTAX });
            const control = fixture.componentInstance.form.get('day')!;

            control.setValue('not-a-date');
            expect(control.valid).toBe(false); // the literal contract still bites

            fixture.componentInstance.applyToken(TEXTS[0], TOKEN);
            expect(control.valid).toBe(true);
        });

        it('still enforces the format when no tokenSyntax was supplied', () => {
            // The default is no exemption, so every pre-existing adopter behaves exactly as before.
            const fixture = create(TEXTS, undefined, undefined, { tokens: { day: [TOKEN] } });
            const control = fixture.componentInstance.form.get('day')!;
            control.setValue('$today');
            expect(control.valid).toBe(false);
        });

        it('replaces every entry of a list, because a list is one value', () => {
            const fixture = create(EMAIL_LIST, undefined, undefined, { tokens: { to: [TOKEN] }, tokenSyntax: SYNTAX });
            const control = fixture.componentInstance.form.get('to')!;
            control.setValue(['ops@example.com', 'oncall@example.com']);

            fixture.componentInstance.applyToken(EMAIL_LIST[0], { token: '$signal.recipient' });

            expect(control.value).toEqual(['$signal.recipient']);
            expect(control.valid).toBe(true); // a lone token is the whole value ⇒ format exempt
        });

        it('holds a token beside other entries to the literal format — it would never resolve', () => {
            // The engine evaluates a value only when it is a token in its ENTIRETY, so "a@x.io,$signal.r"
            // is a CSV literal. Flagging it is the point: silently accepting it authors a Job that fails
            // at fire time with an invalid address.
            const fixture = create(EMAIL_LIST, undefined, undefined, { tokens: { to: [TOKEN] }, tokenSyntax: SYNTAX });
            const control = fixture.componentInstance.form.get('to')!;
            control.setValue(['a@x.io', '$signal.recipient']);
            expect(control.valid).toBe(false);
        });

        it('has no axe violations with pickers rendered', async () => {
            const fixture = create(TEXTS, undefined, undefined, { tokens: { day: [TOKEN] }, tokenSyntax: SYNTAX });
            await expectNoA11yViolations(fixture.nativeElement);
        });
    });

    /**
     * `flat` renders a compact PROPERTY LIST (operator ask 2026-09-04): one ~32px row per spec — label ·
     * current value · pencil — with the real control inline only while that row is edited, help as an
     * info tooltip instead of text under the field, and two columns once the host is wide.
     */
    describe('flat: compact property rows', () => {
        const FLAT: AttributeSpec[] = [
            { key: 'quote', label: 'Quote', type: 'string', tier: 'required', required: false, default: '"' },
            { key: 'encoding', label: 'Encoding', type: 'string', tier: 'optional', help: 'Byte encoding of the file' },
            { key: 'header', label: 'Header row', type: 'boolean', tier: 'optional', default: true },
            { key: 'skip', label: 'Skip lines', type: 'number', tier: 'advanced', default: 0, group: 'Rows' },
        ];

        function createFlat(specs: AttributeSpec[] = FLAT) {
            TestBed.configureTestingModule({
                imports: [InspectoSchemaFormComponent],
                providers: [provideNoopAnimations()],
            });
            const fixture = TestBed.createComponent(InspectoSchemaFormComponent);
            fixture.componentInstance.flat = true;
            fixture.componentInstance.specs = specs;
            fixture.detectChanges();
            return fixture;
        }

        function row(fixture: { nativeElement: HTMLElement }, key: string): HTMLElement {
            return fixture.nativeElement.querySelector(`.sf-row[data-key="${key}"]`) as HTMLElement;
        }

        it('renders a string spec as ONE row: label, display value and a pencil — no mat-label, no input', () => {
            const fixture = createFlat();
            const r = row(fixture, 'encoding');
            expect(r).toBeTruthy();
            expect(r.textContent).toContain('Encoding');
            expect(r.querySelector('.sf-value')?.textContent?.trim()).toBe('—'); // no value, no default
            expect(r.querySelector('[aria-label="Edit Encoding"]')).toBeTruthy();
            expect(r.querySelector('input')).toBeNull();
            expect(fixture.nativeElement.querySelector('mat-label')).toBeNull();
        });

        it('renders a boolean as a toggle with no pencil', () => {
            const fixture = createFlat();
            const r = row(fixture, 'header');
            expect(r.querySelector('mat-slide-toggle')).toBeTruthy();
            expect(r.querySelector('.sf-pencil')).toBeNull();
            expect(fixture.componentInstance.value()['header']).toBe(true);
        });

        it('pencil → inline input; typing + Enter updates value(), leaves edit mode and emits submitted', () => {
            const fixture = createFlat();
            let submits = 0;
            fixture.componentInstance.submitted.subscribe(() => submits++);

            (row(fixture, 'encoding').querySelector('[aria-label="Edit Encoding"]') as HTMLButtonElement).click();
            fixture.detectChanges();
            const input = row(fixture, 'encoding').querySelector('input') as HTMLInputElement;
            expect(input).toBeTruthy();
            expect(fixture.componentInstance.editingKey()).toBe('encoding');
            // No floating label: the row label is the accessible name.
            expect(input.getAttribute('aria-labelledby')).toBe(fixture.componentInstance.labelId(FLAT[1]));
            expect(input.closest('mat-form-field')?.classList.contains('sf-dense')).toBe(true);

            input.value = 'UTF-8';
            input.dispatchEvent(new Event('input'));
            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
            input.form!.dispatchEvent(new Event('submit')); // what the browser does on Enter
            fixture.detectChanges();

            expect(fixture.componentInstance.value()['encoding']).toBe('UTF-8');
            expect(fixture.componentInstance.editingKey()).toBeNull();
            expect(row(fixture, 'encoding').querySelector('input')).toBeNull();
            expect(row(fixture, 'encoding').querySelector('.sf-value')?.textContent?.trim()).toBe('UTF-8');
            expect(submits).toBe(1);
        });

        it('shows a spec default as the display value before any edit', () => {
            const fixture = createFlat();
            expect(row(fixture, 'quote').querySelector('.sf-value')?.textContent?.trim()).toBe('"');
            expect(row(fixture, 'skip').querySelector('.sf-value')?.textContent?.trim()).toBe('0');
        });

        it('renders help as an info tooltip trigger, not as text under the field', () => {
            const fixture = createFlat();
            const r = row(fixture, 'encoding');
            const info = r.querySelector('mat-icon[aria-label="Byte encoding of the file"]');
            expect(info).toBeTruthy();
            expect(r.querySelector('mat-hint')).toBeNull();
            // The help text appears ONLY as the icon's accessible name — never as visible prose.
            const prose = Array.from(fixture.nativeElement.querySelectorAll('p, mat-hint')).map(
                (e: Element) => e.textContent,
            );
            expect(prose.join(' ')).not.toContain('Byte encoding');
        });

        it('lays rows out in a two-column grid when columns() is 2, with group headings spanning both', () => {
            const fixture = createFlat();
            const container = () => row(fixture, 'quote').parentElement as HTMLElement;
            expect(container().classList.contains('grid')).toBe(false);

            fixture.componentInstance.columns.set(2);
            fixture.detectChanges();
            expect(container().classList.contains('grid')).toBe(true);
            expect(container().classList.contains('grid-cols-2')).toBe(true);
            const heading = fixture.nativeElement.querySelector('[role="heading"]') as HTMLElement;
            expect(heading.textContent?.trim()).toBe('Rows');
            expect(heading.classList.contains('col-span-2')).toBe(true);
        });

        it('renders a select inline as the shared picker (its own click-to-pick row), no pencil', () => {
            const fixture = createFlat(SPECS);
            const r = row(fixture, 'type');
            expect(r.querySelector('inspecto-option-picker')).toBeTruthy();
            expect(r.querySelector('.sf-pencil')).toBeNull();
            expect(r.textContent).toContain('Enrich');
        });

        it('leaves the NON-flat path untouched: Optional settings (N) still renders', () => {
            const fixture = create();
            expect(fixture.nativeElement.textContent).toContain('Optional settings (2)');
            expect(fixture.nativeElement.querySelector('.sf-row')).toBeNull();
        });

        it('has no axe violations in flat mode, display and edit', async () => {
            const fixture = createFlat(SPECS);
            await expectNoA11yViolations(fixture.nativeElement);
            fixture.componentInstance.startEditing(SPECS[0]);
            fixture.detectChanges();
            await expectNoA11yViolations(fixture.nativeElement);
        });
    });
});
