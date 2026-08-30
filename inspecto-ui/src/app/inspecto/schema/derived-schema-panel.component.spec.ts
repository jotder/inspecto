import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { ConfigService, DerivedSchemaResult } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { DerivedSchemaPanelComponent } from './derived-schema-panel.component';

@Component({
    standalone: true,
    imports: [DerivedSchemaPanelComponent],
    changeDetection: ChangeDetectionStrategy.Default,
    template: `<inspecto-derived-schema-panel [pipeline]="pipeline()" />`,
})
class HostComponent {
    readonly pipeline = signal('cdr_pipeline');
}

const RESULT: DerivedSchemaResult = {
    pipeline: 'cdr_pipeline',
    sourcePath: 'csv',
    typedSource: false,
    ingesterClass: null,
    schemas: [
        {
            key: 'single',
            table: 'cdr',
            columns: [
                { name: 'ID', type: 'VARCHAR' },
                { name: 'AMT', type: 'DOUBLE' },
            ],
        },
    ],
};

describe('DerivedSchemaPanelComponent', () => {
    let fixture: ComponentFixture<HostComponent>;

    const build = async (config: Partial<ConfigService>): Promise<void> => {
        TestBed.configureTestingModule({
            imports: [HostComponent],
            providers: [{ provide: ConfigService, useValue: config }],
        });
        fixture = TestBed.createComponent(HostComponent);
        fixture.detectChanges();
        await fixture.whenStable();
        fixture.detectChanges();
    };

    beforeEach(() => TestBed.resetTestingModule());

    it('renders the derived columns and their types', async () => {
        await build({ derivedSchema: () => of(RESULT) } as Partial<ConfigService>);

        const text = fixture.nativeElement.textContent as string;
        expect(text).toContain('ID');
        expect(text).toContain('DOUBLE');
    });

    /**
     * 🔴 The whole reason the route reports sourcePath/typedSource. On the CSV path raw columns are
     * VARCHAR; on the plugin path the declared types stand — so the same Schema derives different
     * types. A panel that showed types without saying which path produced them would present an
     * assumption as a fact, which is the trap the backend was built to close.
     */
    it('states which source path the types came from', async () => {
        await build({ derivedSchema: () => of(RESULT) } as Partial<ConfigService>);
        expect(fixture.nativeElement.textContent).toContain('CSV path');

        TestBed.resetTestingModule();
        await build({
            derivedSchema: () => of({ ...RESULT, sourcePath: 'plugin', typedSource: true }),
        } as Partial<ConfigService>);
        expect(fixture.nativeElement.textContent).toContain('Plugin path');
    });

    /** A 422 is the author's own config failing to bind — the most actionable thing this route says. */
    it('surfaces a non-binding schema as a warning carrying the binder message', async () => {
        const err = new HttpErrorResponse({
            status: 422,
            error: { error: { message: 'Referenced column "NOPE" not found' } },
        });
        await build({ derivedSchema: () => throwError(() => err) } as Partial<ConfigService>);

        const alert = fixture.nativeElement.querySelector('inspecto-alert');
        expect(alert).toBeTruthy();
        expect(fixture.nativeElement.textContent).toContain('NOPE');
    });

    /** A transport failure is a different thing from a config that does not compile — don't merge them. */
    it('reports a transport failure separately from a binder refusal', async () => {
        await build({
            derivedSchema: () => throwError(() => new HttpErrorResponse({ status: 500 })),
        } as Partial<ConfigService>);

        expect(fixture.nativeElement.textContent).toContain('Could not derive');
        expect(fixture.nativeElement.textContent).not.toContain('do not compile');
    });

    /** A pipeline with no schema is an honest empty state, not an error. */
    it('shows an empty state when the pipeline declares no schema', async () => {
        await build({ derivedSchema: () => of({ ...RESULT, schemas: [] }) } as Partial<ConfigService>);
        expect(fixture.nativeElement.querySelector('inspecto-empty-state')).toBeTruthy();
    });

    /** A blank name fetches nothing — a draft has no saved config to derive from. */
    it('fetches nothing without a pipeline name', async () => {
        let calls = 0;
        TestBed.configureTestingModule({
            imports: [HostComponent],
            providers: [
                {
                    provide: ConfigService,
                    useValue: {
                        derivedSchema: () => {
                            calls++;
                            return of(RESULT);
                        },
                    },
                },
            ],
        });
        fixture = TestBed.createComponent(HostComponent);
        fixture.componentInstance.pipeline.set('');
        fixture.detectChanges();
        await fixture.whenStable();

        expect(calls).toBe(0);
    });

    it('has no accessibility violations', async () => {
        await build({ derivedSchema: () => of(RESULT) } as Partial<ConfigService>);
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
