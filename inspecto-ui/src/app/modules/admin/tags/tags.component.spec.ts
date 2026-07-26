import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { GammaConfigService } from '@gamma/services/config';
import { LensService, ObjectsService, Tag, TagAssignment, TagsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoGridThemeService } from 'app/inspecto/grid';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { TagsComponent } from './tags.component';

const REGISTRY: Tag[] = [
    { name: 'billing', createdAt: 1 },
    { name: 'q3', createdAt: 2 },
];

const EDGES: TagAssignment[] = [
    { tag: 'billing', targetKind: 'object', targetId: 'INC-1', actor: 'ops', createdAt: 10 },
    { tag: 'billing', targetKind: 'link-analysis-view', targetId: 'fraud-ring', actor: 'ops', createdAt: 11 },
];

async function create(
    tagsApi: Partial<Record<keyof TagsService, unknown>> = {},
    { canAuthor = true, confirmed = true, registry = REGISTRY }: {
        canAuthor?: boolean; confirmed?: boolean; registry?: Tag[];
    } = {},
) {
    const toastr = { info: vi.fn(), error: vi.fn(), warning: vi.fn(), success: vi.fn() };
    const api = {
        targets: vi.fn(() => of(EDGES)),
        assignments: vi.fn(),
        assign: vi.fn(),
        unassign: vi.fn(() => of({ tag: 'billing', removed: true })),
        rename: vi.fn(() => of({ renamed: 'billing', to: 'finance', assignments: 2, objects: 1, rules: 1 })),
        remove: vi.fn(() => of({ deleted: 'billing', assignments: 2, objects: 1, rules: 0 })),
        ...tagsApi,
    } as unknown as TagsService;
    const objects = { tags: vi.fn(() => of(registry)) } as unknown as ObjectsService;

    TestBed.configureTestingModule({
        imports: [TagsComponent],
        providers: [
            provideNoopAnimations(),
            { provide: TagsService, useValue: api },
            { provide: ObjectsService, useValue: objects },
            { provide: ToastrService, useValue: toastr },
            {
                provide: InspectoConfirmService,
                useValue: { confirm: vi.fn(async () => confirmed), confirmDestructive: vi.fn(async () => confirmed) },
            },
            { provide: LensService, useValue: { canAuthorWorkbench: () => canAuthor } },
            { provide: InspectoGridThemeService, useValue: { theme: () => ({}) } },
            { provide: GammaConfigService, useValue: { config$: of({ scheme: 'dark' }) } },
        ],
    });
    await TestBed.compileComponents(); // data-table @defer block
    const fixture = TestBed.createComponent(TagsComponent);
    fixture.detectChanges(); // ngOnInit → loadRegistry()
    return { fixture, api, objects, toastr };
}

describe('TagsComponent', () => {
    it('loads the vocabulary and auto-selects the first tag', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        expect(c.registry()).toEqual(REGISTRY);
        expect(c.selected()).toBe('billing');
        expect(api.targets).toHaveBeenCalledWith('billing');
        expect(c.targets()).toEqual(EDGES);
    });

    it('shows targets of every kind — the cross-entity point of the feature', async () => {
        const { fixture } = await create();
        const kinds = fixture.componentInstance.targets().map((t) => t.targetKind);
        expect(kinds).toEqual(['object', 'link-analysis-view']);
    });

    it('rename and delete are authoring-gated', async () => {
        const { fixture } = await create({}, { canAuthor: false });
        expect(fixture.componentInstance.canAuthor()).toBe(false);
        expect(fixture.nativeElement.textContent).not.toContain('Rename');
    });

    it('rename sends the new name and re-selects it', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.startRename();
        c.renameCtrl.setValue('finance');
        c.commitRename();
        expect(api.rename).toHaveBeenCalledWith('billing', 'finance');
        expect(c.selected()).toBe('finance');
        expect(c.renaming()).toBe(false);
    });

    it('rejects a comma in a tag name without calling the server', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.startRename();
        c.renameCtrl.setValue('a,b');
        c.commitRename();
        expect(api.rename).not.toHaveBeenCalled();
        expect(c.renaming()).toBe(true);
    });

    it('renaming to the same name is a no-op, not a request', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        c.startRename();
        c.commitRename();
        expect(api.rename).not.toHaveBeenCalled();
        expect(c.renaming()).toBe(false);
    });

    it('a 409 from delete (a Tag Rule still applies it) surfaces the server message', async () => {
        const { fixture, toastr } = await create({
            remove: () => throwError(() => ({ status: 409, error: { message: 'tag rule "r1" still applies tag "billing"' } })),
        });
        const c = fixture.componentInstance;
        await c.remove();
        expect(toastr.error).toHaveBeenCalled();
        // The tag survives a refused delete.
        expect(c.selected()).toBe('billing');
    });

    it('a declined confirmation deletes nothing', async () => {
        const { fixture, api } = await create({}, { confirmed: false });
        await fixture.componentInstance.remove();
        expect(api.remove).not.toHaveBeenCalled();
    });

    it('untag drops the row locally after the server confirms', async () => {
        const { fixture, api } = await create();
        const c = fixture.componentInstance;
        await c.untag(EDGES[0]);
        expect(api.unassign).toHaveBeenCalledWith('object', 'INC-1', 'billing');
        expect(c.targets().map((t) => t.targetId)).toEqual(['fraud-ring']);
    });

    it('degrades to an empty pane + a toast when the vocabulary fails to load', async () => {
        const { fixture, toastr } = await create({}, {});
        const c = fixture.componentInstance;
        c.registry.set([]);
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('No tags yet');
        expect(toastr.error).not.toHaveBeenCalled();
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = await create();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
