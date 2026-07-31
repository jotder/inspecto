import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { beforeEach, describe, expect, it } from 'vitest';
import type { ParserTreeNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { OnboardingSegmentsEditorComponent } from './segments-editor.component';

const leaf = (label: string): ParserTreeNode => ({ label, value: 'v' });
const node = (label: string, children: ParserTreeNode[]): ParserTreeNode => ({ label, children });

const TREE: ParserTreeNode[] = [
    node('moCallRecord', [leaf('imsi'), node('party', [leaf('number')])]),
    node('smsRecord', [leaf('imsi')]),
];

describe('OnboardingSegmentsEditorComponent', () => {
    let fixture: ComponentFixture<OnboardingSegmentsEditorComponent>;
    let component: OnboardingSegmentsEditorComponent;

    beforeEach(async () => {
        await TestBed.configureTestingModule({
            imports: [OnboardingSegmentsEditorComponent],
            providers: [provideNoopAnimations()],
        }).compileComponents();
        fixture = TestBed.createComponent(OnboardingSegmentsEditorComponent);
        component = fixture.componentInstance;
        fixture.componentRef.setInput('tree', TREE);
        fixture.detectChanges();
    });

    it('starts empty and refuses to validate with no segments', () => {
        expect(component.segments.length).toBe(0);
        expect(component.validate()).toBe(false);
        expect(component.problem()).toContain('at least one segment');
    });

    it('derives one segment per record type, with leaf paths as selectors', () => {
        component.deriveFromPreview();
        fixture.detectChanges();
        expect(component.value().map((s) => s.key)).toEqual(['moCallRecord', 'smsRecord']);
        expect(component.value()[0].columns).toEqual([
            { name: 'IMSI', selector: 'imsi', type: 'VARCHAR' },
            { name: 'PARTY_NUMBER', selector: 'party.number', type: 'VARCHAR' },
        ]);
        expect(component.validate()).toBe(true);
    });

    it('explains itself instead of silently doing nothing when there is no preview', () => {
        fixture.componentRef.setInput('tree', null);
        fixture.detectChanges();
        component.deriveFromPreview();
        expect(component.segments.length).toBe(0);
        expect(component.deriveNote()).toContain('Test parse');
    });

    it('rejects duplicate segment keys with an actionable message', () => {
        component.addSegment({ key: 'rec', columns: [{ name: 'A', selector: 'a', type: 'VARCHAR' }] });
        component.addSegment({ key: 'rec', columns: [{ name: 'B', selector: 'b', type: 'VARCHAR' }] });
        fixture.detectChanges();
        expect(component.validate()).toBe(false);
        expect(component.problem()).toContain('Duplicate segment key "rec"');
    });

    it('rejects duplicate column names within a segment', () => {
        component.addSegment({
            key: 'rec',
            columns: [
                { name: 'A', selector: 'a', type: 'VARCHAR' },
                { name: 'A', selector: 'b', type: 'VARCHAR' },
            ],
        });
        fixture.detectChanges();
        expect(component.validate()).toBe(false);
        expect(component.problem()).toContain('Duplicate column "A"');
    });

    it('refuses EVENT_TYPE as a column — the ingester derives it', () => {
        component.addSegment({ key: 'rec', columns: [{ name: 'EVENT_TYPE', selector: 'x', type: 'VARCHAR' }] });
        fixture.detectChanges();
        expect(component.validate()).toBe(false);
        expect(component.problem()).toContain('EVENT_TYPE is added automatically');
    });

    it('fills a blank column name from the selector but never overwrites a typed one', () => {
        component.addSegment({ key: 'rec', columns: [{ name: '', selector: 'party.number', type: 'VARCHAR' }] });
        const column = component.columnsOf(component.segments.at(0)).at(0);
        component.nameFromSelector(column);
        expect(column.value['name']).toBe('PARTY_NUMBER');
        column.get('selector')?.setValue('other.path');
        component.nameFromSelector(column);
        expect(column.value['name']).toBe('PARTY_NUMBER');
    });

    it('seeds from saved config without reporting itself dirty', () => {
        fixture.componentRef.setInput('initial', [{ key: 'saved', columns: [] }]);
        fixture.detectChanges();
        expect(component.value().map((s) => s.key)).toEqual(['saved']);
        expect(component.isDirty()).toBe(false);
    });

    it('has no accessibility violations', async () => {
        component.deriveFromPreview();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
