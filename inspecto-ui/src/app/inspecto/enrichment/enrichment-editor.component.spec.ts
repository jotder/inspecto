import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ToastrService } from 'ngx-toastr';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { EnrichmentEditorComponent } from './enrichment-editor.component';

const TOASTR = { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() };

async function create() {
    TestBed.configureTestingModule({
        imports: [EnrichmentEditorComponent],
        providers: [provideNoopAnimations(), { provide: ToastrService, useValue: TOASTR }],
    });
    await TestBed.compileComponents();
    const fixture = TestBed.createComponent(EnrichmentEditorComponent);
    fixture.componentInstance.referenceOptions = [{ id: 'region_dim', label: 'REGION_DIM' }];
    fixture.detectChanges();
    return fixture;
}

describe('EnrichmentEditorComponent', () => {
    beforeEach(() => Object.values(TOASTR).forEach((f) => f.mockClear()));

    it('builds the references map + transform from valid rows', async () => {
        const fixture = await create();
        const c = fixture.componentInstance;
        c.addReference();
        c.referenceRows.at(0).get('name')?.setValue('region_dim');
        c.referenceRows.at(0).get('ref')?.setValue('region_dim');
        c.onSqlChange('SELECT * FROM input i LEFT JOIN region_dim r ON i.R = r.R');
        expect(c.build()).toEqual({
            references: { region_dim: { ref: 'region_dim' } },
            transform: 'SELECT * FROM input i LEFT JOIN region_dim r ON i.R = r.R',
        });
    });

    it('round-trips as_of on a by-name reference (used to be dropped by the onboarding pane)', async () => {
        const fixture = await create();
        const c = fixture.componentInstance;
        c.hydrate({
            references: { region_dim: { ref: 'region_dim', as_of: 'EVENT_DATE' } },
            transform: 'SELECT 1 FROM input',
        });
        expect(c.referenceRows.at(0).get('asOf')?.value).toBe('EVENT_DATE');
        expect(c.build()?.references).toEqual({ region_dim: { ref: 'region_dim', as_of: 'EVENT_DATE' } });
        // and it stays optional — an empty as_of never emits the key
        c.referenceRows.at(0).get('asOf')?.setValue('');
        expect(c.build()?.references).toEqual({ region_dim: { ref: 'region_dim' } });
    });

    it('hydrates path-mode rows and reports pristine until edited', async () => {
        const fixture = await create();
        const c = fixture.componentInstance;
        c.hydrate({
            references: { zones: { path: 'data/zones.csv', format: 'CSV' } },
            transform: 'SELECT * FROM input',
        });
        expect(c.referenceRows.at(0).get('mode')?.value).toBe('path');
        expect(c.isDirty()).toBe(false);
        c.onSqlChange('SELECT 2 FROM input');
        expect(c.isDirty()).toBe(true);
        c.markSaved();
        expect(c.isDirty()).toBe(false);
    });

    it('blocks build on a duplicate alias, an invalid identifier, a missing binding and blank SQL', async () => {
        const fixture = await create();
        const c = fixture.componentInstance;
        // duplicate alias
        c.addReference();
        c.addReference();
        c.referenceRows.at(0).get('name')?.setValue('dupe');
        c.referenceRows.at(0).get('ref')?.setValue('a');
        c.referenceRows.at(1).get('name')?.setValue('dupe');
        c.referenceRows.at(1).get('ref')?.setValue('b');
        expect(c.build()).toBeNull();
        c.removeReference(1);
        // ref mode without a binding
        c.referenceRows.at(0).get('ref')?.setValue('');
        expect(c.build()).toBeNull();
        // invalid identifier
        c.referenceRows.at(0).get('name')?.setValue('1bad');
        expect(c.build()).toBeNull();
        c.removeReference(0);
        // blank SQL
        c.onSqlChange('   ');
        expect(c.build()).toBeNull();
        expect(TOASTR.warning).toHaveBeenCalledTimes(4);
    });

    it('exposes input + the valid aliases as the available views', async () => {
        const fixture = await create();
        const c = fixture.componentInstance;
        expect(c.availableViews()).toEqual(['input']);
        c.addReference();
        c.referenceRows.at(0).get('name')?.setValue('region_dim');
        expect(c.availableViews()).toEqual(['input', 'region_dim']);
    });

    it('has no a11y violations with rows in both bind modes', async () => {
        const fixture = await create();
        const c = fixture.componentInstance;
        c.hydrate({
            references: { a: { ref: 'region_dim' }, b: { path: 'x.csv', format: 'CSV' } },
            transform: 'SELECT 1',
        });
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
