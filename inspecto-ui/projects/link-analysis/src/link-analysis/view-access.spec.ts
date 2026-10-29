import { describe, expect, it } from 'vitest';
import {
    VIEW_AUTHOR_REASON,
    VIEW_DELETE_AUTHOR_REASON,
    VIEW_DELETE_OWNER_REASON,
    ViewAccessContext,
    viewDeleteBlock,
    viewSaveBlock,
} from './view-access';

const author: ViewAccessContext = { canAuthor: true, canConfigureAccess: false, actor: 'fm.analyst' };
const reader: ViewAccessContext = { canAuthor: false, canConfigureAccess: false, actor: 'bi.analyst' };

describe('saved view access (mirrors the /components write gates)', () => {
    it('saving needs canAuthorWorkbench, said in plain words', () => {
        expect(viewSaveBlock(author)).toBe('');
        expect(viewSaveBlock(reader)).toBe(VIEW_AUTHOR_REASON);
        expect(VIEW_AUTHOR_REASON).toBe('Saving views needs the Workbench author permission.');
    });

    it('deleting needs canAuthorWorkbench even for an unshared example view', () => {
        expect(viewDeleteBlock({}, reader)).toBe(VIEW_DELETE_AUTHOR_REASON);
        expect(viewDeleteBlock({}, author)).toBe('');
    });

    it('a shared view is owner-only to delete, unless the caller is an access admin or there is no sign-in', () => {
        const shared = { shared: true, owner: 'someone.else' };
        expect(viewDeleteBlock(shared, author)).toBe(VIEW_DELETE_OWNER_REASON);
        expect(viewDeleteBlock({ ...shared, owner: 'fm.analyst' }, author)).toBe('');
        expect(viewDeleteBlock(shared, { ...author, canConfigureAccess: true })).toBe('');
        expect(viewDeleteBlock(shared, { ...author, actor: null })).toBe('');
    });
});
