import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { DefinitionStateService } from './definition-state.service';

describe('DefinitionStateService', () => {
    let state: DefinitionStateService;

    beforeEach(() => {
        TestBed.configureTestingModule({ providers: [DefinitionStateService] });
        state = TestBed.inject(DefinitionStateService);
    });

    /** The whole point of the thread: downstream results describe the OLD bytes, so they must go. */
    const seedDownstream = (): void => {
        state.parsePreview.set({ columns: ['a'], rows: [['1']] } as never);
        state.parseError.set('stale parse error');
        state.schemaPreview.set({ okCount: 1, rejectedRows: [] } as never);
        state.schemaError.set('stale schema error');
    };

    it('starts empty', () => {
        expect(state.sample()).toBeNull();
        expect(state.parsePreview()).toBeNull();
        expect(state.schemaPreview()).toBeNull();
    });

    it('captureSample stores the sample and invalidates every downstream result', () => {
        seedDownstream();

        state.captureSample('cdr_0813.csv', 'a,b\n1,2');

        expect(state.sample()).toEqual({ name: 'cdr_0813.csv', text: 'a,b\n1,2' });
        expect(state.parsePreview()).toBeNull();
        expect(state.parseError()).toBeNull();
        expect(state.schemaPreview()).toBeNull();
        expect(state.schemaError()).toBeNull();
    });

    it('re-capturing a sample invalidates results derived from the previous one', () => {
        state.captureSample('first.csv', 'a\n1');
        seedDownstream();

        state.captureSample('second.csv', 'b\n2');

        expect(state.sample()?.name).toBe('second.csv');
        expect(state.parsePreview()).toBeNull();
        expect(state.schemaError()).toBeNull();
    });

    it('clearSample drops the sample and every downstream result', () => {
        state.captureSample('cdr.csv', 'a\n1');
        seedDownstream();

        state.clearSample();

        expect(state.sample()).toBeNull();
        expect(state.parsePreview()).toBeNull();
        expect(state.parseError()).toBeNull();
        expect(state.schemaPreview()).toBeNull();
        expect(state.schemaError()).toBeNull();
    });

    it('is host-scoped, not root — two hosts get independent threads', () => {
        state.captureSample('one.csv', 'a\n1');

        // A second injector standing in for a second editor tab.
        const other = TestBed.runInInjectionContext(() => new DefinitionStateService());

        expect(other.sample()).toBeNull();
        expect(state.sample()?.name).toBe('one.csv');
    });
});
