import { describe, expect, it } from 'vitest';
import {
    VIEW_AUTHOR_REASON,
    VIEW_DELETE_AUTHOR_REASON,
    VIEW_DELETE_OWNER_REASON,
    VIEW_EDIT_SHARE_REASON,
    ViewAccessContext,
    viewDeleteBlock,
    viewEditBlock,
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

describe('saved view edit in place (mirrors ComponentAccess.requireEdit)', () => {
    const viewOnly = { shared: true, owner: 'lead', shares: [{ subjectType: 'user', subjectId: 'fm.analyst', access: 'view' }] };

    it('a view shared with me to view only cannot be saved over or renamed, said in words', () => {
        expect(viewEditBlock(viewOnly, author)).toBe(VIEW_EDIT_SHARE_REASON);
        expect(VIEW_EDIT_SHARE_REASON).toContain('view only');
    });

    it('an edit share, the owner, an access admin, an unshared view or no sign-in may edit', () => {
        const editShare = { ...viewOnly, shares: [{ subjectType: 'user', subjectId: 'fm.analyst', access: 'edit' }] };
        expect(viewEditBlock(editShare, author)).toBe('');
        expect(viewEditBlock({ ...viewOnly, owner: 'fm.analyst' }, author)).toBe('');
        expect(viewEditBlock(viewOnly, { ...author, canConfigureAccess: true })).toBe('');
        expect(viewEditBlock({}, author)).toBe('');
        expect(viewEditBlock(viewOnly, { ...author, actor: null })).toBe('');
    });

    it('a role share granting edit gets the benefit of the doubt (the SPA does not know the roles); no capability still locks', () => {
        const roleEdit = { ...viewOnly, shares: [{ subjectType: 'role', subjectId: 'operations', access: 'edit' }] };
        expect(viewEditBlock(roleEdit, author)).toBe('');
        expect(viewEditBlock({}, reader)).toBe(VIEW_AUTHOR_REASON);
    });
});
