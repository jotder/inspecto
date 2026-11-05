import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatRadioModule } from '@angular/material/radio';
import { apiErrorMessage } from '@inspecto/core/api';
import { InspectoAlertComponent } from '@inspecto/core/components/alert.component';
import { InspectoOptionPickerComponent, PickerOption } from '@inspecto/core/components/option-picker.component';
import { PenFieldComponent } from '@inspecto/core/components/pen-field.component';
import {
    ImportKind,
    OnboardDraft,
    PASSWORD_REFERENCE,
    PostgresConnection,
    PostgresPick,
    PostgresTest,
} from './add-data.model';
import { OnboardService } from './onboard.service';

/**
 * **Choose a table** - step 1 of Add data when the data is in Postgres (BASIC-ONBOARD-1 P3). Connect (host, port, database,
 * user, and the password as a `${ENV:NAME}` reference - never the password), **Test connection** (read-only; a login that
 * could write is refused by the server), pick a table or paste one read-only `SELECT`, then **Read the rows**: the server
 * copies them and answers the same draft a file gives, which the host reviews and creates as usual.
 *
 * @example <inspecto-postgres-table [kind]="kind.value" (drafted)="adoptDraft($event)" />
 */
@Component({
    selector: 'inspecto-postgres-table',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatRadioModule,
        InspectoAlertComponent,
        InspectoOptionPickerComponent,
        PenFieldComponent,
    ],
    host: { class: 'block' },
    template: `
        <form class="flex flex-col gap-3" [formGroup]="form" (ngSubmit)="test()" data-testid="postgres-table">
            <div class="grid grid-cols-1 gap-x-8 sm:grid-cols-2">
                <inspecto-pen-field
                    label="Host"
                    formControlName="host"
                    data-testid="pg-host"
                    [required]="true"
                    [error]="errorOf('host', 'Say where the database runs.')"
                ></inspecto-pen-field>
                <inspecto-pen-field
                    label="Port"
                    type="number"
                    formControlName="port"
                    data-testid="pg-port"
                    [error]="errorOf('port', 'A port is 1 to 65535.')"
                ></inspecto-pen-field>
                <inspecto-pen-field
                    label="Database"
                    formControlName="database"
                    data-testid="pg-database"
                    [required]="true"
                    [error]="errorOf('database', 'Name the database.')"
                ></inspecto-pen-field>
                <inspecto-pen-field
                    label="User"
                    formControlName="user"
                    data-testid="pg-user"
                    [required]="true"
                    help="A read-only login: CONNECT, USAGE on the schema and SELECT on its tables."
                    [error]="errorOf('user', 'Name the login.')"
                ></inspecto-pen-field>
                <inspecto-pen-field
                    label="Password"
                    formControlName="password"
                    data-testid="pg-password"
                    [mono]="true"
                    [required]="true"
                    placeholder="\${ENV:INSPECTO_PG_SALES}"
                    help="The name of an environment variable on the server, starting INSPECTO_PG_, that holds it - never the password itself."
                    [error]="
                        errorOf(
                            'password',
                            'Write it as \${ENV:INSPECTO_PG_…} - the variable on the server holding the password.'
                        )
                    "
                ></inspecto-pen-field>
            </div>
            <div class="flex flex-wrap items-center gap-2">
                <button mat-stroked-button type="submit" data-testid="pg-test" [disabled]="busy()">
                    Test connection
                </button>
                @if (tested(); as t) {
                    <span class="text-sm" role="status" data-testid="pg-connected"
                        >Connected as {{ t.user }}, read-only - {{ t.tables.length }}{{ t.truncated ? '+' : '' }}
                        {{ t.tables.length === 1 ? 'table' : 'tables' }}</span
                    >
                }
            </div>

            @if (tested()) {
                <mat-radio-group
                    class="flex flex-wrap gap-x-6 text-sm"
                    [formControl]="by"
                    aria-label="What to copy"
                    data-testid="pg-by"
                >
                    <mat-radio-button value="table">A table</mat-radio-button>
                    <mat-radio-button value="query">A read-only query</mat-radio-button>
                </mat-radio-group>
                @if (by.value === 'table') {
                    <inspecto-option-picker
                        label="Table"
                        data-testid="pg-table"
                        [formControl]="table"
                        [options]="tableOptions()"
                    ></inspecto-option-picker>
                } @else {
                    <inspecto-pen-field
                        label="Query"
                        data-testid="pg-query"
                        [formControl]="query"
                        [multiline]="true"
                        [mono]="true"
                        placeholder="SELECT … FROM …"
                        help="One SELECT (or WITH … SELECT). Add data never writes to your database."
                    ></inspecto-pen-field>
                }
                @if (pickError()) {
                    <p class="text-warn m-0 text-xs" role="alert" data-testid="pg-pick-error">{{ pickError() }}</p>
                }
                <button
                    mat-flat-button
                    color="primary"
                    type="button"
                    class="self-start"
                    data-testid="pg-read"
                    [disabled]="busy()"
                    (click)="read()"
                >
                    Read the rows
                </button>
            }
            @if (busy()) {
                <p class="m-0 text-sm" role="status">{{ reading() ? 'Copying the rows…' : 'Connecting…' }}</p>
            }
            @if (error()) {
                <inspecto-alert variant="error" data-testid="pg-error">{{ error() }}</inspecto-alert>
            }
        </form>
    `,
})
export class PostgresTableComponent {
    private readonly onboard = inject(OnboardService);
    private readonly destroyRef = inject(DestroyRef);
    private readonly fb = inject(FormBuilder);

