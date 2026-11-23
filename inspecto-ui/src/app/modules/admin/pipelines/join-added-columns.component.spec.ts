import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentsService, JoinAddedColumn } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { JoinAddedColumnsComponent } from './join-added-columns.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [JoinAddedColumnsComponent],
    template: `<inspecto-join-added-columns [config]="config()" [inputColumns]="['id', 'SERVED_MSISDN']" />`,
})
class HostComponent {
    readonly config = signal<Record<string, unknown>>({});
}

const COLUMNS: JoinAddedColumn[] = [
    {
        name: 'SERVED_MSISDN_OPER',
        type: 'VARCHAR',
        from: 'provider_desc',
        lookedUpBy: 'SERVED_MSISDN',
        match: 'longest_prefix',
        detail: null,
    },
    {
        name: 'SERVED_MSISDN_match_kind',
        type: 'VARCHAR',
        from: null,
        lookedUpBy: 'SERVED_MSISDN',
        match: 'longest_prefix',
        detail: 'match_kind',
    },
];

const KEYED = { reference: 'reference/dial_digit', key: 'dial_digit_key', lookups: [{ on: 'SERVED_MSISDN' }] };

function setup(answer: ReturnType<ComponentsService['joinAddedColumns']>) {
    const joinAddedColumns = vi.fn(() => answer);
    TestBed.configureTestingModule({
        imports: [HostComponent],
        providers: [{ provide: ComponentsService, useValue: { joinAddedColumns } }],
    });
    const fixture = TestBed.createComponent(HostComponent);
    return { fixture, joinAddedColumns };
}

describe('JoinAddedColumnsComponent (LOOKUP-1 P1d)', () => {
    it('lists each added column with its type, origin and lookup', async () => {
        const { fixture, joinAddedColumns } = setup(of({ columns: COLUMNS }));
        fixture.componentInstance.config.set(KEYED);
        fixture.detectChanges();
        expect(joinAddedColumns).toHaveBeenCalledWith(KEYED, ['id', 'SERVED_MSISDN']);
        const rows = Array.from(
            fixture.nativeElement.querySelectorAll('[data-testid="join-added-columns"] tbody tr'),
        ) as HTMLElement[];
        expect(rows.map((r) => r.querySelector('td')!.textContent!.trim())).toEqual([
            'SERVED_MSISDN_OPER',
            'SERVED_MSISDN_match_kind',
        ]);
        expect(rows[0].textContent).toContain('provider_desc');
        expect(rows[0].textContent).toContain('SERVED_MSISDN (longest_prefix)');
        expect(rows[1].textContent).toContain('(how it matched)');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows the server refusal by name instead of a table, and nothing for a non-keyed join', () => {
        const refusal = new HttpErrorResponse({
            status: 422,
            error: {
                error: {
                    message: "the returned column 'EVENT_CATEGORY_KEY' clashes with a column the rows already have",
                },
            },
        });
        const { fixture, joinAddedColumns } = setup(throwError(() => refusal));
        fixture.componentInstance.config.set({ reference: 'reference/rates', on: 'currency' });
        fixture.detectChanges();
        expect(joinAddedColumns).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).not.toContain('Columns this Step adds');

        fixture.componentInstance.config.set(KEYED);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('[data-testid="join-added-columns"]')).toBeNull();
        expect(fixture.nativeElement.querySelector('inspecto-alert')).not.toBeNull();
    });
});
