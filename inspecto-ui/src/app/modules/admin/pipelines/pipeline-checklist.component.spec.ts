import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineChecklistComponent } from './pipeline-checklist.component';
import { StageChip } from './pipeline-stages';

const CHIPS: StageChip[] = [
    { id: 'collect', label: 'Collect', status: 'validated', findings: 0, nodeId: 'src', optional: false },
    { id: 'parse', label: 'Parse', status: 'blocked', findings: 2, nodeId: 'parse', optional: false },
    { id: 'schema', label: 'Schema', status: 'configured', findings: 0, nodeId: 'map', optional: false },
    { id: 'enrich', label: 'Enrich', status: 'empty', findings: 0, nodeId: null, optional: true },
    { id: 'publish', label: 'Publish', status: 'empty', findings: 0, nodeId: null, optional: false },
];

async function create(chips = CHIPS) {
    TestBed.configureTestingModule({
        imports: [PipelineChecklistComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelineChecklistComponent);
    fixture.componentRef.setInput('chips', chips);
    fixture.detectChanges();
    return fixture;
}

/** ⚠ jsdom implements no `innerText` — every text assertion here reads `textContent`. */
describe('PipelineChecklistComponent', () => {
    it('renders one chip per stage, in order', async () => {
        const fixture = await create();
        const labels = Array.from(fixture.nativeElement.querySelectorAll('li button')).map((b) =>
            ((b as HTMLElement).textContent ?? '').replace(/\s+/g, ' ').trim(),
        );
        expect(labels).toHaveLength(5);
        expect(labels[0]).toContain('Collect');
        expect(labels[4]).toContain('Publish');
    });

    // WCAG 1.4.1: the glyph is decorative and colour carries nothing — the WORD is the status.
    it('states every status as text, not colour or glyph alone', async () => {
        const fixture = await create();
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Validated');
        expect(text).toContain('Blocked');
        expect(text).toContain('Configured');
        expect(text).toContain('Not configured'); // required + empty
        expect(text).toContain('Not used'); // optional + empty
    });

    it('shows a finding count only when there are findings', async () => {
        const fixture = await create();
        const buttons = Array.from(fixture.nativeElement.querySelectorAll('li button')) as HTMLElement[];
        expect(buttons[1].textContent).toContain('(2)');
        expect(buttons[0].textContent).not.toContain('(');
    });

    it('reads the whole chip to a screen reader, and counts findings in English', async () => {
        const fixture = await create();
        const buttons = Array.from(fixture.nativeElement.querySelectorAll('li button')) as HTMLElement[];
        expect(buttons[0].getAttribute('aria-label')).toBe('Collect: Validated');
        expect(buttons[1].getAttribute('aria-label')).toBe('Parse: Blocked, 2 findings');
        // ⚠ One TestBed config per test — mutate the input on the SAME fixture, never call create() twice.
        fixture.componentRef.setInput('chips', [{ ...CHIPS[1], findings: 1 }]);
        fixture.detectChanges();
        expect((fixture.nativeElement.querySelector('li button') as HTMLElement).getAttribute('aria-label')).toContain(
            '1 finding',
        );
    });

    it('disables a chip with no node — a click that could do nothing is worse than none', async () => {
        const fixture = await create();
        const buttons = Array.from(fixture.nativeElement.querySelectorAll('li button')) as HTMLButtonElement[];
        expect(buttons[0].disabled).toBe(false);
        expect(buttons[3].disabled).toBe(true);
        expect(buttons[4].disabled).toBe(true);
    });

    it('emits the chip the operator clicked', async () => {
        const fixture = await create();
        let got: StageChip | null = null;
        fixture.componentInstance.open.subscribe((c) => (got = c));
        (fixture.nativeElement.querySelectorAll('li button')[1] as HTMLButtonElement).click();
        expect(got!.id).toBe('parse');
    });

    it('has no a11y violations', async () => {
        const fixture = await create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
