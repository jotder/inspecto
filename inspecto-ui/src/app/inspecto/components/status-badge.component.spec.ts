import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import {
    STATUS_BADGE_BASE,
    STATUS_TONES,
    StatusBadgeComponent,
    StatusBadgeVariant,
    statusBadgeClasses,
    statusBadgeHtml,
    statusToneSchemeClasses,
} from './status-badge.component';

const classSet = (el: Element | null | undefined) => (el?.className ?? '').split(/\s+/).filter(Boolean).sort();

function create(value: string, variant: StatusBadgeVariant = 'pill', label = '') {
    TestBed.configureTestingModule({ imports: [StatusBadgeComponent] });
    const fixture = TestBed.createComponent(StatusBadgeComponent);
    fixture.componentInstance.value = value;
    fixture.componentInstance.variant = variant;
    fixture.componentInstance.label = label;
    fixture.detectChanges();
    return fixture;
}

describe('StatusBadgeComponent', () => {
    it('pins the height: an explicit line-height, semibold', () => {
        // text-xs has no line-height of its own in this app — without leading-4 the badge inherits the
        // container's (an ag-Grid cell's is the row height) and the pill grows to fill the row.
        for (const cls of ['leading-4', 'py-0.5', 'text-xs', 'font-semibold']) {
            expect(STATUS_BADGE_BASE.split(' ')).toContain(cls);
        }
    });

    it('upper-cases a raw status value, but a label phrase keeps its own case', () => {
        const html = document.createElement('div');
        html.innerHTML = statusBadgeHtml('Pass');
        expect(html.firstElementChild!.classList).toContain('uppercase');
        expect(html.firstElementChild!.classList).toContain('whitespace-nowrap');
        html.innerHTML = statusBadgeHtml('good', 'Down 2.6 pts vs prior period');
        expect(html.firstElementChild!.classList).not.toContain('uppercase');
        // a phrase wraps inside its tile instead of spilling into the next one
        expect(html.firstElementChild!.classList).not.toContain('whitespace-nowrap');
        expect(html.firstElementChild!.classList).toContain('max-w-full');
        const labelled = create('good', 'pill', 'Target 95 % — on target');
        expect((labelled.nativeElement.querySelector('span') as HTMLElement).classList).not.toContain('uppercase');
    });

    it('renders the same classes from the component and the string renderer', () => {
        const fixture = create('FAIL');
        const span = fixture.nativeElement.querySelector('span') as HTMLElement;
        const html = document.createElement('div');
        html.innerHTML = statusBadgeHtml('FAIL');
        // Angular's [class] binding reorders the tokens, so compare as sets
        expect(classSet(span)).toEqual(classSet(html.firstElementChild));
        expect(span.textContent).toBe('FAIL');
    });

    it('the string renderer escapes its text, so a cell value can never inject markup', () => {
        const html = document.createElement('div');
        html.innerHTML = statusBadgeHtml('<img src=x onerror=alert(1)>');
        expect(html.querySelector('img')).toBeNull();
        expect(html.textContent).toBe('<img src=x onerror=alert(1)>');
        html.innerHTML = statusBadgeHtml('failed', 'A & <b>B</b>', 'dot');
        expect(html.querySelector('b')).toBeNull();
        expect(html.textContent).toBe('A & <b>B</b>');
    });

    it('keeps the per-scheme table in step with the dark:-prefixed one', () => {
        for (const tone of STATUS_TONES) {
            const light = statusToneSchemeClasses(tone, 'light');
            const dark = statusToneSchemeClasses(tone, 'dark')
                .split(' ')
                .map((c) => `dark:${c}`)
                .join(' ');
            expect(statusBadgeClasses(tone)).toBe(`${light} ${dark}`);
        }
    });

    it('the dot variant draws a decorative dot and the text in the row ink — no tinted fill', () => {
        const fixture = create('Fail', 'dot');
        const outer = fixture.nativeElement.querySelector(':scope > span') as HTMLElement;
        const dot = outer.querySelector('span') as HTMLElement;
        expect(outer.textContent?.trim()).toBe('Fail');
        expect(outer.className).not.toMatch(/\bbg-/);
        expect(outer.className).toContain('leading-4');
        expect(dot.getAttribute('aria-hidden')).toBe('true');
        expect(dot.className).toContain('bg-red-500'); // ds-allow — asserting the owner's class
        expect(dot.className).toContain('rounded-full');
    });

    it('the dot variant string renderer matches the component', () => {
        const fixture = create('Pass', 'dot', 'Passed');
        const html = document.createElement('div');
        html.innerHTML = statusBadgeHtml('Pass', 'Passed', 'dot');
        const a = fixture.nativeElement.querySelector(':scope > span') as HTMLElement;
        const b = html.firstElementChild as HTMLElement;
        expect(classSet(b)).toEqual(classSet(a));
        expect(classSet(b.querySelector('span'))).toEqual(classSet(a.querySelector('span')));
        expect(b.textContent).toBe('Passed');
    });

    it('has no axe violations in the dot variant', async () => {
        const fixture = create('Warning', 'dot');
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
