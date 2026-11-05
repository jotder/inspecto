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
    penEdit(fixture, penByLabel(el, 'Host')!, 'db.internal');
    penEdit(fixture, penByLabel(el, 'Database')!, 'sales');
    penEdit(fixture, penByLabel(el, 'User')!, 'reader');
    penEdit(fixture, penByLabel(el, 'Password')!, password);
}

describe('PostgresTableComponent - Choose a table (BASIC-ONBOARD-1 P3)', () => {
    afterEach(() => http?.verify());

    it('asks for the connection with the password as a reference (no violations)', async () => {
        const { el } = create();
        for (const label of ['Host', 'Port', 'Database', 'User', 'Password'])
            expect(penByLabel(el, label), label).not.toBeNull();
        expect(q(el, 'pg-table')).toBeNull();
        await expectNoA11yViolations(el);
    });

    it('refuses a raw password on the screen and sends nothing', () => {
        const { fixture, el } = create();
        fill(fixture, el, 'hunter2');
        q(el, 'pg-test')!.click();
        fixture.detectChanges();
        expect(penError(penByLabel(el, 'Password')!)).toContain('INSPECTO_PG_');
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
        expect(q(el, 'pg-connected')!.textContent).toContain('Connected as reader, read-only - 2');

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
