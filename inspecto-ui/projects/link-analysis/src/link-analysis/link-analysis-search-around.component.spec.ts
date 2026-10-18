import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from '@inspecto/core/testing/a11y';
import { LinkAnalysisSearchAroundComponent } from './link-analysis-search-around.component';
import { SearchAroundSpec } from './search-around';

@Component({
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisSearchAroundComponent],
    template: `<inspecto-la-search-around
        [spec]="spec()"
        [nodeKinds]="['Person', 'Phone']"
        [edgeKinds]="['calls']"
        (specChange)="spec.set($event)"
        (run)="runs = runs + 1"
    />`,
})
class Host {
    spec = signal<SearchAroundSpec>({ startType: 'Person', conditions: [] });
    runs = 0;
}

function make() {
    TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    return fixture;
}

const buttons = (el: HTMLElement) => Array.from(el.querySelectorAll<HTMLButtonElement>('button'));

describe('LinkAnalysisSearchAroundComponent', () => {
    it('refuses to run with no condition, then adds one and runs', () => {
        const f = make();
        const el = f.nativeElement as HTMLElement;
        const find = (t: string) => buttons(el).find((b) => b.textContent?.includes(t))!;
        expect(find('Search around').disabled).toBe(true);
        expect(el.querySelector('[data-testid="search-around-issues"]')?.textContent).toContain('at least one');
        find('Add condition').click();
        f.detectChanges();
        expect(f.componentInstance.spec().conditions).toEqual([{ direction: 'out' }]);
        expect(el.querySelector('[data-testid="search-around-words"]')?.textContent).toContain(
            'Follow outgoing any links',
        );
        expect(find('Search around').disabled).toBe(false);
        find('Search around').click();
        expect(f.componentInstance.runs).toBe(1);
    });

    it('removes a condition and has no a11y violations', async () => {
        const f = make();
        f.componentInstance.spec.set({ startType: 'Person', conditions: [{ direction: 'in', linkKind: 'calls' }] });
        f.detectChanges();
        await expectNoA11yViolations(f.nativeElement);
        buttons(f.nativeElement)
            .find((b) => b.getAttribute('aria-label') === 'Remove condition 1')!
            .click();
        expect(f.componentInstance.spec().conditions).toEqual([]);
    });
});