    /** What the rows are: Events or Details (the host's choice above). */
    readonly kind = input<ImportKind>('stream');
    /** The rows were copied: the draft to review. */
    readonly drafted = output<OnboardDraft>();

    protected readonly form = this.fb.nonNullable.group({
        host: ['', Validators.required],
        port: [5432, [Validators.required, Validators.min(1), Validators.max(65535)]],
        database: ['', Validators.required],
        user: ['', Validators.required],
        password: ['', [Validators.required, Validators.pattern(PASSWORD_REFERENCE)]],
    });
    protected readonly by = this.fb.nonNullable.control<'table' | 'query'>('table');
    protected readonly table = this.fb.nonNullable.control('');
    protected readonly query = this.fb.nonNullable.control('');

    protected readonly busy = signal(false);
    protected readonly reading = signal(false);
    protected readonly error = signal('');
    protected readonly pickError = signal('');
    protected readonly tested = signal<PostgresTest | null>(null);
    /** Bumped on submit, so the rows' error lines re-render under OnPush. */
    private readonly submits = signal(0);

    protected readonly tableOptions = computed<PickerOption[]>(() =>
        (this.tested()?.tables ?? []).map((t) => ({
            value: `${t.schema}.${t.table}`,
            label: `${t.schema}.${t.table}`,
            hint: t.view ? 'a view' : undefined,
            group: t.schema,
        })),
    );

    constructor() {
        // A changed connection is not the one tested: test again before reading.
        this.form.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.tested.set(null));
    }

    protected errorOf(key: 'host' | 'port' | 'database' | 'user' | 'password', message: string): string {
        this.submits();
        const c = this.form.controls[key];
        return c.touched && c.invalid ? message : '';
    }

    private connection(): PostgresConnection | null {
        this.submits.update((n) => n + 1);
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return null;
        }
        const v = this.form.getRawValue();
        return {
            ...v,
            host: v.host.trim(),
            database: v.database.trim(),
            user: v.user.trim(),
            password: v.password.trim(),
        };
    }

    test(): void {
        const c = this.connection();
        if (!c) return;
        this.error.set('');
        this.busy.set(true);
        this.onboard
            .testPostgres(c)
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (t) => {
                    this.busy.set(false);
                    this.tested.set(t);
                },
                error: (err: unknown) => {
                    this.busy.set(false);
                    this.error.set(apiErrorMessage(err, 'The connection could not be tested.'));
                },
            });
    }

    read(): void {
        const c = this.connection();
        if (!c || !this.tested()) return;
        const pick = this.pick();
        this.pickError.set(pick ? '' : this.by.value === 'table' ? 'Choose a table.' : 'Write the query.');
        if (!pick) return;
        this.error.set('');
        this.busy.set(true);
        this.reading.set(true);
        this.onboard
            .draftPostgres(c, pick, this.kind())
            .pipe(takeUntilDestroyed(this.destroyRef))
            .subscribe({
                next: (d) => {
                    this.busy.set(false);
                    this.reading.set(false);
                    this.drafted.emit(d);
                },
                error: (err: unknown) => {
                    this.busy.set(false);
                    this.reading.set(false);
                    this.error.set(apiErrorMessage(err, 'The rows could not be copied.'));
                },
            });
    }

    private pick(): PostgresPick | null {
        if (this.by.value === 'query') {
            const q = this.query.value.trim();
            return q ? { query: q } : null;
        }
        const t = this.tested()?.tables.find((x) => `${x.schema}.${x.table}` === this.table.value);
        return t ? { schema: t.schema, table: t.table } : null;
    }
}
