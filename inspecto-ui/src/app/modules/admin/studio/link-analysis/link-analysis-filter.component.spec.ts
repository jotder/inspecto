import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ConditionGroup } from 'app/inspecto/query/query-types';
import { LinkAnalysisFilterComponent } from './link-analysis-filter.component';

@Component({
    standalone: true,
    changeDetection: ChangeDetectionStrategy.Eager,
    imports: [LinkAnalysisFilterComponent],
    template: `
        <inspecto-link-analysis-filter
            [where]="where()"
            [columns]="[
                { name: 'payer_id', type: 'string' },
                { name: 'amount', type: 'number' },
            ]"
            [localMatch]="{ matched: 3, total: 9 }"
            [pushState]="pushState()"
            [truncated]="truncated()"
            (applyLocal)="events.push('local')"
            (pushToServer)="events.push('push')"
            (clear)="events.push('clear')"
            (advanced)="events.push('advanced')"
        ></inspecto-link-analysis-filter>
    `,
})
class Host {
    readonly where = signal<ConditionGroup>({
        kind: 'group',
        op: 'AND',
        items: [{ kind: 'condition', field: 'amount', operator: '>=', value: '10000' }],
    });
    readonly pushState = signal('not pushed');
    readonly truncated = signal(false);
    readonly events: string[] = [];
}

describe('LinkAnalysisFilterComponent', () => {
    it('renders the tree, both stage tiles and the three actions, and is a11y-clean', async () => {
        TestBed.configureTestingModule({ imports: [Host], providers: [provideNoopAnimations()] });
        const fixture = TestBed.createComponent(Host);
        fixture.detectChanges();
        const el: HTMLElement = fixture.nativeElement;
        expect(el.querySelector('inspecto-query-condition-group')).not.toBeNull();
        expect(el.textContent).toContain('3 / 9');
        expect(el.textContent).toContain('not pushed');

        (el.querySelector('[aria-label="Apply the predicate locally"]') as HTMLButtonElement).click();
        (el.querySelector('[aria-label="Push the predicate to the server"]') as HTMLButtonElement).click();
        (el.querySelector('[aria-label="Clear the predicate"]') as HTMLButtonElement).click();
        (el.querySelector('[aria-label="Open advanced search"]') as HTMLButtonElement).click();
        expect(fixture.componentInstance.events).toEqual(['local', 'push', 'clear', 'advanced']);

        fixture.componentInstance.pushState.set('2,000 links · truncated — refine and push again');
        fixture.componentInstance.truncated.set(true);
        fixture.detectChanges();
        expect(el.querySelector('.text-warn')?.textContent).toContain('truncated');
        await expectNoA11yViolations(el);
    });
});
