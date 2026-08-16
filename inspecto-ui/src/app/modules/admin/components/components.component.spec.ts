import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideToastr } from 'ngx-toastr';
import { Observable, of } from 'rxjs';
import { describe, expect, it } from 'vitest';
import { ComponentDef, ComponentsService } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ComponentsComponent } from './components.component';

const GRAMMAR: ComponentDef = {
    type: 'grammar',
    name: 'pipe',
    ref: 'grammar/pipe',
    content: { delimiter: '|', has_header: true },
};
const TRANSFORM: ComponentDef = {
    type: 'transform',
    name: 'filt',
    ref: 'transform/filt',
    content: { type: 'transform.filter' },
};

/** Build the component over a stub service whose per-kind list() returns the given map. */
function create(lists: Partial<Record<string, Observable<ComponentDef[]>>>) {
    const stub = {
        list: (t: string) => lists[t] ?? of([]),
    } as unknown as ComponentsService;
    TestBed.configureTestingModule({
        imports: [ComponentsComponent],
        providers: [provideNoopAnimations(), provideToastr(), { provide: ComponentsService, useValue: stub }],
    });
    const fixture = TestBed.createComponent(ComponentsComponent);
    fixture.detectChanges(); // runs ngOnInit
    return fixture;
}

describe('ComponentsComponent', () => {
    it('lists components grouped by kind', () => {
        const c = create({ grammar: of([GRAMMAR]), transform: of([TRANSFORM]) }).componentInstance;
        expect(c.countFor('grammar')).toBe(1);
        expect(c.countFor('transform')).toBe(1);
        expect(c.countFor('sink')).toBe(0);
    });

    it('summarises a component per kind', () => {
        const c = create({}).componentInstance;
        expect(c.summary(GRAMMAR)).toContain('header');
        expect(c.summary(TRANSFORM)).toContain('transform.filter');
        expect(
            c.summary({ type: 'sink', name: 'o', ref: 'sink/o', content: { type: 'sink.view', store: 's' } }),
        ).toContain('sink.view');
    });

    // Not every Grammar is DSV. Reading a top-level `delimiter` gave the DEFAULT `,` for the seeded
    // asn1/json/xlsx components, so the list asserted a delimiter none of them has.
    it('names a non-delimited Grammar instead of inventing a delimiter', () => {
        const c = create({}).componentInstance;
        const grammar = (content: Record<string, unknown>) =>
            c.summary({ type: 'grammar' as const, name: 'g', ref: 'grammar/g', content });
        expect(grammar({ parser_type: 'asn1', encoding_rules: 'BER' })).toBe('asn1');
        expect(grammar({ plugin: { ingesterClass: 'com.gamma.X' } })).toBe('plugin');
        expect(grammar({ delimiter: '|', has_header: true })).toBe('delimiter |, header');
        // …and a nested block reads through the normaliser rather than missing the settings entirely.
        expect(grammar({ frontend: 'delimited', delimited: { delimiter: ';' } })).toBe('delimiter ;');
    });

    it('renders an accessible empty state when there are no components', async () => {
        const fixture = create({});
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
