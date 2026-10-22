import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { NO_SPACE, SpaceGuideState, SpaceGuideStepId } from './space-guide';
import { SPACE_GUIDE_COLLAPSED_KEY, SpaceGuideComponent } from './space-guide.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [SpaceGuideComponent],
    template: `<inspecto-space-guide [state]="state()" (stepOpen)="opened.push($event)"></inspecto-space-guide>`,
})
class HostComponent {
    readonly state = signal<SpaceGuideState | null>(null);
    readonly opened: SpaceGuideStepId[] = [];
}

const PARTLY: SpaceGuideState = { hasSpace: true, datasets: 1, columnsDescribed: false, views: 0, lists: 0 };
const DONE: SpaceGuideState = { hasSpace: true, datasets: 1, columnsDescribed: true, views: 1, lists: 0 };

function create() {
    TestBed.configureTestingModule({ imports: [HostComponent] });
    const fixture = TestBed.createComponent(HostComponent);
    fixture.detectChanges();
    return fixture;
}

const rows = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLButtonElement>('button[data-step]'));

describe('SpaceGuideComponent', () => {
    beforeEach(() => localStorage.removeItem(SPACE_GUIDE_COLLAPSED_KEY));

    it('renders nothing while the state loads, and nothing once the guide is done', () => {
        const fixture = create();
        expect(fixture.nativeElement.querySelector('section')).toBeNull();
        fixture.componentInstance.state.set(DONE);
        fixture.detectChanges();
        expect(fixture.nativeElement.querySelector('section')).toBeNull();
    });

    it('with no Space: every step to do, counted 0 of 4 (no violations)', async () => {
        const fixture = create();
        fixture.componentInstance.state.set(NO_SPACE);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('h2')!.textContent).toContain('Space guide');
        expect(el.textContent).toContain('0 of 4 done');
        expect(rows(el).map((b) => b.querySelector('.sr-only')!.textContent!.trim())).toEqual([
            ', to do.',
            ', to do.',
            ', to do.',
            ', to do.',
            ', to do.',
        ]);
        await expectNoA11yViolations(el);
    });

    it('part done: the ticks follow the state and the count leaves the optional list out (no violations)', async () => {
        const fixture = create();
        fixture.componentInstance.state.set(PARTLY);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain('2 of 4 done');
        const done = rows(el).map((b) => b.querySelector('.sr-only')!.textContent!.includes('done'));
        expect(done).toEqual([true, true, false, false, false]);
        expect(rows(el)[4].textContent).toContain('(optional)');
        await expectNoA11yViolations(el);
    });

    it('each line opens its step', () => {
        const fixture = create();
        fixture.componentInstance.state.set(PARTLY);
        fixture.detectChanges();
        rows(fixture.nativeElement).forEach((b) => b.click());
        expect(fixture.componentInstance.opened).toEqual(['space', 'data', 'columns', 'graph', 'list']);
    });

    it('collapses and expands, remembering the choice (no violations collapsed)', async () => {
        const fixture = create();
        fixture.componentInstance.state.set(PARTLY);
        fixture.detectChanges();
        const el = fixture.nativeElement as HTMLElement;
        const toggle = el.querySelector<HTMLButtonElement>('button[aria-controls="space-guide-steps"]')!;
        expect(toggle.getAttribute('aria-expanded')).toBe('true');
        toggle.click();
        fixture.detectChanges();
        expect(toggle.getAttribute('aria-expanded')).toBe('false');
        const list = el.querySelector<HTMLElement>('#space-guide-steps')!;
        expect(list.hidden).toBe(true); // kept in the DOM so aria-controls always names a real element
        expect(el.textContent).toContain('2 of 4 done'); // progress stays visible collapsed
        expect(localStorage.getItem(SPACE_GUIDE_COLLAPSED_KEY)).toBe('1');
        await expectNoA11yViolations(el);
        toggle.click();
        fixture.detectChanges();
        expect(list.hidden).toBe(false);
        expect(rows(el)).toHaveLength(5);
    });
});
