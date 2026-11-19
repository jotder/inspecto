import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { afterEach, describe, expect, it } from 'vitest';
import { apiUrl } from '@inspecto/core/api';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { penByLabel, penEdit, penError, pickerSet } from '@inspecto/core/testing/pen-field';
import { OnboardDraft, PostgresTest } from './add-data.model';
import { PostgresTableComponent } from './postgres-table.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [PostgresTableComponent],
    template: `<inspecto-postgres-table kind="reference" (drafted)="drafts.push($event)"></inspecto-postgres-table>`,
})
class HostComponent {
    readonly drafts: OnboardDraft[] = [];
}

const TESTED: PostgresTest = {
    connected: true,
    user: 'reader',
    readOnly: true,
    tables: [
        { schema: 'sales', table: 'transfers', view: false },
        { schema: 'sales', table: 'daily', view: true },
    ],
    truncated: false,
};

let http: HttpTestingController;

function create() {
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [provideNoopAnimations(), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    http = TestBed.inject(HttpTestingController);
    return { fixture, el: fixture.nativeElement as HTMLElement };
}

const q = (el: HTMLElement, id: string) => el.querySelector<HTMLElement>(`[data-testid="${id}"]`);

function fill(fixture: ReturnType<typeof create>['fixture'], el: HTMLElement, password = '${ENV:INSPECTO_PG_SALES}') {
    type(fixture, el, /^Host\b/, 'db.internal');
    type(fixture, el, /^Database\b/, 'sales');
    type(fixture, el, /^Username\b/, 'reader');
    type(fixture, el, /^Password\b/, password);
}

/** The boxed Password row (a secret reference, never shown in a closed row). */
const passwordBox = (el: HTMLElement) =>
    Array.from(el.querySelectorAll<HTMLElement>('mat-form-field')).find(
        (f) => f.querySelector('mat-label')?.textContent?.trim() === 'Password',
    );

/**
 * Type into the connection row a person reads as `label`, whether it is a pen-field row or a boxed field (a secret), and
 * leave it. BASIC-ELT-PROFILE-1 U2 moved these rows onto the core `connection-fields`; the same typing must send the same
 * bytes.
 */
function type(fixture: ReturnType<typeof create>['fixture'], el: HTMLElement, label: RegExp, value: string) {
    const pen = Array.from(el.querySelectorAll<HTMLElement>('inspecto-pen-field')).find((h) =>
        label.test((h.querySelector('span')?.textContent ?? '').trim()),
    );
    if (pen) {
        penEdit(fixture, pen, value);
        return;
    }
    const box = Array.from(el.querySelectorAll<HTMLElement>('mat-form-field')).find((f) =>
        label.test((f.querySelector('mat-label')?.textContent ?? '').trim()),
    );
    if (!box) throw new Error('no connection row reads ' + label);
    const input = box.querySelector('input')!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    input.dispatchEvent(new Event('blur'));
    fixture.detectChanges();
}

/** The same user actions as before U2: every connection row typed, padded with blanks the screen trims. */
function typeAll(fixture: ReturnType<typeof create>['fixture'], el: HTMLElement) {
    type(fixture, el, /^Host\b/, '  db.internal ');
    type(fixture, el, /^Port\b/, '6543');
    type(fixture, el, /^Database\b/, ' sales ');
    type(fixture, el, /^User(name)?(\s|\*|$)/, ' reader ');
    type(fixture, el, /^Password\b/, ' ${ENV:INSPECTO_PG_SALES} ');
}

const CONNECTION_JSON =
    '{"host":"db.internal","port":6543,"database":"sales","user":"reader","password":"${ENV:INSPECTO_PG_SALES}"}';

describe('PostgresTableComponent - request bodies (BASIC-ELT-PROFILE-1 U2 parity, pinned on the pre-U2 form)', () => {
    afterEach(() => http?.verify());

    it('Test connection, then a table: the two bodies byte for byte', () => {
        const { fixture, el } = create();
        typeAll(fixture, el);
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        const test = http.expectOne(apiUrl('/onboard/postgres/test'));
        expect(JSON.stringify(test.request.body)).toBe(`{"connection":${CONNECTION_JSON}}`);
        test.flush(TESTED);
        fixture.detectChanges();
        pickerSet(fixture, '[data-testid="pg-table"]', 'sales.daily');
        q(el, 'pg-read')!.click();
        fixture.detectChanges();
        const read = http.expectOne(apiUrl('/onboard/postgres'));
        expect(JSON.stringify(read.request.body)).toBe(
            `{"connection":${CONNECTION_JSON},"schema":"sales","table":"daily","kind":"reference"}`,
        );
        read.flush({ draftId: 'd1' } as unknown as OnboardDraft);
    });

    it('a read-only query: the body byte for byte', () => {
        const { fixture, el } = create();
        typeAll(fixture, el);
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        http.expectOne(apiUrl('/onboard/postgres/test')).flush(TESTED);
        fixture.detectChanges();
        const radios = q(el, 'pg-by')!.querySelectorAll<HTMLInputElement>('input[type="radio"]');
        radios[1].click();
        fixture.detectChanges();
        penEdit(fixture, q(el, 'pg-query')!, ' SELECT id FROM sales.daily ');
        q(el, 'pg-read')!.click();
        fixture.detectChanges();
        const read = http.expectOne(apiUrl('/onboard/postgres'));
        expect(JSON.stringify(read.request.body)).toBe(
            `{"connection":${CONNECTION_JSON},"query":"SELECT id FROM sales.daily","kind":"reference"}`,
        );
        read.flush({ draftId: 'd2' } as unknown as OnboardDraft);
    });

    it('sends nothing while a row is missing or the port is out of range, and a changed row asks for a new test', () => {
        const { fixture, el } = create();
        type(fixture, el, /^Host\b/, 'db.internal');
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        http.expectNone(apiUrl('/onboard/postgres/test'));
        typeAll(fixture, el);
        type(fixture, el, /^Port\b/, '70000');
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        http.expectNone(apiUrl('/onboard/postgres/test'));
        type(fixture, el, /^Port\b/, '6543');
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        http.expectOne(apiUrl('/onboard/postgres/test')).flush(TESTED);
        fixture.detectChanges();
        expect(q(el, 'pg-read')).not.toBeNull();
        type(fixture, el, /^Database\b/, 'other');
        expect(q(el, 'pg-read')).toBeNull();
    });
});

describe('PostgresTableComponent - Choose a table (BASIC-ONBOARD-1 P3)', () => {
    afterEach(() => http?.verify());

    it('asks for the connection on the core Connection rows, the password as a reference (no violations)', async () => {
        const { el } = create();
        expect(el.querySelector('inspecto-connection-fields')).not.toBeNull();
        for (const label of ['Host', 'Port', 'Database', 'Username'])
            expect(penByLabel(el, label), label).not.toBeNull();
        expect(passwordBox(el)!.textContent).toContain('INSPECTO_PG_');
        expect(q(el, 'pg-table')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('refuses a raw password on the screen and sends nothing', () => {
        const { fixture, el } = create();
        fill(fixture, el, 'hunter2');
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        expect(passwordBox(el)!.querySelector('mat-error')!.textContent).toContain('Write it as ${ENV:INSPECTO_PG_…}');
        expect(penError(penByLabel(el, 'Host')!)).toBe('');
        http.expectNone(apiUrl('/onboard/postgres/test'));
    });

    it('tests the connection, picks a table and emits the draft the server copied', () => {
        const { fixture, el } = create();
        fill(fixture, el);
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        const test = http.expectOne(apiUrl('/onboard/postgres/test'));
        expect(test.request.body).toEqual({
            connection: {
                host: 'db.internal',
                port: 5432,
                database: 'sales',
                user: 'reader',
                password: '${ENV:INSPECTO_PG_SALES}',
            },
        });
        test.flush(TESTED);
        fixture.detectChanges();
        expect(q(el, 'pg-connected')!.textContent).toContain('Connected as reader, read-only: 2');

        // reading without a table names what is missing
        q(el, 'pg-read')!.click();
        fixture.detectChanges();
        expect(q(el, 'pg-pick-error')!.textContent).toContain('Choose a table');
        http.expectNone(apiUrl('/onboard/postgres'));

        pickerSet(fixture, '[data-testid="pg-table"]', 'sales.transfers');
        q(el, 'pg-read')!.click();
        fixture.detectChanges();
        const read = http.expectOne(apiUrl('/onboard/postgres'));
        expect(read.request.body).toMatchObject({ schema: 'sales', table: 'transfers', kind: 'reference' });
        read.flush({ draftId: 'd1' } as unknown as OnboardDraft);
        fixture.detectChanges();
        expect(fixture.componentInstance.drafts.map((d) => d.draftId)).toEqual(['d1']);
    });

    it("says the server's refusal (a login that could write)", () => {
        const { fixture, el } = create();
        fill(fixture, el);
        q(el, 'pg-test')!.click();
        http.expectOne(apiUrl('/onboard/postgres/test')).flush(
            { error: { message: "'writer' may change 2 tables" } },
            { status: 422, statusText: 'Unprocessable' },
        );
        fixture.detectChanges();
        expect(q(el, 'pg-error')!.textContent).toContain('may change 2 tables');
        expect(q(el, 'pg-table')).toBeNull();
    });
});
