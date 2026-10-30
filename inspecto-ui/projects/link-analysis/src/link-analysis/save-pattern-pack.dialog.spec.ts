import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { ComponentsService } from '@inspecto/core/api';
import { InspectoConfirmService } from '@inspecto/core/confirm.service';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { patternPackFromContent, patternPackId, patternPackToContent } from './pattern-packs';
import { SavePatternPackData, SavePatternPackDialog } from './save-pattern-pack.dialog';

const FAN: SavePatternPackData = {
    steps: [],
    stages: [
        { shape: 'fan-in', minBranches: 5, edgeKind: 'pays', threshold: { attr: 'AMOUNT', min: 900, max: 1000 } },
        { shape: 'fan-out', minBranches: 2, afterPrevious: true, maxGapHours: 48 },
    ],
    takenIds: ['structuring'],
};

describe('pattern pack content (LA-PRODUCT-NEXT-1 phase 5b)', () => {
    it('a linear motif round-trips through the persisted shape, wildcards and order kept', () => {
        const pack = {
            id: 'mule-chain',
            label: 'Mule chain',
            category: 'money' as const,
            description: 'Three hops',
            steps: [
                {},
                { direction: 'out' as const, edgeKind: 'pays' },
                { direction: 'in' as const, nodeKind: 'acct', afterPrevious: true },
            ],
        };
        expect(patternPackFromContent(patternPackToContent(pack))).toEqual(pack);
    });

    it('a branching motif round-trips, every row carrying every column', () => {
        const content = patternPackToContent({
            id: 'fan',
            label: 'Fan',
            category: 'money',
            description: 'd',
            steps: [],
            stages: FAN.stages!,
        });
        const rows = content['stages'] as Record<string, unknown>[];
        expect(Object.keys(rows[0])).toEqual(Object.keys(rows[1])); // a tabular TOON array, not ragged rows
        expect(patternPackFromContent(content)?.stages).toEqual(FAN.stages);
    });

    it('a band whose bounds were both cleared is saved as no band, so the pack still reads back', () => {
        const content = patternPackToContent({
            id: 'p',
            label: 'P',
            category: 'money',
            description: 'd',
            steps: [],
            stages: [{ shape: 'fan-in', minBranches: 3, threshold: { attr: 'AMOUNT' } }],
        });
        expect(patternPackFromContent(content)?.stages).toEqual([{ shape: 'fan-in', minBranches: 3 }]);
    });

    it('names a pack id from its label', () => {
        expect(patternPackId('  Mule ring (v2)! ')).toBe('mule-ring-v2');
        expect(patternPackId('!!!')).toBe('');
    });
});

describe('SavePatternPackDialog', () => {
    function make(create = vi.fn(() => of({}))) {
        const close = vi.fn();
        TestBed.configureTestingModule({
            imports: [SavePatternPackDialog],
            providers: [
                provideNoopAnimations(),
                { provide: MAT_DIALOG_DATA, useValue: FAN },
                {
                    provide: MatDialogRef,
                    useValue: { close, disableClose: false, backdropClick: () => of(), keydownEvents: () => of() },
                },
                { provide: ComponentsService, useValue: { create } },
                { provide: InspectoConfirmService, useValue: { confirmDestructive: () => Promise.resolve(true) } },
            ],
        });
        const fixture = TestBed.createComponent(SavePatternPackDialog);
        fixture.detectChanges();
        return { fixture, c: fixture.componentInstance, close, create, el: fixture.nativeElement as HTMLElement };
    }

    it('asks for a name and a description, and refuses a name a pack already has', async () => {
        const { fixture, c, create } = make();
        await c.submit();
        fixture.detectChanges();
        expect(c.labelError()).toBe('Name the pack.');
        expect(c.descriptionError()).toBe('Say what the pattern looks for.');
        c.form.setValue({ label: 'Structuring', category: 'money', description: 'x' });
        await c.submit();
        expect(c.labelError()).toContain('exists');
        expect(create).not.toHaveBeenCalled();
    });

    it('saves through the pattern-pack component route and closes with the pack', async () => {
        const { c, create, close } = make();
        c.form.setValue({ label: 'Collect then split', category: 'money', description: 'Five in, two out' });
        await c.submit();
        expect(create).toHaveBeenCalledWith(
            'pattern-pack',
            expect.objectContaining({ id: 'collect-then-split', label: 'Collect then split' }),
        );
        expect(close).toHaveBeenCalledWith({
            pack: expect.objectContaining({ id: 'collect-then-split', stages: FAN.stages, steps: [] }),
            held: null,
        });
    });

    it('a write held for approval closes as held, not saved', async () => {
        const held = { id: 'pc-7', kind: 'pattern-pack', name: 'mine' };
        const { c, close } = make(vi.fn(() => of({ status: 'pending', pendingChange: held }) as never));
        c.form.setValue({ label: 'Mine', category: 'money', description: 'd' });
        await c.submit();
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ held }));
    });

    it('a refusal stays in the dialog, in the server words', async () => {
        const { fixture, c, close, el } = make(
            vi.fn(() => throwError(() => ({ status: 403, error: { message: 'Needs canAuthorWorkbench' } }))),
        );
        c.form.setValue({ label: 'Mine', category: 'telecom', description: 'd' });
        await c.submit();
        fixture.detectChanges();
        expect(close).not.toHaveBeenCalled();
        expect(el.textContent).toContain('Not saved');
    });

    it('has no a11y violations', async () => {
        await expectNoA11yViolations(make().el);
    });
});
