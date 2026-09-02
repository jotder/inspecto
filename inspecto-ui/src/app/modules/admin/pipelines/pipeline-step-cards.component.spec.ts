import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { describe, expect, it } from 'vitest';
import { AuthoredNode } from 'app/inspecto/api';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { PipelineStepCardsComponent } from './pipeline-step-cards.component';
import { StepRow } from './pipeline-graph';

function create(inputs: Partial<PipelineStepCardsComponent>) {
    TestBed.configureTestingModule({
        imports: [PipelineStepCardsComponent],
        providers: [provideNoopAnimations()],
    });
    const fixture = TestBed.createComponent(PipelineStepCardsComponent);
    Object.assign(fixture.componentInstance, inputs);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance };
}

const COLLECT: AuthoredNode = {
    id: 'collect-1',
    type: 'acquisition',
    name: 'Collect orders',
    config: { files: '*.csv' },
};
const PARSE: AuthoredNode = { id: 'parse-1', type: 'parser', config: { grammar: 'grammar/pipe' } };

describe('PipelineStepCardsComponent', () => {
    it('renders one card per node row, in order, with its display name and category', async () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 },
            { kind: 'node', rowId: PARSE.id, node: PARSE, depth: 0 },
        ];
        const typeCat = new Map([
            ['acquisition', 'SOURCE'],
            ['parser', 'PARSE'],
        ]);
        const { fixture } = create({ rows, typeCat });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Collect orders');
        expect(text).not.toContain('parse-1'); // no name set — heads with its KIND, never the node id
        expect(text).toContain('Collector');
        expect(text).toContain('Parser');
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('heads an unnamed Step with its kind, and does not repeat the kind as a caption', () => {
        // The node id encodes the type it was created with (`transform_join_1`), so using it as a
        // heading describes the type forever — it would start lying the day a Step can be retyped.
        const rows: StepRow[] = [
            {
                kind: 'node',
                rowId: 'transform_join_1',
                node: { id: 'transform_join_1', type: 'transform.join' },
                depth: 0,
            },
        ];
        const typeCat = new Map([['transform.join', 'TRANSFORM']]);
        const typeLabel = new Map([['transform.join', 'Join']]);

        const { fixture } = create({ rows, typeCat, typeLabel });
        const text = ((fixture.nativeElement as HTMLElement).textContent ?? '').trim();
        expect(text).not.toContain('transform_join_1');
        expect((text.match(/Join/g) ?? []).length).toBe(1); // heading only — the caption is suppressed
    });

    it('keeps the kind caption beside a named Step', () => {
        const rows: StepRow[] = [
            {
                kind: 'node',
                rowId: 'j',
                node: { id: 'j', type: 'transform.join', name: 'Enrich with customers' },
                depth: 0,
            },
        ];
        const typeCat = new Map([['transform.join', 'TRANSFORM']]);
        const typeLabel = new Map([['transform.join', 'Join']]);

        const { fixture } = create({ rows, typeCat, typeLabel });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Enrich with customers');
        expect(text).toContain('Join');
    });

    it('distinguishes two TRANSFORM steps by their own type labels, not the shared category', () => {
        // The defect this closes: both are category TRANSFORM, so both cards read 'Transformer' and a
        // join is indistinguishable from a filter unless the operator happened to name it.
        const rows: StepRow[] = [
            { kind: 'node', rowId: 'j', node: { id: 'j', type: 'transform.join' }, depth: 0 },
            { kind: 'node', rowId: 'f', node: { id: 'f', type: 'transform.filter' }, depth: 0 },
        ];
        const typeCat = new Map([
            ['transform.join', 'TRANSFORM'],
            ['transform.filter', 'TRANSFORM'],
        ]);
        const typeLabel = new Map([
            ['transform.join', 'Join'],
            ['transform.filter', 'Filter'],
        ]);

        const { fixture } = create({ rows, typeCat, typeLabel });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Join');
        expect(text).toContain('Filter');
        expect(text).not.toContain('Transformer');
    });

    it('falls back to the category label for a type the catalog gives no label for', () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: 'j', node: { id: 'j', type: 'transform.join' }, depth: 0 },
            { kind: 'node', rowId: 'x', node: { id: 'x', type: 'transform.bespoke' }, depth: 0 },
        ];
        const typeCat = new Map([
            ['transform.join', 'TRANSFORM'],
            ['transform.bespoke', 'TRANSFORM'],
        ]);
        const typeLabel = new Map([['transform.join', 'Join']]); // the plugin type has no served label

        const { fixture } = create({ rows, typeCat, typeLabel });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('Join');
        expect(text).toContain('Transformer');
        expect(text).not.toContain('transform.bespoke'); // never print the raw type at the user
    });

    it('renders a branch-header row indented and shows its predicate + default flag', () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: 'route-1', node: { id: 'route-1', type: 'transform.route' }, depth: 0 },
            {
                kind: 'branch',
                rowId: 'branch:emea:0',
                routeId: 'route-1',
                key: 'emea',
                where: "region = 'EU'",
                isDefault: false,
                depth: 0,
            },
            { kind: 'node', rowId: 'sink-emea', node: { id: 'sink-emea', type: 'sink.persistent' }, depth: 1 },
            { kind: 'branch', rowId: 'branch:other:0', routeId: 'route-1', key: 'other', isDefault: true, depth: 0 },
            { kind: 'node', rowId: 'sink-other', node: { id: 'sink-other', type: 'sink.persistent' }, depth: 1 },
        ];
        const typeCat = new Map([
            ['transform.route', 'TRANSFORM'],
            ['sink.persistent', 'SINK'],
        ]);
        const { fixture } = create({ rows, typeCat });
        const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
        expect(text).toContain('branch: emea');
        expect(text).toContain("when region = 'EU'");
        expect(text).toContain('branch: other');
        expect(text).toContain('default');
    });

    it('shows a status chip only when statusOf is provided', () => {
        // One TestBed/fixture, mutated between assertions — TestBed can only be configured once per test.
        const rows: StepRow[] = [{ kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 }];
        const typeCat = new Map([['acquisition', 'SOURCE']]);
        const { fixture } = create({ rows, typeCat, statusOf: () => 'configured' });
        expect((fixture.nativeElement as HTMLElement).textContent).toContain('Configured');

        fixture.componentRef.setInput('statusOf', undefined);
        fixture.detectChanges();
        expect((fixture.nativeElement as HTMLElement).textContent).not.toContain('Configured');
    });

    it('renders nothing (an empty list) for an empty chain, without error', async () => {
        const { fixture } = create({ rows: [], typeCat: new Map() });
        expect(fixture.nativeElement.querySelectorAll('li')).toHaveLength(0);
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('S2: editable cards emit open / remove / move, and branch rows stay trunk-only', async () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 },
            { kind: 'node', rowId: 'deep', node: { id: 'deep', type: 'sink.persistent' }, depth: 1 },
        ];
        const typeCat = new Map([
            ['acquisition', 'SOURCE'],
            ['sink.persistent', 'SINK'],
        ]);
        const { fixture, c } = create({ rows, typeCat, editable: true });
        const opened: unknown[] = [];
        const removed: string[] = [];
        const moved: unknown[] = [];
        c.open.subscribe((n) => opened.push(n));
        c.remove.subscribe((id) => removed.push(id));
        c.move.subscribe((m) => moved.push(m));

        const el = fixture.nativeElement as HTMLElement;
        const btn = (label: string): HTMLButtonElement | undefined =>
            Array.from(el.querySelectorAll('button')).find((b) => b.getAttribute('aria-label') === label);

        btn('Configure collect-1')!.click();
        expect(opened).toEqual([COLLECT]);
        btn('Remove collect-1')!.click();
        expect(removed).toEqual(['collect-1']);
        btn('Move collect-1 up')!.click();
        expect(moved).toEqual([{ id: 'collect-1', dir: 'up' }]);

        // trunk-only editing (S2 scope): the depth-1 card configures, but never removes/moves
        expect(btn('Configure deep')).toBeTruthy();
        expect(btn('Remove deep')).toBeUndefined();
        expect(btn('Move deep up')).toBeUndefined();

        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('MIDBRANCH-UI-1: a branch is insertable-into — at its head and after each non-tail Step, from a narrowed palette', async () => {
        const rows: StepRow[] = [
            { kind: 'node', rowId: 'route-1', node: { id: 'route-1', type: 'transform.route' }, depth: 0 },
            { kind: 'branch', rowId: 'branch:emea:0', routeId: 'route-1', key: 'emea', isDefault: false, depth: 0 },
            { kind: 'node', rowId: 'filter-emea', node: { id: 'filter-emea', type: 'transform.filter' }, depth: 1 },
            { kind: 'node', rowId: 'sink-emea', node: { id: 'sink-emea', type: 'sink.persistent' }, depth: 1 },
        ];
        const typeCat = new Map([
            ['transform.route', 'TRANSFORM'],
            ['transform.filter', 'TRANSFORM'],
            ['sink.persistent', 'SINK'],
        ]);
        const { fixture, c } = create({ rows, typeCat, editable: true });
        const inserted: unknown[] = [];
        c.insertStep.subscribe((e) => inserted.push(e));
        const el = fixture.nativeElement as HTMLElement;
        const btn = (label: string): HTMLButtonElement | undefined =>
            Array.from(el.querySelectorAll('button')).find((b) => b.getAttribute('aria-label') === label);

        // head-of-branch insert arms the palette for that branch
        expect(btn('Add a Step at the start of branch emea')).toBeTruthy();
        btn('Add a Step at the start of branch emea')!.click();
        expect(c.insertAfterId).toBeNull();
        expect(c.insertBranch).toEqual({ routeId: 'route-1', key: 'emea' });

        // the in-branch card that has a successor inserts after itself; the tail (its sink) does not
        expect(btn('Add a Step after filter-emea in this branch')).toBeTruthy();
        expect(btn('Add a Step after sink-emea in this branch')).toBeUndefined();
        expect(btn('Add a Step after sink-emea')).toBeUndefined(); // and it never gets the TRUNK affordance
        btn('Add a Step after filter-emea in this branch')!.click();
        expect(c.insertAfterId).toBe('filter-emea');
        expect(c.insertBranch).toEqual({ routeId: 'route-1', key: 'emea' });

        // the branch palette is the served/fallback palette narrowed to what arms mid-branch
        expect(c.branchVerbs().map((v) => v.type)).toEqual([
            'transform.dedup',
            'transform.filter',
            'transform.summarize',
        ]);

        // a trunk "+" clears the branch context again, so the trunk palette inserts on the trunk
        btn('Add a Step after route-1')!.click();
        expect(c.insertBranch).toBeNull();

        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('S2: hides every editing affordance when not editable', () => {
        const rows: StepRow[] = [{ kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 }];
        const { fixture } = create({ rows, typeCat: new Map([['acquisition', 'SOURCE']]), editable: false });
        expect((fixture.nativeElement as HTMLElement).querySelectorAll('button')).toHaveLength(0);
    });

    it('S3: a route card offers mode toggle + add-branch; branch rows emit where/default/remove', async () => {
        const ROUTE: AuthoredNode = {
            id: 'route-1',
            type: 'transform.route',
            config: { mode: 'case', branches: [{ key: 'emea', where: "region = 'EU'" }], default: 'emea' },
        };
        const rows: StepRow[] = [
            { kind: 'node', rowId: ROUTE.id, node: ROUTE, depth: 0 },
            {
                kind: 'branch',
                rowId: 'branch:emea:0',
                routeId: 'route-1',
                key: 'emea',
                where: "region = 'EU'",
                isDefault: true,
                depth: 0,
            },
            { kind: 'node', rowId: 'sink-emea', node: { id: 'sink-emea', type: 'sink.persistent' }, depth: 1 },
        ];
        const typeCat = new Map([
            ['transform.route', 'TRANSFORM'],
            ['sink.persistent', 'SINK'],
        ]);
        const { fixture, c } = create({ rows, typeCat, editable: true });
        const added: unknown[] = [];
        const removed: unknown[] = [];
        const wheres: unknown[] = [];
        const defaults: unknown[] = [];
        const modes: unknown[] = [];
        c.addBranch.subscribe((e) => added.push(e));
        c.removeBranch.subscribe((e) => removed.push(e));
        c.branchWhere.subscribe((e) => wheres.push(e));
        c.setDefault.subscribe((e) => defaults.push(e));
        c.modeChange.subscribe((e) => modes.push(e));

        const el = fixture.nativeElement as HTMLElement;
        const btn = (label: string): HTMLButtonElement | undefined =>
            Array.from(el.querySelectorAll('button')).find((b) => b.getAttribute('aria-label') === label);

        // mode toggle flips case → clone
        btn('Route mode: case — click to switch')!.click();
        expect(modes).toEqual([{ routeId: 'route-1', mode: 'clone' }]);

        // add-branch commits the draft on the + button and clears it
        const draft = el.querySelector<HTMLInputElement>('[aria-label="New branch key for route-1"]')!;
        draft.value = 'apac';
        btn('Add a branch to route-1')!.click();
        expect(added).toEqual([{ routeId: 'route-1', key: 'apac' }]);
        expect(draft.value).toBe('');

        // branch row: predicate input emits on change; star toggles default off (it IS the default)
        const when = el.querySelector<HTMLInputElement>('[aria-label="Branch emea predicate"]')!;
        expect(when.value).toBe("region = 'EU'");
        when.value = "region <> 'US'";
        when.dispatchEvent(new Event('change'));
        expect(wheres).toEqual([{ routeId: 'route-1', key: 'emea', where: "region <> 'US'" }]);

        btn('Clear default branch emea')!.click();
        expect(defaults).toEqual([{ routeId: 'route-1', key: null }]);

        btn('Remove branch emea')!.click();
        expect(removed).toEqual([{ routeId: 'route-1', key: 'emea' }]);

        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('S3: branch rows show the predicate as text only when not editable', () => {
        const rows: StepRow[] = [
            {
                kind: 'branch',
                rowId: 'branch:emea:0',
                routeId: 'route-1',
                key: 'emea',
                where: "region = 'EU'",
                isDefault: false,
                depth: 0,
            },
        ];
        const { fixture } = create({ rows, typeCat: new Map(), editable: false });
        const el = fixture.nativeElement as HTMLElement;
        expect(el.textContent).toContain("when region = 'EU'");
        expect(el.querySelectorAll('input')).toHaveLength(0);
        expect(el.querySelectorAll('button')).toHaveLength(0);
    });

    /**
     * The Add-Step menu keys on `type`, the entry's only unique field — two entries can author the same
     * recipe verb (filter and join are both `transform:`). Keying on a shared value renders duplicate
     * `track` keys, which Angular treats as an error, so BOTH entries have to survive into the opened
     * menu. Asserting on the component's `verbs` input would pass either way; the panel is what matters.
     */
    it('offers every palette entry when two entries share a recipe verb (filter + join)', () => {
        const verbs = [
            { type: 'transform.filter', label: 'Transform (filter)' },
            { type: 'transform.join', label: 'Transform (join)' },
            { type: 'sink.persistent', label: 'Sink' },
        ];
        const rows: StepRow[] = [{ kind: 'node', rowId: COLLECT.id, node: COLLECT, depth: 0 }];
        const { fixture, c } = create({ rows, typeCat: new Map([['acquisition', 'SOURCE']]), editable: true, verbs });
        const inserted: { type: string; afterId: string | null }[] = [];
        c.insertStep.subscribe((e) => inserted.push(e));

        const trigger = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('button')).find(
            (b) => b.getAttribute('aria-label') === 'Add a Step at the start',
        )!;
        trigger.click();
        fixture.detectChanges();

        // the menu renders in an overlay, not inside the component's own element
        const items = Array.from(document.querySelectorAll('.mat-mdc-menu-panel button.mat-mdc-menu-item'));
        expect(items.map((i) => i.textContent?.trim())).toEqual(['Transform (filter)', 'Transform (join)', 'Sink']);

        (items[1] as HTMLButtonElement).click();
        expect(inserted).toEqual([{ type: 'transform.join', afterId: null }]);
    });
});
