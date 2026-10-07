import { authorKeys } from './author-keys';

describe('authorKeys', () => {
    it('keeps only the x- annotation keys of a stored document', () => {
        expect(authorKeys({ id: 'r1', name: 'r1', 'x-team': 'noc', 'x-n': 2, other: 1 })).toEqual({
            'x-team': 'noc',
            'x-n': 2,
        });
    });

    it('is empty for a new document', () => {
        expect(authorKeys(undefined)).toEqual({});
        expect(authorKeys(null)).toEqual({});
    });
});
