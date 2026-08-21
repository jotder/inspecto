import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it, vi } from 'vitest';
import { PipelineNodeType } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NodeTypeGroup } from './pipeline-graph';
import { PipelinePaletteComponent } from './pipeline-palette.component';

const type = (t: string, category: string, label: string, lowerable = true): PipelineNodeType => ({
    type: t,
    category,
    label,
    description: `Add a ${label}`,
    accepts: [],
    emits: [],
    emitsNamedRoutes: false,
    lowerable,
});

const GROUPS: NodeTypeGroup[] = [
    { category: 'SOURCE', types: [type('acquisition', 'SOURCE', 'File')] },
    { category: 'SINK', types: [type('sink.persistent', 'SINK', 'File writer')] },
];

function create() {
    TestBed.configureTestingModule({
        imports: [PipelinePaletteComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelinePaletteComponent);
    fixture.componentRef.setInput('groups', GROUPS);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const text = (fixture: { nativeElement: HTMLElement }): string => fixture.nativeElement.textContent ?? '';

describe('PipelinePaletteComponent', () => {
    it('is docked and expanded by default — every group and type is listed', () => {
        const { fixture } = create();
        expect(text(fixture)).toContain('File');
        expect(text(fixture)).toContain('File writer');
    });

    it('emits pick with the type id on click-to-add', () => {
        const { fixture, c } = create();
        const pick = vi.fn();
        c.pick.subscribe(pick);
        const btn = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
            (b) => b.getAttribute('aria-label') === 'Add File',
        );
        btn?.click();
        expect(pick).toHaveBeenCalledWith('acquisition');
    });

    it('filters the catalog by the search query, dropping groups with no hit', () => {
        const { fixture, c } = create();
        c.query.set('writer');
        fixture.detectChanges();
        expect(text(fixture)).toContain('File writer');
        expect(c.filtered().map((g) => g.category)).toEqual(['SINK']);
    });

    it('folds a category away on its header click, and reports the empty search case', () => {
        const { fixture, c } = create();
        c.toggleGroup('SINK');
        fixture.detectChanges();
        expect(c.isOpen('SINK')).toBe(false);
        expect(text(fixture)).not.toContain('File writer');

        // A search overrides the fold — a user searching wants to see the hits.
        c.query.set('writer');
        fixture.detectChanges();
        expect(c.isOpen('SINK')).toBe(true);

        c.query.set('nothing-matches-this');
        fixture.detectChanges();
        expect(text(fixture)).toContain('No step type matches');
    });

    it('renders the catalog when it arrives AFTER the first render (it loads async)', () => {
        // Regression: `filtered()` is a computed, so `groups` must be a signal input — with a plain
        // @Input it cached the empty first pass and the docked palette stayed empty forever.
        TestBed.configureTestingModule({
            imports: [PipelinePaletteComponent],
            providers: [provideNoopAnimations()],
        });
        const fixture = TestBed.createComponent(PipelinePaletteComponent);
        fixture.componentRef.setInput('groups', []);
        fixture.detectChanges();
        expect(text(fixture)).toContain('No step type matches');

        fixture.componentRef.setInput('groups', GROUPS);
        fixture.detectChanges();
        expect(text(fixture)).toContain('File writer');
    });

    it('renders every entry addable and draggable — the host pre-filters to lowerable types', () => {
        const { fixture } = create();
        const btn = (label: string) =>
            Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find((b) =>
                b.textContent?.includes(label),
            )!;
        expect(btn('File').disabled).toBe(false);
        expect(btn('File').getAttribute('draggable')).toBe('true');
        expect(btn('File writer').getAttribute('draggable')).toBe('true');
    });

    /**
     * Operator ask (2026-08-21): each item carries its OWN glyph; the category is the COLOR. Two
     * items of different types must not share an icon, and every item icon is tinted with its
     * group's `categoryColor` — the glyph identifies the Step, the tint says which family.
     */
    it('gives every item its own type glyph, tinted by its category color', () => {
        const { fixture } = create();
        const items = Array.from(
            (fixture.nativeElement as HTMLElement).querySelectorAll('button[draggable="true"] mat-icon'),
        ) as HTMLElement[];
        expect(items).toHaveLength(2);
        const names = items.map((i) => i.getAttribute('data-mat-icon-name'));
        expect(names[0]).toBe('arrow-down-on-square'); // acquisition's own glyph
        expect(names[1]).toBe('circle-stack'); // sink.persistent's own glyph
        expect(names[0]).not.toBe(names[1]);
        for (const i of items) expect(i.style.color).not.toBe('');
    });

    /** An unknown / plugin-served type falls back to its CATEGORY glyph rather than rendering blank. */
    it('falls back to the category glyph for a type it does not know', () => {
        TestBed.configureTestingModule({ imports: [PipelinePaletteComponent], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(PipelinePaletteComponent);
        fixture.componentRef.setInput('groups', [
            { category: 'TRANSFORM', types: [type('plugin.acme', 'TRANSFORM', 'Acme')] },
        ]);
        fixture.detectChanges();
        const icon = (fixture.nativeElement as HTMLElement).querySelector(
            'button[draggable="true"] mat-icon',
        ) as HTMLElement;
        expect(icon.getAttribute('data-mat-icon-name')).toBe('arrows-right-left'); // TRANSFORM's glyph
    });

    it('has no a11y violations expanded or filtered', async () => {
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        c.query.set('writer');
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
